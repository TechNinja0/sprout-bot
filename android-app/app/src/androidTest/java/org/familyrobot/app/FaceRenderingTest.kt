package org.familyrobot.app

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** 真机渲染全部原创表情，横竖屏各一轮；不替代儿童对状态的理解验收。 */
@RunWith(AndroidJUnit4::class)
class FaceRenderingTest {
    @Test fun allExpressionsInBothOrientations() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val identity=vault.get("identity")!!
        vault.save("identity",JSONObject().put("mode","parent"))
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val output=File(context.filesDir,"face-gallery").apply { mkdirs() }
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                for((orientation,label) in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape")) {
                    scenario.onActivity { it.requestedOrientation=orientation };Thread.sleep(1000)
                    for(mode in listOf("standby","listening","thinking","vision","speaking","song","story","happy","laugh","hurt","cry","rest")) {
                        scenario.onActivity { activity ->
                            WindowCompat.getInsetsController(activity.window,activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
                            activity.setContent { RobotFace(mode,0.65f,true,{}, {}, {}) }
                        }
                        Thread.sleep(250)
                        assertTrue("纯脸不得出现正文：$mode",device.findObjects(By.pkg(context.packageName).text(java.util.regex.Pattern.compile(".+"))).isEmpty())
                        assertEquals(label=="landscape",device.displayWidth>device.displayHeight)
                        assertTrue(device.takeScreenshot(File(output,"$label-$mode.png")))
                    }
                }
                scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        } finally { vault.save("identity",identity) }
    }
}
