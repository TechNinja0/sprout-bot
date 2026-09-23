package org.familyrobot.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import java.util.UUID

@Composable fun ParentDashboard(connection:JSONObject,open:(String)->Unit) {
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope();val rid=connection.getString("robotId")
    var device by remember{mutableStateOf(JSONObject())};var online by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    var receivedAt by remember{mutableLongStateOf(0)}
    suspend fun refresh(){val all=withContext(Dispatchers.IO){api.array("/v1/devices",timeoutMs=5000)};device=(0 until all.length()).map{all.getJSONObject(it)}.firstOrNull{it.getString("id")==rid} ?: JSONObject();online=true;receivedAt=System.currentTimeMillis()}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api,lifecycle){lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive){try{refresh()}catch(e:CancellationException){throw e}catch(_:Exception){online=false};delay(3000)}}}
    DisposableEffect(api){onDispose{api.cancel()}}
    val fresh=online&&device.optBoolean("online")&&System.currentTimeMillis()-receivedAt<15000
    val status=device.optJSONObject("status") ?: JSONObject();val playback=status.optJSONObject("playback") ?: JSONObject();val mediaState=playback.optString("state","idle")
    fun control(action:String){if(busy||!fresh)return;busy=true;scope.launch{try{val id=UUID.randomUUID().toString();withContext(Dispatchers.IO){api.json("/v1/robots/$rid/control","POST",JSONObject().put("requestId",id).put("action",action))};message="指令已发送，等待机器人状态更新";var result="pending";repeat(12){if(result=="pending"){delay(1000);result=withContext(Dispatchers.IO){api.json("/v1/commands/$id").getString("state")}}};message=when(result){"applied"->"机器人已接收，请以上方最新播放状态为准";"rejected"->"机器人未执行，请检查使用限制";else->"未确认执行结果，请刷新核对，不自动重发"};refresh()}catch(e:Exception){message=e.message ?: "控制失败"}finally{busy=false}}}
    ConnectionCard(online,if(fresh)"华为机器人在线" else "机器人状态已过期，远程控制暂不可用"){open("连接")}
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(if(fresh)statusLabel(mediaState.takeIf{it!="idle"} ?: status.optString("status")) else "上次上报状态",fontSize=14.sp,color=MaterialTheme.colorScheme.primary)
        Text(playback.optString("title").ifBlank{"小伙伴"},fontSize=22.sp)
        if(playback.optInt("total")>0)Text("第 ${playback.optInt("segment")+1} 段 / ${playback.optInt("total")} 段 · ${playback.optLong("positionMs")/1000} 秒")
        Row{if(mediaState in listOf("playing","paused","loading")){Action(if(mediaState=="paused")"继续播放" else "暂停",fresh&&!busy){control(if(mediaState=="paused")"resume" else "pause")};TextButton(onClick={control("stop")},enabled=fresh&&!busy){Text("结束")}}else Action("选择内容",fresh&&!busy){open("资源库")}}
        TextButton(onClick={open("资源库")},enabled=!busy){Text("从资源库换一个内容 ›")}
        if(message.isNotEmpty())Text(message,fontSize=12.sp)
    }}
    Text("采集与可用状态",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text("麦克风：${if(!fresh)"未知" else if(status.optBoolean("microphone"))"已开启" else "已关闭"} · 相机：${if(!fresh)"未知" else if(status.optBoolean("camera"))"已开启" else "已关闭"}")
    Text("使用许可：${if(fresh)statusLabel(status.optString("reason")) else "未知"} · ${if(fresh)"刚刚上报" else "请检查机器人连接"}",fontSize=12.sp)
    if(status.optString("wakeName").isNotBlank())Text("当前唤醒词：${status.getString("wakeName")}",fontSize=12.sp)
    SectionLink("资源库","故事、图书、儿歌与英语短句"){open("资源库")}
    SectionLink("使用安排","星期、禁用时段与每日时长"){open("使用安排")}
    SectionLink("播放清单","选择资源和英语计划"){open("清单")}
}

@Composable fun ResourceOverview(api:Api,rid:String,robotId:String,edit:()->Unit,back:()->Unit) {
    val scope=rememberCoroutineScope();var value by remember{mutableStateOf<JSONObject?>(null)};var downloads by remember{mutableStateOf(org.json.JSONArray())};var message by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf<String?>(null)}
    fun run(block:suspend ()->Unit){scope.launch{busy=true;try{block()}catch(e:Exception){message=e.message ?: "操作失败"}finally{busy=false}}}
    suspend fun refresh(){value=withContext(Dispatchers.IO){api.json("/v1/resources/$rid")};downloads=withContext(Dispatchers.IO){api.array("/v1/downloads")}}
    LaunchedEffect(rid){run{refresh()}}
    fun control(action:String){run{val id=UUID.randomUUID().toString();withContext(Dispatchers.IO){api.json("/v1/robots/$robotId/control","POST",JSONObject().put("requestId",id).put("action",action).put("resourceId",rid))};var state="pending";repeat(12){if(state=="pending"){delay(1000);state=withContext(Dispatchers.IO){api.json("/v1/commands/$id").getString("state")}}};message=if(state=="applied")"指令已接收，播放状态请看首页；下载请刷新本页查看" else "尚未确认执行（$state），请核对状态";refresh()}}
    androidx.activity.compose.BackHandler(onBack=back)
    Page("资源详情",message,busy,onBack=back){value?.let{v->val draft=v.getJSONObject("draft");val published=v.optString("status")=="published"
        Text(draft.getString("title"),fontSize=24.sp);Text(if(published)"已发布固定版本 · 工作草稿可独立编辑" else "草稿 / 已下架")
        val downloaded=(0 until downloads.length()).map{downloads.getJSONObject(it)}.firstOrNull{it.optString("resource_id")==rid}
        Text("机器人副本：${downloaded?.optString("state") ?: "未下载"}")
        Text(if(draft.optString("audioAsset").isNotEmpty())"原录音 · 保留原声" else "按发布版本的声音设置朗读")
        Action("在机器人上播放",published&&!busy){confirm="play"}
        SectionLink("编辑工作草稿","录入、逐页校对、试听与发布",edit)
        Action("下载发布版",published&&!busy){control("download")}
        Action("清理手机副本",!busy){confirm="remove_download"}
        Action("刷新下载状态",!busy){run{refresh()}}
        Text("发布不代表完成音频下载。清理手机副本不会删除家庭电脑原稿。",fontSize=12.sp)
    }}
    confirm?.let{action->AlertDialog(onDismissRequest={confirm=null},title={Text(if(action=="play")"播放这项内容？" else "清理手机副本？")},text={Text(if(action=="play")"会结束机器人当前互动，按允许时段开始播放。" else "家庭电脑原稿保留，需要时可重新下载。")},confirmButton={TextButton(onClick={confirm=null;control(action)}){Text("确认")}},dismissButton={TextButton(onClick={confirm=null}){Text("取消")}})}
}
