package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 真实墙钟长测；通过参数缩短只作脚本冒烟，报告不能冒充4/24小时。 */
@RunWith(AndroidJUnit4::class)
class StabilityTest {
    @Test fun endurance() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments();val seconds=args.getString("seconds","120")!!.toLong();val mode=args.getString("mode","mixed")!!
        require(seconds in 60..86400 && mode in setOf("mixed","standby"))
        val vault=Vault(context);val rc=vault.get("robot")!!;val api=Api(vault.get("parent")!!);val robotId=rc.getString("deviceId")
        val results=File(context.filesDir,"stability-$mode.jsonl");results.writeText("")
        fun record(value:JSONObject) { value.put("wallTimeMs",System.currentTimeMillis());results.appendText(value.toString()+"\n") }
        var begun=SystemClock.elapsedRealtime()
        vault.save("identity",JSONObject().put("mode","robot"))
        var runtime:RobotRuntime?=null
        val keywordEvents=CopyOnWriteArrayList<JSONObject>()
        val configuration=StabilityConfiguration(context)
        var configurationSaved=false
        var primaryFailure:Throwable?=null
        fun waitFor(label:String,limit:Long=15000,predicate:()->Boolean) {
            val at=SystemClock.elapsedRealtime();while(!predicate() && SystemClock.elapsedRealtime()-at<limit)Thread.sleep(100)
            assertTrue(label,predicate())
        }
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            waitFor("运行时初始化") { scenario.onActivity { activity -> runtime=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(activity) as? RobotRuntime };runtime!=null }
            // 仅增加固定口令与时间诊断；原回调仍完整执行，不过滤真实停止事件。
            val input=RobotRuntime::class.java.getDeclaredField("input").apply { isAccessible=true }.get(runtime) as AudioInput
            val callback=AudioInput::class.java.getDeclaredField("keyword").apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST")
            val originalKeyword=callback.get(input) as (String)->Unit
            callback.set(input,{ word:String ->
                keywordEvents.add(JSONObject().put("atMs",SystemClock.elapsedRealtime()).put("action",if(word.startsWith("book_"))"book" else word))
                while(keywordEvents.size>20)keywordEvents.removeAt(0)
                originalKeyword(word)
            })
            fun onRobot(action:(RobotRuntime)->Unit) { scenario.onActivity { action(runtime!!) } }
            fun snapshot():JSONObject {
                var value=JSONObject()
                onRobot { robot ->
                    val session=RobotRuntime::class.java.getDeclaredField("session").apply { isAccessible=true }.get(robot) as org.familyrobot.core.Session
                    value=JSONObject().put("state",session.state.name).put("online",robot.online)
                        .put("microphone",robot.micActive).put("camera",robot.cameraActive)
                        .put("lastInputAgeMs",SystemClock.elapsedRealtime()-session.lastInputAt)
                        .put("wakeSource",robot.lastWakeSource).put("wakeAtMs",robot.lastWakeAtMs)
                        .put("keywordWakeCount",robot.keywordWakeCount).put("keywordAction",robot.lastKeywordAction)
                        .put("keywordEvents",JSONArray(keywordEvents.toList())).put("diagnostic",robot.diagnostic)
                        .put("mediaPlaying",session.mediaPlaying).put("mediaPaused",session.mediaPaused)
                }
                return value
            }
            try {
                waitFor("联网") { runtime!!.online }
                record(JSONObject().put("event","models").put("capabilities",api.json("/v1/models")))
                configuration.saveOriginal();configurationSaved=true
                val current=api.json("/v1/robots/$robotId/config");val config=current.getJSONObject("config")
                config.getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false)
                config.getJSONObject("voice").put("volume",0.12)
                config.put("muted",false).put("cameraAllowed",true).put("listeningPlans",JSONArray())
                val command=UUID.randomUUID().toString()
                api.json("/v1/robots/$robotId/config","POST",JSONObject().put("requestId",command).put("expectedVersion",current.getInt("version")).put("config",config))
                waitFor("长测配置生效") { api.json("/v1/commands/$command").getString("state")=="applied" }
                val text="Hello, little friend. The cat is red. The ball is blue. Hello, little friend. The cat is red. The ball is blue."
                val book=api.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject().put("title","长测原创短文").put("complete",true).put("auditioned",true).put("pages",JSONArray().put(JSONObject().put("id","one").put("text",text).put("reviewed",true)))))
                val rid=book.getString("id")
                api.json("/v1/resources/$rid/publish","POST",JSONObject().put("expectedVersion",1).put("requestId",UUID.randomUUID().toString()))
                val audio=context.getSystemService(AudioManager::class.java);val power=context.getSystemService(PowerManager::class.java)
                val speech=File(context.filesDir,"test-speech.wav").readBytes()
                onRobot { it.stop() }
                waitFor("计时前进入待机") { runtime!!.micActive && !runtime!!.cameraActive && !audio.isMusicActive }
                begun=SystemClock.elapsedRealtime()
                val initialKeywordWakes=snapshot().getLong("keywordWakeCount")
                record(JSONObject().put("event","start").put("seconds",seconds).put("mode",mode).put("qualifies",seconds==if(mode=="mixed")14400L else 86400L))
                var cycle=0;var lastCycle=Long.MIN_VALUE;var lastSample=Long.MIN_VALUE
                var activeMs=0L
                val period=if(mode=="mixed")60000L else 900000L
                while(SystemClock.elapsedRealtime()-begun<seconds*1000) {
                    val now=SystemClock.elapsedRealtime()
                    if(lastCycle==Long.MIN_VALUE || now-lastCycle>=period) {
                        lastCycle=now;cycle++
                        onRobot { it.wake() }
                        waitFor("唤醒并生成新相机帧",8000) { runtime!!.cameraActive && runtime!!.state==org.familyrobot.core.SessionState.LISTENING }
                        if(mode=="mixed" && cycle%2==0) {
                            onRobot { it.playResource(rid) };waitFor("实际媒体播出",20000) { audio.isMusicActive };Thread.sleep(5000)
                        } else {
                            val start=SystemClock.elapsedRealtime()
                            onRobot { RobotRuntime::class.java.getDeclaredMethod("recognize",ByteArray::class.java).apply { isAccessible=true }.invoke(it,speech) }
                            waitFor("真实ASR对话TTS播放",65000) { audio.isMusicActive && runtime!!.faceMode=="speaking" }
                            record(JSONObject().put("event","dialogue").put("cycle",cycle).put("responseMs",SystemClock.elapsedRealtime()-start))
                            Thread.sleep(3000)
                        }
                        onRobot { it.stop() }
                        waitFor("退出后释放相机与音频",1500) { !runtime!!.cameraActive && !audio.isMusicActive }
                        activeMs+=SystemClock.elapsedRealtime()-now
                        record(JSONObject().put("event","cycle").put("cycle",cycle).put("thermal",power.currentThermalStatus))
                    }
                    if(lastSample==Long.MIN_VALUE || now-lastSample>=60000) {
                        val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
                        val battery=context.registerReceiver(null,android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val observed=snapshot()
                        record(observed.put("event","sample").put("elapsedMs",now-begun).put("pssKb",memory.totalPss).put("thermal",power.currentThermalStatus).put("batteryTemperatureTenths",battery?.getIntExtra("temperature",-1)))
                        if(mode=="standby") {
                            assertTrue("待机保持本地唤醒入口",runtime!!.micActive)
                            // 真实麦克风仍工作；两次计划问答之间可能有外部唤醒。
                            // 在实际STANDBY检查残留，外部会话另留来源证据，不能算背景误唤醒验收通过。
                            if(observed.getString("state")=="STANDBY") {
                                waitFor("实际待机不保留相机或媒体",1500) {
                                    val latest=snapshot()
                                    latest.getString("state")!="STANDBY" || (!latest.getBoolean("camera") && !audio.isMusicActive)
                                }
                            }
                            if(observed.getLong("lastInputAgeMs")>31500) {
                                assertFalse("无新输入超过30秒应关闭相机",observed.getBoolean("camera"))
                            }
                        }
                        lastSample=now
                    }
                    Thread.sleep(500)
                }
                onRobot { it.stop() };api.json("/v1/resources/$rid/unlist","POST",JSONObject())
                record(JSONObject().put("event","complete").put("elapsedMs",SystemClock.elapsedRealtime()-begun).put("activeMs",activeMs).put("cycles",cycle).put("keywordWakeCount",snapshot().getLong("keywordWakeCount")-initialKeywordWakes).put("passed",true))
            } catch(error:Throwable) {
                primaryFailure=error
                record(runCatching { snapshot() }.getOrElse { JSONObject().put("snapshotUnavailable",it.javaClass.simpleName) }.put("event","failure-state"))
                record(JSONObject().put("event","failed").put("elapsedMs",SystemClock.elapsedRealtime()-begun).put("error",error.javaClass.simpleName+": "+error.message))
                throw error
            } finally {
                try {
                    onRobot { it.stop() }
                    if(configurationSaved) {
                        check(configuration.restore())
                        record(JSONObject().put("event","configuration-restored").put("acknowledged",true))
                    }
                } catch(cleanup:Throwable) {
                    record(JSONObject().put("event","configuration-restore-failed").put("error",cleanup.javaClass.simpleName))
                    if(primaryFailure!=null) primaryFailure!!.addSuppressed(cleanup) else throw cleanup
                } finally { onRobot { it.background() } }
            }
        }
    }
}
