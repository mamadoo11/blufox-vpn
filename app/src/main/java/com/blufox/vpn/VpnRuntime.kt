package com.blufox.vpn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
data class TunnelProbeResult(val tag:String,val latencyMs:Long?,val error:String?=null) {
 val successful:Boolean get()=latencyMs!=null
}
data class VpnUiState(
 val status:VpnStatus=VpnStatus.DISCONNECTED,
 val serverName:String="",
 val message:String="",
 val exitIp:String="",
 val probeResults:List<TunnelProbeResult> = emptyList(),
 val probeCompletedAt:Long = 0L
)
object VpnRuntime {
 private val mutableState=MutableStateFlow(VpnUiState())
 val state=mutableState.asStateFlow()
 fun update(
  status:VpnStatus,
  serverName:String="",
  message:String="",
  exitIp:String="",
  probeResults:List<TunnelProbeResult> = emptyList(),
  probeCompletedAt:Long = 0L
 ){mutableState.value=VpnUiState(status,serverName,message,exitIp,probeResults,probeCompletedAt)}
}
