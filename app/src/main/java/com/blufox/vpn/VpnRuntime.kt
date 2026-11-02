package com.blufox.vpn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
data class VpnUiState(val status:VpnStatus=VpnStatus.DISCONNECTED,val serverName:String="",val message:String="",val exitIp:String="")
object VpnRuntime {
 private val mutableState=MutableStateFlow(VpnUiState())
 val state=mutableState.asStateFlow()
 fun update(status:VpnStatus,serverName:String="",message:String="",exitIp:String=""){mutableState.value=VpnUiState(status,serverName,message,exitIp)}
}
