package org.familyrobot.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    var receivedAt by remember{mutableLongStateOf(0)};var usageAt by remember{mutableLongStateOf(0)};var nickname by remember{mutableStateOf("小伙伴")};var todayMinutes by remember{mutableStateOf<Int?>(null)}
    suspend fun refresh(){val all=withContext(Dispatchers.IO){api.array("/v1/devices",timeoutMs=5000)};device=(0 until all.length()).map{all.getJSONObject(it)}.firstOrNull{it.getString("id")==rid} ?: JSONObject();online=true;receivedAt=System.currentTimeMillis()
        if(receivedAt-usageAt>30000)try{
            val info=withContext(Dispatchers.IO){api.json("/v1/robots/$rid/config",timeoutMs=5000).getJSONObject("config")};nickname=info.optString("nickname","小伙伴")
            val zone=java.time.ZoneId.of(info.getJSONObject("policy").getString("timezone"));val today=java.time.LocalDate.now(zone).toString()
            val usage=withContext(Dispatchers.IO){api.array("/v1/usage",timeoutMs=5000)};todayMinutes=(0 until usage.length()).map{usage.getJSONObject(it)}.firstOrNull{it.optString("day")==today}?.optInt("seconds")?.div(60) ?: 0;usageAt=receivedAt
        }catch(e:CancellationException){throw e}catch(_:Exception){todayMinutes=null}
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api,lifecycle){lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive){try{refresh()}catch(e:CancellationException){throw e}catch(_:Exception){online=false};delay(3000)}}}
    DisposableEffect(api){onDispose{api.cancel()}}
    val fresh=online&&device.optBoolean("online")&&System.currentTimeMillis()-receivedAt<15000
    val status=device.optJSONObject("status") ?: JSONObject();val playback=status.optJSONObject("playback") ?: JSONObject();val mediaState=playback.optString("state","idle")
    fun control(action:String){if(busy||!fresh)return;busy=true;scope.launch{try{val id=UUID.randomUUID().toString();withContext(Dispatchers.IO){api.json("/v1/robots/$rid/control","POST",JSONObject().put("requestId",id).put("action",action))};message="指令已发送，等待机器人状态更新";var result="pending";repeat(12){if(result=="pending"){delay(1000);result=withContext(Dispatchers.IO){api.json("/v1/commands/$id").getString("state")}}};message=when(result){"applied"->"机器人已接收，请以上方最新播放状态为准";"rejected"->"机器人未执行，请检查使用限制";else->"未确认执行结果，请刷新核对，不自动重发"};refresh()}catch(e:Exception){message=e.message ?: "控制失败"}finally{busy=false}}}
    Surface(Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.primaryContainer){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){UiIcon("bot",Modifier.size(26.dp));Column(Modifier.weight(1f)){Text(nickname,fontSize=18.sp);Text(device.optString("name").ifBlank{"机器人手机"},fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick={open("连接")}){Text("查看连接",fontSize=12.sp)}}
        Text("●  家庭服务器${if(online)"已连接" else "连接失败"}",fontSize=12.sp,color=if(online)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text("●  机器人${if(fresh)"在线" else if(online)"离线 / 状态已过期" else "状态未知"}",fontSize=12.sp,color=if(fresh)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        InfoRow("今日使用",todayMinutes?.let{"$it 分钟"} ?: "待同步")
    }}
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(if(fresh)statusLabel(mediaState.takeIf{it!="idle"} ?: status.optString("status")) else "上次上报状态",fontSize=14.sp,color=MaterialTheme.colorScheme.primary)
        Text(playback.optString("title").ifBlank{if(fresh)statusLabel(mediaState.takeIf{it!="idle"} ?: status.optString("status")) else "状态待更新"},fontSize=22.sp)
        if(!fresh)Text("上方为上次上报状态，远程控制暂不可用。",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        if(playback.optInt("total")>0)Text("第 ${playback.optInt("segment")+1} 段 / ${playback.optInt("total")} 段 · ${playback.optLong("positionMs")/1000} 秒")
        Row{if(mediaState in listOf("playing","paused","loading")){Action(if(mediaState=="paused")"继续播放" else "暂停",fresh&&!busy){control(if(mediaState=="paused")"resume" else "pause")};TextButton(onClick={control("stop")},enabled=fresh&&!busy){Text("结束")}}else Action("选择内容",fresh&&!busy){open("资源库")}}
        TextButton(onClick={open("资源库")},enabled=!busy){Text("从资源库换一个内容 ›")}
        if(message.isNotEmpty())Text(message,fontSize=12.sp)
    }}
    DetailDisclosure("采集与可用状态"){
        InfoRow("麦克风",if(!fresh)"未知" else if(status.optBoolean("microphone"))"已开启" else "已关闭")
        InfoRow("相机",if(!fresh)"未知" else if(status.optBoolean("camera"))"已开启" else "已关闭")
        InfoRow("使用许可",if(fresh)statusLabel(status.optString("reason")) else "未知")
        InfoRow("当前唤醒词",status.optString("wakeName").ifBlank{"等待上报"})
    }
    SectionHeading("内容与安排")
    DesignGroup{
        DesignRow("资源库","故事、图书、儿歌与英语短句",icon="book"){open("资源库")}
        DesignRow("使用安排","可用时段与每日时长"){open("使用安排")}
        DesignRow("播放清单","选择资源和英语计划",icon="book",divider=false){open("清单")}
    }
}

@Composable fun ResourceOverview(api:Api,rid:String,robotId:String,edit:()->Unit,back:()->Unit) {
    val scope=rememberCoroutineScope();var value by remember{mutableStateOf<JSONObject?>(null)};var downloads by remember{mutableStateOf(org.json.JSONArray())};var message by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf<String?>(null)}
    val context=LocalContext.current
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)value?.let{v->scope.launch{try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)!!.use{it.write(v.toString(2).toByteArray())}};message="资源资料已保存；原始素材请使用资源库备份导出"}catch(_:Exception){message="保存失败，请检查目标位置"}}}}
    fun run(block:suspend ()->Unit){if(busy)return;scope.launch{busy=true;try{block()}catch(e:Exception){message=e.message ?: "操作失败"}finally{busy=false}}}
    suspend fun refresh(){value=withContext(Dispatchers.IO){api.json("/v1/resources/$rid")};downloads=withContext(Dispatchers.IO){api.array("/v1/downloads")}}
    LaunchedEffect(rid){run{refresh()}}
    fun control(action:String){run{val id=UUID.randomUUID().toString();withContext(Dispatchers.IO){api.json("/v1/robots/$robotId/control","POST",JSONObject().put("requestId",id).put("action",action).put("resourceId",rid))};var state="pending";repeat(12){if(state=="pending"){delay(1000);state=withContext(Dispatchers.IO){api.json("/v1/commands/$id").getString("state")}}};message=if(state=="applied")"指令已接收，播放状态请看首页；下载请刷新本页查看" else "尚未确认执行（$state），请核对状态";refresh()}}
    androidx.activity.compose.BackHandler(onBack=back)
    Page("资源详情",message,busy,onBack=back){value?.let{v->val draft=v.getJSONObject("draft");val published=v.optString("status")=="published"
        Text(draft.getString("title"),fontSize=24.sp);Text(if(published)"已发布固定版本 · 工作草稿可独立编辑" else "草稿 / 已下架")
        if(published)AudioPreparationStatus(api,rid,v.optString("published_id"))
        val downloaded=(0 until downloads.length()).map{downloads.getJSONObject(it)}.firstOrNull{it.optString("resource_id")==rid}
        InfoRow("机器人离线副本",downloaded?.optString("state")?.let{resourceStatus(it)} ?: "未下载")
        Text(if(draft.optString("audioAsset").isNotEmpty())"原录音 · 保留原声" else "按发布版本的声音设置朗读")
        FullAction("在机器人上播放",enabled=published&&!busy){confirm="play"}
        SectionLink("编辑工作草稿","录入、逐页校对、试听与发布",edit)
        Action("下载发布版",published&&!busy){control("download")}
        Action("清理手机副本",!busy){confirm="remove_download"}
        Action("刷新下载状态",!busy){run{refresh()}}
        FullAction(if(draft.optBoolean("favorite"))"取消收藏" else "收藏",secondary=true,enabled=!busy){run{val changed=JSONObject(draft.toString()).put("favorite",!draft.optBoolean("favorite"));withContext(Dispatchers.IO){api.json("/v1/resources/$rid","PUT",JSONObject().put("expectedVersion",v.getInt("draft_version")).put("draft",changed))};refresh()}}
        FullAction("下架",secondary=true,enabled=published&&!busy){confirm="unlist"}
        FullAction("删除资源",secondary=true,enabled=!busy){confirm="delete"}
        FullAction("导出资源资料",secondary=true,enabled=!busy){export.launch("resource.json")}

        Text("发布不代表完成音频下载。清理手机副本不会删除家庭电脑原稿。",fontSize=12.sp)
    }}
    confirm?.let{action->
        val title=when(action){"play"->"播放这项内容？";"unlist"->"下架此资源？";"delete"->"删除此资源？";else->"清理手机副本？"}
        val detail=when(action){"play"->"会结束机器人当前互动，按允许时段开始播放。";"unlist"->"停止后续在线点播，离线设备将在联网同步后移除。";"delete"->"删除家庭电脑原稿和正文，不能恢复；离线设备联网后同步清理。";else->"家庭电脑原稿保留，需要时可重新下载。"}
        AlertDialog(onDismissRequest={confirm=null},title={Text(title)},text={Text(detail)},confirmButton={TextButton(onClick={confirm=null;if(action in listOf("play","remove_download"))control(action) else run{withContext(Dispatchers.IO){if(action=="delete")api.json("/v1/resources/$rid","DELETE") else api.json("/v1/resources/$rid/unlist","POST",JSONObject())};if(action=="delete")back() else refresh()}}){Text("确认")}},dismissButton={TextButton(onClick={confirm=null}){Text("取消")}})
    }
}
