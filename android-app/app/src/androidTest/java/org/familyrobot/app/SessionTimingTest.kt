package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.familyrobot.core.Session
import org.familyrobot.core.SessionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** 使用真实墙钟验证计时；续问事件由测试注入，不冒充儿童声学成功率。 */
@RunWith(AndroidJUnit4::class)
class SessionTimingTest {
    @Test fun realWallClockBoundariesAndCameraCycles() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val vault=Vault(context);val rc=vault.get("robot")!!;val api=Api(vault.get("parent")!!)
        val report=File(context.filesDir,"session-timing.jsonl");report.writeText("")
        fun record(event:String,round:Int,elapsed:Long) { report.appendText(JSONObject().put("event",event).put("round",round).put("elapsedMs",elapsed).put("wallTimeMs",System.currentTimeMillis()).toString()+"\n") }
        vault.save("identity",JSONObject().put("mode","robot"))
        var runtime:RobotRuntime?=null;var session:Session?=null
        fun snapshot(event:String) {
            var row=JSONObject()
            instrumentation.runOnMainSync {
                val robot=runtime;val current=session
                row=JSONObject().put("event",event).put("wallTimeMs",System.currentTimeMillis())
                    .put("state",robot?.state?.name).put("online",robot?.online).put("camera",robot?.cameraActive)
                    .put("microphone",robot?.micActive).put("keywordCount",robot?.keywordWakeCount)
                    .put("keywordAction",robot?.lastKeywordAction).put("wakeSource",robot?.lastWakeSource)
                    .put("feedback",robot?.lastFeedbackResult).put("lastInputAt",current?.lastInputAt)
                    .put("followUpAt",current?.followUpAt).put("generation",current?.generation)
                    .put("observedAtMs",SystemClock.elapsedRealtime())
            }
            report.appendText(row.toString()+"\n")
        }
        fun waitFor(label:String,limit:Long=10000,predicate:()->Boolean) {
            val at=SystemClock.elapsedRealtime();var lastSample=at
            while(!predicate() && SystemClock.elapsedRealtime()-at<limit) {
                if(SystemClock.elapsedRealtime()-lastSample>=1000) { snapshot("waiting:$label");lastSample=SystemClock.elapsedRealtime() }
                Thread.sleep(25)
            }
            if(!predicate())snapshot("failed:$label")
            assertTrue(label,predicate())
        }
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                runtime=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(activity) as RobotRuntime
                session=RobotRuntime::class.java.getDeclaredField("session").apply { isAccessible=true }.get(runtime) as Session
            }
            fun onRobot(action:(RobotRuntime)->Unit) { scenario.onActivity { action(runtime!!) } }
            val audio=context.getSystemService(AudioManager::class.java)
            try {
                waitFor("机器人联网") { runtime!!.online }
                val current=api.json("/v1/robots/${rc.getString("deviceId")}/config");val config=current.getJSONObject("config")
                config.getJSONObject("policy").put("intervals",JSONArray()).put("manualBlocked",false).put("dailyMinutes",0)
                config.put("muted",false).put("cameraAllowed",true).put("listeningPlans",JSONArray())
                config.getJSONObject("voice").put("volume",0.12)
                val id=UUID.randomUUID().toString()
                api.json("/v1/robots/${rc.getString("deviceId")}/config","POST",JSONObject().put("requestId",id).put("expectedVersion",current.getInt("version")).put("config",config))
                waitFor("配置生效") { api.json("/v1/commands/$id").getString("state")=="applied" }
                repeat(20) { round ->
                    onRobot { it.wake() };waitFor("相机新帧") { runtime!!.cameraActive && runtime!!.state==SessionState.LISTENING }
                    val requestedAt=SystemClock.elapsedRealtime()
                    val fresh=runBlocking {
                        suspendCoroutine<CameraInput.Frame?> { continuation ->
                            val result=RobotRuntime::class.java.getDeclaredMethod("waitFrame",Continuation::class.java).apply { isAccessible=true }.invoke(runtime,continuation)
                            if(result!==COROUTINE_SUSPENDED)continuation.resume(result as CameraInput.Frame?)
                        }
                    }
                    assertNotNull("视觉请求必须取得新帧",fresh)
                    assertTrue("不能重用请求前画面",fresh!!.at>=requestedAt)
                    record("camera-fresh-after-request",round,fresh.at-requestedAt)
                    val at=SystemClock.elapsedRealtime();onRobot { it.stop() }
                    waitFor("退出一秒内不再消费相机帧",1000) { !runtime!!.cameraActive && !audio.isMusicActive }
                    record("camera-release",round,SystemClock.elapsedRealtime()-at)
                }
                val diagnosticOnly=InstrumentationRegistry.getArguments().getString("timingFocus")=="idle"
                repeat(if(diagnosticOnly)1 else 5) { round ->
                    onRobot { it.wake() }
                    val roundDeadline=SystemClock.elapsedRealtime()+180000
                    var completed=false
                    repeat(4) window@{
                        if(completed)return@window
                        waitFor("等待开场或输入后的回答结束",minOf(45000,(roundDeadline-SystemClock.elapsedRealtime()).coerceAtLeast(1))) {
                            var ready=false
                            onRobot { ready=runtime!!.state in setOf(SessionState.LISTENING,SessionState.FOLLOW_UP) && session!!.followUpAt!=null && !audio.isMusicActive }
                            ready
                        }
                        var at=0L;var generation=0L;var userAt=0L
                        onRobot { at=session!!.followUpAt!!;generation=session!!.generation;userAt=session!!.lastInputAt }
                        snapshot("idle-start")
                        waitFor("完整安静窗口退出或输入打断",minOf(32000,(roundDeadline-SystemClock.elapsedRealtime()).coerceAtLeast(1))) {
                            var changed=false
                            onRobot { changed=runtime!!.state in setOf(SessionState.CLOSING,SessionState.STANDBY) || session!!.generation!=generation || session!!.lastInputAt!=userAt || session!!.followUpAt!=at }
                            changed
                        }
                        if(runtime!!.state !in setOf(SessionState.CLOSING,SessionState.STANDBY)) {
                            snapshot("idle-interrupted-by-input")
                            return@window
                        }
                        val elapsed=SystemClock.elapsedRealtime()-at
                        assertEquals("退出窗口内没有新的输入",userAt,session!!.lastInputAt)
                        assertTrue("30秒误差不超过500ms",elapsed in 30000..30500)
                        assertFalse("无输入边界相机已关闭",runtime!!.cameraActive)
                        record("idle-30-seconds",round,elapsed)
                        // 输入打断单独留档，不关闭拾音、不把被打断窗口算通过。
                        waitFor("短结束音后确实待机且无播放",1000) { runtime!!.state==SessionState.STANDBY && !audio.isMusicActive }
                        record("idle-exit-after-chime",round,SystemClock.elapsedRealtime()-at)
                        completed=true
                    }
                    assertTrue("180秒内至多4个窗口仍未取得完整安静30秒，环境条件未满足",completed)
                }
                if(diagnosticOnly) { record("diagnostic-complete",1,0);return@use }
                repeat(5) { round ->
                    onRobot { it.wake() };waitFor("进入听取") { runtime!!.state==SessionState.LISTENING }
                    val at=session!!.startedAt;var lastInput=at
                    while(SystemClock.elapsedRealtime()-at<599000) {
                        if(SystemClock.elapsedRealtime()-lastInput>=15000) {
                            onRobot { session!!.voiceStarted(SystemClock.elapsedRealtime()) }
                            lastInput=SystemClock.elapsedRealtime()
                        }
                        assertTrue("有效续问期间不提前退出",session!!.active)
                        assertEquals("未发生新一轮唤醒重置十分钟起点",at,session!!.startedAt)
                        Thread.sleep(100)
                    }
                    waitFor("十分钟到期进入关闭提示",3000) { runtime!!.state==SessionState.CLOSING }
                    val elapsed=SystemClock.elapsedRealtime()-at
                    assertTrue("真实十分钟边界",elapsed in 600000..600500)
                    assertFalse("提示时相机已关闭",runtime!!.cameraActive)
                    waitFor("本地休息提示实际出声",1000) { audio.isMusicActive }
                    waitFor("四秒内提示结束回待机",4500) { runtime!!.state==SessionState.STANDBY && !audio.isMusicActive }
                    record("session-10-minutes",round,elapsed)
                }
                record("complete",5,0)
            } catch(error:Throwable) {
                runCatching { snapshot("failure-state") }
                throw error
            } finally { onRobot { it.stop() } }
        }
    }
}
