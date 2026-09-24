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

@RunWith(AndroidJUnit4::class)
class SetupFlowTest {
    @Test fun addressEnrollmentAndFirstRunPages(){
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val device=UiDevice.getInstance(inst);val vault=Vault(context)
        Configurator.getInstance().setWaitForIdleTimeout(150).setWaitForSelectorTimeout(1000)
        val prior=listOf("identity","robot","pin").associateWith{vault.get(it)}
        val appearance=context.getSharedPreferences("appearance",0);val theme=appearance.getString("theme",null)
        val dir=File(context.filesDir,"setup-flow").apply{mkdirs()}
        fun capture(name:String){device.takeScreenshot(File(dir,"$name.png"));device.dumpWindowHierarchy(File(dir,"$name.xml"))}
        fun find(text:String):UiObject2{
            fun match()=device.findObject(By.text(text))?:device.findObject(By.desc(text))
            repeat(8){match()?.let{return it};device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.65f);Thread.sleep(100)}
            repeat(8){match()?.let{return it};device.findObject(By.scrollable(true))?.scroll(Direction.UP,.65f);Thread.sleep(100)}
            capture("missing");error("未找到 $text")
        }
        fun tap(text:String){find(text).click();Thread.sleep(250)}
        fun field(label:String,value:String){find(label);val edit=device.findObject(By.clazz("android.widget.EditText").hasDescendant(By.desc(label))) ?: error("输入框不存在 $label");edit.click();edit.text=value}
        fun waitFor(text:String,timeout:Long=15000){if(!device.wait(Until.hasObject(By.text(text)),timeout)){capture("failed-wait");assertTrue(text,false)}}
        try{
            vault.save("identity",JSONObject().put("mode","setup"));appearance.edit().putString("theme","dark").commit()
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{scenario->
                waitFor("首次设置");capture("roles-dark");tap("家长手机");waitFor("扫描小伙伴二维码");capture("parent-dark")
                assertFalse(device.hasObject(By.textContains("JSON")))
                assertFalse(device.hasObject(By.text("导入家长配对文件")))
                tap("无法扫码？");find("导入家长配对文件");capture("parent-backup-dark");device.pressBack()
                tap("机器人手机");find("电脑 IP / 主机名");capture("robot-dark")
                assertFalse(device.hasObject(By.textContains("JSON")))
                field("电脑 IP / 主机名","127.0.0.1");field("端口","0");device.pressBack();tap("检测并连接")
                waitFor("端口请输入 1—65535 之间的数字");capture("invalid-port")
                field("端口",InstrumentationRegistry.getArguments().getString("setupPort")?:"8877");device.pressBack();tap("检测并连接")
                waitFor("家庭服务已连接",60000);capture("pin-dark")
                field("设置管理 PIN","123456");field("确认管理 PIN","654321");device.pressBack();tap("稍后授权，进入小伙伴")
                waitFor("请设置6—12位数字 PIN，并确保两次输入一致")
                scenario.recreate();waitFor("家庭服务已连接");capture("pin-restored");field("设置管理 PIN","123456");field("确认管理 PIN","123456");device.pressBack();tap("稍后授权，进入小伙伴")
                if(!device.wait(Until.gone(By.text("完成设置")),15000)){capture("finish-failure");error("完成设置后仍停留在设置页")}
                assertEquals("robot",vault.get("identity")!!.getString("mode"))
                assertTrue(vault.verifyPin("123456"));assertTrue(vault.get("robot")!!.getString("address").endsWith(":8877"))
            }
            vault.save("identity",JSONObject().put("mode","setup"));appearance.edit().putString("theme","light").commit()
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{
                waitFor("首次设置");tap("机器人手机");find("电脑 IP / 主机名");capture("robot-light");device.pressBack();tap("家长手机");find("扫描小伙伴二维码");capture("parent-light")
            }
        }finally{
            for((key,value) in prior)if(value==null)vault.remove(key)else vault.save(key,value)
            appearance.edit().apply{if(theme==null)remove("theme")else putString("theme",theme)}.commit()
        }
    }
    @Test fun addressValidation(){
        assertEquals("https://192.168.1.2:8765",setupAddress("192.168.1.2","8765"))
        assertEquals("https://[::1]:8765",setupAddress("::1","8765"))
        for(host in listOf("", "https://example.com", "user@example.com", "a/path", "a?x", "a#x", "a b"))assertTrue(runCatching{setupAddress(host,"8765")}.isFailure)
        for(port in listOf("", "0", "65536", "bad"))assertTrue(runCatching{setupAddress("localhost",port)}.isFailure)

    }
}
