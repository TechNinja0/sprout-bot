package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
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

/** 实际CameraX取帧/请求边界；注入ASR和回答，不保存帧或进行儿童声学验收。 */
@RunWith(AndroidJUnit4::class)
class VisualRequestPrivacyTest {
    @Test fun onlyExplicitCurrentVisualRequestsAttachAnImage() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val rc=vault.get("robot")!!;val api=Api(vault.get("parent")!!)
        val path="/v1/robots/${rc.getString("deviceId")}/config";val original=api.json(path).getJSONObject("config")
        val manager=context.getSystemService(AudioManager::class.java);val volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val observations=CopyOnWriteArrayList<JSONObject>();val text=AtomicReference("")
        fun waitFor(label:String,condition:()->Boolean) { val end=SystemClock.elapsedRealtime()+20000;while(!condition() && SystemClock.elapsedRealtime()<end)Thread.sleep(50);assertTrue(label,condition()) }
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
                            observations.add(JSONObject().put("phrase",text.get()).put("visualRequest",payload.optBoolean("visualRequest")).put("hasImage",payload.has("image")).put("imageAgeMs",payload.optLong("imageAgeMs",-1)))
                            reply("{\"action\":\"speak\",\"text\":\"好的。\"}".toByteArray(),"application/json")
                        }
                        "/v1/speech/reply" -> reply(context.assets.open("prompts/unclear.wav").use { it.readBytes() },"audio/wav")
                        else -> chain.proceed(request)
                    }
                }.build())
                onRobot { robot.wake() };waitFor("实际相机新帧与听取") { robot.cameraActive && robot.state==SessionState.LISTENING }
                for((phrase,visual) in listOf("不要记这个" to false,"这个故事真好听" to false,"这个怎么用" to true,"它怎么用" to false,"What is this?" to true)) {
                    text.set(phrase);val count=observations.size
                    onRobot { RobotRuntime::class.java.getDeclaredMethod("recognize",ByteArray::class.java).apply { isAccessible=true }.invoke(robot,AudioInput.wav(ByteArray(3200))) }
                    waitFor("取得请求元数据") { observations.size>count }
                    val seen=observations.last();assertEquals(phrase,visual,seen.getBoolean("visualRequest"));assertEquals(phrase,visual,seen.getBoolean("hasImage"))
                    if(visual)assertTrue("真实新帧不能过期",seen.getLong("imageAgeMs") in 0..2000)
                    waitFor("回答实际播完") { robot.state==SessionState.FOLLOW_UP }
                }
                onRobot { robot.stop() };val restored=update(original);waitFor("恢复配置") { api.json("/v1/commands/$restored").getString("state")=="applied" };onRobot { robot.background() }
            }
        } finally {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
            if(api.json(path).getJSONObject("config").toString()!=original.toString())update(original)
            File(context.filesDir,"visual-request-privacy.json").writeText(JSONObject().put("acousticAcceptance",false).put("checks",JSONArray(observations.toList())).toString())
        }
    }
}
