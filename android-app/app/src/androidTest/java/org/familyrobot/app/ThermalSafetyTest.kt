package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Android框架热状态注入，仅验证降级规则，不代表真实高温散热验收。 */
@RunWith(AndroidJUnit4::class)
class ThermalSafetyTest {
    @Test fun frameworkSevereTemperatureReleasesHardware() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation);val vault=Vault(context);val api=Api(vault.get("parent")!!)
        val robotId=vault.get("robot")!!.getString("deviceId");vault.save("identity",JSONObject().put("mode","robot"))
        fun waitFor(message:String,timeout:Long=15000,test:()->Boolean) { val began=SystemClock.elapsedRealtime();while(!test() && SystemClock.elapsedRealtime()-began<timeout)Thread.sleep(100);assertTrue(message,test()) }
        var runtime:RobotRuntime?=null
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> runtime=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(activity) as RobotRuntime }
            fun onRobot(action:(RobotRuntime)->Unit) { scenario.onActivity { action(runtime!!) } }
            try {
                waitFor("联网") { runtime!!.online }
                val current=api.json("/v1/robots/$robotId/config");val config=current.getJSONObject("config")
                config.getJSONObject("policy").put("intervals",JSONArray()).put("manualBlocked",false)
                config.put("muted",false).put("cameraAllowed",true).put("listeningPlans",JSONArray())
                val command=UUID.randomUUID().toString()
                api.json("/v1/robots/$robotId/config","POST",JSONObject().put("expectedVersion",current.getInt("version")).put("requestId",command).put("config",config))
                waitFor("配置生效") { api.json("/v1/commands/$command").getString("state")=="applied" }
                val book=api.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject().put("title","温控原创测试").put("complete",true).put("auditioned",true).put("pages",JSONArray().put(JSONObject().put("id","one").put("text","Hello, little cat. The ball is blue. ".repeat(8)).put("reviewed",true)))))
                val rid=book.getString("id");api.json("/v1/resources/$rid/publish","POST",JSONObject().put("expectedVersion",1).put("requestId",UUID.randomUUID().toString()))
                onRobot { it.wake() };waitFor("实际相机新帧") { runtime!!.cameraActive }
                onRobot { it.playResource(rid) }
                val audio=context.getSystemService(AudioManager::class.java);val power=context.getSystemService(PowerManager::class.java)
                waitFor("实际媒体播放",30000) { audio.isMusicActive }
                device.executeShellCommand("cmd thermalservice override-status 3")
                waitFor("框架已进入SEVERE",3000) { power.currentThermalStatus==PowerManager.THERMAL_STATUS_SEVERE }
                waitFor("热降级关闭麦克风相机音频",3000) { !runtime!!.micActive && !runtime!!.cameraActive && !audio.isMusicActive }
                device.executeShellCommand("cmd thermalservice reset")
                waitFor("解除注入恢复本地唤醒") { power.currentThermalStatus<PowerManager.THERMAL_STATUS_SEVERE && runtime!!.micActive }
                assertFalse("降温不擅自续播",audio.isMusicActive)
                api.json("/v1/resources/$rid/unlist","POST",JSONObject())
                File(context.filesDir,"thermal-safety.json").writeText(JSONObject().put("frameworkInjection",true).put("physicalHeatTest",false).put("hardwareReleased",true).put("noAutomaticResume",true).toString())
            } finally { device.executeShellCommand("cmd thermalservice reset");onRobot { it.stop() } }
        }
    }
}
