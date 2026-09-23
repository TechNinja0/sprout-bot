package org.familyrobot.app

import android.content.Intent
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

/** 配置ACK与真实收音流切换；不比较儿童声学成功率。 */
@RunWith(AndroidJUnit4::class)
class SettingsApplicationTest {
    @Test fun sensitivityWaitsForConversationEndAndPreservesStopWords() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val connection=vault.get("robot")!!;val api=Api(vault.get("parent")!!)
        val path="/v1/robots/${connection.getString("deviceId")}/config";val original=api.json(path).getJSONObject("config")
        val config=JSONObject(original.toString()).put("muted",false).put("cameraAllowed",false).put("listeningPlans",JSONArray())
        config.getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false)
        config.getJSONObject("interaction").put("wakeFeedback","visual").put("wakeSensitivity","standard")
        fun waitFor(label:String,condition:()->Boolean) { val until=SystemClock.elapsedRealtime()+15000;while(!condition() && SystemClock.elapsedRealtime()<until)Thread.sleep(50);assertTrue(label,condition()) }
        fun update(value:JSONObject):String { val prior=api.json(path);val id=UUID.randomUUID().toString();api.json(path,"POST",JSONObject().put("requestId",id).put("expectedVersion",prior.getInt("version")).put("config",value));return id }
        vault.save("identity",JSONObject().put("mode","robot"))
        val report=JSONObject().put("acousticAcceptance",false)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                lateinit var robot:RobotRuntime
                scenario.onActivity { robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as RobotRuntime }
                fun onRobot(action:()->Unit)=scenario.onActivity { action() }
                val input=RobotRuntime::class.java.getDeclaredField("input").apply { isAccessible=true }.get(robot) as AudioInput
                fun configure(value:JSONObject) { val id=update(value);waitFor("配置ACK") { api.json("/v1/commands/$id").getString("state")=="applied" } }
                waitFor("在线") { robot.online };configure(config)
                waitFor("标准词表进入真实录音") { input.customKeywords.contains("#0.25 @wake") && input.recording }
                val controls=input.customKeywords.lines().filter { !it.contains("@wake") }
                onRobot { robot.wake() };Thread.sleep(500)
                config.getJSONObject("interaction").put("wakeSensitivity","low").put("expressionIntensity","normal");configure(config)
                Thread.sleep(700)
                assertTrue("活跃会话保留词表",input.customKeywords.contains("#0.25 @wake"))
                onRobot { robot.stop() }
                waitFor("结束后切低灵敏度") { input.customKeywords.contains("#0.35 @wake") && input.recording }
                Thread.sleep(500);assertTrue(input.recording)
                assertEquals(controls,input.customKeywords.lines().filter { !it.contains("@wake") })
                assertEquals("normal",robot.config.getJSONObject("interaction").getString("expressionIntensity"))
                config.getJSONObject("interaction").put("wakeSensitivity","high");configure(config)
                waitFor("待机切高灵敏度") { input.customKeywords.contains("#0.15 @wake") && input.recording }
                Thread.sleep(500);assertTrue(input.recording)
                assertEquals(controls,input.customKeywords.lines().filter { !it.contains("@wake") })
                report.put("deferredUntilConversationEnds",true).put("controlsUnchanged",true).put("realMicrophoneRemainsAvailable",true)
                configure(original);onRobot { robot.background() }
            }
        } finally {
            if(api.json(path).getJSONObject("config").toString()!=original.toString())update(original)
            File(context.filesDir,"settings-application.json").writeText(report.toString())
        }
    }
}
