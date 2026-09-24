package org.familyrobot.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** No changes to family service configuration. Restores local PIN, identity and theme. */
@RunWith(AndroidJUnit4::class)
class DesignAlignmentTest {
    init { Configurator.getInstance().setWaitForIdleTimeout(200).setWaitForSelectorTimeout(2000) }
    @Test fun parentConnectionAndNavigation() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val device=UiDevice.getInstance(inst);val vault=Vault(context);val identity=vault.get("identity")
        val dir=File(context.filesDir,"design-alignment").apply{mkdirs()}
        try {
            check(vault.get("parent")!=null){"需要已有家长身份"}
            vault.save("identity",JSONObject().put("mode","parent"))
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use {
                assertTrue(device.wait(Until.hasObject(By.text("查看连接")),15000))
                device.findObject(By.text("查看连接")).click()
                assertTrue(device.wait(Until.hasObject(By.text("服务器连接")),5000))
                assertTrue(device.wait(Until.hasObject(By.text("证书指纹一致")),15000))
                device.takeScreenshot(File(dir,"parent-connection.png"));device.dumpWindowHierarchy(File(dir,"parent-connection.xml"))
                device.pressBack();Thread.sleep(300)
                verifyParentTabs(device,context)
            }
        } finally {if(identity!=null)vault.save("identity",identity) else vault.remove("identity")}
    }
    @Test fun robotPagesMatchPrototypeAndChatWorks() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val device=UiDevice.getInstance(inst);val vault=Vault(context)
        val pin=vault.get("pin");val identity=vault.get("identity")
        val appearance=context.getSharedPreferences("appearance",0);val priorTheme=appearance.getString("theme",null)
        val dir=File(context.filesDir,"design-alignment").apply{mkdirs()}
        fun capture(name:String){device.takeScreenshot(File(dir,"$name.png"));device.dumpWindowHierarchy(File(dir,"$name.xml"))}
        fun waitFor(label:String,seconds:Int=15,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+seconds*1000;while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(150)};capture("failure");assertTrue(label,condition())}
        fun find(text:String):UiObject2{
            fun match()=device.findObject(By.text(text)) ?: device.findObject(By.desc(text))
            repeat(10){match()?.let{return it};device.findObject(By.scrollable(true))?.scroll(Direction.UP,.8f);Thread.sleep(100)}
            repeat(14){match()?.let{return it};device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.6f);Thread.sleep(100)}
            capture("missing");error("未找到 $text")
        }
        fun tap(text:String){find(text).click();Thread.sleep(250)}
        fun manage(){
            val t=SystemClock.uptimeMillis()
            fun touch(action:Int){val e=android.view.MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,device.displayWidth*.9f,device.displayHeight*.1f,0);inst.sendPointerSync(e);e.recycle()}
            touch(0);Thread.sleep(2200);touch(1)
            waitFor("PIN"){device.hasObject(By.text("进入"))};device.findObject(By.clazz("android.widget.EditText")).text="258369";tap("进入")
            waitFor("管理首页"){device.hasObject(By.text("机器人管理"))}
        }
        try {
            vault.setPin("258369");vault.save("identity",JSONObject().put("mode","robot"));appearance.edit().putString("theme","dark").commit()
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{scenario->
                var robot:RobotRuntime?=null
                scenario.onActivity{robot=MainActivity::class.java.getDeclaredField("runtime").apply{isAccessible=true}.get(it) as RobotRuntime}
                waitFor("服务在线"){robot!!.online};manage();capture("robot-home-dark")
                val pairing=find("家长与配对").visibleBounds;val diagnostics=find("检查与调试").visibleBounds
                assertTrue("首页功能卡片应为双列",kotlin.math.abs(pairing.top-diagnostics.top)<15&&pairing.left<diagnostics.left)
                tap("查看连接");find("家庭服务器");find("身份验证");capture("connection-dark")
                assertFalse("不应直接堆叠诊断导出控件",device.hasObject(By.text("刷新脱敏诊断")))
                tap("技术详情");find("连接结果");capture("connection-expanded");tap("重新检查连接");find("服务器身份验证通过");device.pressBack()
                tap("检查与调试");find("设备检查");find("调试");capture("diagnostics-dark")
                assertFalse("未确认临时采集前按钮禁用",find("开始本机自检").parent.isEnabled)
                tap("开始本机自检");assertFalse("未确认不得启动采集",robot!!.selfCheckRunning)
                tap("调试");find("当前调试会话");capture("chat-empty-dark")
                device.findObject(By.clazz("android.widget.EditText")).text="你好，请简短介绍自己。"
                tap("发送");waitFor("真实模型回复",100){device.hasObject(By.text("播放 / 再次播放"))};capture("chat-reply-dark")
                tap("播放 / 再次播放");tap("会话记录");find("调试记录");device.pressBack()
                tap("切换语音输入");tap("点击录音");waitFor("录音"){device.hasObject(By.text("正在录音…"))};capture("chat-recording")
                tap("设备检查");waitFor("设备页"){device.hasObject(By.text("我已了解临时采集范围"))};assertFalse(device.hasObject(By.text("正在录音…")))
                device.pressBack();tap("小伙伴设置");capture("settings-dark");tap("修改 PIN");find("当前 PIN");find("确认新 PIN");capture("pin-dark");device.pressBack();find("小伙伴设置")
                tap("主题颜色");tap("浅色");capture("theme-light");device.pressBack();device.pressBack();capture("robot-home-light")
                tap("查看连接");capture("connection-light");device.pressBack();tap("检查与调试");capture("diagnostics-light");tap("调试");capture("chat-empty-light");device.pressBack()
                tap("家长与配对");find("已绑定家长");capture("pairing-light");device.pressBack();tap("使用教程");capture("tutorial-light")
            }
        } finally {
            if(pin!=null)vault.save("pin",pin)else vault.remove("pin")
            if(identity!=null)vault.save("identity",identity)else vault.remove("identity")
            appearance.edit().apply{if(priorTheme==null)remove("theme")else putString("theme",priorTheme)}.commit()
        }
    }
}
