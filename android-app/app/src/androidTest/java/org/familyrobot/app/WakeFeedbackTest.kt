package org.familyrobot.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.familyrobot.core.FaceTouch
import org.familyrobot.core.Session
import org.familyrobot.core.SessionState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 使用独立、不可连接的测试身份验证本地唤醒，不依赖家庭服务或真实用户资料。 */
@RunWith(AndroidJUnit4::class)
class WakeFeedbackTest {
    @Test fun localWakeSpeaksListensAndCancellationWins() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val audio=context.getSystemService(android.media.AudioManager::class.java)
        val originalVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val vault=Vault(context);val identity=vault.get("identity")
        vault.save("identity",JSONObject().put("mode","setup"))
        val connection=JSONObject().put("address","https://127.0.0.1:9").put("certificateSha256","0".repeat(64))
            .put("serviceId","feedback-test").put("deviceId",UUID.randomUUID().toString())
        val config=JSONObject().put("cameraAllowed",false).put("muted",false).put("nickname","小伙伴")
            .put("voice",JSONObject().put("volume",.5))
            .put("interaction",JSONObject().put("wakeFeedback","voice").put("playful",true))
            .put("policy",JSONObject().put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false))
        vault.save(robotKey(connection,"robot-config"),config)
        vault.save(robotKey(connection,"clock"),JSONObject().put("boot",System.currentTimeMillis()-SystemClock.elapsedRealtime()))
        try {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,3.coerceAtMost(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)),0)
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                lateinit var robot:RobotRuntime
                lateinit var session:Session
                scenario.onActivity {
                    robot=RobotRuntime(it,vault,connection);robot.resumeForeground()
                    session=RobotRuntime::class.java.getDeclaredField("session").apply { isAccessible=true }.get(robot) as Session
                }
                fun onRobot(action:()->Unit)=scenario.onActivity { action() }
                fun await(label:String,limit:Long=5000,check:()->Boolean) {
                    val until=SystemClock.elapsedRealtime()+limit
                    var ok=false
                    while(!ok && SystemClock.elapsedRealtime()<until) { onRobot { ok=check() };Thread.sleep(20) }
                    assertTrue("$label；state=${robot.state}，feedback=${robot.lastFeedbackResult}，mouth=${robot.mouth}，keyword=${robot.lastKeywordAction}",ok)
                }
                try {
                    await("麦克风就绪") { robot.micActive }
                    onRobot {
                        robot.touch(FaceTouch.TAP)
                        assertEquals(SessionState.OPENING,robot.state)
                        assertEquals("waking",robot.faceMode)
                        assertEquals("touch",robot.lastWakeSource)
                    }
                    await("开场确实驱动嘴形") { robot.mouth>.04f && robot.lastFeedbackResult=="playing" }
                    await("声音结束后倾听") { robot.state==SessionState.LISTENING && robot.mouth==0f }
                    var waitingAt=0L
                    onRobot { waitingAt=session.followUpAt!! }
                    await("30秒无输入后结束提示并退出",31_000) { robot.state==SessionState.STANDBY }
                    assertTrue("结束提示不显著延长等待",SystemClock.elapsedRealtime()-waitingAt in 30_000..30_700)
                    onRobot { robot.wake() }
                    await("再次唤醒仍可倾听") { robot.state==SessionState.LISTENING && robot.mouth==0f }
                    onRobot {
                        assertEquals("listening",robot.faceMode)
                        val started=session.startedAt;val waiting=session.followUpAt
                        robot.touch(FaceTouch.TICKLE)
                        assertEquals("tickle",robot.faceMode)
                        assertEquals(waiting,session.followUpAt);assertEquals(started,session.startedAt)
                        assertEquals(0f,robot.mouth)
                        robot.stop();robot.touch(FaceTouch.TICKLE)
                        assertEquals("tickle",robot.faceMode)
                    }
                    await("揉脸开场实际播放") { robot.mouth>.04f }
                    onRobot { robot.pause() }
                    Thread.sleep(1600)
                    onRobot {
                        assertEquals(SessionState.FOLLOW_UP,robot.state)
                        assertEquals("listening",robot.faceMode);assertEquals(0f,robot.mouth)
                        robot.config.getJSONObject("interaction").put("wakeFeedback","visual")
                        robot.stop();robot.wake()
                    }
                    await("仅表情进入倾听") { robot.state==SessionState.LISTENING }
                    onRobot {
                        assertEquals("visual-only",robot.lastFeedbackResult)
                        robot.config.getJSONObject("interaction").put("wakeFeedback","voice")
                        robot.stop()
                    }
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,0,0)
                    onRobot { robot.wake() }
                    await("系统零音量得到遵守") { robot.lastFeedbackResult=="muted-output" }
                    assertEquals(0,audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                    onRobot {
                        robot.config.put("muted",true);robot.stop();robot.touch(FaceTouch.TAP)
                        assertEquals(SessionState.MUTED,robot.state)
                        robot.config.put("muted",false);robot.resumeForeground();robot.wake();robot.background()
                    }
                    Thread.sleep(1300)
                    onRobot { assertFalse(session.active);assertEquals(0f,robot.mouth);assertFalse(robot.micActive) }
                } finally { onRobot { robot.close() } }
            }
        } finally {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,originalVolume,0)
            for(key in listOf("robot-config","clock","usage","service-failures","robot-version"))vault.remove(robotKey(connection,key))
            if(identity==null)vault.remove("identity") else vault.save("identity",identity)
        }
    }
}
