package org.familyrobot.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.familyrobot.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.sqrt

class RobotRuntime(private val activity:ComponentActivity,val vault:Vault,val connection:JSONObject) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val api=Api(connection)
    private val session=Session()
    private val camera=CameraInput(activity,activity) { diagnostic="相机不可用，已退回语音";cameraFailurePending=foreground && session.active;session.closeCamera() }
    private val input=AudioInput(activity,{ word -> scope.launch { keyword(word) } },{ bytes,ticket,echo -> scope.launch { recognizeCaptured(bytes,ticket,echo) } },{ reason -> scope.launch { diagnostic=reason } },{ ticket -> scope.launch { voiceStarted(ticket) } })
    private val manager=activity.getSystemService(AudioManager::class.java)
    private val power=activity.getSystemService(PowerManager::class.java)
    var state by mutableStateOf(SessionState.STANDBY);private set
    var mouth by mutableFloatStateOf(0f);private set
    var faceMode by mutableStateOf("standby");private set
    private var performance=""
    private var performanceUntil=0L
    private var visionTask=false
    private var cameraFailurePending=false
    private var cameraWarningPlaying=false
    private val wakeReplies=WakeReplies()
    private val promptCache=java.util.concurrent.ConcurrentHashMap<String,ByteArray>()
    private var openingFace="waking"
    private var openingReply:String?=null
    private var firstInput=false
    private var voiceActiveUntil=0L
    private var lastTouchAt=-5000L
    var lastFeedbackResult="none";private set
    var lastFeedbackAtMs=0L;private set
    var diagnostic by mutableStateOf("正在连接家庭服务");private set
    private val failures=vault.get(robotKey(connection,"service-failures")) ?: JSONObject()
    private fun failure(kind:String) { failures.put(kind,(failures.optLong(kind)+1).coerceAtMost(1000000000));vault.save(robotKey(connection,"service-failures"),failures) }
    var config by mutableStateOf(vault.get(robotKey(connection,"robot-config")) ?: JSONObject());private set
    var version by mutableIntStateOf(vault.get(robotKey(connection,"robot-version"))?.optInt("value") ?: 0);private set
    var online by mutableStateOf(false);private set
    var cameraActive by mutableStateOf(false);private set
    var micActive by mutableStateOf(false);private set
    // 仅内存中的有限状态诊断，不保存录音、识别文本或书名。
    var lastWakeSource="none";private set
    var lastWakeAtMs=0L;private set
    var keywordWakeCount=0L;private set
    var lastKeywordAction="none";private set
    var lastPauseStartedMs=0L;private set
    var lastPauseFinishedMs=0L;private set
    var lastPauseReason="none";private set
    var lastPersistenceDurationMs=0L;private set
    var foreground=false
    private var job:Job?=null
    private val cacheWriteLock=Mutex()
    private val downloadJobs=mutableMapOf<String,Job>()
    private var track:AudioTrack?=null
    @Volatile private var playbackSerial=0L
    private var trackRate=16000
    private var trackStartMs=0
    private var trackIsMedia=false
    private var focus:AudioFocusRequest?=null
    private var sessionId=UUID.randomUUID().toString()
    private val totals=(vault.get(robotKey(connection,"usage")) ?: JSONObject()).let { j -> j.keys().asSequence().associateWith { j.optLong(it) }.toMutableMap() }
    private val ledger=UsageLedger(totals)
    private var verifiedClock=run {
        val prior=vault.get(robotKey(connection,"clock"))?.optLong("boot",Long.MIN_VALUE) ?: Long.MIN_VALUE
        prior!=Long.MIN_VALUE && kotlin.math.abs(prior-(System.currentTimeMillis()-SystemClock.elapsedRealtime()))<120000
    }
    private val restoredReading=vault.get(robotKey(connection,"reading"))
    private var currentManifest:JSONObject?=restoredReading?.optJSONObject("manifest")
    private val queue=java.util.ArrayDeque<String>()
    private var scopeAnnouncedRevision=""
    private data class BookChoice(val session:String,val kind:String,val candidates:List<JSONObject>,val progress:JSONObject?=null,var expires:Long=Long.MAX_VALUE,var ready:Boolean=false)
    private var bookChoice:BookChoice?=null
    private var segmentIndex=restoredReading?.optInt("segmentIndex") ?: 0
    private var offsetMs=restoredReading?.optInt("offsetMs") ?: 0
    private val progressPending=vault.get(robotKey(connection,"progress-pending")) ?: JSONObject()
    private var progressSeq=maxOf(System.currentTimeMillis(),vault.get(robotKey(connection,"progress-sequence"))?.optLong("value") ?: 0,progressPending.keys().asSequence().map { progressPending.getJSONObject(it).optLong("seq") }.maxOrNull() ?: 0)
    private var lastPolicy=Permission.ALLOWED
    private val scheduler=ListeningScheduler()
    private var scheduledUntil=0L
    var appliedWakeName by mutableStateOf(vault.get(robotKey(connection,"kws"))?.optString("nickname") ?: "小伙伴");private set
    var appliedWakeVersion by mutableIntStateOf(vault.get(robotKey(connection,"kws"))?.optInt("version") ?: 0);private set
    private var pendingWakeName=""
    private var keywordVersion=-1
    private var pendingKeywords:String?=null
    private val cache=File(activity.filesDir,"library/"+robotScope(connection)).apply { mkdirs() }
    private val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val cameraFeedback=CameraFeedback(scope,
        volume={ (config.optJSONObject("voice")?.optDouble("volume",0.5)?.toFloat() ?: 0.5f) },
        permitted={ foreground && session.allowed && !session.muted },
        outputAllowed={ manager.getStreamVolume(AudioManager.STREAM_MUSIC)>0 && !manager.isStreamMute(AudioManager.STREAM_MUSIC) },
        acquireFocus={ focus() })
    val lastCameraFeedback get()=cameraFeedback.lastEvent+":"+cameraFeedback.lastResult
    private val selfCheck=HardwareSelfCheck(activity,scope,input,camera,connection,
        allowed={ !foreground && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            !config.optBoolean("muted") && power.currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE &&
            PolicyEngine.permission(policy(),System.currentTimeMillis(),ledger.used(System.currentTimeMillis(),policy().zone),verifiedClock && ledger.trusted && config.length()>0)==Permission.ALLOWED },
        cameraAllowed={ config.optBoolean("cameraAllowed",true) },
        volume={ (config.optJSONObject("voice")?.optDouble("volume",0.5)?.toFloat() ?: 0.5f) })
    val selfCheckReport get()=selfCheck.report
    val selfCheckMessage get()=selfCheck.message
    val selfCheckTranscript get()=selfCheck.transcript
    val selfCheckRunning get()=selfCheck.running
    fun startSelfCheck() { if(!foreground)selfCheck.start() }
    fun cancelSelfCheck()=selfCheck.cancel(clearText=true)
    init {
        scope.launch(Dispatchers.IO) {
            for(name in listOf("wake","wake_listen","wake_here","wake_touch","pet","tickle","chime","thinking","camera_unavailable")) {
                runCatching { activity.assets.open("prompts/$name.wav").use { promptCache[name]=it.readBytes() } }
            }
        }
        if(currentManifest!=null)session.restorePausedMedia()
        input.customKeywords=localKeywords(vault.get(robotKey(connection,"kws"))?.optString("keywords") ?: "")
        input.start()
        scope.launch {
            while(isActive) { tick();delay(200) }
        }
        scope.launch {
            while(isActive) {
                if(foreground || selfCheck.running || activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) sync()
                delay(2500)
            }
        }
        scope.launch {
            while(isActive) {
                if(foreground && online)flushProgress()
                delay(2500)
            }
        }
    }
    fun resumeForeground() { selfCheck.cancel(clearText=true);foreground=true;input.start();tick() }
    fun background() { selfCheck.cancel(clearText=true);foreground=false;stop();input.enabled=false;camera.setEnabled(false) }
    fun close() { background();input.close();camera.close();api.cancel();scope.cancel() }
    private fun has(permission:String)=ContextCompat.checkSelfPermission(activity,permission)==PackageManager.PERMISSION_GRANTED
    private fun policy():UsagePolicy {
        val p=config.optJSONObject("policy") ?: return UsagePolicy()
        val array=p.optJSONArray("intervals") ?: JSONArray()
        val intervals=(0 until array.length()).map { i -> val item=array.getJSONObject(i);val days=item.getJSONArray("days")
            QuietInterval((0 until days.length()).map { days.getInt(it) }.toSet(),LocalTime.parse(item.getString("start")),LocalTime.parse(item.getString("end"))) }
        return UsagePolicy(ZoneId.of(p.optString("timezone","Asia/Shanghai")),intervals,p.optInt("dailyMinutes"),p.optInt("mediaMinutes",30),p.optBoolean("manualBlocked"), (p.optDouble("overrideUntil",0.0)*1000).toLong())
    }
    private fun tick() {
        val now=SystemClock.elapsedRealtime();val wall=System.currentTimeMillis();val p=policy()
        ledger.tick(now,wall,p.zone,foreground && session.billable)
        val allowed=PolicyEngine.permission(p,wall,ledger.used(wall,p.zone),verifiedClock && ledger.trusted && config.length()>0)
        val thermal=power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE
        if(selfCheck.running) {
            session.configure(false,config.optBoolean("muted"),false)
            cameraFeedback.reset();input.cameraFeedbackPlaying=false
            selfCheck.tick();state=session.state;cameraActive=camera.fresh()!=null;micActive=input.recording
            return
        }
        val permitted=foreground && allowed==Permission.ALLOWED && !thermal
        val plans=config.optJSONArray("listeningPlans") ?: JSONArray()
        val due=scheduler.due(wall,p.zone,(0 until plans.length()).map { i -> val plan=plans.getJSONObject(i);val days=plan.getJSONArray("days");ListeningPlan(plan.getString("id"),plan.getString("playlistId"),(0 until days.length()).map { days.getInt(it) }.toSet(),LocalTime.parse(plan.getString("time")),plan.getInt("minutes"),plan.optBoolean("enabled")) })
        if(scheduledUntil>0 && now>=scheduledUntil) { stop();scheduledUntil=0 }
        if(due.isNotEmpty() && permitted && online && !session.active && !session.mediaPlaying && !config.optBoolean("muted")) {
            val plan=due.first();val date=java.time.Instant.ofEpochMilli(wall).atZone(p.zone).toLocalDate().toString()
            val key=robotKey(connection,"plan-"+plan.id)
            if(vault.get(key)?.optString("day")!=date) { vault.save(key,JSONObject().put("day",date));scheduledUntil=now+plan.minutes*60000L;playPlaylist(plan.playlistId,true) }
        }
        session.configure(permitted,config.optBoolean("muted"),config.optBoolean("cameraAllowed",true) && has(Manifest.permission.CAMERA))
        val previousState=session.state
        session.tick(now,p.mediaMinutes*60000L)
        if(!session.active || bookChoice?.let { it.session!=sessionId || now>=it.expires }==true)bookChoice=null
        if(session.state==SessionState.CLOSING && previousState!=SessionState.CLOSING) {
            val cameraWasReady=camera.ready
            cancelAudio();camera.setEnabled(false);val ticket=session.generation
            job=scope.launch {
                try {
                    if(cameraWasReady)delay(200)
                    if((session.closingPrompt!="chime" || !cameraWasReady) && (session.closingPrompt!="chime" || feedbackMode()!="visual"))localPrompt(session.closingPrompt,ticket)
                }
                finally { if(session.generation==ticket && session.state==SessionState.CLOSING)session.stop() }
            }
        }
        if(!permitted || session.muted || (!session.active && !session.mediaPlaying && session.state!=SessionState.CLOSING)) cancelAudio()
        if(!session.active && !session.mediaPlaying)pendingKeywords?.let { input.customKeywords=it;pendingKeywords=null;if(pendingWakeName.isNotBlank()){appliedWakeName=pendingWakeName;appliedWakeVersion=keywordVersion;pendingWakeName=""} }
        input.enabled=permitted && !session.muted && has(Manifest.permission.RECORD_AUDIO)
        input.capture=AudioInput.Capture(session.generation,input.enabled && session.state in setOf(SessionState.OPENING,SessionState.LISTENING,SessionState.FOLLOW_UP))
        camera.setEnabled(foreground && session.camera && !thermal)
        cameraFeedback.observe(camera.ready,!input.feedbackPlaying && now>=voiceActiveUntil)
        input.cameraFeedbackPlaying=cameraFeedback.playing;input.cameraFeedbackEndedAt=cameraFeedback.endedAt
        if(cameraFailurePending && session.active && !session.mediaPlaying && session.state in setOf(SessionState.LISTENING,SessionState.FOLLOW_UP) && job?.isActive!=true && now>=voiceActiveUntil) {
            cameraFailurePending=false
            val ticket=session.generation
            job=scope.launch {
                try {
                    if(firstInput)openingReply="camera_unavailable" else session.speaking(ticket)
                    cameraWarningPlaying=true;input.feedbackPlaying=true
                    localPrompt("camera_unavailable",ticket)
                    if(session.valid(ticket))session.playbackEnded(ticket,SystemClock.elapsedRealtime())
                } finally { if(session.generation==ticket) { cameraWarningPlaying=false;input.feedbackPlaying=false;input.feedbackEndedAt=SystemClock.elapsedRealtime() } }
            }
        }
        faceMode=when {
            cameraWarningPlaying -> "speaking"
            session.state==SessionState.BLOCKED && allowed==Permission.UNTRUSTED_TIME && !online -> "fault"
            session.state==SessionState.STANDBY && (!online || !has(Manifest.permission.RECORD_AUDIO)) -> "fault"
            session.state in setOf(SessionState.LISTENING,SessionState.FOLLOW_UP) && !input.recording -> "fault"
            else -> interactionFace(session.state,session.mediaPlaying,session.activity==ActivityMode.SONG,visionTask,
                if(performanceUntil>now)performance else "",openingFace,now<voiceActiveUntil)
        }
        state=session.state;cameraActive=session.camera && camera.fresh()!=null;micActive=input.recording
        if(allowed!=lastPolicy) { diagnostic="使用状态：$allowed";lastPolicy=allowed }
        if(thermal)diagnostic="设备温度较高，已暂停并释放相机/麦克风"
        // 每秒落盘，进程崩溃最大计费误差1秒。
        if(now/1000!=lastSave) { lastSave=now/1000
            val checkpointStarted=SystemClock.elapsedRealtime()
            val usage=JSONObject(totals as Map<*,*>);val usageText=usage.toString()
            if(usageText!=lastSavedUsage) { vault.save(robotKey(connection,"usage"),usage);lastSavedUsage=usageText }
            currentManifest?.let { val reading=JSONObject().put("manifest",it).put("segmentIndex",segmentIndex).put("offsetMs",offsetMs);val text=reading.toString();if(text!=lastSavedReading) { vault.save(robotKey(connection,"reading"),reading);lastSavedReading=text } }
            lastPersistenceDurationMs=SystemClock.elapsedRealtime()-checkpointStarted
        }
    }
    private var lastSavedUsage=""
    private var lastSavedReading=""
    private var lastSave=0L
    private suspend fun sync() {
        try {
            val failureSnapshot=JSONObject(failures.toString())
            val playback=JSONObject().put("state",if(session.mediaPaused)"paused" else if(session.mediaPlaying && trackIsMedia && track?.playState==AudioTrack.PLAYSTATE_PLAYING)"playing" else if(session.mediaPlaying)"loading" else "idle")
            currentManifest?.takeIf { session.mediaPlaying || session.mediaPaused }?.let { m -> playback.put("resourceId",m.optString("resourceId")).put("revisionId",m.optString("revisionId")).put("title",m.optString("title")).put("segment",segmentIndex).put("total",m.optJSONArray("segments")?.length() ?: 0).put("positionMs",offsetMs.coerceAtLeast(0)) }
            val reason=when { selfCheck.running -> "SELF_CHECK";!foreground -> "MANAGEMENT";power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE -> "THERMAL";config.optBoolean("muted") -> "muted";else -> lastPolicy.name }
            val heartbeat=withContext(Dispatchers.IO) { api.json("/v1/heartbeat","POST",JSONObject().put("status",if(selfCheck.running)"self_check" else state.name.lowercase()).put("appliedVersion",version).put("camera",cameraActive).put("microphone",micActive).put("reason",reason).put("serviceFailures",failureSnapshot).put("playback",playback).put("wakeName",appliedWakeName).put("wakeVersion",appliedWakeVersion.coerceAtLeast(0)),timeoutMs=5000) }
            val delta=kotlin.math.abs(heartbeat.getDouble("serverTime")*1000-System.currentTimeMillis())
            if(delta<120000) { if(!verifiedClock || !ledger.trusted) { ledger.revalidate();vault.save(robotKey(connection,"clock"),JSONObject().put("boot",System.currentTimeMillis()-SystemClock.elapsedRealtime())) };verifiedClock=true }
            else { verifiedClock=false;diagnostic="手机与服务端时间不一致，请修正时间" }
            val current=withContext(Dispatchers.IO) { api.json("/v1/robots/${connection.getString("deviceId")}/config") }
            applyConfig(current.getInt("version"),current.getJSONObject("config"))
            if(keywordVersion!=version) {
                val words=withContext(Dispatchers.IO) { api.json("/v1/kws/keywords") }
                pendingWakeName=words.optString("nickname");pendingKeywords=localKeywords(words.getString("keywords"));vault.save(robotKey(connection,"kws"),words);keywordVersion=words.getInt("version")
            }
            val pending=withContext(Dispatchers.IO) { api.array("/v1/commands") }
            for(i in 0 until pending.length()) {
                val command=pending.getJSONObject(i)
                if(command.optString("kind")=="control") {
                    val body=command.getJSONObject("body");val action=body.getString("action");val resource=body.optString("resourceId")
                    val accepted=action in setOf("download","remove_download","stop","pause") || session.allowed
                    if(accepted) when(action) {
                        "stop" -> stop()
                        "pause" -> pause()
                        "resume" -> resumeMedia()
                        "play" -> playResource(resource)
                        "playlist" -> playPlaylist(body.getString("playlistId"))
                        "download" -> download(resource)
                        "remove_download" -> { downloadJobs.remove(resource)?.cancel();withContext(Dispatchers.IO) { File(cache,resource).deleteRecursively() } }
                    }
                    withContext(Dispatchers.IO) { api.json("/v1/commands/${command.getString("id")}/ack","POST",JSONObject().put("applied",accepted).put("version",version)) }
                    continue
                }
                if(command.getInt("expected")!=version || command.getDouble("expires")*1000<=System.currentTimeMillis())continue
                // 先暂存；ACK成功才作为已确认配置，ACK丢失由下一次配置同步收敛。
                vault.save(robotKey(connection,"config-staged"),command)
                withContext(Dispatchers.IO) { api.json("/v1/commands/${command.getString("id")}/ack","POST",JSONObject().put("applied",true).put("version",version+1)) }
                applyConfig(version+1,command.getJSONObject("body"));vault.remove(robotKey(connection,"config-staged"))
            }
            withContext(Dispatchers.IO) {
                val revocations=api.json("/v1/catalog/revocations").getJSONArray("items")
                for(i in 0 until revocations.length()) {
                    val revoked=revocations.getJSONObject(i);val rid=revoked.getString("id")
                    if(!rid.matches(Regex("[a-f0-9]{32}")))continue
                    val dir=File(cache,rid);val manifest=File(dir,"manifest.json")
                    val revision=if(manifest.isFile)runCatching { JSONObject(manifest.readText()).optString("revisionId") }.getOrDefault("") else ""
                    val revokedIds=revoked.optJSONArray("revokedRevisionIds")?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() }
                    fun withdrawn(value:String)=ResourceVersions.withdrawn(value,revoked.optString("activeRevision"),revokedIds)
                    if(withdrawn(revision))dir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        if(bookChoice?.candidates?.any { it.optString("resourceId")==rid && withdrawn(it.optString("revisionId")) }==true)bookChoice=null
                        if(currentManifest?.optString("resourceId")==rid && withdrawn(currentManifest!!.optString("revisionId"))) { currentManifest=null;stop() }
                    }
                }
                val day=java.time.Instant.ofEpochMilli(System.currentTimeMillis()).atZone(policy().zone).toLocalDate().toString()
                val usage=withContext(Dispatchers.Main) { JSONObject().put("day",day).put("seconds",(totals[day] ?: 0)/1000).put("seq",nextProgressSeq()) }
                api.json("/v1/usage","POST",usage)
            }
            val reading=currentManifest
            if(reading!=null) {
                val segs=reading.getJSONArray("segments");val id=if(reading.optString("audioAsset").isNotEmpty())"audio" else if(segmentIndex<segs.length())segs.getJSONObject(segmentIndex).getString("id") else ""
                if(id.isNotEmpty())queueProgress(reading.getString("resourceId"),reading.getString("revisionId"),id,offsetMs)
            }
            online=true
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            if(online)failure("network")
            online=false
            if(e is ProtocolMismatchException)diagnostic=e.message ?: "服务协议不兼容"
            if(e.message?.startsWith("401")==true) { config=JSONObject();verifiedClock=false;diagnostic="设备凭据已撤销，请重新登记";stop() }
        }
    }
    /** 进度回报与播音完全分离；主线程合并每本书的最新位置，单一后台发送者。 */
    private fun nextProgressSeq():Long {
        progressSeq++
        // 先保存序号再入队/发请求；进程重启或小幅校时不能回退到已确认序号。
        vault.save(robotKey(connection,"progress-sequence"),JSONObject().put("value",progressSeq))
        return progressSeq
    }
    private fun queueProgress(rid:String,revision:String,segment:String,position:Int) {
        if(!progressPending.has(rid) && progressPending.length()>=128) {
            val oldest=progressPending.keys().asSequence().minByOrNull { progressPending.getJSONObject(it).getLong("seq") }
            oldest?.let { progressPending.remove(it) }
            failure("progress-overflow");diagnostic="离线待同步进度过多，仅保留最近128项；当前阅读不受影响"
        }
        progressPending.put(rid,JSONObject().put("revisionId",revision).put("segmentId",segment).put("offsetMs",position).put("seq",nextProgressSeq()))
        vault.save(robotKey(connection,"progress-pending"),progressPending)
    }
    private suspend fun flushProgress() {
        val rid=progressPending.keys().asSequence().minByOrNull { progressPending.getJSONObject(it).getLong("seq") } ?: return
        val value=JSONObject(progressPending.getJSONObject(rid).toString())
        val finished=try {
            withContext(Dispatchers.IO) { api.json("/v1/resources/$rid/progress","PUT",value,timeoutMs=5000) }
            true
        } catch(e:CancellationException) { throw e }
        catch(e:ApiHttpException) {
            // 已删除/撤回/换版等永久拒绝不反复重试；网络和限流保留待同步项。
            if(e.status in setOf(400,404,409,410,422)) { failure("progress-rejected");true } else false
        } catch(_:java.io.IOException) { false }
        catch(_:Exception) { failure("progress-response");false }
        if(finished && progressPending.optJSONObject(rid)?.optLong("seq")==value.getLong("seq")) {
            progressPending.remove(rid);vault.save(robotKey(connection,"progress-pending"),progressPending)
        }
    }
    private fun applyConfig(newVersion:Int,newConfig:JSONObject) {
        if(newVersion!=version || config.length()==0) {
            config=newConfig;version=newVersion;vault.save(robotKey(connection,"robot-config"),newConfig);vault.save(robotKey(connection,"robot-version"),JSONObject().put("value",version))
            tick()
        }
    }
    private fun keyword(word:String) {
        if(!foreground || selfCheck.running)return
        lastKeywordAction=when {
            word.startsWith("book_") -> "book"
            word in setOf("wake","rest","camera_off","next_chapter","previous_chapter","stop","resume","next","previous","停止","继续","下一页","上一页") -> word
            else -> "unknown"
        }
        when(word) {
            "rest" -> stop()
            "camera_off" -> { session.closeCamera();camera.setEnabled(false) }
            "next_chapter" -> changePage(1,"chapter")
            "previous_chapter" -> changePage(-1,"chapter")
            "停止","stop" -> if(session.active || session.mediaPlaying)pause()
            "继续","resume" -> if(bookChoice!=null) { if(bookChoice?.kind=="resume" && bookChoice?.ready==true) { cancelAudio();val ticket=session.generation;job=scope.launch { handleBookChoice("继续",ticket) } } } else if(session.mediaPaused)resumeMedia()
            "下一页","next" -> if(session.mediaPlaying || session.mediaPaused)changePage(1)
            "上一页","previous" -> if(session.mediaPlaying || session.mediaPaused)changePage(-1)
            else -> if(word.startsWith("book_"))playResource(word.removePrefix("book_")) else wakeFrom("keyword")
        }
    }
    fun wake() = wakeFrom("local-control")
    private fun feedbackMode()=config.optJSONObject("interaction")?.optString("wakeFeedback","voice") ?: "voice"
    private fun playful()=config.optJSONObject("interaction")?.optBoolean("playful",true) ?: true
    private fun wakeFrom(source:String,touch:FaceTouch?=null) {
        tick()
        if(!session.allowed || session.muted || !has(Manifest.permission.RECORD_AUDIO)) {
            diagnostic="当前不可唤醒，请检查使用安排、麦克风开关和权限";return
        }
        if(source=="keyword" && session.state in setOf(SessionState.OPENING,SessionState.LISTENING,SessionState.FOLLOW_UP)) {
            performanceUntil=0;tick();return
        }
        cancelAudio();performanceUntil=0;visionTask=false;voiceActiveUntil=0
        val ticket=session.wake(SystemClock.elapsedRealtime(),config.optBoolean("cameraAllowed",true) && has(Manifest.permission.CAMERA)) ?: return
        lastWakeSource=source;lastWakeAtMs=SystemClock.elapsedRealtime()
        if(source=="keyword")keywordWakeCount++
        openingFace=if(!playful())"waking" else when(touch) { FaceTouch.PET -> "pet";FaceTouch.TICKLE -> "tickle";else -> "waking" }
        openingReply=wakeReplies.next(touch,feedbackMode(),playful())
        cameraFailurePending=config.optBoolean("cameraAllowed",true) && !has(Manifest.permission.CAMERA)
        bookChoice=null;firstInput=true;sessionId=UUID.randomUUID().toString();diagnostic="已唤醒";tick()
        job=scope.launch {
            try {
                // 先显示睁眼并开始保护首句；连着说时VAD可在短窗口内让出开场。
                delay(350)
                if(!session.valid(ticket))return@launch
                val name=if(cameraFailurePending && voiceActiveUntil<=SystemClock.elapsedRealtime()) { cameraFailurePending=false;"camera_unavailable" } else if(session.state==SessionState.LISTENING && voiceActiveUntil>SystemClock.elapsedRealtime()) {
                    if(feedbackMode()=="visual")null else "chime"
                } else openingReply
                openingReply=name
                if(name!=null) {
                    input.feedbackPlaying=true
                    try { localPrompt(name,ticket) }
                    finally { if(session.generation==ticket) { input.feedbackPlaying=false;input.feedbackEndedAt=SystemClock.elapsedRealtime() } }
                } else lastFeedbackResult="visual-only"
            } finally {
                if(session.generation==ticket) { session.openingFinished(ticket,SystemClock.elapsedRealtime());tick() }
            }
        }
    }
    private fun voiceStarted(ticket:Long) {
        if(!session.valid(ticket) || input.feedbackPlaying || cameraFeedback.playing)return
        val now=SystemClock.elapsedRealtime()
        if(session.state in setOf(SessionState.OPENING,SessionState.LISTENING,SessionState.FOLLOW_UP)) {
            performanceUntil=0;voiceActiveUntil=now+350;session.voiceStarted(now);tick()
        }
    }
    fun stop() { cameraFailurePending=false;cameraFeedback.reset();input.cameraFeedbackPlaying=false;selfCheck.cancel(clearText=true);bookChoice=null;scheduledUntil=0;queue.clear();performanceUntil=0;visionTask=false;voiceActiveUntil=0;session.stop();cancelAudio();camera.setEnabled(false);mouth=0f;tick() }
    fun pause()=pause("local-control")
    fun pause(reason:String) { bookChoice=null;lastPauseReason=reason;lastPauseStartedMs=SystemClock.elapsedRealtime();performanceUntil=0;visionTask=false;voiceActiveUntil=0;session.pause(SystemClock.elapsedRealtime());cancelAudio();tick();lastPauseFinishedMs=SystemClock.elapsedRealtime() }
    fun touch()=touch(FaceTouch.TAP)
    fun touch(gesture:FaceTouch) {
        tick()
        if(!session.allowed || session.muted)return
        if(session.state==SessionState.STANDBY && !session.mediaPlaying) { wakeFrom("touch",gesture);return }
        val now=SystemClock.elapsedRealtime()
        if(session.state !in setOf(SessionState.LISTENING,SessionState.FOLLOW_UP) || voiceActiveUntil>now || now-lastTouchAt<5000 || !playful())return
        // 续听中的逗趣只做无声表情，不能让未经验证的双讲抢走孩子的话轮。
        lastTouchAt=now;performance=when(gesture) { FaceTouch.TAP -> "happy";FaceTouch.PET -> "pet";FaceTouch.TICKLE -> "tickle" }
        performanceUntil=now+1100;tick()
    }
    private fun cancelAudio() {
        cameraWarningPlaying=false
        cameraFeedback.cancelPending();input.cameraFeedbackPlaying=false
        job?.cancel();job=null
        input.feedbackPlaying=false
        playbackSerial++
        track?.let { runCatching {
            if(trackIsMedia)offsetMs=trackStartMs+(it.playbackHeadPosition*1000L/trackRate).toInt()
            // 续播会创建新音轨；立即静音并结束旧音轨，不等待IO协程finally执行stop。
            it.setVolume(0f);it.pause();it.flush();it.stop()
        } }
        val previousFocus=focus;focus=null;previousFocus?.let { manager.abandonAudioFocusRequest(it) };mouth=0f
    }
    private fun focus():Boolean {
        if(focus!=null)return true
        lateinit var request:AudioFocusRequest
        request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> if(change<0)scope.launch { if(focus===request)pause("focus-loss") } }.build()
        val granted=manager.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if(granted)focus=request else { focus=null;lastFeedbackResult="audio-focus-denied";diagnostic="声音未播放：其他应用占用音频焦点" }
        return granted
    }
    private fun recognize(bytes:ByteArray)=recognizeCaptured(bytes,session.generation,false)
    private fun recognizeCaptured(bytes:ByteArray,captureTicket:Long,echo:Boolean) {
        if(!session.valid(captureTicket) || session.state !in setOf(SessionState.OPENING,SessionState.LISTENING,SessionState.FOLLOW_UP))return
        val wasFirst=firstInput
        val reply=if(echo)openingReply else null
        val waitingSince=session.followUpAt ?: SystemClock.elapsedRealtime()
        val userAt=session.lastInputAt
        cancelAudio();performanceUntil=0;voiceActiveUntil=0
        val ticket=session.recognizing(SystemClock.elapsedRealtime()) ?: return
        tick()
        job=scope.launch {
            try {
                val raw=withContext(Dispatchers.IO) { api.upload("/v1/speech/recognize","speech.wav",bytes,"audio/wav").getString("text") }
                if(!session.valid(ticket))return@launch
                val text=if(wasFirst)OpeningTranscript.clean(raw,config.optString("nickname","小伙伴"),reply) else raw
                if(wasFirst && text.isBlank()) {
                    session.discardOpeningEcho(ticket,waitingSince,userAt);tick();return@launch
                }
                firstInput=false
                if(text.isBlank()) { localPrompt("unclear",ticket);session.playbackEnded(ticket,SystemClock.elapsedRealtime());return@launch }
                if(text.contains("停止") || text.trim().equals("stop",true)) { pause();return@launch }
                session.thinking(ticket)
                if(handleBookChoice(text,ticket))return@launch
                val readingCommand=ReadingNavigation.command(text)
                if(readingCommand!=null && currentManifest!=null) { navigateReading(readingCommand,ticket);return@launch }
                val payload=JSONObject().put("sessionId",sessionId).put("text",text)
                currentManifest?.optString("resourceId")?.takeIf { it.isNotEmpty() }?.let { payload.put("currentResourceId",it) }
                val visual=org.familyrobot.core.VisualIntent.needsFreshFrame(text)
                if(visual) { payload.put("visualRequest",true);visionTask=true }
                if(visual && session.camera) {
                    val frame=waitFrame()
                    if(frame!=null)payload.put("image",android.util.Base64.encodeToString(frame.jpeg,2)).put("imageAgeMs",SystemClock.elapsedRealtime()-frame.at)
                }
                val response=coroutineScope {
                    val cue=launch {
                        delay(2000)
                        if(session.valid(ticket) && feedbackMode()=="voice")localPrompt("thinking",ticket)
                    }
                    try { withContext(Dispatchers.IO) { api.json("/v1/turns","POST",payload) } }
                    finally { cue.cancelAndJoin();if(session.valid(ticket))mouth=0f }
                }
                if(!session.valid(ticket))return@launch
                when(response.getString("action")) {
                    "stop" -> pause()
                    "rest" -> stop()
                    "camera_off" -> { session.closeCamera();camera.setEnabled(false);session.playbackEnded(ticket,SystemClock.elapsedRealtime()) }
                    "next_chapter" -> changePage(1,"chapter")
                    "previous_chapter" -> changePage(-1,"chapter")
                    "resume" -> resumeMedia()
                    "next_page" -> changePage(1)
                    "previous_page" -> changePage(-1)
                    "perform" -> { performance=response.optString("expression");performanceUntil=SystemClock.elapsedRealtime()+4000;session.playbackEnded(ticket,SystemClock.elapsedRealtime()) }
                    "book" -> {
                        visionTask=true
                        if(!session.camera) { say("相机现在没有开启，请爸爸妈妈检查相机开关和权限后再叫我看书。",ticket);return@launch }
                        val frame=waitFrame()
                        if(frame==null)say("暂时没有拿到相机画面，请爸爸妈妈检查相机。",ticket)
                        else {
                            val result=withContext(Dispatchers.IO) { api.upload("/v1/books/recognize?imageAgeMs=${SystemClock.elapsedRealtime()-frame.at}","cover.jpg",frame.jpeg,"image/jpeg") }
                            if(!session.valid(ticket))return@launch
                            offerBooks(result,ticket)
                        }
                    }
                    "play" -> requestBook(response.getString("resourceId"),ticket)
                    "book_result" -> offerBooks(response.getJSONObject("result"),ticket)
                    else -> say(response.optString("text"),ticket,response.optBoolean("story",false))
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(session.valid(ticket)) {
                failure("turn");diagnostic="服务暂不可用；已取消本次回答"
                if(echo && wasFirst)session.discardOpeningEcho(ticket,waitingSince,userAt)
                else { localPrompt("offline",ticket);session.playbackEnded(ticket,SystemClock.elapsedRealtime()) }
            } }
            finally { visionTask=false }
        }
    }
    private suspend fun waitFrame():CameraInput.Frame? {
        if(!session.camera)return null
        val requestedAt=SystemClock.elapsedRealtime()
        repeat(20) { camera.fresh()?.takeIf { it.at>=requestedAt }?.let { return it };delay(100) }
        return null
    }
    private suspend fun localPrompt(name:String,ticket:Long) {
        if(!session.valid(ticket))return
        try {
            val bytes=promptCache[name] ?: withContext(Dispatchers.IO) {
                activity.assets.open("prompts/$name.wav").use { it.readBytes() }.also { promptCache[name]=it }
            }
            if(session.valid(ticket))playAudio(bytes,ticket,false)
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) {
            lastFeedbackResult="prompt-failed:$name";diagnostic="本地提示音播放失败，已保留表情反馈"
            if(name!="chime" && session.valid(ticket))try {
                val cue=promptCache["chime"] ?: withContext(Dispatchers.IO) { activity.assets.open("prompts/chime.wav").use { it.readBytes() } }
                playAudio(cue,ticket,false)
            } catch(e:CancellationException) { throw e } catch(_:Exception) { lastFeedbackResult="audio-unavailable" }
        }
    }
    private suspend fun say(text:String,ticket:Long,story:Boolean=false) {
        if(text.isBlank() || !session.valid(ticket))return
        val chunks=text.take(600).split(Regex("(?<=[。！？.!?])")).map { it.trim() }.filter { it.isNotEmpty() }
        coroutineScope {
            fun load(chunk:String)=async(Dispatchers.IO) { api.raw("/v1/speech/reply","POST",JSONObject().put("text",chunk).put("voice",config.optJSONObject("voice") ?: JSONObject()).put("story",story).toBody()) }
            var pending=load(chunks.first())
            for(i in chunks.indices) {
                val audio=pending.await()
                if(!session.valid(ticket))return@coroutineScope
                if(i+1<chunks.size)pending=load(chunks[i+1])
                session.speaking(ticket);playAudio(audio,ticket,false)
            }
        }
        session.playbackEnded(ticket,SystemClock.elapsedRealtime())
    }

    fun playPlaylist(pid:String,scheduled:Boolean=false) {
        if(!scheduled)scheduledUntil=0
        if(!session.allowed || session.muted)return
        cancelAudio();queue.clear()
        if(!session.active)session.wake(SystemClock.elapsedRealtime(),false)
        job=scope.launch {
            try {
                val lists=withContext(Dispatchers.IO) { api.array("/v1/playlists") }
                val list=(0 until lists.length()).map { lists.getJSONObject(it) }.first { it.getString("id")==pid }
                val ids=list.getJSONArray("resources")
                for(i in 0 until ids.length())queue.addLast(ids.getString(i))
                if(queue.isNotEmpty())playResource(queue.removeFirst(),true)
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { failure("media");diagnostic="清单不可播放，请在家长端检查资源状态" }
        }
    }
    private suspend fun offerBooks(result:JSONObject,ticket:Long) {
        bookChoice=null
    when(result.getString("status")) {
        "MATCH" -> { val candidate=result.getJSONArray("candidates").getJSONObject(0);requestBook(candidate.getString("resourceId"),ticket,candidate.getString("revisionId")) }
        "AMBIGUOUS" -> {
            val array=result.getJSONArray("candidates")
            if(result.optInt("candidateCount",array.length())>2)say("找到了好几个版本，请给我看看书的ISBN，或请爸爸妈妈在书架里选一本。",ticket)
            else {
                val candidates=(0 until array.length()).map { array.getJSONObject(it) }
                val choice=BookChoice(sessionId,"choose",candidates)
                val options=candidates.mapIndexed { i,c -> "第${i+1}本，${c.getString("title")}，${c.optString("edition").ifBlank { "未标注版本" }}，${when(c.optString("language")) { "en" -> "英语";"bilingual" -> "双语";else -> "中文" }}" }.joinToString("；")
                if(options.length>450)say("这些图书的名称和版本比较长，请爸爸妈妈在书架里帮忙选一本。",ticket)
                else announceBookChoice(choice,"找到两本书。$options。你想读第一本还是第二本？",ticket)
            }
        }
        "UNAVAILABLE" -> say("封面识别服务暂时不可用，请稍后再试，或请爸爸妈妈从书架选书。",ticket)
        "RETAKE" -> say("封面的字还没看清，请把书拿稳，避开反光，再让我看一次。",ticket)
        "NOT_READABLE" -> say(when(result.optString("reason")) {
            "UNLISTED" -> "这本书已经下架了，请爸爸妈妈在书架里检查。"
            "UNPUBLISHED" -> "这本书已经录入，还要等爸爸妈妈校对并发布才能读。"
            "NO_TEXT" -> "书架里有这本书，但还没有录入可读正文，请爸爸妈妈补上书页。"
            else -> "书架里有相关图书，目前还不能读，请爸爸妈妈检查正文和发布状态。"
        },ticket)
        else -> say("书架里还没有找到这本书，请爸爸妈妈先把它录入。",ticket)
    }
    }
    private suspend fun announceBookChoice(choice:BookChoice,text:String,ticket:Long) {
        bookChoice=choice
        try {
            say(text,ticket)
            if(bookChoice===choice) {
                if(session.valid(ticket)) { choice.ready=true;choice.expires=SystemClock.elapsedRealtime()+30000 }
                else bookChoice=null
            }
        } catch(error:Throwable) { if(bookChoice===choice)bookChoice=null;throw error }
    }
    private suspend fun requestBook(rid:String,ticket:Long,expectedRevision:String?=null) {
        val manifest=try { withContext(Dispatchers.IO) { api.json("/v1/resources/$rid/manifest") } }
        catch(error:ApiHttpException) {
            if(error.status !in setOf(404,409))throw error
            say(if(error.status==404)"这本书已经不在书架里了，请爸爸妈妈检查。" else "这本书目前没有可读的发布版本，请爸爸妈妈检查发布或下架状态。",ticket);return
        }
        if(!session.valid(ticket))return
        if(expectedRevision!=null && manifest.getString("revisionId")!=expectedRevision) {
            say("图书版本刚刚变化了，请再给我看一次封面。",ticket);return
        }
        if(manifest.optString("kind")!="book") { playResource(rid,expectedRevision=manifest.getString("revisionId"));return }
        val current=currentManifest?.takeIf { it.optString("resourceId")==rid }
        val progress=if(current!=null) {
            val segments=current.getJSONArray("segments")
            JSONObject().put("revision_id",current.getString("revisionId")).put("segment_id",if(current.optString("audioAsset").isNotEmpty())"audio" else segments.optJSONObject(segmentIndex)?.optString("id") ?: "").put("offset_ms",offsetMs)
        } else withContext(Dispatchers.IO) { api.json("/v1/resources/$rid/progress") }
        if(!session.valid(ticket))return
        val candidate=JSONObject().put("resourceId",rid).put("revisionId",manifest.getString("revisionId"))
        val segments=manifest.getJSONArray("segments")
        val position=(0 until segments.length()).firstOrNull { segments.getJSONObject(it).getString("id")==progress.optString("segment_id") }
        val validPosition=position!=null || (manifest.optString("audioAsset").isNotEmpty() && progress.optString("segment_id")=="audio")
        if(progress.optString("revision_id")==manifest.getString("revisionId") && validPosition && ((position ?: 0)>0 || progress.optInt("offset_ms")>0)) {
            val choice=BookChoice(sessionId,"resume",listOf(candidate),progress)
            announceBookChoice(choice,"找到《${manifest.getString("title")}》。要接着上次读，还是从头开始读？",ticket)
        } else {
            say("找到《${manifest.getString("title")}》，我们开始读。",ticket)
            if(session.valid(ticket))playResource(rid,expectedRevision=manifest.getString("revisionId"))
        }
    }
    private suspend fun handleBookChoice(text:String,ticket:Long):Boolean {
        val choice=bookChoice ?: return false
        if(choice.session!=sessionId || SystemClock.elapsedRealtime()>=choice.expires) { bookChoice=null;return false }
        if(!choice.ready)return true
        if(ReadingNavigation.cancelled(text)) { bookChoice=null;say("好的，先不读。",ticket);return true }
        if(choice.kind=="choose") {
            val index=ReadingNavigation.choice(text)
            if(index!=null && index in choice.candidates.indices) {
                bookChoice=null;val selected=choice.candidates[index]
                requestBook(selected.getString("resourceId"),ticket,selected.getString("revisionId"));return true
            }
        } else {
            val command=ReadingNavigation.command(text)
            if(command==ReadingNavigation.Command.Restart || command==ReadingNavigation.Command.Resume) {
                bookChoice=null;val selected=choice.candidates.single()
                playResource(selected.getString("resourceId"),expectedRevision=selected.getString("revisionId"),resumePoint=if(command==ReadingNavigation.Command.Resume)choice.progress else null)
                return true
            }
        }
        // 新问题结束当前选择，不能让之后无关的“第二个”触发旧书。
        bookChoice=null
        return false
    }
    private suspend fun navigateReading(command:ReadingNavigation.Command,ticket:Long) {
        if(command==ReadingNavigation.Command.Resume) { resumeMedia();return }
        val manifest=currentManifest ?: return
        if(manifest.optString("audioAsset").isNotEmpty() && command!=ReadingNavigation.Command.Restart) {
            say("这本书使用整段录音，还没有分段位置，只能继续或从头播放。",ticket);return
        }
        val array=manifest.getJSONArray("segments")
        val segments=(0 until array.length()).map { val s=array.getJSONObject(it);ReadingNavigation.Segment(s.getString("pageId"),s.optString("label"),s.optString("chapter")) }
        val location=if(command==ReadingNavigation.Command.Restart)ReadingNavigation.Location.Found(0) else ReadingNavigation.locate(command,segments)
        when(location) {
            is ReadingNavigation.Location.Unavailable -> say(location.message,ticket)
            is ReadingNavigation.Location.Found -> {
                if(location.notice.isNotEmpty())say(location.notice,ticket)
                if(!session.valid(ticket))return
                pause();segmentIndex=location.index;offsetMs=0;session.restorePausedMedia();resumeMedia()
            }
        }
    }
    fun playResource(rid:String,fromQueue:Boolean=false,expectedRevision:String?=null,resumePoint:JSONObject?=null) {
        bookChoice=null
        if(!fromQueue) { queue.clear();scheduledUntil=0 }
        if(!rid.matches(Regex("[a-f0-9]{32}")))return
        if(!session.allowed || session.muted)return
        cancelAudio()
        if(!session.active)session.wake(SystemClock.elapsedRealtime(),false)
        job=scope.launch {
            try {
                val manifest=withContext(Dispatchers.IO) {
                    if(!online)readOffline(rid) else try { api.json("/v1/resources/$rid/manifest") }
                    catch(error:java.io.IOException) {
                        // 心跳状态可能尚未发现断网；只对传输失败回退，撤回/凭据/证书错误不能绕过。
                        if(error is ApiHttpException || error is javax.net.ssl.SSLException)throw error
                        val cached=readOffline(rid)
                        withContext(Dispatchers.Main) { if(online)failure("network");online=false }
                        cached
                    }
                }
                val revision=manifest.getString("revisionId")
                if(expectedRevision!=null && expectedRevision!=revision) {
                    say("图书版本刚刚变化了，请再选一次。",session.generation);return@launch
                }
                val previous=currentManifest?.takeIf { it.optString("resourceId")==rid }?.optString("revisionId")
                    ?: if(online)withContext(Dispatchers.IO) { runCatching { api.json("/v1/resources/$rid/progress").optString("revision_id") }.getOrDefault("") } else ""
                if(online && !previous.isNullOrEmpty() && previous!=revision) {
                    diagnostic="这本书有新版本，将从头开始读"
                    val ticket=session.generation
                    say("这本书有新版本，我们从头开始读。",ticket)
                    if(!session.valid(ticket))return@launch
                }
                currentManifest=manifest;segmentIndex=0;offsetMs=0;scopeAnnouncedRevision=""
                if(resumePoint!=null && resumePoint.optString("revision_id")==revision) {
                    val segments=manifest.getJSONArray("segments")
                    val selected=(0 until segments.length()).firstOrNull { segments.getJSONObject(it).getString("id")==resumePoint.optString("segment_id") }
                    if(selected!=null) { segmentIndex=selected;offsetMs=resumePoint.optInt("offset_ms").coerceAtLeast(0) }
                    else if(manifest.optString("audioAsset").isNotEmpty() && resumePoint.optString("segment_id")=="audio")offsetMs=resumePoint.optInt("offset_ms").coerceAtLeast(0)
                }
                runMedia()
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { failure("media");diagnostic="此资源无法播放，请检查发布或完整下载状态";session.stop() }
        }
    }
    fun download(rid:String) {
        if(!rid.matches(Regex("[a-f0-9]{32}")) || downloadJobs[rid]?.isActive==true)return
        downloadJobs[rid]=scope.launch {
            var revision=""
            val stage=File(cache,"$rid.partial")
            try {
                withContext(Dispatchers.IO) {
                    stage.deleteRecursively();stage.mkdirs()
                    val manifest=api.json("/v1/resources/$rid/manifest");revision=manifest.getString("revisionId")
                    api.json("/v1/resources/$rid/download","PUT",JSONObject().put("revisionId",revision).put("state","downloading"))
                    val segments=manifest.getJSONArray("segments");val original=manifest.optString("audioAsset")
                    val ids=(if(original.isNotEmpty())listOf("audio") else (0 until segments.length()).map { segments.getJSONObject(it).getString("id") })+listOfNotNull(manifest.optJSONObject("scopeNotice")?.getString("id"))
                    val hashes=JSONObject()
                    for(id in ids) {
                        ensureActive()
                        check(stage.usableSpace>256L*1024*1024) { "存储空间不足" }
                        val total=File(activity.filesDir,"library").walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        check(total<1024L*1024*1024) { "离线库已达到1GB上限" }
                        val bytes=retryBusy { if(id=="audio")api.raw("/v1/assets/$original") else api.raw("/v1/resources/$rid/audio/$id?revisionId=$revision") }
                        ensureActive();val name="$id.bin"
                        cacheWriteLock.withLock {
                            ensureActive()
                            val used=File(activity.filesDir,"library").walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            check(used+bytes.size<=1024L*1024*1024 && stage.usableSpace-bytes.size>=256L*1024*1024) { "存储容量不足" }
                            File(stage,name).writeBytes(bytes)
                        }
                        hashes.put(name,sha256(bytes))
                    }
                    // 下载过程中若已换版/撤回，不把旧临时目录发布成完整可播资源。
                    check(api.json("/v1/resources/$rid/manifest").getString("revisionId")==revision)
                    manifest.put("hashes",hashes).put("downloadComplete",true)
                    File(stage,"manifest.json").writeText(manifest.toString())
                    val target=File(cache,rid);target.deleteRecursively();check(stage.renameTo(target))
                    api.json("/v1/resources/$rid/download","PUT",JSONObject().put("revisionId",revision).put("state","downloaded"))
                }
                diagnostic="资源已完整下载，可以离线点播";pendingKeywords=localKeywords(vault.get(robotKey(connection,"kws"))?.optString("keywords") ?: "")
            } catch(e:CancellationException) { stage.deleteRecursively();throw e }
            catch(e:Exception) {
                failure("download");stage.deleteRecursively();diagnostic="离线下载失败："+(e.message ?: e.javaClass.simpleName).take(160)
                vault.save(robotKey(connection,"download-error"),JSONObject().put("detail",diagnostic))
                withContext(Dispatchers.IO) { runCatching { api.json("/v1/resources/$rid/download","PUT",JSONObject().put("revisionId",revision).put("state","failed")) } }
            }
        }
    }
    private suspend fun <T> retryBusy(action:()->T):T {
        repeat(6) { attempt ->
            try { return action() }
            catch(e:java.io.IOException) { if(!e.message.orEmpty().startsWith("429") || attempt==5)throw e;delay((attempt+1)*1000L) }
        }
        error("本地模型仍忙，请稍后重试下载")
    }
    private fun localKeywords(base:String):String {
        return base+cache.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{32}")) }.take(30).mapNotNull { dir ->
            runCatching { val m=JSONObject(File(dir,"manifest.json").readText());if(m.optBoolean("downloadComplete"))m.optString("offlineKeywords") else null }.getOrNull()
        }.joinToString("")
    }
    fun offlineItems():List<Pair<String,String>> = cache.listFiles().orEmpty().mapNotNull { dir ->
        if(!dir.name.matches(Regex("[a-f0-9]{32}")))null else runCatching { val m=JSONObject(File(dir,"manifest.json").readText());if(m.optBoolean("downloadComplete"))dir.name to m.getString("title") else null }.getOrNull()
    }
    private fun readOffline(rid:String):JSONObject {
        val dir=File(cache,rid);val manifest=JSONObject(File(dir,"manifest.json").readText())
        check(manifest.optBoolean("downloadComplete"))
        val hashes=manifest.getJSONObject("hashes")
        check(manifest.optBoolean("complete") || manifest.optJSONObject("scopeNotice")!=null) { "节选资源需重新完整下载范围说明" }
        val segments=manifest.getJSONArray("segments")
        val expected=(if(manifest.optString("audioAsset").isNotEmpty())listOf("audio") else (0 until segments.length()).map { segments.getJSONObject(it).getString("id") })+listOfNotNull(manifest.optJSONObject("scopeNotice")?.getString("id"))
        check(expected.isNotEmpty() && expected.all { hashes.has("$it.bin") }) { "离线资源尚未完整下载" }
        for(key in hashes.keys())check(sha256(File(dir,key).readBytes())==hashes.getString(key))
        return manifest
    }
    private suspend fun runMedia() {
        val m=currentManifest ?: return
        val rid=m.getString("resourceId");val revision=m.getString("revisionId");val ticket=session.generation
        session.startMedia(SystemClock.elapsedRealtime(),if(m.optString("kind")=="song")ActivityMode.SONG else ActivityMode.BOOK)
        val notice=m.optJSONObject("scopeNotice")
        if(notice!=null && scopeAnnouncedRevision!=revision) {
            val id=notice.getString("id")
            val audio=withContext(Dispatchers.IO) {
                fun offlineNotice():ByteArray {
                    check(readOffline(rid).getString("revisionId")==revision)
                    return File(cache,"$rid/$id.bin").readBytes()
                }
                if(!online)offlineNotice() else try { api.raw("/v1/resources/$rid/audio/$id?revisionId=$revision") }
                catch(error:java.io.IOException) {
                    if(error is ApiHttpException || error is javax.net.ssl.SSLException)throw error
                    val cached=offlineNotice()
                    withContext(Dispatchers.Main) { if(online)failure("network");online=false }
                    cached
                }
            }
            if(!session.valid(ticket) || !session.mediaPlaying)return
            playAudio(audio,ticket,false)
            if(!session.valid(ticket) || !session.mediaPlaying)return
            scopeAnnouncedRevision=revision
        }
        val segments=m.getJSONArray("segments");val original=m.optString("audioAsset")
        val count=if(original.isNotEmpty())1 else segments.length()
        while(segmentIndex<count && session.mediaPlaying) {
            val id=if(original.isNotEmpty())"audio" else segments.getJSONObject(segmentIndex).getString("id")
            val filename="$id.bin"
            val bytes=withContext(Dispatchers.IO) {
                fun offlineAudio():ByteArray {
                    check(readOffline(rid).getString("revisionId")==revision) { "离线版本与当前阅读版本不同" }
                    return File(cache,"$rid/$filename").readBytes()
                }
                if(!online)offlineAudio() else try {
                    if(original.isNotEmpty())api.raw("/v1/assets/$original")
                    else api.raw("/v1/resources/$rid/audio/$id?revisionId=$revision")
                } catch(error:java.io.IOException) {
                    if(error is ApiHttpException || error is javax.net.ssl.SSLException)throw error
                    val cached=offlineAudio()
                    withContext(Dispatchers.Main) { if(online)failure("network");online=false }
                    cached
                }
            }
            if(!session.mediaPlaying)return
            playAudio(bytes,ticket,true)
            if(!session.mediaPlaying)return
            val completedOffset=offsetMs
            segmentIndex++
            queueProgress(rid,revision,id,completedOffset)
            offsetMs=0
        }
        session.playbackEnded(session.generation,SystemClock.elapsedRealtime());currentManifest=null;vault.remove(robotKey(connection,"reading"))
        if(queue.isNotEmpty() && session.allowed)playResource(queue.removeFirst(),true)
    }
    fun resumeMedia() {
        if(currentManifest==null || !session.resume(SystemClock.elapsedRealtime()))return
        cancelAudio();job=scope.launch { try {
            val m=currentManifest!!
            withContext(Dispatchers.IO) {
                if(online)api.json("/v1/resources/${m.getString("resourceId")}/manifest?revisionId=${m.getString("revisionId")}")
                else check(readOffline(m.getString("resourceId")).getString("revisionId")==m.getString("revisionId"))
            }
            runMedia()
        } catch(e:CancellationException) { throw e } catch(_:Exception) { session.pause(SystemClock.elapsedRealtime());diagnostic="该阅读版本不可续播，请选择可用版本" } }
    }
    private fun changePage(direction:Int,group:String="pageId") {
        val m=currentManifest ?: return
        val segments=m.getJSONArray("segments");if(segments.length()==0)return
        val current=segments.getJSONObject(segmentIndex.coerceAtMost(segments.length()-1)).getString(group)
        var next=segmentIndex+direction
        while(next in 0 until segments.length() && segments.getJSONObject(next).getString(group)==current)next+=direction
        if(next !in 0 until segments.length())return
        if(direction<0) { val page=segments.getJSONObject(next).getString(group);while(next>0 && segments.getJSONObject(next-1).getString(group)==page)next-- }
        pause();segmentIndex=next;offsetMs=0;resumeMedia()
    }
    /** MP3/M4A流式解码，不把整首歌曲展开成内存PCM；输出与嘴形使用同一份采样。 */
    private suspend fun playCompressed(bytes:ByteArray,ticket:Long,media:Boolean,playbackId:Long,volume:Float) = withContext(Dispatchers.IO) {
        val file=File(activity.cacheDir,"decode-${UUID.randomUUID()}.audio")
        val extractor=MediaExtractor();var codec:MediaCodec?=null;var player:AudioTrack?=null
        val seekMs=if(media)offsetMs else 0
        try {
            file.writeBytes(bytes);extractor.setDataSource(file.absolutePath)
            val index=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            extractor.selectTrack(index);val source=extractor.getTrackFormat(index)
            source.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
            val decoder=MediaCodec.createDecoderByType(source.getString(MediaFormat.KEY_MIME)!!);codec=decoder
            decoder.configure(source,null,null,0);decoder.start()
            if(seekMs>0)extractor.seekTo(seekMs*1000L,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var inputEnded=false;var outputEnded=false;var sampleRate=0;var channels=0;var framesWritten=0L
            val info=MediaCodec.BufferInfo()
            while(!outputEnded) {
                ensureActive()
                if(!session.allowed || (media && !session.mediaPlaying) || (!media && !session.valid(ticket)))return@withContext
                if(!inputEnded) {
                    val slot=decoder.dequeueInputBuffer(10000)
                    if(slot>=0) {
                        val input=decoder.getInputBuffer(slot)!!;val size=extractor.readSampleData(input,0)
                        if(size<0) { decoder.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true }
                        else { decoder.queueInputBuffer(slot,0,size,extractor.sampleTime,0);extractor.advance() }
                    }
                }
                val slot=decoder.dequeueOutputBuffer(info,10000)
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(player==null) { "不支持中途变化的音频格式" }
                    val decoded=decoder.outputFormat;sampleRate=decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    check(channels in 1..2 && (!decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) || decoded.getInteger(MediaFormat.KEY_PCM_ENCODING)==AudioFormat.ENCODING_PCM_16BIT))
                    val mask=if(channels==1)AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val format=AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build()
                    player=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setBufferSizeInBytes(maxOf(sampleRate*channels/5,AudioTrack.getMinBufferSize(sampleRate,mask,AudioFormat.ENCODING_PCM_16BIT))).setTransferMode(AudioTrack.MODE_STREAM).build()
                    track=player;trackRate=sampleRate;trackStartMs=seekMs;trackIsMedia=media;player.setVolume(volume);player.play()
                } else if(slot>=0) {
                    try {
                        if(info.size>0) {
                            val output=decoder.getOutputBuffer(slot)!!
                            val skipFrames=((seekMs*1000L-info.presentationTimeUs).coerceAtLeast(0)*sampleRate/1_000_000).coerceAtMost(info.size.toLong()/(channels*2))
                            val skip=(skipFrames*channels*2).toInt();val count=info.size-skip
                            if(count>0) {
                                output.position(info.offset+skip);output.limit(info.offset+info.size)
                                val pcm=ByteArray(count);output.get(pcm);val samples=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                                var energy=0.0
                                for(i in pcm.indices step 2) { val x=samples.getShort(i)/32768.0;energy+=x*x }
                                withContext(Dispatchers.Main) { if(playbackId==playbackSerial)mouth=(sqrt(energy/(count/2))*5*volume).toFloat().coerceIn(0f,1f) }
                                var written=0
                                while(written<count) { ensureActive();val n=player!!.write(pcm,written,count-written,AudioTrack.WRITE_BLOCKING);check(n>=0);written+=n }
                                framesWritten+=count/(channels*2)
                                if(media && playbackId==playbackSerial)offsetMs=seekMs+(player!!.playbackHeadPosition*1000L/sampleRate).toInt()
                            }
                        }
                        outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    } finally { decoder.releaseOutputBuffer(slot,false) }
                }
            }
            val playing=player
            while(playing!=null && playing.playbackHeadPosition<framesWritten) { ensureActive();delay(20) }
        } finally {
            player?.let { if(media && playbackId==playbackSerial)offsetMs=seekMs+(it.playbackHeadPosition*1000L/trackRate).toInt();runCatching { it.stop() };it.release();if(track===it)track=null }
            codec?.let { runCatching { it.stop() };it.release() };extractor.release();file.delete()
        }
    }
    private suspend fun playAudio(bytes:ByteArray,ticket:Long,media:Boolean) {
        if(!focus()) { if(media)pause("focus-denied");return }
        val playbackId=++playbackSerial
        val volume=(config.optJSONObject("voice")?.optDouble("volume",0.5)?.toFloat() ?: 0.5f).coerceIn(0f,1f)
        lastFeedbackResult=if(volume==0f || manager.getStreamVolume(AudioManager.STREAM_MUSIC)==0)"muted-output" else "playing"
        lastFeedbackAtMs=SystemClock.elapsedRealtime()
        if(bytes.size>=44 && String(bytes,0,4)=="RIFF") {
            withContext(Dispatchers.IO) {
                val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                require(String(bytes,8,4)=="WAVE")
                var start=12;var dataStart=-1;var dataSize=0;var sampleRate=0;var channels=0;var bits=0;var formatCode=0
                while(start+8<=bytes.size) { val length=buffer.getInt(start+4);require(length>=0 && start+8L+length<=bytes.size)
                    when(String(bytes,start,4)) {
                        "fmt " -> { require(length>=16);formatCode=buffer.getShort(start+8).toInt();channels=buffer.getShort(start+10).toInt();sampleRate=buffer.getInt(start+12);bits=buffer.getShort(start+22).toInt() }
                        "data" -> { dataStart=start+8;dataSize=length }
                    }
                    start+=8+length+(length%2)
                }
                require(dataStart>=0 && channels in 1..2 && bits==16 && formatCode==1 && sampleRate in 8000..192000)
                val format=AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(if(channels==1)AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO).build()
                val player=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setBufferSizeInBytes(maxOf(sampleRate*channels/5,AudioTrack.getMinBufferSize(sampleRate,format.channelMask,format.encoding))).setTransferMode(AudioTrack.MODE_STREAM).build()
                track=player;trackRate=sampleRate;trackStartMs=if(media)offsetMs else 0;trackIsMedia=media;player.setVolume(volume)
                val initial=((if(media)offsetMs else 0).toLong()*sampleRate/1000*channels*2).toInt().coerceIn(0,dataSize)
                var cursor=dataStart+initial;player.play()
                try {
                    while(cursor<dataStart+dataSize) {
                        ensureActive()
                        if(!session.allowed || (!media && !session.valid(ticket)) || (media && !session.mediaPlaying))return@withContext
                        val n=minOf(sampleRate*channels/10*2,dataStart+dataSize-cursor)
                        var energy=0.0
                        for(i in cursor until cursor+n step 2) { val value=buffer.getShort(i)/32768.0;energy+=value*value }
                        withContext(Dispatchers.Main) { if(playbackId==playbackSerial)mouth=(sqrt(energy/(n/2))*5*volume).toFloat().coerceIn(0f,1f) }
                        val written=player.write(bytes,cursor,n,AudioTrack.WRITE_BLOCKING);if(written<0)error("音频输出失败")
                        cursor+=written
                        if(media && playbackId==playbackSerial)offsetMs=(initial*1000L/(sampleRate*channels*2)+player.playbackHeadPosition*1000L/sampleRate).toInt()
                    }
                    val expected=(dataSize-initial)/(channels*2)
                    while(player.playbackHeadPosition<expected) { ensureActive();delay(20) }
                } finally {
                    if(media && playbackId==playbackSerial)offsetMs=(initial*1000L/(sampleRate*channels*2)+player.playbackHeadPosition*1000L/sampleRate).toInt()
                    runCatching { player.stop() };player.release();if(track===player)track=null
                }
            }
        } else {
            playCompressed(bytes,ticket,media,playbackId,volume)
        }
        if(playbackId==playbackSerial)mouth=0f
    }
}

fun JSONObject.toBody()=toString().let { with(okhttp3.RequestBody.Companion) { it.toRequestBody("application/json".let { type -> with(okhttp3.MediaType.Companion) { type.toMediaType() } }) } }
