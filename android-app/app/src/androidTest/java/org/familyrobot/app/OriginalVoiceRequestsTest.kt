package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.familyrobot.core.SessionState
import org.familyrobot.core.Session
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** 只注入ASR转写，验证真实App路由→家庭API→TTS/媒体；不计儿童声学成功。 */
@RunWith(AndroidJUnit4::class)
class OriginalVoiceRequestsTest {
    @Test fun namedBooksChangeSongAndRelatedMemoryWithdrawal() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val rc=vault.get("robot")!!;val api=Api(vault.get("parent")!!)
        val path="/v1/robots/${rc.getString("deviceId")}/config";val original=api.json(path).getJSONObject("config")
        val manager=context.getSystemService(AudioManager::class.java);val volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val testPreference="测试小猫"+UUID.randomUUID().toString().take(8)
        val created=mutableListOf<String>();val originalMemories=api.array("/v1/memories").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }
        val report=JSONObject().put("acousticAcceptance",false)
        fun waitFor(label:String,limit:Long=45000,condition:()->Boolean) { val until=SystemClock.elapsedRealtime()+limit;while(!condition() && SystemClock.elapsedRealtime()<until)Thread.sleep(60);assertTrue(label,condition()) }
        fun update(value:JSONObject):String { val prior=api.json(path);val id=UUID.randomUUID().toString();api.json(path,"POST",JSONObject().put("requestId",id).put("expectedVersion",prior.getInt("version")).put("config",value));return id }
        val config=JSONObject(original.toString()).put("muted",false).put("cameraAllowed",false).put("listeningPlans",JSONArray())
        config.getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false)
        config.getJSONObject("interaction").put("wakeFeedback","visual")
        val title="语音入口小猫"+UUID.randomUUID().toString().take(6)
        fun createBook(name:String):String {
            val draft=JSONObject().put("title",name).put("language","zh").put("complete",true).put("auditioned",true)
                .put("pages",JSONArray().put(JSONObject().put("id","first").put("text","小猫看见一朵花。花开了。小猫轻轻说，你好。").put("reviewed",true)))
            val id=api.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",draft)).getString("id");created.add(id)
            api.json("/v1/resources/$id/publish","POST",JSONObject().put("requestId",UUID.randomUUID().toString()).put("expectedVersion",1));return id
        }
        vault.save("identity",JSONObject().put("mode","robot"));manager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0)
        try {
            val first=createBook(title);createBook("语音入口蓝鲸"+UUID.randomUUID().toString().take(6))
            val song=api.json("/v1/resources","POST",JSONObject().put("kind","song").put("draft",JSONObject().put("title","测试歌曲入口").put("language","zh").put("complete",true))).getString("id");created.add(song)
            val asset=api.upload("/v1/resources/$song/assets?purpose=audio&expectedVersion=1","original.wav",context.assets.open("prompts/unclear.wav").use { it.readBytes() },"audio/wav").getString("assetId")
            val draft=api.json("/v1/resources/$song");draft.getJSONObject("draft").put("audioAsset",asset).put("auditioned",true)
            val edited=api.json("/v1/resources/$song","PUT",JSONObject().put("expectedVersion",draft.getInt("draft_version")).put("draft",draft.getJSONObject("draft")))
            api.json("/v1/resources/$song/publish","POST",JSONObject().put("requestId",UUID.randomUUID().toString()).put("expectedVersion",edited.getInt("draft_version")))
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                lateinit var robot:RobotRuntime
                scenario.onActivity { robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as RobotRuntime }
                fun onRobot(action:()->Unit)=scenario.onActivity { action() }
                fun field(name:String)=RobotRuntime::class.java.getDeclaredField(name).apply { isAccessible=true }.get(robot)
                fun configure(value:JSONObject) { val id=update(value);waitFor("配置ACK") { api.json("/v1/commands/$id").getString("state")=="applied" } }
                waitFor("在线") { robot.online };configure(config);onRobot { (field("input") as AudioInput).close() }
                val runtimeApi=field("api") as Api;val clientField=Api::class.java.getDeclaredField("client").apply { isAccessible=true }
                val initial=clientField.get(runtimeApi) as okhttp3.OkHttpClient
                val text=AtomicReference("");val replies=CopyOnWriteArrayList<JSONObject>()
                clientField.set(runtimeApi,initial.newBuilder().addInterceptor { chain ->
                    if(chain.request().url.encodedPath=="/v1/speech/recognize")return@addInterceptor okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture").body(JSONObject().put("text",text.get()).toString().toResponseBody("application/json".toMediaType())).build()
                    val response=chain.proceed(chain.request())
                    if(chain.request().url.encodedPath=="/v1/turns")replies.add(JSONObject(response.peekBody(100000).string()))
                    response
                }.build())
                fun say(value:String):JSONObject {
                    text.set(value);val count=replies.size
                    onRobot {
                        // 读取真实会话状态；UI快照最多滞后一次tick，不能据此省略播音中的叫醒。
                        val session=field("session") as Session
                        if(session.mediaPlaying || session.state !in setOf(SessionState.OPENING,SessionState.LISTENING,SessionState.FOLLOW_UP))robot.wake()
                        assertTrue("识别注入前会话确实可接收输入",session.state in setOf(SessionState.OPENING,SessionState.LISTENING,SessionState.FOLLOW_UP))
                        RobotRuntime::class.java.getDeclaredMethod("recognize",ByteArray::class.java).apply { isAccessible=true }.invoke(robot,AudioInput.wav(ByteArray(3200)))
                    }
                    waitFor("语音入口的真实服务响应") { replies.size>count };return replies.last()
                }
                val bookResponse=say("请读《$title》")
                assertEquals("book_result",bookResponse.getString("action"))
                waitFor("按名字读取并实际播放") { (field("currentManifest") as? JSONObject)?.optString("resourceId")==first && field("trackIsMedia")==true && runCatching { (field("track") as? AudioTrack)?.playbackHeadPosition ?: 0 }.getOrDefault(0)>0 }
                assertFalse(robot.cameraActive);report.put("namedReadWithoutCamera",true)
                val changed=say("换一个");assertEquals("play",changed.getString("action"));assertNotEquals(first,changed.getString("resourceId"));report.put("changeExcludesCurrent",true)
                onRobot { robot.stop() }
                val singing=say("唱首歌");assertEquals("play",singing.getString("action"))
                assertEquals("song",api.json("/v1/resources/${singing.getString("resourceId")}").getString("kind"));report.put("songUsesPublishedMedia",true)
                onRobot { robot.stop() }
                val question=say("这本书为什么有很多页")
                assertEquals("speak",question.getString("action"));report.put("ordinaryBookQuestionNotCover",true)
                waitFor("问题回答结束") { robot.state==SessionState.FOLLOW_UP }
                say("记住我喜欢"+testPreference)
                waitFor("候选回答结束") { robot.state==SessionState.FOLLOW_UP }
                val withdrawn=say("不要记这个")
                assertTrue(withdrawn.optString("text").contains("刚刚那条偏好已经删除"))
                val events=api.array("/v1/memory-actions");assertEquals("related_deleted",events.getJSONObject(0).getString("action"))
                report.put("immediateMemoryWithdrawal",true)
                onRobot { robot.stop() };configure(original);onRobot { robot.background() }
            }
        } finally {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
            if(api.json(path).getJSONObject("config").toString()!=original.toString())update(original)
            for(id in created)runCatching { api.json("/v1/resources/$id","DELETE") }
            val memories=api.array("/v1/memories")
            for(i in 0 until memories.length()) { val id=memories.getJSONObject(i).getString("id");if(id !in originalMemories && memories.getJSONObject(i).getJSONObject("body").optString("content")=="我喜欢"+testPreference)api.json("/v1/memories/$id","PUT",JSONObject().put("state","deleted")) }
            File(context.filesDir,"original-voice-requests.json").writeText(report.toString())
        }
    }
}
