package org.familyrobot.app

import android.content.Intent
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 真实服务合成和USB断网；只在下载第二段处注入一次传输失败，不模拟音频。 */
@RunWith(AndroidJUnit4::class)
class ResourceVoiceRevisionTest {
    @Test fun publishedVoiceAndOfflineDownloadsStayOnOneRevision() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val connection=vault.get("robot")!!;val parent=Api(vault.get("parent")!!)
        require(InstrumentationRegistry.getArguments().getString("offline")=="true") { "必须由支持USB断网握手的驱动运行" }
        val robotId=connection.getString("deviceId")
        val configPath="/v1/robots/$robotId/config"
        val originalConfig=parent.json(configPath).getJSONObject("config")
        val signal=File(context.filesDir,"offline-test-state")
        val report=File(context.filesDir,"resource-voice-revision.jsonl").apply { writeText("") }
        fun record(event:String,body:JSONObject=JSONObject()) {
            report.appendText(body.put("event",event).put("atMs",SystemClock.elapsedRealtime()).toString()+"\n")
        }
        fun waitFor(label:String,limit:Long=15000,condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+limit
            while(!condition() && SystemClock.elapsedRealtime()<until)Thread.sleep(40)
            assertTrue(label,condition())
        }
        fun connected(value:Boolean) {
            signal.writeText(if(value)"reconnect" else "ready")
            waitFor("USB映射握手：$value") { signal.readText().trim()==if(value)"connected" else "disconnected" }
        }
        fun configure(config:JSONObject) {
            val current=parent.json(configPath);val id=UUID.randomUUID().toString()
            parent.json(configPath,"POST",JSONObject().put("requestId",id).put("expectedVersion",current.getInt("version")).put("config",config))
            waitFor("配置应用ACK") { parent.json("/v1/commands/$id").getString("state")=="applied" }
        }
        vault.save("identity",JSONObject().put("mode","robot"))
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            lateinit var robot:RobotRuntime
            scenario.onActivity { robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as RobotRuntime }
            fun onRobot(action:()->Unit)=scenario.onActivity { action() }
            fun field(name:String)=RobotRuntime::class.java.getDeclaredField(name).apply { isAccessible=true }.get(robot)
            fun snapshot():JSONObject {
                var result=JSONObject()
                onRobot {
                    val manifest=field("currentManifest") as? JSONObject
                    val track=field("track") as? AudioTrack
                    result=JSONObject().put("online",robot.online).put("state",robot.state.name)
                        .put("revisionId",manifest?.optString("revisionId") ?: "")
                        .put("segmentIndex",field("segmentIndex")).put("trackStartMs",field("trackStartMs"))
                        .put("head",runCatching { track?.playbackHeadPosition ?: 0 }.getOrDefault(0))
                        .put("playing",runCatching { track?.playState==AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false))
                }
                return result
            }
            var resourceId:String?=null
            val failSecond=AtomicBoolean(false);val failureObserved=AtomicBoolean(false)
            val revisionNotices=java.util.concurrent.atomic.AtomicInteger()
            val runtimeApi=field("api") as Api
            val clientField=Api::class.java.getDeclaredField("client").apply { isAccessible=true }
            val initialClient=clientField.get(runtimeApi) as okhttp3.OkHttpClient
            clientField.set(runtimeApi,initialClient.newBuilder().addInterceptor { chain ->
                val request=chain.request()
                if(request.url.encodedPath=="/v1/speech/reply") {
                    val buffer=okio.Buffer();request.body?.writeTo(buffer)
                    if(JSONObject(buffer.readUtf8()).optString("text")=="这本书有新版本，我们从头开始读。")revisionNotices.incrementAndGet()
                }
                chain.proceed(request)
            }.build())
            fun disconnectTransport() {
                connected(false)
                // adb reverse --remove只关闭新连接入口，已有keep-alive连接仍可能存活。
                // 取消在途请求并释放连接池后，使用全新客户端证明真实网络不可达。
                val client=clientField.get(runtimeApi) as okhttp3.OkHttpClient
                client.dispatcher.cancelAll();client.connectionPool.evictAll()
                val failure=runCatching { Api(connection).json("/health") }.exceptionOrNull()
                assertTrue("移除USB映射后新连接确实失败",failure is IOException && failure !is ApiHttpException && failure !is javax.net.ssl.SSLException)
                waitFor("真正离线") { !snapshot().getBoolean("online") }
                record("transport-disconnected",JSONObject().put("freshRequestFailure",failure!!.javaClass.simpleName))
            }
            try {
                waitFor("机器人联网") { snapshot().getBoolean("online") }
                val allowed=JSONObject(originalConfig.toString())
                allowed.getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false).put("overrideUntil",0)
                allowed.put("muted",false).put("listeningPlans",JSONArray())
                allowed.getJSONObject("voice").put("volume",0.12)
                configure(allowed)
                val pages=JSONArray()
                for((id,text) in listOf(
                    "one" to "Hello, little rabbit. The sun is warm. Let us sit beside the tree and listen to the birds. A small butterfly rests on a flower. The rabbit smiles and waves to a friend.",
                    "two" to "A blue bird sings a song. The little rabbit listens. It is a happy morning."
                ))pages.put(JSONObject().put("id",id).put("text",text).put("reviewed",true))
                var resource=parent.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject()
                    .put("title","原创版本与声音测试").put("language","en").put("complete",true).put("auditioned",true)
                    .put("voice",JSONObject().put("en","Ryan").put("story","Ryan")).put("pages",pages)))
                val rid=resource.getString("id");resourceId=rid
                fun publish():String=parent.json("/v1/resources/$rid/publish","POST",JSONObject()
                    .put("expectedVersion",resource.getInt("draft_version")).put("requestId",UUID.randomUUID().toString())).getString("revisionId")
                val revokedRevision=publish()
                parent.json("/v1/resources/$rid/unlist","POST",JSONObject())
                val oldRevision=publish()
                record("historical-unlist",JSONObject().put("revokedRevision",revokedRevision).put("readingRevision",oldRevision))
                val cache=File(context.filesDir,"library/${robotScope(connection)}/$rid")
                val partial=File(cache.parentFile,"$rid.partial")
                fun manifest():JSONObject=JSONObject(File(cache,"manifest.json").readText())
                fun verifiedHashSet():JSONObject {
                    val m=manifest();assertTrue(m.getBoolean("downloadComplete"))
                    val hashes=m.getJSONObject("hashes");assertEquals(2,hashes.length())
                    for(name in hashes.keys())assertEquals(hashes.getString(name),sha256(File(cache,name).readBytes()))
                    return hashes
                }
                fun downloadsIdle():Boolean {
                    var idle=false
                    onRobot { idle=(field("downloadJobs") as Map<*,*>).values.none { (it as Job).isActive } }
                    return idle
                }
                fun download(revision:String) {
                    onRobot { robot.download(rid) }
                    waitFor("完整下载$revision",90000) { File(cache,"manifest.json").isFile && manifest().optString("revisionId")==revision && downloadsIdle() }
                }
                fun play(revision:String) {
                    onRobot { robot.playResource(rid) }
                    waitFor("实际播放固定版本$revision",45000) {
                        val s=snapshot();s.getString("revisionId")==revision && s.getBoolean("playing") && s.getInt("head")>0
                    }
                    val s=snapshot();assertEquals(0,s.getInt("segmentIndex"));assertEquals(0,s.getInt("trackStartMs"))
                    record("play",s)
                }
                download(oldRevision)
                val oldHashes=verifiedHashSet();val oldManifest=File(cache,"manifest.json").readBytes()
                assertEquals("Ryan",manifest().getJSONObject("voice").getString("en"))
                allowed.getJSONObject("voice").put("en","Serena").put("story","Serena")
                configure(allowed)
                assertArrayEquals(oldManifest,File(cache,"manifest.json").readBytes())
                verifiedHashSet();record("global-voice-preserves-download",JSONObject().put("revisionId",oldRevision).put("hashes",oldHashes))
                disconnectTransport()
                play(oldRevision);onRobot { robot.stop() }
                connected(true);waitFor("恢复在线") { snapshot().getBoolean("online") }
                play(oldRevision)
                resource=parent.json("/v1/resources/$rid")
                resource.getJSONObject("draft").getJSONObject("voice").put("en","Serena").put("story","Serena")
                resource=parent.json("/v1/resources/$rid","PUT",JSONObject().put("expectedVersion",resource.getInt("draft_version")).put("draft",resource.getJSONObject("draft")))
                assertFalse("换声必须重新确认试听",resource.getJSONObject("draft").getBoolean("auditioned"))
                // 测试审核标记；不把自动设置标记当作人工听音质量验收。
                resource.getJSONObject("draft").put("auditioned",true)
                resource=parent.json("/v1/resources/$rid","PUT",JSONObject().put("expectedVersion",resource.getInt("draft_version")).put("draft",resource.getJSONObject("draft")))
                val newRevision=publish();assertNotEquals(oldRevision,newRevision)
                Thread.sleep(3500) // 至少跨过一轮真实撤回同步，不能只检查发布后的瞬时状态。
                assertEquals("普通改版不能改变当前阅读快照",oldRevision,snapshot().getString("revisionId"))
                assertTrue("普通改版后旧音轨仍继续播放",snapshot().getBoolean("playing"))
                assertArrayEquals("普通改版不能删除原离线版",oldManifest,File(cache,"manifest.json").readBytes())
                onRobot { robot.stop() }
                play(newRevision);onRobot { robot.stop() }
                assertEquals("重新点播新版先说明从头读",1,revisionNotices.get())
                val newManifest=Api(connection).json("/v1/resources/$rid/manifest")
                val secondId=newManifest.getJSONArray("segments").getJSONObject(1).getString("id")
                val client=clientField.get(runtimeApi) as okhttp3.OkHttpClient
                clientField.set(runtimeApi,client.newBuilder().addInterceptor { chain ->
                    if(chain.request().url.encodedPath=="/v1/resources/$rid/audio/$secondId" && failSecond.compareAndSet(true,false)) {
                        failureObserved.set(true);throw IOException("test-only second segment download interruption")
                    }
                    chain.proceed(chain.request())
                }.build())
                failSecond.set(true);onRobot { robot.download(rid) }
                waitFor("第二段下载中断被实际触发",60000) { failureObserved.get() && downloadsIdle() }
                assertFalse("失败清理partial",partial.exists())
                assertArrayEquals("中断不能覆盖完整旧版",oldManifest,File(cache,"manifest.json").readBytes())
                verifiedHashSet();record("partial-failure-preserves-old",JSONObject().put("revisionId",oldRevision))
                download(newRevision)
                val newHashes=verifiedHashSet();assertEquals("Serena",manifest().getJSONObject("voice").getString("en"))
                assertEquals(oldHashes.keys().asSequence().toSet(),newHashes.keys().asSequence().toSet())
                for(name in oldHashes.keys())assertNotEquals("新声音必须有独立音频字节",oldHashes.getString(name),newHashes.getString(name))
                disconnectTransport()
                play(newRevision);onRobot { robot.stop() }
                record("complete",JSONObject().put("oldRevision",oldRevision).put("newRevision",newRevision).put("oldHashes",oldHashes).put("newHashes",newHashes))
            } finally {
                failSecond.set(false);onRobot { robot.stop() }
                connected(true);waitFor("清理前恢复连接") { snapshot().getBoolean("online") }
                resourceId?.let { parent.json("/v1/resources/$it/unlist","POST",JSONObject()) }
                configure(originalConfig)
                record("configuration-restored")
            }
        }
    }
}
