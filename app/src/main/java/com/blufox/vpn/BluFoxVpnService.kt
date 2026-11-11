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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BluFoxVpnService:VpnService(),PlatformInterface,CommandServerHandler {
 companion object {
  const val ACTION_START="com.blufox.vpn.START"
  const val ACTION_STOP="com.blufox.vpn.STOP"
  const val ACTION_TEST_SERVERS="com.blufox.vpn.TEST_SERVERS"
  const val EXTRA_CONFIG="config_json"
  const val EXTRA_NAME="server_name"
  const val EXTRA_PROBE_TAGS="probe_tags"
  const val EXTRA_PROBE_GROUP_TAGS="probe_group_tags"
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
 @Volatile private var commandServer:CommandServer?=null
 @Volatile private var tunFd:ParcelFileDescriptor?=null
 @Volatile private var closing=false
 @Volatile private var operationJob:Job?=null

 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  when(intent?.action) {
   ACTION_STOP->{operationJob?.cancel();stopTunnel();stopSelf(startId)}
   ACTION_START, ACTION_TEST_SERVERS->{
    val config=intent.getStringExtra(EXTRA_CONFIG).orEmpty()
    val name=intent.getStringExtra(EXTRA_NAME).orEmpty()
    if(config.isBlank()){VpnRuntime.update(VpnStatus.ERROR,message="تنظیمات اتصال خالی است.");stopSelf(startId);return START_NOT_STICKY}
    startForegroundCompat(buildNotification(if(intent.action==ACTION_TEST_SERVERS) "آزمون HTTPS واقعی از Android TUN…" else "در حال اتصال…"))
    val probeTags=intent.getStringArrayListExtra(EXTRA_PROBE_TAGS).orEmpty()
    val probeGroupTags=intent.getStringArrayListExtra(EXTRA_PROBE_GROUP_TAGS).orEmpty()
    if(intent.action==ACTION_TEST_SERVERS && probeTags.isEmpty()){
     VpnRuntime.update(VpnStatus.ERROR,message="فهرست سرورها برای آزمایش خالی است.");stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);return START_NOT_STICKY
    }
    startTunnel(config,name,startId,probeTags,probeGroupTags)
   }
   else->{stopSelf(startId);return START_NOT_STICKY}
  }
  return START_NOT_STICKY
 }

 private fun startTunnel(
  config:String,
  name:String,
  startId:Int,
  probeTags:List<String> = emptyList(),
  probeGroupTags:List<String> = emptyList()
 ) {
  operationJob?.cancel()
  if(commandServer!=null)stopTunnel()
  closing=false
  VpnRuntime.update(
   VpnStatus.CONNECTING,
   name,
   if(probeTags.isNotEmpty()) "در حال آماده‌سازی آزمون HTTPS واقعی از Android TUN برای " + probeTags.size + " سرور…" else "در حال راه‌اندازی موتور و تونل امن…",
   probeResults=emptyList(),
   probeCompletedAt=0L
  )
  operationJob=scope.launch {
   try {
    setupLibbox(applicationContext)
    if(probeTags.isNotEmpty()) {
     val results=probeServersThroughTun(config,probeTags,probeGroupTags)
     val passed=results.count{it.successful}
     VpnRuntime.update(
      VpnStatus.DISCONNECTED,
      name,
      "آزمون HTTPS واقعی از Android TUN پایان یافت: " + passed + " از " + results.size + " سرور موفق بود؛ هر سرور جداگانه و متوالی با TUN همان VpnService آزمایش شد.",
      probeResults=results,
      probeCompletedAt=System.currentTimeMillis()
     )
     updateNotification("آزمون واقعی TUN • " + passed + "/" + results.size)
     stopForeground(STOP_FOREGROUND_REMOVE)
     stopSelf(startId)
     return@launch
    }
    Libbox.checkConfig(config)
    val server=CommandServer(this@BluFoxVpnService,this@BluFoxVpnService)
    commandServer=server
    server.start()
    server.startOrReloadService(config,OverrideOptions())
    val verificationStartedAt=android.os.SystemClock.elapsedRealtime()
    val exitIp=verifyTunnelTraffic()
    val verificationLatencyMs=(android.os.SystemClock.elapsedRealtime()-verificationStartedAt).coerceAtLeast(1L)
    VpnRuntime.update(VpnStatus.CONNECTED,name,"تونل فعال است؛ عبور واقعی اینترنت تأیید شد.",exitIp,tunnelLatencyMs=verificationLatencyMs)
    updateNotification("متصل • IP خروجی: $exitIp")
    Log.i(TAG,"Tunnel verified; exit IP=$exitIp")
   } catch(cancelled:CancellationException) {
    tearDownCore()
    VpnRuntime.update(VpnStatus.DISCONNECTED,name,"عملیات متوقف شد.")
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf(startId)
   } catch(e:Throwable) {
    Log.e(TAG,"VPN start failed",e)
    tearDownCore()
    VpnRuntime.update(VpnStatus.ERROR,name,e.message?: "اتصال برقرار نشد.")
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf(startId)
   }
  }
 }

 private suspend fun probeServersThroughTun(
  baseConfig:String,
  tags:List<String>,
  groupTags:List<String>
 ):List<TunnelProbeResult> {
  val groupTagSet=groupTags.toSet()
  val results=mutableListOf<TunnelProbeResult>()
  for((index,tag) in tags.withIndex()) {
   currentCoroutineContext().ensureActive()
   VpnRuntime.update(
    VpnStatus.CONNECTING,
    "آزمایش واقعی سرورها",
    "آزمایش HTTPS از TUN: " + (index+1) + "/" + tags.size + " • " + tag
   )
   updateNotification("آزمایش TUN " + (index+1) + "/" + tags.size + " • " + tag)
   try {
    val candidateConfig=buildCandidateConfig(baseConfig,tag,groupTagSet)
    Libbox.checkConfig(candidateConfig)
    val server=CommandServer(this@BluFoxVpnService,this@BluFoxVpnService)
    commandServer=server
    server.start()
    server.startOrReloadService(candidateConfig,OverrideOptions())
    val verificationStartedAt=android.os.SystemClock.elapsedRealtime()
    val exitIp=verifyCandidateTunnelTraffic()
    currentCoroutineContext().ensureActive()
    val latencyMs=(android.os.SystemClock.elapsedRealtime()-verificationStartedAt).coerceAtLeast(1L)
    results.add(TunnelProbeResult(tag,latencyMs,null))
    Log.i(TAG,"Per-server Android TUN HTTPS verified; tag=$tag exitIp=$exitIp latencyMs=$latencyMs")
   } catch(cancelled:CancellationException) {
    throw cancelled
   } catch(error:Throwable) {
    Log.w(TAG,"Per-server Android TUN HTTPS failed; tag=$tag",error)
    results.add(TunnelProbeResult(tag,null,error.message?: "HTTPS از مسیر Android TUN تأیید نشد."))
   } finally {
    tearDownCore()
   }
   if(index<tags.lastIndex)delay(300)
  }
  return results.sortedWith(
   compareBy<TunnelProbeResult>{!it.successful}
    .thenBy{it.latencyMs?:Long.MAX_VALUE}
    .thenBy{it.tag}
  )
 }

 private fun buildCandidateConfig(baseConfig:String,tag:String,groupTags:Set<String>):String {
  require(tag.isNotBlank()){"برچسب سرور برای آزمایش خالی است."}
  val root=JSONObject(baseConfig)
  val sourceOutbounds=root.optJSONArray("outbounds")?:throw IllegalStateException("فهرست outboundها در تنظیمات آزمایش موجود نیست.")
  val selected=(0 until sourceOutbounds.length())
   .mapNotNull{sourceOutbounds.optJSONObject(it)}
   .firstOrNull{it.optString("tag")==tag}
   ?:throw IllegalStateException("سرور $tag در تنظیمات تازه آزمایش پیدا نشد.")
  require(selected.optString("type") !in setOf("selector","urltest")){"گروه سرور به‌جای outbound مستقل انتخاب شده است: $tag"}
  require(selected.optString("detour").isBlank()){"سرور $tag به outbound دیگری وابسته است و مستقل آزمایش نمی‌شود."}

  val outbounds=JSONArray().put(selected)
  val retainedTags=mutableSetOf(tag)
  for(i in 0 until sourceOutbounds.length()) {
   val outbound=sourceOutbounds.optJSONObject(i)?:continue
   val outboundTag=outbound.optString("tag")
   if(outboundTag==tag)continue
   if(outbound.optString("type") in setOf("direct","block","dns") &&
      (outboundTag.isBlank() || retainedTags.add(outboundTag))) {
    outbounds.put(outbound)
   }
  }
  if((0 until outbounds.length()).none{outbounds.optJSONObject(it)?.optString("type")=="direct"}) {
   outbounds.put(JSONObject().put("type","direct").put("tag","direct"))
  }
  root.put("outbounds",outbounds)

  root.optJSONObject("dns")?.optJSONArray("servers")?.let{dnsServers->
   for(i in 0 until dnsServers.length()) {
    val dnsServer=dnsServers.optJSONObject(i)?:continue
    if(dnsServer.optString("detour") in groupTags)dnsServer.put("detour",tag)
   }
  }
  val route=root.optJSONObject("route")?:JSONObject()
  route.optJSONArray("rules")?.let{rules->
   for(i in 0 until rules.length()) {
    val rule=rules.optJSONObject(i)?:continue
    if(rule.optString("outbound") in groupTags)rule.put("outbound",tag)
   }
  }
  route.put("final",tag)
  root.put("route",route)
  root.optJSONObject("experimental")?.remove("clash_api")
  return root.toString()
 }

 private fun verifyCandidateTunnelTraffic():String {
  var lastError:Throwable?=null
  try {
   val trace=requestText("https://www.cloudflare.com/cdn-cgi/trace",7000)
   val ip=trace.lineSequence().firstOrNull{it.startsWith("ip=")}?.substringAfter("=")?.trim()
   require(!ip.isNullOrBlank() && ip.matches(Regex("[0-9a-fA-F:.]{3,45}"))){"Cloudflare trace پاسخ IP معتبر نداد."}
   return ip
  } catch(error:Exception) {
   lastError=error
  }
  try {
   val ip=requestText("https://api.ipify.org",7000).trim()
   require(ip.matches(Regex("[0-9a-fA-F:.]{3,45}"))){"سرویس IP خروجی پاسخ معتبر نداد."}
   return ip
  } catch(error:Exception) {
   lastError=error
  }
  throw IllegalStateException("درخواست HTTPS از داخل Android TUN تأیید نشد.",lastError)
 }

 private fun verifyTunnelTraffic():String {
  var lastError:Throwable?=null
  repeat(3) { attempt ->
   try {
    val trace=requestText("https://www.cloudflare.com/cdn-cgi/trace")
    val ip=trace.lineSequence().firstOrNull{it.startsWith("ip=")}?.substringAfter("=")?.trim()
    if(!ip.isNullOrBlank())return ip
    val fallback=requestText("https://api.ipify.org").trim()
    require(fallback.matches(Regex("[0-9a-fA-F:.]{3,45}"))){"پاسخ IP خروجی معتبر نیست."}
    return fallback
   } catch(error:Throwable) {
    lastError=error
    if(attempt<2)Thread.sleep(2500)
   }
  }
  throw IllegalStateException("اتصال تونل شروع شد، اما عبور واقعی اینترنت تأیید نشد. ممکن است سرور عمومی از دسترس خارج شده باشد.",lastError)
 }

 private fun requestText(address:String,timeoutMs:Int=12000):String {
  val c=(URL(address).openConnection() as HttpURLConnection).apply{
   connectTimeout=timeoutMs
   readTimeout=timeoutMs
   requestMethod="GET"
   useCaches=false
   instanceFollowRedirects=true
  }
  return try {
   val code=c.responseCode
   require(code in 200..299){"آزمون اینترنت از داخل تونل ناموفق بود (HTTP $code)."}
   c.inputStream.bufferedReader().use{it.readText()}
  } finally {c.disconnect()}
 }

 private fun stopTunnel() {
  operationJob?.cancel()
  operationJob=null
  tearDownCore()
  VpnRuntime.update(VpnStatus.DISCONNECTED,message="اتصال قطع شد.")
  stopForeground(STOP_FOREGROUND_REMOVE)
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

 override fun onRevoke(){stopTunnel();super.onRevoke()}
 override fun onDestroy(){operationJob?.cancel();operationJob=null;tearDownCore();scope.cancel();super.onDestroy()}

 // CommandServerHandler
 override fun serviceStop(){if(!closing)scope.launch{stopTunnel();stopSelf()}}
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
     addresses=StringArray(ni.interfaceAddresses.mapNotNull{a->val host=a.address?.hostAddress?:return@mapNotNull null;"$host/"+a.networkPrefixLength})
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

 private class StringArray(private val values:List<String>):StringIterator {
  private var index=0
  override fun len():Int=values.size
  override fun hasNext():Boolean=index<values.size
  override fun next():String=values[index++]
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
