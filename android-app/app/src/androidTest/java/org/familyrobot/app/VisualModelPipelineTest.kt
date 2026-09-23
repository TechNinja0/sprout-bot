package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.media.AudioTrack
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.familyrobot.core.SessionState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** ASR及发送图像为原创夹具；CameraX、TLS、VLM/文字追问、Qwen TTS与AudioTrack真实执行。 */
@RunWith(AndroidJUnit4::class)
class VisualModelPipelineTest {
    @Test fun localVisualModelAndFollowupReachActualPlayback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val rc=vault.get("robot")!!;val api=Api(vault.get("parent")!!)
        val path="/v1/robots/${rc.getString("deviceId")}/config";val original=api.json(path).getJSONObject("config")
        val manager=context.getSystemService(AudioManager::class.java);val volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val observations=CopyOnWriteArrayList<JSONObject>();val text=AtomicReference("");val speechBytes=AtomicInteger(0)
        val bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);canvas.drawCircle(320f,240f,140f,Paint().apply { color=Color.RED })
        fun encode(bitmap:Bitmap)=ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG,100,output);android.util.Base64.encodeToString(output.toByteArray(),2) }
        val fixture=encode(bitmap);bitmap.eraseColor(Color.rgb(3,3,3));val dark=encode(bitmap);bitmap.recycle()
        fun waitFor(label:String,condition:()->Boolean) { val end=SystemClock.elapsedRealtime()+60000;while(!condition() && SystemClock.elapsedRealtime()<end)Thread.sleep(50);assertTrue(label,condition()) }
        fun update(config:JSONObject):String { val prior=api.json(path);val id=UUID.randomUUID().toString();api.json(path,"POST",JSONObject().put("requestId",id).put("expectedVersion",prior.getInt("version")).put("config",config));return id }
        val config=JSONObject(original.toString()).put("muted",false).put("cameraAllowed",true).put("listeningPlans",JSONArray())
        config.getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false)
        config.getJSONObject("interaction").put("wakeFeedback","visual")
        vault.save("identity",JSONObject().put("mode","robot"));manager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                lateinit var robot:RobotRuntime
                scenario.onActivity { robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as RobotRuntime }
                fun onRobot(action:()->Unit)=scenario.onActivity { action() }
                waitFor("联网") { robot.online }
                val command=update(config);waitFor("配置应用") { api.json("/v1/commands/$command").getString("state")=="applied" }
                val input=RobotRuntime::class.java.getDeclaredField("input").apply { isAccessible=true }.get(robot) as AudioInput
                onRobot { input.close() }
                val runtimeApi=RobotRuntime::class.java.getDeclaredField("api").apply { isAccessible=true }.get(robot) as Api
                val clientField=Api::class.java.getDeclaredField("client").apply { isAccessible=true }
                val initial=clientField.get(runtimeApi) as okhttp3.OkHttpClient
                clientField.set(runtimeApi,initial.newBuilder().addInterceptor { chain ->
                    val request=chain.request()
                    fun reply(bytes:ByteArray,type:String)=okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture").body(bytes.toResponseBody(type.toMediaType())).build()
                    when(request.url.encodedPath) {
                        "/v1/speech/recognize" -> reply(JSONObject().put("text",text.get()).toString().toByteArray(),"application/json")
                        "/v1/turns" -> {
                            val buffer=Buffer();request.body!!.writeTo(buffer);val payload=JSONObject(buffer.readUtf8())
                            val seen=JSONObject().put("phrase",text.get()).put("visualRequest",payload.optBoolean("visualRequest")).put("hasImage",payload.has("image")).put("imageAgeMs",payload.optLong("imageAgeMs",-1))
                            // 真实CameraX必须先提供新帧，随后仅测试请求替换为原创夹具；不发送现场私人画面。
                            if(payload.optBoolean("visualRequest")) {
                                assertTrue("必须取得实际新帧",payload.has("image") && payload.optLong("imageAgeMs",-1) in 0..2000)
                                payload.put("image",if(text.get()=="看看现在")dark else fixture)
                            }
                            val response=chain.proceed(request.newBuilder().post(payload.toString().toRequestBody("application/json".toMediaType())).build())
                            seen.put("status",response.code).put("reply",JSONObject(response.peekBody(100000).string()).optString("text"));observations.add(seen)
                            response
                        }
                        "/v1/speech/reply" -> {
                            val response=chain.proceed(request)
                            if(response.isSuccessful)speechBytes.addAndGet(response.peekBody(2_000_000).bytes().size)
                            response
                        }
                        else -> chain.proceed(request)
                    }
                }.build())
                onRobot { robot.wake() };waitFor("实际相机新帧与听取") { robot.cameraActive && robot.state==SessionState.LISTENING }
                for((phrase,expected) in listOf("这是什么颜色" to "红","它是什么颜色" to "红","看看现在" to "太暗")) {
                    text.set(phrase);val count=observations.size;val priorBytes=speechBytes.get();var played=false
                    onRobot { RobotRuntime::class.java.getDeclaredMethod("recognize",ByteArray::class.java).apply { isAccessible=true }.invoke(robot,AudioInput.wav(ByteArray(3200))) }
                    waitFor("真实模型响应与播音") {
                        val track=RobotRuntime::class.java.getDeclaredField("track").apply { isAccessible=true }.get(robot) as? AudioTrack
                        if(runCatching { (track?.playbackHeadPosition ?: 0)>0 }.getOrDefault(false))played=true
                        observations.size>count && speechBytes.get()>priorBytes && played && robot.state==SessionState.FOLLOW_UP
                    }
                    val seen=observations.last();assertEquals(200,seen.getInt("status"));assertTrue(seen.toString(),seen.getString("reply").contains(expected))
                    assertEquals(phrase,phrase!="它是什么颜色",seen.getBoolean("hasImage"))
                    seen.put("actualPlayback",played).put("ttsBytes",speechBytes.get()-priorBytes)
                }
                onRobot { robot.stop() };val restored=update(original);waitFor("恢复配置") { api.json("/v1/commands/$restored").getString("state")=="applied" };onRobot { robot.background() }
            }
        } finally {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
            if(api.json(path).getJSONObject("config").toString()!=original.toString())update(original)
            File(context.filesDir,"visual-model-pipeline.json").writeText(JSONObject().put("acousticAcceptance",false).put("syntheticImage",true).put("checks",JSONArray(observations.toList())).toString())
        }
    }
}
