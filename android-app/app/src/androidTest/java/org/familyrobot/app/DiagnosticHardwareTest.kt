package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** 真实采样/新帧/AudioTrack；不把环境音转写或播放进度当声学验收。 */
@RunWith(AndroidJUnit4::class)
class DiagnosticHardwareTest {
    @Test fun boundedSelfCheckExportAndCancellation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val vault=Vault(context);val connection=vault.get("robot")!!;val api=Api(vault.get("parent")!!)
        val device=UiDevice.getInstance(instrumentation)
        val configPath="/v1/robots/${connection.getString("deviceId")}/config"
        val original=api.json(configPath).getJSONObject("config")
        val manager=context.getSystemService(AudioManager::class.java);val volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val result=JSONObject().put("acousticAcceptance",false)
        fun waitFor(label:String,limit:Long=15000,condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+limit
            while(!condition() && SystemClock.elapsedRealtime()<until)Thread.sleep(100)
            assertTrue(label,condition())
        }
        fun update(value:JSONObject):String {
            val prior=api.json(configPath);val request=UUID.randomUUID().toString()
            api.json(configPath,"POST",JSONObject().put("requestId",request).put("expectedVersion",prior.getInt("version")).put("config",value))
            return request
        }
        fun ack(id:String)=waitFor("配置ACK") { api.json("/v1/commands/$id").getString("state")=="applied" }
        val enabled=JSONObject(original.toString()).put("muted",false).put("cameraAllowed",true).put("listeningPlans",JSONArray())
        enabled.getJSONObject("voice").put("volume",0.2)
        enabled.getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false)
        vault.save("identity",JSONObject().put("mode","robot"))
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                lateinit var robot:RobotRuntime
                scenario.onActivity { robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as RobotRuntime }
                fun onRobot(action:()->Unit)=scenario.onActivity { action() }
                fun input()=RobotRuntime::class.java.getDeclaredField("input").apply { isAccessible=true }.get(robot) as AudioInput
                waitFor("家庭服务在线") { robot.online };ack(update(enabled));manager.setStreamVolume(AudioManager.STREAM_MUSIC,1,0)
                val began=SystemClock.uptimeMillis()
                fun touch(action:Int) { val e=android.view.MotionEvent.obtain(began,SystemClock.uptimeMillis(),action,device.displayWidth*.9f,device.displayHeight*.1f,0);instrumentation.sendPointerSync(e);e.recycle() }
                touch(0);Thread.sleep(2150);touch(1)
                assertTrue(device.wait(Until.hasObject(By.text("家长管理")),5000))
                device.findObject(By.clazz("android.widget.EditText")).text="258369";device.findObject(By.text("进入")).click()
                assertTrue(device.wait(Until.hasObject(By.text("机器人管理")),5000));device.findObject(By.text("检查与调试")).click();Thread.sleep(400)
                for(i in 0..6) { if(device.hasObject(By.text("开始本机自检")))break;device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,0.7f) }
                assertNotNull(device.findObject(By.text("开始本机自检")));device.findObject(By.text("开始本机自检")).click()
                waitFor("自检启动") { robot.selfCheckRunning }
                waitFor("有界自检结束",35000) { !robot.selfCheckRunning && robot.selfCheckReport!=null }
                val report=robot.selfCheckReport!!;result.put("hardware",JSONObject(report.toString()))
                assertEquals("completed",report.optString("status"));assertEquals("captured",report.optString("microphone"))
                assertEquals(48000,report.getInt("sampleCount"));assertEquals("fresh-frame",report.optString("camera"))
                assertEquals("played-unconfirmed",report.optString("audioOutput"))
                assertTrue(report.optString("asr") in setOf("responded","no-text"))
                waitFor("自检结束释放麦克风") { !input().recording }
                assertFalse(robot.selfCheckReport.toString().contains("transcript"))
                val exported=api.json("/v1/diagnostics")
                assertFalse(exported.toString().contains(connection.getString("token")))
                assertFalse(exported.toString().contains(connection.getString("deviceId")))
                result.put("sanitizedServiceReport",exported)
                onRobot { robot.startSelfCheck() };waitFor("第二次收音") { input().recording }
                scenario.moveToState(Lifecycle.State.CREATED)
                waitFor("后台取消并释放",3000) { !robot.selfCheckRunning && !input().recording }
                assertEquals("",robot.selfCheckTranscript);result.put("backgroundCancelled",true)
                scenario.moveToState(Lifecycle.State.RESUMED)
                onRobot { robot.startSelfCheck() };waitFor("第三次收音") { input().recording }
                ack(update(JSONObject(enabled.toString()).put("muted",true)))
                waitFor("远端静音取消",3000) { !robot.selfCheckRunning && !input().recording }
                onRobot { robot.startSelfCheck() };assertFalse(robot.selfCheckRunning);result.put("muteDenied",true)
                onRobot { robot.resumeForeground() };ack(update(enabled));onRobot { robot.background() }
                // 故意让取消后的ASR在IO线程晚返回，随后立即恢复新的儿童会话。
                val selfCheck=RobotRuntime::class.java.getDeclaredField("selfCheck").apply { isAccessible=true }.get(robot) as HardwareSelfCheck
                val selfApi=HardwareSelfCheck::class.java.getDeclaredField("api").apply { isAccessible=true }.get(selfCheck) as Api
                val clientField=Api::class.java.getDeclaredField("client").apply { isAccessible=true }
                val initial=clientField.get(selfApi) as okhttp3.OkHttpClient
                val entered=AtomicBoolean(false);val finished=AtomicBoolean(false)
                clientField.set(selfApi,initial.newBuilder().addInterceptor { chain ->
                    if(chain.request().url.encodedPath=="/v1/speech/recognize") {
                        entered.set(true);Thread.sleep(2200);finished.set(true)
                        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("delayed fixture")
                            .body("{\"text\":\"\"}".toResponseBody("application/json".toMediaType())).build()
                    } else chain.proceed(chain.request())
                }.build())
                onRobot { robot.startSelfCheck() };waitFor("迟到ASR测试已进入请求") { entered.get() }
                onRobot { robot.cancelSelfCheck();robot.resumeForeground();robot.wake() }
                waitFor("恢复新会话真实收音") { input().recording }
                waitFor("旧ASR返回") { finished.get() }
                val watchUntil=SystemClock.elapsedRealtime()+1000
                while(SystemClock.elapsedRealtime()<watchUntil) { assertTrue("旧自检finally不得关闭新收音",input().enabled && input().recording);Thread.sleep(25) }
                assertEquals("",robot.selfCheckTranscript);result.put("lateAsrCannotReleaseNewSession",true)
                clientField.set(selfApi,initial)
                onRobot { robot.stop() };ack(update(original));onRobot { robot.background() }
            }
        } finally {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
            if(api.json(configPath).getJSONObject("config").toString()!=original.toString())update(original)
            File(context.filesDir,"diagnostic-hardware.json").writeText(result.toString())
        }
    }
}
