package org.familyrobot.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

@Composable private fun SetupIntro(title:String,description:String,icon:String="bot"){
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                UiIcon(icon,Modifier.size(22.dp));Text(title,fontSize=17.sp)
            }
            Text(description,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SetupSteps(step:Int){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        listOf("选择用途","连接验证","完成设置").forEachIndexed{index,label->
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("${index+1} $label",fontSize=12.sp,color=if(index==step)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color=if(index<=step)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable private fun SetupField(label:String,value:String,numeric:Boolean=false,secret:Boolean=false,change:(String)->Unit){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(label,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value,change,modifier=Modifier.fillMaxWidth().semantics{contentDescription=label},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=if(secret)KeyboardType.NumberPassword else if(numeric)KeyboardType.Number else KeyboardType.Ascii),
            visualTransformation=if(secret)PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surface,unfocusedContainerColor=MaterialTheme.colorScheme.surface,unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant),shape=RoundedCornerShape(13.dp))
    }
}

@Composable fun SetupScreen(activity:ComponentActivity,vault:Vault,done:(String)->Unit){
    val scope=rememberCoroutineScope();val focus=LocalFocusManager.current
    var step by rememberSaveable{mutableIntStateOf(0)}
    var role by rememberSaveable{mutableStateOf("robot")}
    var host by rememberSaveable{mutableStateOf("")};var port by rememberSaveable{mutableStateOf("8766")}
    var pin by rememberSaveable{mutableStateOf("")};var repeatPin by rememberSaveable{mutableStateOf("")}
    var message by rememberSaveable{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    var scanning by remember{mutableStateOf(false)}
    var connectionText by rememberSaveable{mutableStateOf("")}
    var requestText by rememberSaveable{mutableStateOf("")}
    var claim by rememberSaveable{mutableStateOf("")}
    var deadline by rememberSaveable{mutableLongStateOf(0L)}
    var expired by remember{mutableStateOf(false)}
    val pending=requestText.isNotEmpty()
    val scroll=rememberScrollState()
    LaunchedEffect(message,step,pending){if(message.isNotEmpty()||!busy)scroll.animateScrollTo(0)}
    LaunchedEffect(step,pending){focus.clearFocus()}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){done("robot")}
    fun acceptMaterial(text:String){
        if(busy||pending)return
        scope.launch{
            busy=true;message="";focus.clearFocus()
            try{
                val c=parseConnectionMaterial(text)
                val purpose=c.optString("purpose")
                require(if(role=="parent")purpose=="pair" else purpose in listOf("register","recover")){if(role=="parent")"请扫描机器人管理页生成的家长配对二维码" else "这是家长配对材料，请返回选择家长手机"}
                val result=withContext(Dispatchers.IO){
                    val api=Api(c);api.checkIdentity()
                    api.json(if(role=="parent")"/v1/pairing/claim" else if(purpose=="recover")"/v1/recover" else "/v1/register","POST",JSONObject().put("invite",c.getString("invite")).put("name",if(role=="parent")"家长手机" else "家庭小伙伴"))
                }
                c.remove("invite")
                if(role=="robot"){
                    c.put("token",result.getString("token")).put("deviceId",result.getString("deviceId"));connectionText=c.toString();step=2
                }else{
                    c.put("token",UUID.randomUUID().toString()+UUID.randomUUID());connectionText=c.toString()
                    claim=result.getString("claim");deadline=System.currentTimeMillis()+120000;expired=false;requestText=result.toString()
                }
            }catch(e:Exception){message=e.message ?: "连接失败，请重新扫描"}finally{busy=false}
        }
    }
    val importFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)runCatching{activity.contentResolver.openInputStream(uri)!!.use{readLimited(it,16384).decodeToString()}}.onSuccess{acceptMaterial(it)}.onFailure{message="连接文件读取失败，请重新选择"}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)scanning=true else message="未获得相机权限，可以在系统设置中允许，或使用备用文件导入"}
    fun scan(){
        message=""
        if(ContextCompat.checkSelfPermission(activity,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)scanning=true else permission.launch(Manifest.permission.CAMERA)
    }
    if(scanning)PairingScanner(activity,onDismiss={scanning=false}){text->scanning=false;acceptMaterial(text)}
    LaunchedEffect(requestText){
        if(requestText.isEmpty())return@LaunchedEffect
        val c=JSONObject(connectionText);val req=JSONObject(requestText);val api=Api(c)
        try{
            while(isActive && System.currentTimeMillis()<deadline){
                try{
                    val result=withContext(Dispatchers.IO){
                        api.json(if(role=="robot")"/v1/setup/requests/${req.getString("requestId")}/complete" else "/v1/pairing/${req.getString("pairId")}/complete","POST",JSONObject().put("claim",claim).put("token",c.getString("token")),timeoutMs=6000)
                    }
                    if(result.optString("state")=="completed"){
                        c.put("deviceId",result.getString("deviceId"))
                        if(role=="parent"){c.put("robotId",result.getString("robotId"));vault.save("parent",c);requestText="";done("parent")}
                        else{connectionText=c.toString();requestText="";step=2}
                        message="";return@LaunchedEffect
                    }
                    if(result.optString("state")=="rejected"){message="配对已被拒绝，请重新扫码";expired=true;return@LaunchedEffect}
                    message=""
                }catch(e:CancellationException){throw e}catch(e:Exception){
                    message=if(e is ApiHttpException)e.message ?: "连接失败" else "连接中断，请检查同一 Wi-Fi 下的家庭电脑；正在重试"
                    if(e is ApiHttpException && e.status in listOf(403,409,410)){expired=true;return@LaunchedEffect}
                }
                delay(2000)
            }
            expired=true;message="连接确认已过期，请重新发起连接"
        }finally{api.cancel()}
    }
    fun resetPending(){requestText="";connectionText="";claim="";expired=false;message=""}
    BackHandler(enabled=step==1&&!busy){if(pending)resetPending() else{step=0;message=""}}
    val title=when(step){0->"首次设置";2->"完成设置";else->if(role=="robot")"连接家庭服务" else "绑定家长手机"}
    Page(title,"",busy,onBack=if(step==1&&!busy)({if(pending)resetPending() else{step=0;message=""}})else null,header={SetupSteps(step)},scroll=scroll){
        if(message.isNotEmpty())Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.errorContainer){Text(message,Modifier.padding(14.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onErrorContainer)}
        if(step==0){
            SetupIntro("这台手机用来做什么？","选择用途，开始连接你的小伙伴。")
            DesignGroup{
                DesignRow("机器人手机","显示表情、语音互动与播放内容","robot"){role="robot";step=1;message=""}
                DesignRow("家长手机","管理资源、使用安排与小伙伴设置","settings",false){role="parent";step=1;message=""}
            }
            if(vault.get("parent")!=null)FullAction("返回已绑定家长端",secondary=true){done("parent")}
            if(vault.get("robot")!=null)FullAction("返回已登记机器人",secondary=true){done("robot")}
        }else if(step==2){
            SetupIntro("家庭服务已连接","设置管理 PIN，用于进入这台小伙伴的管理页。")
            SetupField("设置管理 PIN",pin,secret=true){pin=it.filter(Char::isDigit).take(12)}
            SetupField("确认管理 PIN",repeatPin,secret=true){repeatPin=it.filter(Char::isDigit).take(12)}
            Text("使用 6—12 位数字，仅保存在这台手机上。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeading("陪伴权限")
            Text("麦克风用于唤醒和对话，相机只在允许的会话中使用。也可以稍后在设置中授权。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            fun finish(withPermissions:Boolean){
                if(!pin.matches(Regex("[0-9]{6,12}"))||pin!=repeatPin){message="请设置6—12位数字 PIN，并确保两次输入一致";return}
                vault.save("robot",JSONObject(connectionText));vault.setPin(pin)
                if(withPermissions)permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA)) else done("robot")
            }
            FullAction("完成设置并授权"){finish(true)}
            FullAction("稍后授权，进入小伙伴",secondary=true){finish(false)}
        }else if(pending){
            SetupIntro(if(expired)"连接尚未完成" else "等待小伙伴确认","请在机器人管理页确认这台家长手机，确认后自动进入家长端。")
            FullAction(if(expired)"重新连接" else "取消连接",secondary=true){resetPending()}
        }else if(role=="robot"){
            SetupIntro("连接你的家庭电脑","手机与电脑连接同一 Wi-Fi，填写电脑的局域网地址。","server")
            SetupField("电脑 IP / 主机名",host){host=it}
            SetupField("端口",port,numeric=true){port=it.filter(Char::isDigit).take(5)}
            Text("例如 192.168.1.100，默认端口 8766，一般无需修改。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FullAction("检测并连接",enabled=!busy&&host.isNotBlank()&&port.isNotBlank()){
                scope.launch{
                    busy=true;message="";focus.clearFocus()
                    try{
                        val address=setupAddress(host,port)
                        val secret=UUID.randomUUID().toString()+UUID.randomUUID()
                        val c=withContext(Dispatchers.IO){discoverSetupConnection(address)}
                        c.put("token",UUID.randomUUID().toString()+UUID.randomUUID())
                        val result=withContext(Dispatchers.IO){
                            val api=Api(c)
                            val req=api.json("/v1/setup/requests","POST",JSONObject().put("claimHash",sha256(secret.toByteArray())).put("name","家庭小伙伴"),timeoutMs=6000)
                            api.json("/v1/setup/requests/${req.getString("requestId")}/complete","POST",JSONObject().put("claim",secret).put("token",c.getString("token")),timeoutMs=6000)
                        }
                        check(result.optString("state")=="completed"){"家庭服务未完成登记，请更新电脑端后重试"}
                        c.put("deviceId",result.getString("deviceId"));connectionText=c.toString();step=2

                    }catch(e:Exception){message=if(e is IllegalArgumentException || e is ApiHttpException)e.message ?: "连接失败" else "无法连接家庭电脑，请检查 IP、端口、同一 Wi-Fi，以及电脑服务是否已启动"}finally{busy=false}
                }
            }
            DetailDisclosure("其他连接方式"){
                FullAction("扫描电脑连接二维码",secondary=true,enabled=!busy){scan()}
                FullAction("导入连接 / 恢复文件",secondary=true,enabled=!busy){importFile.launch(arrayOf("application/json","text/plain","application/octet-stream"))}
            }
            DetailDisclosure("在哪里查看电脑地址？"){
                Text("在家庭电脑的网络设置中查看当前 Wi-Fi 的 IP 地址。家庭服务需允许局域网访问；若使用默认命令启动，在 serve 后添加 --bind 0.0.0.0。",fontSize=13.sp)
            }
        }else{
            SetupIntro("扫码绑定小伙伴","在机器人手机进入「机器人管理 → 家长与配对」，生成配对二维码。","qr")
            DesignGroup{
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
                    listOf("在机器人手机展示配对二维码","用这台家长手机扫描","在机器人手机确认绑定").forEachIndexed{index,text->
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.primaryContainer){Box(Modifier.size(28.dp),contentAlignment=Alignment.Center){Text("${index+1}",color=MaterialTheme.colorScheme.primary,fontSize=13.sp)}}
                            Text(text,Modifier.weight(1f),fontSize=14.sp)
                        }
                    }
                }
            }
            FullAction("扫描小伙伴二维码",enabled=!busy){scan()}
            Text("两台手机需能访问同一家庭服务。二维码 2 分钟内有效。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            DetailDisclosure("无法扫码？"){
                Text("可以导入机器人导出的家长配对文件，无需编辑文件内容。",fontSize=13.sp)
                FullAction("导入家长配对文件",secondary=true,enabled=!busy){importFile.launch(arrayOf("application/json","text/plain","application/octet-stream"))}
            }
        }
    }
}
