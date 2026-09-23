package org.familyrobot.app

import android.content.Intent
import android.media.AudioTrack
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.familyrobot.core.ReadingNavigation
import org.familyrobot.core.Session
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** 注入明确的阅读控制；真实服务、TTS、AudioTrack和离线文件。不是儿童ASR或实物封面验收。 */
@RunWith(AndroidJUnit4::class)
class BookWorkflowTest {
    @Test fun excerptResumeSeekRevisionAndOfflineIntegrity() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val connection=vault.get("robot")!!;val parent=Api(vault.get("parent")!!)
        val audioManager=context.getSystemService(AudioManager::class.java);val originalVolume=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val identity=vault.get("identity");val readingKey=robotKey(connection,"reading");val priorReading=vault.get(readingKey)
        val robotId=connection.getString("deviceId");val configPath="/v1/robots/$robotId/config"
        val originalConfig=parent.json(configPath).getJSONObject("config")
        val lookupMarker="workflow-"+UUID.randomUUID().toString()
        val signal=File(context.filesDir,"offline-test-state")
        val report=File(context.filesDir,"book-workflow.jsonl").apply { writeText("") }
        fun record(event:String,body:JSONObject=JSONObject()) { report.appendText(body.put("event",event).put("atMs",SystemClock.elapsedRealtime()).toString()+"\n") }
        var diagnostic:(()->JSONObject)?=null
        fun waitFor(label:String,limit:Long=30000,condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+limit
            var sampleAt=0L
            while(!condition() && SystemClock.elapsedRealtime()<until) {
                if(SystemClock.elapsedRealtime()-sampleAt>=1000) { sampleAt=SystemClock.elapsedRealtime();diagnostic?.let { record("waiting:"+label,it()) } }
                Thread.sleep(50)
            }
            if(!condition())diagnostic?.let { record("failed:"+label,it()) }
            assertTrue(label,condition())
        }
        fun connected(value:Boolean) {
            signal.writeText(if(value)"reconnect" else "ready")
            waitFor("USB映射握手") { signal.readText().trim()==if(value)"connected" else "disconnected" }
        }
        fun configure(config:JSONObject) {
            val current=parent.json(configPath);val id=UUID.randomUUID().toString()
            parent.json(configPath,"POST",JSONObject().put("requestId",id).put("expectedVersion",current.getInt("version")).put("config",config))
            waitFor("配置ACK") { parent.json("/v1/commands/$id").getString("state")=="applied" }
        }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0)
        vault.save("identity",JSONObject().put("mode","robot"));vault.remove(readingKey)
        var rid="";var siblingId=""
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                lateinit var robot:RobotRuntime
                scenario.onActivity { robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as RobotRuntime }
                fun onRobot(action:()->Unit)=scenario.onActivity { action() }
                fun field(name:String)=RobotRuntime::class.java.getDeclaredField(name).apply { isAccessible=true }.get(robot)
                fun set(name:String,value:Any?)=RobotRuntime::class.java.getDeclaredField(name).apply { isAccessible=true }.set(robot,value)
                fun snapshot():JSONObject {
                    var result=JSONObject()
                    onRobot {
                        val track=field("track") as? AudioTrack
                        result=JSONObject().put("online",robot.online).put("index",field("segmentIndex")).put("offset",field("offsetMs"))
                            .put("resourceId",(field("currentManifest") as? JSONObject)?.optString("resourceId") ?: "")
                            .put("revision",(field("currentManifest") as? JSONObject)?.optString("revisionId") ?: "")
                            .put("state",robot.state.name).put("diagnostic",robot.diagnostic).put("keyword",robot.lastKeywordAction).put("wakeSource",robot.lastWakeSource).put("pause",robot.lastPauseReason).put("feedback",robot.lastFeedbackResult).put("head",runCatching { track?.playbackHeadPosition ?: 0 }.getOrDefault(0)).put("trackIsMedia",field("trackIsMedia"))
                            .put("scopeRevision",field("scopeAnnouncedRevision")).put("choice",field("bookChoice")!=null)
                            .put("playing",runCatching { track?.playState==AudioTrack.PLAYSTATE_PLAYING && (track.playbackHeadPosition)>0 && field("trackIsMedia")==true }.getOrDefault(false))
                    };return result
                }
                diagnostic=::snapshot
                onRobot { (field("input") as AudioInput).close() }
                record("microphone-isolated-for-control-test",JSONObject().put("acousticAcceptance",false).put("outputVolume",0))
                val errors=AtomicReference<Throwable?>()
                fun invokeSuspend(name:String,vararg arguments:Any?) {
                    onRobot {
                        RobotRuntime::class.java.getDeclaredMethod("cancelAudio").apply { isAccessible=true }.invoke(robot)
                        val session=field("session") as Session
                        session.thinking(session.generation)
                        val scope=field("scope") as CoroutineScope
                        val task=scope.launch(start=CoroutineStart.LAZY) {
                            try {
                                suspendCoroutine<Any?> { continuation ->
                                    try {
                                        val method=RobotRuntime::class.java.declaredMethods.single { it.name==name && it.parameterTypes.lastOrNull()==Continuation::class.java }.apply { isAccessible=true }
                                        val returned=method.invoke(robot,*arguments,continuation)
                                        if(returned!==COROUTINE_SUSPENDED)continuation.resume(returned)
                                    } catch(e:Throwable) { continuation.resumeWithException(e.cause ?: e) }
                                }
                            } catch(e:CancellationException) { throw e } catch(e:Throwable) { if(currentCoroutineContext().isActive) { errors.set(e);record("control-error",JSONObject().put("method",name).put("class",e.javaClass.simpleName)) } }
                        }
                        set("job",task);task.start()
                    }
                }
                fun ticket():Long { var value=0L;onRobot { value=(field("session") as Session).generation };return value }
                val runtimeApi=field("api") as Api
                val clientField=Api::class.java.getDeclaredField("client").apply { isAccessible=true }
                val initial=clientField.get(runtimeApi) as okhttp3.OkHttpClient
                val replies=CopyOnWriteArrayList<String>();val failReply=AtomicBoolean(false)
                clientField.set(runtimeApi,initial.newBuilder().addInterceptor { chain ->
                    if(chain.request().url.encodedPath=="/v1/speech/reply") {
                        val buffer=okio.Buffer();chain.request().body?.writeTo(buffer);replies.add(JSONObject(buffer.readUtf8()).optString("text"))
                        if(failReply.get())return@addInterceptor okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(503).message("injected TTS failure")
                            .body("{\"detail\":\"injected test failure\"}".toResponseBody("application/json".toMediaType())).build()
                    };chain.proceed(chain.request())
                }.build())
                try {
                    waitFor("在线") { snapshot().getBoolean("online") }
                    val allowed=JSONObject(originalConfig.toString())
                    allowed.getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false).put("overrideUntil",0)
                    allowed.put("muted",false).put("listeningPlans",JSONArray());configure(allowed)
                    val pages=JSONArray()
                    for(i in 1..2)pages.put(JSONObject().put("id","page-$i").put("label","3").put("chapter","第${i}章")
                        .put("text","The little rabbit sits under a tree. A blue bird sings in the sunshine. The rabbit listens to the bird and smiles. A friend brings a red ball. They play together in the garden.").put("reviewed",true))
                    val book=parent.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject().put("title","原创节选流程测试").put("aliases",JSONArray().put(lookupMarker)).put("language","en")
                        .put("complete",false).put("excerpt","第一章和第二章的第3页").put("auditioned",true).put("pages",pages)))
                    rid=book.getString("id")
                    fun publish():String=parent.json("/v1/resources/$rid/publish","POST",JSONObject().put("expectedVersion",1).put("requestId",UUID.randomUUID().toString())).getString("revisionId")
                    val revision=publish()
                    val cache=File(context.filesDir,"library/${robotScope(connection)}/$rid")
                    fun downloadsIdle():Boolean { var idle=false;onRobot { idle=(field("downloadJobs") as Map<*,*>).values.none { (it as Job).isActive } };return idle }
                    onRobot { robot.download(rid) }
                    waitFor("节选及两页完整下载",120000) { File(cache,"manifest.json").isFile && downloadsIdle() }
                    val manifest=JSONObject(File(cache,"manifest.json").readText());val hashes=manifest.getJSONObject("hashes")
                    assertEquals(3,hashes.length());assertTrue(hashes.has("scope-notice.bin"))
                    for(name in hashes.keys())assertEquals(hashes.getString(name),sha256(File(cache,name).readBytes()))
                    record("complete-excerpt-download",JSONObject().put("hashCount",hashes.length()))
                    onRobot { robot.playResource(rid) }
                    waitFor("范围说明后开始正文",60000) { val s=snapshot();s.getBoolean("playing") && s.getString("scopeRevision")==revision }
                    Thread.sleep(400);onRobot { robot.pause() }
                    assertTrue(snapshot().getInt("offset")>0)
                    invokeSuspend("requestBook",rid,ticket(),revision)
                    waitFor("同版续读询问",45000) { snapshot().getBoolean("choice") && replies.any { it.contains("接着上次读") } }
                    onRobot { RobotRuntime::class.java.getDeclaredMethod("keyword",String::class.java).apply { isAccessible=true }.invoke(robot,"resume") }
                    assertTrue("询问尚未播完不能接受继续口令",snapshot().getBoolean("choice"));assertFalse(snapshot().getBoolean("playing"))
                    // 待提示音自然结束后回答，避免把合成时间算作儿童输入。
                    waitFor("续读提示结束",30000) { var done=false;onRobot { done=(field("session") as Session).state.name=="FOLLOW_UP" };done }
                    invokeSuspend("handleBookChoice","继续",ticket())
                    waitFor("从同版原位置续读",45000) { snapshot().getBoolean("playing") && snapshot().getInt("offset")>0 }
                    onRobot { robot.pause() };record("same-revision-resume",snapshot())
                    invokeSuspend("navigateReading",ReadingNavigation.Command.Page("3"),ticket())
                    waitFor("重复页码澄清",30000) { replies.any { it.contains("有多个第3页") } }
                    waitFor("澄清结束",30000) { var done=false;onRobot { done=(field("session") as Session).state.name=="FOLLOW_UP" };done }
                    invokeSuspend("navigateReading",ReadingNavigation.Command.Page("3","2"),ticket())
                    waitFor("第二章定位",30000) { snapshot().getBoolean("playing") && snapshot().getInt("index")==1 }
                    onRobot { robot.pause() };record("chapter-qualified-seek",snapshot())
                    invokeSuspend("navigateReading",ReadingNavigation.Command.Restart,ticket())
                    waitFor("从头读",30000) { snapshot().getBoolean("playing") && snapshot().getInt("index")==0 }
                    onRobot { robot.pause() }
                    invokeSuspend("requestBook",rid,ticket(),revision)
                    waitFor("第二次续读选择",30000) { snapshot().getBoolean("choice") }
                    waitFor("提示结束",30000) { var done=false;onRobot { done=(field("session") as Session).state.name=="FOLLOW_UP" };done }
                    val newer=publish()
                    invokeSuspend("handleBookChoice","继续",ticket())
                    waitFor("选择期间换版拒绝",30000) { replies.any { it.contains("图书版本刚刚变化") } }
                    assertEquals(revision,snapshot().getString("revision"));assertFalse(snapshot().getBoolean("playing"))
                    assertNotEquals(revision,newer);record("changed-revision-rejected")
                    waitFor("换版提示结束",30000) { var done=false;onRobot { done=(field("session") as Session).state.name=="FOLLOW_UP" };done }
                    val sibling=parent.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject(book.getJSONObject("draft").toString()).put("edition","第二版")))
                    siblingId=sibling.getString("id")
                    parent.json("/v1/resources/$siblingId/publish","POST",JSONObject().put("expectedVersion",1).put("requestId",UUID.randomUUID().toString()))
                    val candidates=parent.json("/v1/books/lookup","POST",JSONObject().put("text",lookupMarker))
                    assertEquals("AMBIGUOUS",candidates.getString("status"));assertEquals(2,candidates.getInt("candidateCount"))
                    fun offer(waitUntilSpoken:Boolean=true) {
                        invokeSuspend("offerBooks",candidates,ticket())
                        waitFor("两候选待确认",30000) { snapshot().getBoolean("choice") }
                        if(waitUntilSpoken)waitFor("候选说明结束",60000) { var done=false;onRobot { done=(field("session") as Session).state.name=="FOLLOW_UP" };done }
                    }
                    offer();invokeSuspend("handleBookChoice","取消",ticket())
                    waitFor("取消选择说明结束",30000) { var done=false;onRobot { done=(field("session") as Session).state.name=="FOLLOW_UP" };done }
                    assertFalse(snapshot().getBoolean("choice"));assertFalse(snapshot().getBoolean("playing"));record("candidate-cancelled")
                    failReply.set(true);invokeSuspend("offerBooks",candidates,ticket())
                    waitFor("注入提示合成失败已返回") { errors.get()!=null }
                    assertTrue(errors.get() is ApiHttpException);assertFalse(snapshot().getBoolean("choice"))
                    errors.set(null);failReply.set(false);record("failed-announcement-clears-choice")
                    offer()
                    onRobot { val choice=field("bookChoice")!!;choice.javaClass.getDeclaredField("expires").apply { isAccessible=true }.setLong(choice,SystemClock.elapsedRealtime()-1) }
                    waitFor("已过期候选被清理") { !snapshot().getBoolean("choice") }
                    assertFalse(snapshot().getBoolean("playing"));record("candidate-expiry-injected-not-wall-clock")
                    offer()
                    val choices=candidates.getJSONArray("candidates")
                    val siblingIndex=(0 until choices.length()).first { choices.getJSONObject(it).getString("resourceId")==siblingId }
                    invokeSuspend("handleBookChoice",if(siblingIndex==0)"第一本" else "第二本",ticket())
                    waitFor("按口头序号选择正确图书",60000) { snapshot().getString("resourceId")==siblingId && snapshot().getBoolean("playing") }
                    onRobot { robot.pause() };record("candidate-selected",snapshot())
                    offer(false)
                    parent.json("/v1/resources/$siblingId/unlist","POST",JSONObject())
                    waitFor("撤回使候选失效") { !snapshot().getBoolean("choice") }
                    Thread.sleep(300);assertFalse(snapshot().getBoolean("playing"));record("candidate-unlisted-before-confirmation")
                    onRobot { robot.stop() }
                    connected(false)
                    val client=clientField.get(runtimeApi) as okhttp3.OkHttpClient;client.dispatcher.cancelAll();client.connectionPool.evictAll()
                    assertTrue(runCatching { Api(connection).json("/health") }.exceptionOrNull() is IOException)
                    waitFor("真实离线") { !snapshot().getBoolean("online") }
                    onRobot { robot.playResource(rid) }
                    waitFor("离线范围说明及正文",45000) { snapshot().getBoolean("playing") && snapshot().getString("scopeRevision")==revision }
                    onRobot { robot.stop() };record("offline-excerpt-play",snapshot())
                    File(cache,"scope-notice.bin").delete()
                    onRobot { robot.playResource(rid) };Thread.sleep(1000)
                    assertFalse(snapshot().getBoolean("playing"));record("missing-notice-refused")
                    connected(true);waitFor("重新联网") { snapshot().getBoolean("online") }
                    assertNull("控制流程没有异常",errors.get())
                    record("passed-injected-controls-not-acoustic")
                } finally {
                    runCatching { connected(true) }
                    onRobot { robot.stop() }
                    if(rid.isNotBlank())runCatching { parent.json("/v1/resources/$rid","DELETE") }
                    if(siblingId.isNotBlank())runCatching { parent.json("/v1/resources/$siblingId","DELETE") }
                    runCatching { configure(originalConfig) }
                    clientField.set(runtimeApi,initial)
                }
            }
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,originalVolume,0)
            identity?.let { vault.save("identity",it) } ?: vault.remove("identity")
            priorReading?.let { vault.save(readingKey,it) } ?: vault.remove(readingKey)
        }
    }
}
