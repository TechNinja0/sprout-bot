package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CameraFeedbackTest {
    @Test fun realFrameTonesMuteAndStopPriority() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val rc=vault.get("robot")!!;val api=Api(vault.get("parent")!!)
        val path="/v1/robots/${rc.getString("deviceId")}/config";val original=api.json(path).getJSONObject("config")
        val manager=context.getSystemService(AudioManager::class.java);val volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val report=JSONObject().put("acousticAcceptance",false)
        fun waitFor(label:String,condition:()->Boolean) { val until=SystemClock.elapsedRealtime()+15000;while(!condition() && SystemClock.elapsedRealtime()<until)Thread.sleep(30);assertTrue(label,condition()) }
        fun update(value:JSONObject):String { val prior=api.json(path);val id=UUID.randomUUID().toString();api.json(path,"POST",JSONObject().put("requestId",id).put("expectedVersion",prior.getInt("version")).put("config",value));return id }
        val config=JSONObject(original.toString()).put("muted",false).put("cameraAllowed",true).put("listeningPlans",JSONArray())
        config.getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false)
        config.getJSONObject("voice").put("volume",0.2);config.getJSONObject("interaction").put("wakeFeedback","visual")
        vault.save("identity",JSONObject().put("mode","robot"))
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                lateinit var robot:RobotRuntime
                scenario.onActivity { robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as RobotRuntime }
                fun onRobot(action:()->Unit)=scenario.onActivity { action() }
                fun field(name:String)=RobotRuntime::class.java.getDeclaredField(name).apply { isAccessible=true }.get(robot)
                val cues=field("cameraFeedback") as CameraFeedback;val camera=field("camera") as CameraInput
                fun configure(value:JSONObject) { val id=update(value);waitFor("配置ACK") { api.json("/v1/commands/$id").getString("state")=="applied" } }
                waitFor("机器人在线") { robot.online };configure(config)
                onRobot { (field("input") as AudioInput).close() };manager.setStreamVolume(AudioManager.STREAM_MUSIC,1,0)
                onRobot { robot.wake();assertFalse("启动请求不是画面就绪",camera.ready);assertEquals(0,cues.playCount) }
                waitFor("真实新帧后启用音实际播完") { camera.ready && cues.lastEvent=="on" && cues.lastResult=="played" }
                val once=cues.playCount
                // 普通回答取消旧音频不能把同一次相机启用重复播报。
                onRobot { RobotRuntime::class.java.getDeclaredMethod("cancelAudio").apply { isAccessible=true }.invoke(robot) }
                Thread.sleep(700);assertEquals(once,cues.playCount)
                onRobot { RobotRuntime::class.java.getDeclaredMethod("keyword",String::class.java).apply { isAccessible=true }.invoke(robot,"camera_off") }
                waitFor("关闭后降音实际播完") { !camera.ready && cues.lastEvent=="off" && cues.lastResult=="played" && cues.playCount==once+1 }
                report.put("frameOnThenOff",true).put("noRepeatedOn",true)
                onRobot { robot.stop() };manager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0)
                onRobot { robot.wake() };waitFor("系统静音不出声") { camera.ready && cues.lastResult=="muted-output" }
                assertEquals(once+1,cues.playCount);onRobot { robot.stop() };manager.setStreamVolume(AudioManager.STREAM_MUSIC,1,0)
                onRobot {
                    robot.wake()
                    RobotRuntime::class.java.getDeclaredField("voiceActiveUntil").apply { isAccessible=true }.setLong(robot,SystemClock.elapsedRealtime()+5000)
                }
                waitFor("等到真实帧但让出孩子说话") { camera.ready };onRobot { robot.stop() }
                Thread.sleep(2300);assertFalse(camera.ready);assertFalse(cues.playing);assertEquals(once+1,cues.playCount)
                report.put("muteAndStopPriority",true)
                configure(JSONObject(config.toString()).put("cameraAllowed",false));onRobot { robot.wake() };Thread.sleep(800)
                assertFalse(camera.ready);assertEquals(once+1,cues.playCount);report.put("disabledCameraNoTone",true)
                onRobot { robot.stop() };configure(original);onRobot { robot.background() }
            }
        } finally {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
            if(api.json(path).getJSONObject("config").toString()!=original.toString())update(original)
            File(context.filesDir,"camera-feedback.json").writeText(report.toString())
        }
    }
}
