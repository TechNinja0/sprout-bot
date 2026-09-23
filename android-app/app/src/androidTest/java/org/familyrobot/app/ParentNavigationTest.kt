package org.familyrobot.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class ParentNavigationTest {
    @Test fun tabNavigation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        Vault(context).save("identity",JSONObject().put("mode","parent"))
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("家庭小伙伴")),10000))
        device.findObject(By.text("资源库")).click()
        assertTrue(device.wait(Until.hasObject(By.text("创建草稿")),5000))
        verifyParentTabs(device,context)
    }
}

fun verifyParentTabs(device:UiDevice,context:android.content.Context) {
        fun boundsOf(name:String):android.graphics.Rect? = try { device.findObject(By.text(name))?.visibleBounds } catch(_:StaleObjectException) { null }
        fun clickSettled(name:String) {
            var previous:android.graphics.Rect?=null;var stable=0
            for(attempt in 0..30) {
                val bounds=boundsOf(name)
                stable=if(bounds!=null && bounds==previous)stable+1 else 0
                previous=bounds
                if(stable>=3 && bounds!=null) { device.click(bounds.centerX(),bounds.centerY());return }
                Thread.sleep(100)
            }
            error("标签滚动未稳定：$name")
        }
        val tabOrder=listOf("首页","资源库","成长","使用安排","声音","隐私","记忆","清单","英语计划","摘要","维护")
        fun navigate(name:String) {
            val target=tabOrder.indexOf(name)
            for(attempt in 0..20) {
                val bounds=boundsOf(name)
                if(bounds!=null && bounds.width()>=80 && bounds.centerX() in 90..device.displayWidth-90) { clickSettled(name);return }
                val visible=tabOrder.mapIndexedNotNull { index,text -> boundsOf(text)?.let { index to it } }.filter { it.second.width()>10 }
                if(visible.isEmpty()) { Thread.sleep(200);continue }
                val y=visible.first().second.centerY()
                val goLeft=if(bounds!=null)bounds.centerX()<device.displayWidth/2 else target<visible.map { it.first }.average()
                if(goLeft)device.swipe(device.displayWidth*3/10,y,device.displayWidth*7/10,y,80)
                else device.swipe(device.displayWidth*7/10,y,device.displayWidth*3/10,y,80)
                Thread.sleep(400)
            }
            error("未找到可点击标签：$name")
        }
        navigate("摘要")
        val summaryVisible=device.wait(Until.hasObject(By.textContains("待审核偏好")),5000)
        device.dumpWindowHierarchy(File(context.filesDir,"parent-navigation.xml"))
        device.takeScreenshot(File(context.filesDir,"parent-navigation.png"))
        assertTrue("摘要内容可见",summaryVisible)
        assertTrue(device.hasObject(By.textContains("当前机器人登记期间")))
        for((name,label) in listOf("记忆" to "添加明确偏好","清单" to "播放清单名称","英语计划" to "添加未启用计划","摘要" to "待审核偏好","首页" to "让英语自然出现在每天的生活里","成长" to "基准年龄","使用安排" to "每日使用分钟","声音" to "中文声音","隐私" to "允许会话内看图")) {
            navigate(name)
            assertTrue("家长标签切换：$name",device.wait(Until.hasObject(By.textContains(label)),5000))
            Thread.sleep(250)
        }
}
