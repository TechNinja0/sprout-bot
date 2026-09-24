package org.familyrobot.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
private suspend fun captureDebugVoice(keep:AtomicBoolean):ByteArray=withContext(Dispatchers.IO){
    val size=maxOf(4096,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT))
    val recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size)
    val bytes=ByteArrayOutputStream();val buffer=ByteArray(3200)
    try{check(recorder.state==AudioRecord.STATE_INITIALIZED){"无法打开麦克风"};recorder.startRecording();val deadline=SystemClock.elapsedRealtime()+30000
        while(keep.get()&&SystemClock.elapsedRealtime()<deadline){ensureActive();val n=recorder.read(buffer,0,buffer.size,AudioRecord.READ_NON_BLOCKING);if(n>0)bytes.write(buffer,0,n) else if(n<0)error("录音中断，请重新尝试");delay(10)}
    }finally{runCatching{recorder.stop()};recorder.release()}
    check(bytes.size()>3200){"录音过短，请重新录制"};AudioInput.wav(bytes.toByteArray())
}

@Composable fun DebugChat(connection:JSONObject,back:()->Unit,play:(ByteArray)->Unit,prompts:JSONObject?=null,promptKind:String="daily",title:String="调试",tabs:(@Composable ()->Unit)?=null,online:Boolean?=null) {
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope();val context=LocalContext.current
    var session by remember{mutableStateOf(UUID.randomUUID().toString())};var text by remember{mutableStateOf("")}
    var messages by remember{mutableStateOf(listOf<JSONObject>())};var busy by remember{mutableStateOf(false)};var notice by remember{mutableStateOf("")}
    var autoPlay by remember{mutableStateOf(true)};var recording by remember{mutableStateOf(false)};var history by remember{mutableStateOf(false)}
    val keep=remember{AtomicBoolean(false)};var job by remember{mutableStateOf<Job?>(null)};var speechJob by remember{mutableStateOf<Job?>(null)}
    var voiceMode by remember{mutableStateOf(false)};var voiceDraft by remember{mutableStateOf<ByteArray?>(null)}
    val voiceMessages=remember{mutableMapOf<String,ByteArray>()};val scroll=rememberScrollState()
    val audioCache=remember{mutableMapOf<String,ByteArray>()};val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun stop(){keep.set(false);job?.cancel();speechJob?.cancel();api.cancel();recording=false;busy=false;(context as? MainActivity)?.stopPreview()}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)stop()};lifecycle.addObserver(observer);onDispose{stop();audioCache.clear();voiceMessages.clear();voiceDraft=null;lifecycle.removeObserver(observer)}}
    fun startRecording(){if(busy||recording)return;speechJob?.cancel();(context as? MainActivity)?.stopPreview();keep.set(true);recording=true;notice="录音中，最多30秒。点击结束后可编辑识别文字。";job=scope.launch{try{val wav=captureDebugVoice(keep);voiceDraft=wav;recording=false;busy=true;val result=withContext(Dispatchers.IO){api.upload("/v1/debug/recognize","debug.wav",wav,"audio/wav")};text=result.optString("text");notice="语音已转为文字，请确认后发送"}catch(e:CancellationException){throw e}catch(e:Exception){notice=e.message ?: "录音失败"}finally{recording=false;busy=false;keep.set(false)}}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)startRecording() else notice="未获得麦克风权限，可继续使用文字调试"}
    fun speak(row:JSONObject){val id=row.optString("recordId");if(id.isBlank()||recording||busy)return;speechJob?.cancel();(context as? MainActivity)?.stopPreview();speechJob=scope.launch{try{val bytes=audioCache[id] ?: withContext(Dispatchers.IO){api.raw("/v1/records/$id/speech","POST",JSONObject().toBody())}.also{if(audioCache.size>=20)audioCache.remove(audioCache.keys.first());audioCache[id]=it};play(bytes)}catch(e:CancellationException){throw e}catch(e:Exception){notice="朗读失败，可再次点击重试：${e.message}"}}}
    BackHandler{stop();back()}
    if(history){RecordsScreen(connection,debug=true,back={history=false},play=play);return}
    fun send(){
        if(busy||recording||text.isBlank())return
        speechJob?.cancel();(context as? MainActivity)?.stopPreview()
        val question=text.trim();val id=UUID.randomUUID().toString()
        val outgoing=JSONObject().put("role","user").put("text",question).put("id",id)
        voiceDraft?.let{if(voiceMessages.size>=20)voiceMessages.remove(voiceMessages.keys.first());voiceMessages[id]=it;outgoing.put("voice",true)}
        voiceDraft=null;text="";messages=messages+outgoing;busy=true;notice=""
        job=scope.launch{try{
            val body=JSONObject().put("sessionId",session).put("text",question).put("promptKind",promptKind);prompts?.let{body.put("prompts",it)}
            val response=withContext(Dispatchers.IO){api.json("/v1/debug/turn","POST",body)};response.put("role","assistant");messages=messages+response;busy=false;if(autoPlay)speak(response)
        }catch(e:CancellationException){throw e}catch(e:Exception){text=question;notice="未收到回复，输入已保留，可重试：${e.message}"}finally{busy=false}}
    }
    fun record(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)}
    LaunchedEffect(messages.size,busy,scroll.maxValue){scroll.animateScrollTo(scroll.maxValue)}
    Page(if(prompts==null)title else "提示词草稿试聊",notice,busy,onBack={stop();back()},header=tabs,scroll=scroll,bottom={
        Surface(color=MaterialTheme.colorScheme.background,shadowElevation=0.dp){Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            if(recording){
                Text("正在录音…",fontWeight=FontWeight.Medium)
                Text("最多 30 秒，结束后确认识别文字再发送。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FullAction("结束录音"){keep.set(false)}
                TextButton(onClick={stop();voiceDraft=null;notice="已取消录音"}){Text("取消")}
            }else{
                if(voiceDraft!=null){Text("语音待发送 · 可编辑识别文字",fontSize=12.sp);Row{TextButton(onClick={voiceDraft?.let(play)},enabled=!busy){Text("播放语音")};TextButton(onClick={voiceDraft=null;text="";record()},enabled=!busy){Text("重新录音")};TextButton(onClick={voiceDraft=null;text=""},enabled=!busy){Text("取消发送")}}}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    IconButton(onClick={voiceMode=!voiceMode},enabled=!busy,modifier=Modifier.semantics{contentDescription=if(voiceMode)"切换文字输入" else "切换语音输入"}){UiIcon(if(voiceMode)"book" else "mic")}
                    if(voiceMode&&voiceDraft==null){OutlinedButton(onClick={record()},enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(13.dp)){UiIcon("mic");Spacer(Modifier.width(8.dp));Text("点击录音")}}
                    else{
                        OutlinedTextField(text,{text=it.take(1000)},placeholder={Text("输入消息…")},modifier=Modifier.weight(1f).semantics{contentDescription="发送文字或语音转写"},maxLines=4,enabled=!busy,shape=RoundedCornerShape(13.dp))
                        FilledIconButton(onClick={send()},enabled=!busy&&text.isNotBlank(),modifier=Modifier.semantics{contentDescription="发送"}){UiIcon("send",color=MaterialTheme.colorScheme.onPrimary)}
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(autoPlay,{autoPlay=it});Text("自动播放新回复",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);if(busy)TextButton(onClick={stop();notice="已取消本次操作"}){Text("取消")}}
            }
        }}
    }){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(online==null)"独立管理会话" else if(online)"● 家庭服务已连接" else "● 家庭服务未连接",Modifier.weight(1f),fontSize=12.sp,color=if(online==null)MaterialTheme.colorScheme.onSurfaceVariant else if(online)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error);TextButton(onClick={stop();audioCache.clear();voiceMessages.clear();history=true}){Text("会话记录")}}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("当前调试会话",Modifier.weight(1f),fontWeight=FontWeight.Medium);TextButton(onClick={stop();session=UUID.randomUUID().toString();messages=emptyList();voiceMessages.clear();voiceDraft=null;text="";notice=""}){Text("＋ 新会话")}}
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        if(messages.isEmpty())Column(Modifier.fillMaxWidth().heightIn(min=160.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){UiIcon("bot",Modifier.size(28.dp));Spacer(Modifier.height(12.dp));Text("发送文字或语音，开始本次调试",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        for(row in messages){val outgoing=row.optString("role")=="user"
            Column(Modifier.fillMaxWidth().padding(start=if(outgoing)30.dp else 0.dp,end=if(outgoing)0.dp else 24.dp),horizontalAlignment=if(outgoing)Alignment.End else Alignment.Start){
                Text(if(outgoing)"我" else "小伙伴",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape=if(outgoing)RoundedCornerShape(17.dp,4.dp,17.dp,17.dp) else RoundedCornerShape(4.dp,17.dp,17.dp,17.dp),color=if(outgoing)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,border=if(outgoing)null else BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Column(Modifier.padding(horizontal=15.dp,vertical=13.dp)){
                    Text(row.optString("text"),fontSize=14.sp,lineHeight=24.sp)
                    row.optJSONObject("knowledge")?.let{Text("知识库 · ${it.optString("source")}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    if(outgoing&&row.optBoolean("voice"))TextButton(onClick={voiceMessages[row.optString("id")]?.let(play)},enabled=!busy&&!recording&&voiceMessages.containsKey(row.optString("id"))){Text("播放语音")}
                    if(!outgoing&&row.has("recordId"))TextButton(onClick={speak(row)},enabled=!busy&&!recording){Text("播放 / 再次播放")}
                }}
            }
        }
        if(busy)Text("小伙伴正在回复…",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("只使用本手机输入，不控制另一台手机采集。调试记录独立保存，不加入孩子的陪伴记录。原录音仅在当前会话临时回放，离开后释放。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }

}

@Composable fun RecordsScreen(connection:JSONObject,debug:Boolean=false,back:()->Unit,play:(ByteArray)->Unit,bottom:(@Composable ()->Unit)?=null,openUsage:(()->Unit)?=null) {
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope();var rows by remember{mutableStateOf(JSONArray())};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};var remove by remember{mutableStateOf<String?>(null)}
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    var kind by remember{mutableStateOf(if(debug)"debug" else "companion")};var selectedSession by remember{mutableStateOf<String?>(null)}
    fun exit(){(context as? MainActivity)?.stopPreview();if(selectedSession!=null)selectedSession=null else back()}
    fun launch(block:suspend ()->Unit){if(busy)return;scope.launch{busy=true;try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "读取失败"}finally{busy=false}}}
    suspend fun refresh(){rows=withContext(Dispatchers.IO){api.array("/v1/records?kind=$kind")}}
    LaunchedEffect(kind){rows=JSONArray();selectedSession=null;launch{refresh()}}
    DisposableEffect(api,lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){scope.coroutineContext.cancelChildren();api.cancel();(context as? MainActivity)?.stopPreview()}};lifecycle.addObserver(observer);onDispose{api.cancel();(context as? MainActivity)?.stopPreview();lifecycle.removeObserver(observer)}}
    BackHandler{exit()}
    Page(if(selectedSession!=null)"会话详情" else if(debug)"调试记录" else "对话记录",message,busy,onBack={exit()},bottom=if(selectedSession==null)bottom else null){
        if(!debug&&selectedSession==null)Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){FilterChip(kind=="companion",{kind="companion"},enabled=!busy,label={Text("陪伴对话")});FilterChip(kind=="debug",{kind="debug"},enabled=!busy,label={Text("调试会话")});if(openUsage!=null)TextButton(onClick=openUsage){Text("使用记录")}}
        Text(if(debug)"仅显示这台管理设备的调试会话" else "默认不保存；启用后记录后续对话。这里显示请求及服务回答，不代表孩子已听完。",fontSize=12.sp)
        Row{Action("刷新",!busy){launch{refresh()}};TextButton(onClick={remove="all"},enabled=!busy&&rows.length()>0){Text("清空")}}
        if(rows.length()==0)EmptyState("暂无记录","启用记录后只保存后续对话，不补录过去会话。")
        val sessions=(0 until rows.length()).map{rows.getJSONObject(it)}.groupBy{it.optString("session_id")}
        if(selectedSession==null && !debug && sessions.isNotEmpty())DesignGroup{sessions.entries.forEachIndexed{index,(id,turns)->DesignRow(turns.first().optString("question").take(40),"${turns.size} 条 · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date((turns.first().getDouble("created")*1000).toLong()))}",icon="chat",divider=index<sessions.size-1){selectedSession=id}}}
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);if(!debug&&row.optString("session_id")!=selectedSession)continue;Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date((row.getDouble("created")*1000).toLong())),fontSize=12.sp)
            Text("我：${row.getString("question")}");Text("小伙伴：${row.getString("answer")}")
            Row{TextButton(onClick={launch{val audio=withContext(Dispatchers.IO){api.raw("/v1/records/${row.getString("id")}/speech","POST",JSONObject().toBody())};play(audio)}},enabled=!busy){Text("朗读回答")};TextButton(onClick={remove=row.getString("id")},enabled=!busy){Text("删除")}}
        }}}
        Text("历史回答按记录中的声音设置重新朗读，不是保存的儿童原声。",fontSize=12.sp)
    }
    remove?.let{id->AlertDialog(onDismissRequest={remove=null},title={Text(if(id=="all")"清空这类记录？" else "删除这条记录？")},text={Text("删除后不能恢复，不会修改资源库和已审核偏好。")},confirmButton={TextButton(onClick={remove=null;(context as? MainActivity)?.stopPreview();launch{withContext(Dispatchers.IO){api.json(if(id=="all")"/v1/records?kind=$kind" else "/v1/records/$id","DELETE")};refresh()}}){Text("删除")}},dismissButton={TextButton(onClick={remove=null}){Text("取消")}})}
}
