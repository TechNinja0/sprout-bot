package org.familyrobot.app

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DebugKeyboardLayoutTest {
    @Test fun keyboardResizesMessagesAndKeepsComposerAttached(){
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val device=UiDevice.getInstance(inst)
        Configurator.getInstance().setWaitForIdleTimeout(150).setWaitForSelectorTimeout(2000)
        val dir=File(context.filesDir,"debug-keyboard").apply{mkdirs()}
        val connection=JSONObject().put("address","https://127.0.0.1:1").put("serviceId","keyboard-layout-test").put("certificateSha256","0".repeat(64))
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{scenario->
            var density=1f
            fun ime():Int{var result=0;scenario.onActivity{result=it.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0};return result}
            fun waitForIme(visible:Boolean){val end=android.os.SystemClock.elapsedRealtime()+10000;while(android.os.SystemClock.elapsedRealtime()<end){if((ime()>0)==visible){Thread.sleep(500);return};Thread.sleep(100)};error("键盘可见性未变为 $visible")}
            fun capture(name:String){device.takeScreenshot(File(dir,"$name.png"));device.dumpWindowHierarchy(File(dir,"$name.xml"))}
            for(theme in listOf("light","dark")){
                scenario.onActivity{activity->
                    density=activity.resources.displayMetrics.density
                    activity.setContent{RobotTheme(theme){DebugChat(connection,back={},play={},title="检查与调试",tabs={DiagnosticTabs(true){}},online=true)}}
                }
                assertTrue(device.wait(Until.hasObject(By.text("检查与调试")),10000))
                val headerTop=device.findObject(By.text("检查与调试")).visibleBounds.top
                capture("$theme-closed")
                val input=device.findObject(By.clazz("android.widget.EditText"));input.click();input.text="测试输入"
                waitForIme(true)
                fun verify(name:String){
                    val header=device.findObject(By.text("检查与调试")) ?: error("标题被顶出了屏幕")
                    assertTrue("标题随键盘平移",abs(header.visibleBounds.top-headerTop)<=2)
                    assertTrue("页签被顶出了屏幕",device.hasObject(By.text("设备检查")))
                    val field=device.findObject(By.clazz("android.widget.EditText")).visibleBounds
                    var keyboardTop=0
                    val imeHeight=ime();scenario.onActivity{keyboardTop=it.window.decorView.height-imeHeight}
                    val gap=keyboardTop-field.bottom
                    assertTrue("输入框没有紧贴键盘：gap=$gap",abs(gap)<=2)
                    val auto=device.findObject(By.text("自动播放新回复")).visibleBounds
                    assertTrue("自动播放设置不应隔在输入框与键盘之间",auto.bottom<=field.top)
                    File(dir,"$name.txt").writeText("headerTop=$headerTop\nkeyboardTop=$keyboardTop\ninputBottom=${field.bottom}\ngapPx=$gap\ndensity=$density\n")
                    capture(name)
                }
                verify("$theme-keyboard")
                device.findObject(By.clazz("android.widget.EditText")).text="第一行调试内容\n第二行调试内容\n第三行调试内容\n第四行调试内容"
                Thread.sleep(500);verify("$theme-multiline")
                device.pressBack();waitForIme(false)
                assertEquals(headerTop,device.findObject(By.text("检查与调试")).visibleBounds.top)
                capture("$theme-dismissed")
            }
        }
    }
}
