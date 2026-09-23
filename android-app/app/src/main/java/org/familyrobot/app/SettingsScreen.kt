package org.familyrobot.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

@Composable fun DeviceSettingsScreen(connection:JSONObject,local:Boolean,section:String,back:()->Unit,play:(ByteArray)->Unit) {
    val api=remember(connection) { Api(connection) };val scope=rememberCoroutineScope()
    val id=connection.getString(if(local)"deviceId" else "robotId")
    var config by remember { mutableStateOf<JSONObject?>(null) };var saved by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) };var busy by remember { mutableStateOf(false) };var message by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(JSONObject()) };var leave by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) };var trial by remember { mutableStateOf(false) };var promptKind by remember { mutableStateOf("daily") }
    var reloadConfirm by remember { mutableStateOf(false) }
    var revisions by remember { mutableStateOf<JSONArray?>(null) }
    fun launch(block:suspend ()->Unit) { if(busy)return;scope.launch { busy=true;try { block() } catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "操作失败"}finally{busy=false} } }
    suspend fun load(){val r=withContext(Dispatchers.IO){api.json("/v1/robots/$id/config")};config=r.getJSONObject("config");version=r.getInt("version");saved=config.toString()}
    fun exit(){if(busy)return;if(config!=null&&config.toString()!=saved)leave=true else back()}
    LaunchedEffect(Unit){busy=true;try{load();if(section=="声音")models=withContext(Dispatchers.IO){api.json("/v1/models")}}catch(e:Exception){message=e.message ?: "无法读取配置"}finally{busy=false}}
    DisposableEffect(api){onDispose{api.cancel()}}
    BackHandler { if(trial)trial=false else exit() }
    if(trial&&config!=null){DebugChat(connection,back={trial=false},play=play,prompts=config!!.getJSONObject("prompts"),promptKind=promptKind);return}
    Page(section,message,busy,onBack={exit()}) {
        val c=config
        if(c==null)Action("重新加载",!busy){launch{load()}}
        else {
            Text("${if(local)"机器人本机 · PIN 管理" else "家长设置"} · 配置版本 $version",fontSize=12.sp)
            if(!busy)when(section){
                "唤醒与互动" -> {
                    Text("当前配置：${JSONObject(saved).optString("nickname","小伙伴")}")
                    JsonField(c,"nickname","新的唤醒词（2—6个汉字）")
                    Row { for(name in listOf("小蜜桃","小蜜蜂","小伙伴"))TextButton(onClick={c.put("nickname",name);config=JSONObject(c.toString())}){Text(name)} }
                    Text("不能使用停止、继续等控制口令。保存后等当前对话和播放结束，再实际试叫。",fontSize=13.sp)
                    val interaction=c.getJSONObject("interaction")
                    Dropdown(interaction,"wakeSensitivity","唤醒灵敏度",listOf("low" to "较低","standard" to "标准","high" to "较高"))
                    Dropdown(interaction,"wakeFeedback","叫醒回应",listOf("voice" to "语音回应","chime" to "短提示音","visual" to "仅表情"))
                    Toggle(interaction,"playful","轻抚、揉脸时逗趣回应")
                    Dropdown(interaction,"expressionIntensity","表演强度",listOf("gentle" to "轻柔","normal" to "普通"))
                }
                "AI 提示词" -> {
                    val prompts=c.getJSONObject("prompts")
                    Row { for((key,label) in listOf("daily" to "日常","english" to "英语","story" to "故事","visual" to "视觉"))FilterChip(promptKind==key,{promptKind=key},label={Text(label)}) }
                    JsonField(prompts,promptKind,"默认提示词（最多4000字）",8)
                    Text("可用变量：{{robot_name}}、{{age}}、{{english_level}}。配置保存在家庭服务；新会话读取新版本。固定权限、时间限制及输出结构不受模板改变。",fontSize=12.sp)
                    Row { Action("预览内容"){preview=true};Action("草稿试聊"){trial=true} }
                    Action("恢复本项默认",!busy){launch{val defaults=withContext(Dispatchers.IO){api.json("/v1/prompts/defaults")};prompts.put(promptKind,defaults.getString(promptKind));config=JSONObject(c.toString());message="默认内容已填入，保存后才生效"}}
                    Action("历史版本",!busy){launch{revisions=withContext(Dispatchers.IO){api.array("/v1/prompts/versions")}}}
                }
                "声音" -> {
                    VoiceControls(c.getJSONObject("voice"),models)
                    Action("试听声音",!busy){launch{val bytes=withContext(Dispatchers.IO){api.raw("/v1/debug/speech","POST",JSONObject().put("text","早上好，我们一起探索有趣的世界。").put("voice",c.getJSONObject("voice")).toBody())};play(bytes)}}
                }
                "隐私与权限" -> {Toggle(c,"cameraAllowed","允许会话内使用相机");Toggle(c,"muted","麦克风静音");Toggle(c,"reducedMotion","减少表情动态");Text("许可开关不表示正在采集。所有模型运行在家庭电脑。")}
            }
            Action("保存并应用",!busy){launch{
                val snapshot=JSONObject(c.toString());val command=UUID.randomUUID().toString()
                val body=JSONObject().put("requestId",command).put("expectedVersion",version)
                if(local){val settings=JSONObject();val keys=when(section){"唤醒与互动"->listOf("nickname","interaction");"AI 提示词"->listOf("prompts");"声音"->listOf("voice");else->listOf("cameraAllowed","muted","reducedMotion")};keys.forEach{settings.put(it,snapshot.get(it))};body.put("settings",settings)}else body.put("config",snapshot)
                withContext(Dispatchers.IO){api.json(if(local)"/v1/local/settings" else "/v1/robots/$id/config","POST",body)}
                var state="pending";message="已提交，等待机器人确认"
                repeat(12){if(state=="pending"){delay(1000);state=withContext(Dispatchers.IO){api.json("/v1/commands/$command").getString("state")}}}
                if(state=="applied"){load();message=if(section=="唤醒与互动")"配置已确认；唤醒词等当前互动结束后生效，请核对状态并试叫" else if(section=="AI 提示词")"已保存；新会话使用新提示词，当前会话保持原版本" else "机器人已确认配置"}
                else message="未确认应用（$state），输入保留，请核对当前版本后重试"
            }}
            Action("重新读取已生效设置",!busy){if(c.toString()!=saved)reloadConfirm=true else launch{load()}}
        }
    }
    if(reloadConfirm)AlertDialog(onDismissRequest={reloadConfirm=false},title={Text("重新读取当前配置？")},text={Text("将丢弃编辑区的修改，用服务器已生效版本替换。")},confirmButton={TextButton(onClick={reloadConfirm=false;launch{load()}}){Text("重新读取")}},dismissButton={TextButton(onClick={reloadConfirm=false}){Text("保留编辑")}})
    if(leave)AlertDialog(onDismissRequest={leave=false},title={Text("有未保存修改")},text={Text("离开会放弃编辑，已生效配置保持不变。")},confirmButton={TextButton(onClick={leave=false;back()}){Text("放弃并离开")}},dismissButton={TextButton(onClick={leave=false}){Text("继续编辑")}})
    if(preview&&config!=null){val c=config!!;val text=c.getJSONObject("prompts").getString(promptKind).replace("{{robot_name}}",c.getString("nickname")).replace("{{age}}",c.getJSONObject("profile").optInt("ageAtBaseline").toString()).replace("{{english_level}}",c.getJSONObject("profile").getString("englishLevel"));AlertDialog(onDismissRequest={preview=false},title={Text("组合内容预览")},text={Text(text.take(4500),Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={preview=false}){Text("返回编辑")}})}
    revisions?.let{versions->AlertDialog(onDismissRequest={revisions=null},title={Text("提示词历史版本")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){if(versions.length()==0)Text("还没有历史版本");for(i in 0 until versions.length()){val row=versions.getJSONObject(i);TextButton(onClick={config!!.put("prompts",row.getJSONObject("body"));config=JSONObject(config.toString());revisions=null;message="已恢复到编辑区，保存后才应用"}){Text("恢复版本 ${row.getInt("version")}")}}}},confirmButton={TextButton(onClick={revisions=null}){Text("关闭")}})}
}
