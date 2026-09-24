package org.familyrobot.app

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject

/** Connection details do not mix model diagnostics with transport health. */
@Composable fun RobotConnectionScreen(robot:RobotRuntime,back:()->Unit,replace:()->Unit) = ConnectionDetailsScreen(robot.connection,robot.online,robot.version,robot.appliedWakeName,robot.diagnostic,back,replace)

@Composable fun ParentConnectionScreen(connection:JSONObject,back:()->Unit,replace:()->Unit) {
    val api=remember(connection){Api(connection)}
    var online by remember{mutableStateOf(false)};var version by remember{mutableIntStateOf(0)};var name by remember{mutableStateOf("")}
    LaunchedEffect(api){while(isActive){try{withContext(Dispatchers.IO){api.checkIdentity()};online=true;val result=withContext(Dispatchers.IO){api.json("/v1/robots/${connection.getString("robotId")}/config",timeoutMs=5000)};version=result.optInt("version");name=result.getJSONObject("config").optString("nickname")}catch(e:CancellationException){throw e}catch(_:Exception){online=false};delay(5000)}}
    DisposableEffect(api){onDispose{api.cancel()}}
    ConnectionDetailsScreen(connection,online,version,name,"",back,replace)
}

@Composable private fun ConnectionDetailsScreen(connection:JSONObject,online:Boolean,version:Int,wakeName:String,diagnostic:String,back:()->Unit,replace:()->Unit) {
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope()
    var checking by remember{mutableStateOf(false)};var checked by remember(online){mutableStateOf<Boolean?>(null)}
    var message by remember{mutableStateOf("")};var task by remember{mutableStateOf<Job?>(null)}
    val connected=checked ?: online
    DisposableEffect(api){onDispose{task?.cancel();api.cancel()}}
    Page("服务器连接","",checking,onBack=back){
        ConnectionCard(connected,if(checking)"正在验证服务身份与响应状态。" else if(connected)"家庭电脑在线。模型是否就绪可到检查与调试中确认。" else "暂时无法访问家庭电脑，在线对话不可用。",checking=checking)
        SectionHeading("家庭服务器")
        Column {
        InfoRow("服务名称",connection.optString("serviceName").ifBlank{"家庭电脑"})
        InfoRow("服务地址",connection.optString("address"))
        InfoRow("身份验证",if(connected)"证书指纹一致" else "等待连接后验证")
        InfoRow("最近同步",if(connected)"当前配置 · 版本 ${version}" else "上次成功配置 · 版本 ${version}")
        }
        if(!connected)Text("请确认家庭电脑已开机、服务已启动。USB 调试连接需保持数据线和端口转发；局域网连接需在同一网络。",fontSize=13.sp,color=MaterialTheme.colorScheme.error)
        DetailDisclosure("技术详情"){
            InfoRow("连接结果",if(connected)"HTTPS 请求成功" else "未建立连接")
            InfoRow("服务身份",if(connected)"验证通过" else "尚未验证")
            InfoRow("配置版本",version.toString())
            InfoRow("当前唤醒词",wakeName)
            Text(diagnostic.ifBlank{"暂无额外诊断信息"},fontSize=12.sp)
        }
        FullAction(if(checking)"正在检查…" else "重新检查连接",enabled=!checking){
            checking=true;message="";task=scope.launch{try{withContext(Dispatchers.IO){api.checkIdentity()};checked=true;message="服务器身份验证通过"}catch(e:CancellationException){throw e}catch(e:Exception){checked=false;message=e.message ?: "连接失败，请稍后重试"}finally{checking=false}}
        }
        if(message.isNotBlank())Text(message,fontSize=13.sp,color=if(connected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        if(checking)FullAction("取消检查",secondary=true){task?.cancel();api.cancel();checking=false;message="已取消本次检查"}
        SectionHeading("需要更换家庭服务？")
        DesignGroup{DesignRow("更换连接材料","扫描新的服务二维码或导入文件","qr",false,replace)}
    }
}
