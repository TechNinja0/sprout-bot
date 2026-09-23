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

@Composable fun DebugChat(connection:JSONObject,back:()->Unit,play:(ByteArray)->Unit,prompts:JSONObject?=null,promptKind:String="daily") {
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope();val context=LocalContext.current
    var session by remember{mutableStateOf(UUID.randomUUID().toString())};var text by remember{mutableStateOf("")}
    var messages by remember{mutableStateOf(listOf<JSONObject>())};var busy by remember{mutableStateOf(false)};var notice by remember{mutableStateOf("")}
    var autoPlay by remember{mutableStateOf(true)};var recording by remember{mutableStateOf(false)};var history by remember{mutableStateOf(false)}
    val keep=remember{AtomicBoolean(false)};var job by remember{mutableStateOf<Job?>(null)};var speechJob by remember{mutableStateOf<Job?>(null)}
    val audioCache=remember{mutableMapOf<String,ByteArray>()};val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun stop(){keep.set(false);job?.cancel();speechJob?.cancel();api.cancel();recording=false;busy=false;(context as? MainActivity)?.stopPreview()}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)stop()};lifecycle.addObserver(observer);onDispose{stop();audioCache.clear();lifecycle.removeObserver(observer)}}
    fun startRecording(){if(busy||recording)return;speechJob?.cancel();(context as? MainActivity)?.stopPreview();keep.set(true);recording=true;notice="录音中，最多30秒。点击结束后可编辑识别文字。";job=scope.launch{try{val wav=captureDebugVoice(keep);recording=false;busy=true;val result=withContext(Dispatchers.IO){api.upload("/v1/debug/recognize","debug.wav",wav,"audio/wav")};text=result.optString("text");notice="语音已转为文字，请确认后发送"}catch(e:CancellationException){throw e}catch(e:Exception){notice=e.message ?: "录音失败"}finally{recording=false;busy=false;keep.set(false)}}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)startRecording() else notice="未获得麦克风权限，可继续使用文字调试"}
    fun speak(row:JSONObject){val id=row.optString("recordId");if(id.isBlank()||recording||busy)return;speechJob?.cancel();(context as? MainActivity)?.stopPreview();speechJob=scope.launch{try{val bytes=audioCache[id] ?: withContext(Dispatchers.IO){api.raw("/v1/records/$id/speech","POST",JSONObject().toBody())}.also{if(audioCache.size>=20)audioCache.remove(audioCache.keys.first());audioCache[id]=it};play(bytes)}catch(e:CancellationException){throw e}catch(e:Exception){notice="朗读失败，可再次点击重试：${e.message}"}}}
    BackHandler{stop();back()}
    if(history){RecordsScreen(connection,debug=true,back={history=false},play=play);return}
    Page(if(prompts==null)"调试" else "提示词草稿试聊",notice,busy,onBack={stop();back()}){
        Text("独立管理会话 · 只使用本手机输入，不控制机器人采集",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row{TextButton(onClick={stop();session=UUID.randomUUID().toString();messages=emptyList();text="";notice=""}){Text("新会话")};TextButton(onClick={stop();audioCache.clear();history=true}){Text("调试记录")}}
        for(row in messages){val outgoing=row.optString("role")=="user";Card(Modifier.fillMaxWidth().padding(start=if(outgoing)32.dp else 0.dp,end=if(outgoing)0.dp else 24.dp),colors=CardDefaults.cardColors(containerColor=if(outgoing)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {Column(Modifier.padding(16.dp)){Text(if(outgoing)"我" else "小伙伴",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(row.optString("text"));if(!outgoing&&row.has("recordId"))TextButton(onClick={speak(row)},enabled=!busy&&!recording){Text("播放 / 再次播放")}}}}
        OutlinedTextField(text,{text=it.take(1000)},label={Text("发送文字或语音转写")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5,enabled=!busy&&!recording)
        Row{Action(if(recording)"结束录音" else "录制语音",!busy){if(recording)keep.set(false) else if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)};Action("发送",!busy&&!recording&&text.isNotBlank()){
            speechJob?.cancel();(context as? MainActivity)?.stopPreview();val question=text.trim();text="";messages=messages+JSONObject().put("role","user").put("text",question);busy=true;notice=""
            job=scope.launch{try{val body=JSONObject().put("sessionId",session).put("text",question).put("promptKind",promptKind);prompts?.let{body.put("prompts",it)}
                val response=withContext(Dispatchers.IO){api.json("/v1/debug/turn","POST",body)};response.put("role","assistant");messages=messages+response;busy=false;if(autoPlay)speak(response)
            }catch(e:CancellationException){throw e}catch(e:Exception){text=question;notice="未收到回复，输入已保留，可重试：${e.message}"}finally{busy=false}}
        }}
        Row(verticalAlignment=Alignment.CenterVertically){Switch(autoPlay,{autoPlay=it});Text("自动朗读回复")}
        if(busy||recording)TextButton(onClick={stop();notice="已取消本次操作"}){Text("取消")}
        Text("调试记录独立保存；不会加入孩子的陪伴记录。语音仅用于本次转写，不保存原录音。",fontSize=12.sp)
    }
}

@Composable fun RecordsScreen(connection:JSONObject,debug:Boolean=false,back:()->Unit,play:(ByteArray)->Unit,bottom:(@Composable ()->Unit)?=null) {
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope();var rows by remember{mutableStateOf(JSONArray())};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};var remove by remember{mutableStateOf<String?>(null)}
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    val kind=if(debug)"debug" else "companion"
    fun launch(block:suspend ()->Unit){if(busy)return;scope.launch{busy=true;try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "读取失败"}finally{busy=false}}}
    suspend fun refresh(){rows=withContext(Dispatchers.IO){api.array("/v1/records?kind=$kind")}}
    LaunchedEffect(kind){launch{refresh()}}
    DisposableEffect(api,lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){scope.coroutineContext.cancelChildren();api.cancel();(context as? MainActivity)?.stopPreview()}};lifecycle.addObserver(observer);onDispose{api.cancel();(context as? MainActivity)?.stopPreview();lifecycle.removeObserver(observer)}}
    BackHandler(onBack=back)
    Page(if(debug)"调试记录" else "对话记录",message,busy,onBack=back,bottom=bottom){
        Text(if(debug)"仅显示这台管理设备的调试会话" else "默认不保存；启用后记录后续对话。这里显示请求及服务回答，不代表孩子已听完。",fontSize=12.sp)
        Row{Action("刷新",!busy){launch{refresh()}};TextButton(onClick={remove="all"},enabled=!busy&&rows.length()>0){Text("清空")}}
        if(rows.length()==0)Text("暂无记录")
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date((row.getDouble("created")*1000).toLong())),fontSize=12.sp)
            Text("我：${row.getString("question")}");Text("小伙伴：${row.getString("answer")}")
            Row{TextButton(onClick={launch{val audio=withContext(Dispatchers.IO){api.raw("/v1/records/${row.getString("id")}/speech","POST",JSONObject().toBody())};play(audio)}},enabled=!busy){Text("朗读回答")};TextButton(onClick={remove=row.getString("id")},enabled=!busy){Text("删除")}}
        }}}
        Text("历史回答按记录中的声音设置重新朗读，不是保存的儿童原声。",fontSize=12.sp)
    }
    remove?.let{id->AlertDialog(onDismissRequest={remove=null},title={Text(if(id=="all")"清空这类记录？" else "删除这条记录？")},text={Text("删除后不能恢复，不会修改资源库和已审核偏好。")},confirmButton={TextButton(onClick={remove=null;(context as? MainActivity)?.stopPreview();launch{withContext(Dispatchers.IO){api.json(if(id=="all")"/v1/records?kind=$kind" else "/v1/records/$id","DELETE")};refresh()}}){Text("删除")}},dismissButton={TextButton(onClick={remove=null}){Text("取消")}})}
}
