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

@RunWith(AndroidJUnit4::class)
class StabilityConfigurationTest {
    private fun connected(action: (StabilityConfiguration, RobotRuntime) -> Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        Vault(context).save("identity",JSONObject().put("mode","robot"))
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            var robot:RobotRuntime?=null
            val deadline=SystemClock.elapsedRealtime()+20000
            while(robot?.online!=true && SystemClock.elapsedRealtime()<deadline) {
                scenario.onActivity { robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as? RobotRuntime }
                Thread.sleep(100)
            }
            check(robot?.online==true) { "机器人未联网" }
            try { action(StabilityConfiguration(context),robot!!) }
            finally { scenario.onActivity { robot!!.background() } }
        }
    }
    /** 已授权测试设备的推荐初值恢复；不冒充旧长测不存在的备份。 */
    @Test fun restoreRecommendedPolicy() = connected { config,_ ->
        config.restore()
        val value=config.current()
        value.getJSONObject("policy").put("timezone","Asia/Shanghai")
            .put("intervals",JSONArray().put(JSONObject().put("days",JSONArray(listOf(1,2,3,4,5,6,7))).put("start","20:00").put("end","09:00")))
            .put("dailyMinutes",0).put("mediaMinutes",30).put("manualBlocked",false).put("overrideUntil",0)
        value.put("listeningPlans",JSONArray())
        config.apply(value)
        assertEquals("20:00",config.current().getJSONObject("policy").getJSONArray("intervals").getJSONObject(0).getString("start"))
    }
    @Test fun originalSurvivesFailureAndHelperRecreation() = connected { config,_ ->
        val original=config.current()
        config.saveOriginal()
        try {
            val modified=JSONObject(original.toString()).put("cameraAllowed",!original.getBoolean("cameraAllowed"))
            config.apply(modified)
            throw IllegalStateException("deliberate-test-failure")
        } catch(expected:IllegalStateException) {
            assertEquals("deliberate-test-failure",expected.message)
        } finally {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            assertTrue(StabilityConfiguration(context).restore())
        }
        assertEquals(original.getBoolean("cameraAllowed"),config.current().getBoolean("cameraAllowed"))
        assertEquals(original.getJSONObject("policy").toString(),config.current().getJSONObject("policy").toString())
        assertFalse(config.restore())
    }
}
