package com.blufox.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.*
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.Notification as LibboxNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class BluFoxVpnService:VpnService(),PlatformInterface,CommandServerHandler {
 companion object {
  const val ACTION_START="com.blufox.vpn.START"
  const val ACTION_STOP="com.blufox.vpn.STOP"
  const val EXTRA_CONFIG="config_json"
  const val EXTRA_NAME="server_name"
  private const val TAG="BluFoxVPN"
  private const val NOTIF_ID=731
  private const val CHANNEL_ID="blufox_vpn"
  @Volatile private var libboxReady=false
  private fun setupLibbox(context:android.content.Context) {
   if(libboxReady)return
   synchronized(BluFoxVpnService::class.java) {
    if(libboxReady)return
    val working=File(context.filesDir,"libbox/working").apply{mkdirs()}
    val temp=File(context.cacheDir,"libbox/temp").apply{mkdirs()}
    Libbox.setup(SetupOptions().apply{basePath=context.filesDir.absolutePath;workingPath=working.absolutePath;tempPath=temp.absolutePath})
    libboxReady=true
   }
  }
 }
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 // Serialises engine start/stop. It is NOT held during traffic verification, so a stop never waits on it.
 private val lifecycle=Mutex()
 // Bumped on every start and stop. A coroutine that sees a different value is stale and must not touch state.
 private val generation=AtomicInteger(0)
 @Volatile private var startJob:Job?=null
 @Volatile private var commandServer:CommandServer?=null
 @Volatile private var tunFd:ParcelFileDescriptor?=null
 @Volatile private var closing=false

 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  when(intent?.action) {
   ACTION_STOP->{scope.launch{if(stopTunnel())stopSelf(startId)}}
   ACTION_START->{
    // startForegroundService() obliges us to call startForeground() promptly, even if we bail out right after.
    startForegroundCompat(buildNotification("در حال اتصال…"))
    val config=intent.getStringExtra(EXTRA_CONFIG).orEmpty()
    val name=intent.getStringExtra(EXTRA_NAME).orEmpty()
    if(config.isBlank()){
     VpnRuntime.update(VpnStatus.ERROR,message="تنظیمات اتصال خالی است.")
     stopForeground(STOP_FOREGROUND_REMOVE)
     stopSelf(startId)
     return START_NOT_STICKY
    }
    startTunnel(config,name,startId)
   }
   else->{stopSelf(startId);return START_NOT_STICKY}
  }
  return START_NOT_STICKY
 }

 private fun startTunnel(config:String,name:String,startId:Int) {
  val gen=generation.incrementAndGet()
  startJob?.cancel()
  VpnRuntime.update(VpnStatus.CONNECTING,name,"در حال راه‌اندازی موتور و تونل امن…")
  startJob=scope.launch {
   try {
    val proceed=lifecycle.withLock {
     if(gen!=generation.get())false
     else {
      tearDownCore()
      setupLibbox(applicationContext)
      Libbox.checkConfig(config)
      val server=CommandServer(this@BluFoxVpnService,this@BluFoxVpnService)
      // Assigned before start() so a failed start is still closed by tearDownCore().
      commandServer=server
      server.start()
      server.startOrReloadService(config,OverrideOptions())
      true
     }
    }
    if(!proceed)return@launch
    awaitTun()
    val exitIp=verifyTunnelTraffic()
    // A stop (or newer start) during verification makes this result stale: never report CONNECTED for it.
    if(gen!=generation.get())return@launch
    VpnRuntime.update(VpnStatus.CONNECTED,name,"تونل فعال است؛ عبور واقعی اینترنت تأیید شد.",exitIp)
    updateNotification("متصل • IP خروجی: $exitIp")
    Log.i(TAG,"Tunnel verified; exit IP=$exitIp")
   } catch(e:Throwable) {
    if(e is CancellationException||gen!=generation.get())return@launch
    Log.e(TAG,"VPN start failed",e)
    lifecycle.withLock{if(gen==generation.get())tearDownCore()}
    if(gen!=generation.get())return@launch
    VpnRuntime.update(VpnStatus.ERROR,name,e.message?:"اتصال برقرار نشد.")
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf(startId)
   }
  }
 }

 /** The engine opens the TUN synchronously while starting; if it never did, the config has no TUN inbound. */
 private suspend fun awaitTun() {
  var waited=0
  while(tunFd==null&&waited<3000){delay(100);waited+=100}
  check(tunFd!=null){"تونل VPN ساخته نشد؛ تنظیمات اتصال فاقد ورودی TUN است."}
 }

 private suspend fun verifyTunnelTraffic():String {
  var lastError:Throwable?=null
  repeat(3) { attempt ->
   try {
    val trace=requestText("https://www.cloudflare.com/cdn-cgi/trace")
    val ip=trace.lineSequence().firstOrNull{it.startsWith("ip=")}?.substringAfter("=")?.trim()
    if(!ip.isNullOrBlank())return ip
    val fallback=requestText("https://api.ipify.org").trim()
    require(fallback.matches(Regex("[0-9a-fA-F:.]{3,45}"))){"پاسخ IP خروجی معتبر نیست."}
    return fallback
   } catch(error:CancellationException) {
    throw error
   } catch(error:Throwable) {
    lastError=error
    if(attempt<2)delay(2500)
   }
  }
  throw IllegalStateException("اتصال تونل شروع شد، اما عبور واقعی اینترنت تأیید نشد. ممکن است سرور عمومی از دسترس خارج شده باشد.",lastError)
 }

 private fun requestText(address:String):String {
  val c=(URL(address).openConnection() as HttpURLConnection).apply{connectTimeout=12000;readTimeout=12000;requestMethod="GET";useCaches=false;instanceFollowRedirects=true}
  return try {
   val code=c.responseCode
   require(code in 200..299){"آزمون اینترنت از داخل تونل ناموفق بود (HTTP "+code+")."}
   c.inputStream.bufferedReader().use{it.readText()}
  } finally {c.disconnect()}
 }

 /** Returns false when a newer start/stop took over while this one was tearing down (caller must not stopSelf). */
 private suspend fun stopTunnel():Boolean {
  val gen=generation.incrementAndGet()
  startJob?.cancel()
  startJob=null
  lifecycle.withLock{tearDownCore()}
  if(gen!=generation.get())return false
  VpnRuntime.update(VpnStatus.DISCONNECTED,message="اتصال قطع شد.")
  stopForeground(STOP_FOREGROUND_REMOVE)
  return true
 }

 private fun tearDownCore() {
  closing=true
  val current=commandServer
  commandServer=null
  runCatching{current?.closeService()}
  runCatching{current?.close()}
  runCatching{tunFd?.close()}
  tunFd=null
  closing=false
 }

 override fun onRevoke(){
  VpnRuntime.update(VpnStatus.DISCONNECTED,message="اجازه VPN توسط اندروید لغو شد.")
  super.onRevoke()
 }
 override fun onDestroy(){
  generation.incrementAndGet()
  startJob?.cancel()
  tearDownCore()
  scope.cancel()
  // If the service dies while the UI still believes it is up, do not leave a stale CONNECTING/CONNECTED state.
  val status=VpnRuntime.state.value.status
  if(status==VpnStatus.CONNECTING||status==VpnStatus.CONNECTED)VpnRuntime.update(VpnStatus.DISCONNECTED,message="اتصال قطع شد.")
  super.onDestroy()
 }

 // CommandServerHandler
 override fun serviceStop(){if(!closing)scope.launch{if(stopTunnel())stopSelf()}}
 override fun serviceReload()=Unit
 override fun getSystemProxyStatus():SystemProxyStatus?=null
 override fun setSystemProxyEnabled(enabled:Boolean)=Unit
 override fun triggerNativeCrash()=Unit
 override fun writeDebugMessage(message:String?){if(!message.isNullOrBlank())Log.d(TAG,message)}
 override fun connectSSHAgent():Int=-1

 // PlatformInterface
 override fun usePlatformAutoDetectInterfaceControl():Boolean=true
 override fun autoDetectInterfaceControl(fd:Int){check(protect(fd)){"Android could not protect the VPN engine socket."}}
 override fun openTun(options:TunOptions?):Int {
  if(VpnService.prepare(this)!=null)error("اجازه VPN از اندروید دریافت نشده است.")
  val builder=Builder().setSession("BluFox VPN").setMtu(options?.mtu?.coerceIn(1280,9000)?:1500)
   .addAddress("172.19.0.1",30).addAddress("fdfe:dcba:9876::1",126)
   .addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("172.19.0.1").setBlocking(false)
  if(Build.VERSION.SDK_INT>=29)builder.setMetered(false)
  val pfd=builder.establish()?:error("اندروید نتوانست تونل VPN را ایجاد کند.")
  tunFd=pfd
  return pfd.fd
 }
 override fun findConnectionOwner(ipProtocol:Int,sourceAddress:String?,sourcePort:Int,destinationAddress:String?,destinationPort:Int):ConnectionOwner=error("Connection owner lookup is not enabled.")
 override fun startDefaultInterfaceMonitor(listener:InterfaceUpdateListener?)=Unit
 override fun closeDefaultInterfaceMonitor(listener:InterfaceUpdateListener?)=Unit
 override fun getInterfaces():NetworkInterfaceIterator {
  val result=mutableListOf<LibboxNetworkInterface>()
  runCatching {
   val all=java.net.NetworkInterface.getNetworkInterfaces()
   while(all.hasMoreElements()) {
    val ni=all.nextElement()
    if(!ni.isUp||ni.isLoopback||ni.name.startsWith("tun"))continue
    val item=LibboxNetworkInterface().apply {
     name=ni.name;index=ni.index
     runCatching{mtu=ni.mtu}
     type=when{ni.name.startsWith("wlan")->Libbox.InterfaceTypeWIFI;ni.name.startsWith("rmnet")->Libbox.InterfaceTypeCellular;else->Libbox.InterfaceTypeOther}
     addresses=StringArray(ni.interfaceAddresses.mapNotNull{a->val host=a.address?.hostAddress?:return@mapNotNull null;"$host/"+a.networkPrefixLength}.iterator())
    }
    result.add(item)
   }
  }.onFailure{Log.w(TAG,"Interface enumeration failed",it)}
  return InterfaceArray(result.iterator())
 }
 override fun underNetworkExtension():Boolean=false
 override fun includeAllNetworks():Boolean=false
 override fun clearDNSCache()=Unit
 override fun readWIFIState():WIFIState?=null
 override fun localDNSTransport():LocalDNSTransport?=null
 override fun sendNotification(notification:LibboxNotification?)=Unit
 override fun cancelNotification(identifier:String?,typeID:Int)=Unit
 override fun startNeighborMonitor(listener:NeighborUpdateListener?)=Unit
 override fun closeNeighborMonitor(listener:NeighborUpdateListener?)=Unit
 override fun registerMyInterface(name:String?)=Unit
 override fun usePlatformShell():Boolean=false
 override fun checkPlatformShell()=Unit
 override fun openShellSession(user:PlatformUser?,command:String?,environ:StringIterator?,term:String?,rows:Int,cols:Int):ShellSession=error("Shell is not enabled.")
 override fun lookupUser(username:String?):PlatformUser=error("User lookup is not enabled.")
 override fun lookupSFTPServer():String=error("SFTP is not enabled.")
 override fun readSystemSSHHostKey():String=error("SSH is not enabled.")
 override fun tailscaleHostname():String="BluFox Android"
 override fun usePlatformBridge():Boolean=false
 override fun createBridge(options:BridgeOptions?):BridgeSession=error("Bridge is not enabled.")

 private class StringArray(private val iterator:Iterator<String>):StringIterator {
  override fun len():Int=0
  override fun hasNext():Boolean=iterator.hasNext()
  override fun next():String=iterator.next()
 }
 private class InterfaceArray(private val iterator:Iterator<LibboxNetworkInterface>):NetworkInterfaceIterator {
  override fun hasNext():Boolean=iterator.hasNext()
  override fun next():LibboxNetworkInterface=iterator.next()
 }

 private fun startForegroundCompat(notification:Notification) {
  if(Build.VERSION.SDK_INT>=34)startForeground(NOTIF_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
  else startForeground(NOTIF_ID,notification)
 }
 private fun buildNotification(status:String):Notification {
  val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
  if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,"BluFox VPN",NotificationManager.IMPORTANCE_LOW))
  val stop=PendingIntent.getService(this,1,Intent(this,BluFoxVpnService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val open=packageManager.getLaunchIntentForPackage(packageName)?.let{PendingIntent.getActivity(this,2,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
  val builder=NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("BluFox VPN").setContentText(status).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,"قطع اتصال",stop)
  if(open!=null)builder.setContentIntent(open)
  return builder.build()
 }
 private fun updateNotification(status:String){(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID,buildNotification(status))}
}
