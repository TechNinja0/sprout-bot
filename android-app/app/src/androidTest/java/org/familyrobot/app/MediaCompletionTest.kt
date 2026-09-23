package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import org.familyrobot.core.SessionState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 两条真实MP3须自然衔接并自然结束。隔离麦克风以测试播放/进度，不作为声学验收。 */
@RunWith(AndroidJUnit4::class)
class MediaCompletionTest {
    @Test fun playlistAdvancesAndCompletes() = exercisePlaylist(false)
    @Test fun slowProgressReportDoesNotBlockPlayback() = exercisePlaylist(true)
    private fun exercisePlaylist(delayProgress:Boolean) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val api=Api(vault.get("parent")!!)
        val audio=context.getSystemService(AudioManager::class.java)
        val originalVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val originalIdentity=vault.get("identity")
        val source=File(context.filesDir,"test-audio.mp3")
        val metadata=MediaMetadataRetriever()
        val duration=try { metadata.setDataSource(source.absolutePath);metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() } finally { metadata.release() }
        require(duration in 1000..120000) { "测试音轨时长不在预期范围" }
        val finalPosition=duration-500
        val result=File(context.filesDir,"media-completion-${if(delayProgress)"delayed" else "normal"}.jsonl")
        result.writeText(JSONObject().put("event","fixture").put("durationMs",duration).put("sha256",sha256(source.readBytes())).toString()+"\n")
        result.appendText(JSONObject().put("event","microphone-isolated-for-control-test").put("acousticAcceptance",false).put("outputVolume",0).toString()+"\n")
        try {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0)
        vault.save("identity",JSONObject().put("mode","robot"))
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            lateinit var robot:RobotRuntime
            scenario.onActivity {
                robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as RobotRuntime
                (RobotRuntime::class.java.getDeclaredField("input").apply { isAccessible=true }.get(robot) as AudioInput).close()
            }
            val releaseProgress=java.util.concurrent.CountDownLatch(1)
            val blockedReports=java.util.concurrent.atomic.AtomicInteger()
            val delayedResource=java.util.concurrent.atomic.AtomicReference<String?>(null)
            if(delayProgress) {
                // 仅测试客户端注入慢响应，真实媒体下载/解码/输出仍走家庭服务与手机。
                val runtimeApi=RobotRuntime::class.java.getDeclaredField("api").apply { isAccessible=true }.get(robot) as Api
                val field=Api::class.java.getDeclaredField("client").apply { isAccessible=true }
                val client=field.get(runtimeApi) as okhttp3.OkHttpClient
                field.set(runtimeApi,client.newBuilder().addInterceptor { chain ->
                    val request=chain.request()
                    if(request.method=="PUT" && request.url.encodedPath=="/v1/resources/${delayedResource.get()}/progress") {
                        val buffer=okio.Buffer();request.body!!.writeTo(buffer)
                        if(JSONObject(buffer.readUtf8()).optInt("offsetMs")>=1000) {
                            blockedReports.incrementAndGet()
                            check(releaseProgress.await(90,java.util.concurrent.TimeUnit.SECONDS)) { "测试进度延迟超时" }
                        }
                    }
                    chain.proceed(request)
                }.build())
            }
            fun snapshot():JSONObject {
                var value=JSONObject()
                scenario.onActivity {
                    fun field(name:String)=RobotRuntime::class.java.getDeclaredField(name).apply { isAccessible=true }.get(robot)
                    val track=field("track") as? AudioTrack;val job=field("job") as? Job
                    value=JSONObject().put("atMs",SystemClock.elapsedRealtime()).put("state",robot.state.name)
                        .put("musicActive",audio.isMusicActive).put("playbackSerial",field("playbackSerial"))
                        .put("trackPresent",track!=null).put("trackHead",runCatching { track?.playbackHeadPosition }.getOrNull())
                        .put("trackRate",runCatching { track?.sampleRate }.getOrNull())
                        .put("trackState",runCatching { track?.playState }.getOrNull())
                        .put("jobActive",job?.isActive).put("jobCancelled",job?.isCancelled)
                        .put("queueSize",(field("queue") as java.util.ArrayDeque<*>).size)
                        .put("manifestPresent",field("currentManifest")!=null)
                        .put("configurationVersion",robot.version).put("permission",field("lastPolicy")?.toString())
                        .put("pauseReason",robot.lastPauseReason)
                        .put("keywordWakeCount",robot.keywordWakeCount).put("keywordAction",robot.lastKeywordAction).put("online",robot.online).put("blockedReports",blockedReports.get())
                }
                return value
            }
            fun waitFor(label:String,limit:Long,predicate:()->Boolean) {
                val began=SystemClock.elapsedRealtime();var lastSample=0L
                while(!predicate() && SystemClock.elapsedRealtime()-began<limit) {
                    if(SystemClock.elapsedRealtime()-lastSample>1000) { result.appendText(snapshot().toString()+"\n");lastSample=SystemClock.elapsedRealtime() }
                    Thread.sleep(50)
                }
                result.appendText(snapshot().put("checkpoint",label).toString()+"\n")
                assertTrue(label,predicate())
            }
            var rid:String?=null;var playlist:String?=null
            try {
                waitFor("联网",15000) { robot.online }
                val resource=api.json("/v1/resources","POST",JSONObject().put("kind","song").put("draft",JSONObject().put("title","原创清单自然结束测试")))
                rid=resource.getString("id");delayedResource.set(rid)
                api.upload("/v1/resources/$rid/assets?purpose=audio&expectedVersion=1","original.mp3",source.readBytes(),"audio/mpeg")
                var current=api.json("/v1/resources/$rid")
                current.getJSONObject("draft").put("complete",true)
                current=api.json("/v1/resources/$rid","PUT",JSONObject().put("expectedVersion",current.getInt("draft_version")).put("draft",current.getJSONObject("draft")))
                current.getJSONObject("draft").put("auditioned",true)
                current=api.json("/v1/resources/$rid","PUT",JSONObject().put("expectedVersion",current.getInt("draft_version")).put("draft",current.getJSONObject("draft")))
                api.json("/v1/resources/$rid/publish","POST",JSONObject().put("expectedVersion",current.getInt("draft_version")).put("requestId",UUID.randomUUID().toString()))
                playlist=api.json("/v1/playlists","POST",JSONObject().put("name","两条自然衔接").put("resources",JSONArray(listOf(rid,rid)))).getString("id")
                result.appendText(JSONObject().put("event","test-resources").put("resourceId",rid).put("playlistId",playlist).toString()+"\n")
                scenario.onActivity { robot.playPlaylist(playlist!!) }
                waitFor("首条实际播放",15000) { audio.isMusicActive }
                val playbackBegan=SystemClock.elapsedRealtime()
                val firstSerial=snapshot().getLong("playbackSerial")
                if(delayProgress)waitFor("目标资源进度已进入阻塞",15000) { blockedReports.get()>0 }
                waitFor("第二条自然衔接并实际播放",duration+10000) { val current=snapshot();current.getLong("playbackSerial")>firstSerial && current.getInt("queueSize")==0 && current.optLong("trackHead")>0 && audio.isMusicActive }
                waitFor("清单自然结束且退出播放状态",duration+10000) { val current=snapshot();!current.getBoolean("manifestPresent") && current.getInt("queueSize")==0 && !audio.isMusicActive && robot.state in setOf(SessionState.FOLLOW_UP,SessionState.STANDBY) }
                assertTrue("两条音轨不能被提前结束",SystemClock.elapsedRealtime()-playbackBegan>=duration*2-1000)
                if(delayProgress) {
                    assertTrue("确实注入进度上报阻塞",blockedReports.get()>0)
                    val connection=vault.get("robot")!!
                    val pending=vault.get(robotKey(connection,"progress-pending"))!!
                    assertTrue("完整结束位置在未确认期间已持久保存",pending.getJSONObject(rid).getInt("offsetMs")>=finalPosition)
                    releaseProgress.countDown()
                }
                waitFor("最终阅读位置实际同步服务端",15000) {
                    api.json("/v1/resources/$rid/progress").optInt("offset_ms")>=finalPosition
                }
                result.appendText(JSONObject().put("event","complete").put("passed",true).toString()+"\n")
            } finally {
                releaseProgress.countDown()
                result.appendText(snapshot().put("event","final-state-before-cleanup").toString()+"\n")
                scenario.onActivity { robot.stop() }
                playlist?.let { api.json("/v1/playlists/$it","DELETE") }
                rid?.let { api.json("/v1/resources/$it/unlist","POST",JSONObject()) }
            }
        }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,originalVolume,0)
            originalIdentity?.let { vault.save("identity",it) } ?: vault.remove("identity")
        }
    }
}
