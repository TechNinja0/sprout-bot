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
        assertTrue(device.wait(Until.hasObject(By.text("家长管理")),10000))
        verifyParentTabs(device,context)
    }
}

fun verifyParentTabs(device:UiDevice,context:android.content.Context) {
    fun find(text:String):UiObject2 {
        repeat(12){device.findObject(By.text(text))?.let{return it};device.findObject(By.scrollable(true))?.scroll(Direction.UP,.9f);Thread.sleep(100)}
        repeat(20){device.findObject(By.text(text))?.let{return it};device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.6f);Thread.sleep(150)}
        device.dumpWindowHierarchy(File(context.filesDir,"parent-navigation.xml"))
        device.takeScreenshot(File(context.filesDir,"parent-navigation.png"))
        error("未找到二级页入口：$text")
    }
    fun tap(text:String){find(text).click();Thread.sleep(600)}
    tap("设置")
    for((entry,label) in listOf("记忆" to "添加明确偏好","摘要" to "待审核偏好：","成长" to "基准年龄（3—17）","声音" to "中文声音","隐私" to "允许会话内看图")) {
        tap(entry)
        assertTrue("家长二级页：$entry",device.wait(Until.hasObject(By.textContains(label)),10000))
        device.pressBack();Thread.sleep(400)
    }
    tap("首页");tap("播放清单");find("播放清单名称");tap("英语计划");find("添加未启用计划")
    device.pressBack();Thread.sleep(400);tap("首页");tap("使用安排");find("每日使用分钟，0表示不设额度")
    device.dumpWindowHierarchy(File(context.filesDir,"parent-navigation.xml"));device.takeScreenshot(File(context.filesDir,"parent-navigation.png"))
}
