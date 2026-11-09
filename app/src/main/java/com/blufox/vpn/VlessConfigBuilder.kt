package com.blufox.vpn
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class VlessProfile(val name:String,val configJson:String)
object VlessConfigBuilder {
 fun fromShareLink(raw:String):VlessProfile {
  val link=raw.trim()
  require(link.startsWith("vless://",true)){"فعلاً فقط لینک VLESS پشتیبانی می‌شود."}
  val uri=Uri.parse(link)
  val uuid=Uri.decode(uri.encodedUserInfo.orEmpty()).substringBefore(':')
  val host=uri.host.orEmpty()
  val port=uri.port
  require(uuid.matches(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))){"شناسه UUID داخل لینک معتبر نیست."}
  require(host.isNotBlank() && port in 1..65535){"آدرس یا پورت سرور در لینک معتبر نیست."}
  fun q(key:String)=uri.getQueryParameter(key)?.takeIf{it.isNotBlank()}
  val security=q("security")?.lowercase() ?: "none"
  val transportType=q("type")?.lowercase() ?: "tcp"
  val outbound=JSONObject().put("type","vless").put("tag","proxy").put("server",host).put("server_port",port).put("uuid",uuid)
  q("flow")?.let{outbound.put("flow",it)}
  q("packetEncoding")?.let{outbound.put("packet_encoding",it)}
  if(security=="tls" || security=="reality") {
   val tls=JSONObject().put("enabled",true).put("server_name",q("sni")?:host).put("insecure",false)
   tls.put("utls",JSONObject().put("enabled",true).put("fingerprint",q("fp")?:"chrome"))
   q("alpn")?.let{tls.put("alpn",JSONArray(it.split(',').map(String::trim).filter(String::isNotEmpty)))}
   if(security=="reality") {
    val key=q("pbk")?:throw IllegalArgumentException("کلید عمومی Reality (pbk) در لینک نیست.")
    val reality=JSONObject().put("enabled",true).put("public_key",key)
    q("sid")?.let{reality.put("short_id",it)}
    tls.put("reality",reality)
   }
   outbound.put("tls",tls)
  } else if(security!="none") throw IllegalArgumentException("نوع رمزنگاری $security هنوز پشتیبانی نشده است.")
  when(transportType) {
   "tcp","raw","" -> Unit
   "ws" -> {
    val headers=JSONObject()
    q("host")?.let{headers.put("Host",it)}
    val transport=JSONObject().put("type","ws")
    q("path")?.let{transport.put("path",it)}
    if(headers.length()>0)transport.put("headers",headers)
    outbound.put("transport",transport)
   }
   "grpc" -> {
    val transport=JSONObject().put("type","grpc")
    q("serviceName")?.let{transport.put("service_name",it)}
    outbound.put("transport",transport)
   }
   else -> throw IllegalArgumentException("انتقال $transportType فعلاً پشتیبانی نشده است.")
  }
  val tun=SingBoxConfig.tunInbound()
  val dns=SingBoxConfig.dns("proxy")
  val route=SingBoxConfig.route("proxy")
  val config=JSONObject().put("log",JSONObject().put("level","info"))
   .put("dns",dns).put("inbounds",JSONArray().put(tun))
   .put("outbounds",JSONArray().put(outbound).put(JSONObject().put("type","direct").put("tag","direct")))
   .put("route",route)
  val name=uri.fragment?.let{Uri.decode(it)}?.takeIf{it.isNotBlank()}?:host
  return VlessProfile(name,config.toString())
 }
}
