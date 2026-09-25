package org.familyrobot.app

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.familyrobot.core.faceFeedback
import org.familyrobot.core.FaceFeedbackTracker
import org.familyrobot.core.FaceSignal
import java.io.File

/** 真机渲染全部原创表情，横竖屏各一轮；不替代儿童对状态的理解验收。 */
@RunWith(AndroidJUnit4::class)
class FaceRenderingTest {
    @Test fun allExpressionsInBothOrientations() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val identity=vault.get("identity")
        vault.save("identity",JSONObject().put("mode","setup"))
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val output=File(context.filesDir,"face-gallery").apply { mkdirs() }
        val feedback=mutableStateOf(faceFeedback("standby"))
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                for((orientation,label) in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape")) {
                    scenario.onActivity { it.requestedOrientation=orientation };Thread.sleep(1000)
                    scenario.onActivity { activity ->
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        WindowCompat.getInsetsController(activity.window,activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
                        activity.setContent { RobotFace(feedback.value.mode,if(feedback.value.signal in setOf(FaceSignal.SPEAK,FaceSignal.WAKE))1f else 0f,true,{},{},{},feedback=feedback.value) }
                    }
                    for(mode in listOf("standby","waking","listening","hearing","recognizing","thinking","vision","preparing","loading","speaking","song","story","paused","fault","muted","blocked","happy","laugh","hurt","cry","rest")) {
                        scenario.onActivity { feedback.value=faceFeedback(mode) }
                        Thread.sleep(250)
                        // Android 36 在连续更新 Compose 文本时可能仍返回上一次的节点缓存。
                        if(android.os.Build.VERSION.SDK_INT>=33)InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
                        val visible=device.wait(Until.hasObject(By.text(faceFeedback(mode).title)),3000)
                        if(!visible) {
                            device.takeScreenshot(File(output,"failure-$label-$mode.png"))
                            device.dumpWindowHierarchy(File(output,"failure-$label-$mode.xml"))
                        }
                        assertTrue("状态标题必须可读：$mode",visible)
                        assertEquals(label=="landscape",device.displayWidth>device.displayHeight)
                        assertTrue(device.takeScreenshot(File(output,"$label-$mode.png")))
                    }
                }
                val tracker=FaceFeedbackTracker()
                tracker.present("thinking",1,0)
                val delayed=tracker.present("thinking",1,35000)
                scenario.onActivity { feedback.value=delayed }
                Thread.sleep(300)
                if(android.os.Build.VERSION.SDK_INT>=33)InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
                assertTrue(device.hasObject(By.text("等待有点久")))
                assertTrue(device.hasObject(By.textContains("已等待 35 秒")))
                assertTrue(device.takeScreenshot(File(output,"landscape-delayed.png")))
                scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        } finally { if(identity==null)vault.remove("identity") else vault.save("identity",identity) }
    }
}
