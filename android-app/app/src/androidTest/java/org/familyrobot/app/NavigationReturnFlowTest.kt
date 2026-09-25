package org.familyrobot.app

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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

/** scripts/navigation_ui_fixture.py 提供隔离 HTTPS 服务；不连接正式家庭数据。 */
@RunWith(AndroidJUnit4::class)
class NavigationReturnFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private fun fresh() { if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.clearCache() }
    private fun node(text: String): UiObject2? { fresh(); return device.findObject(By.text(text)) ?: device.findObject(By.desc(text)) }
    private fun await(text: String): UiObject2 {
        repeat(60) { node(text)?.let { return it }; SystemClock.sleep(100) }
        capture("missing"); error("未找到 $text")
    }
    private fun find(text: String): UiObject2 {
        node(text)?.let { return it }
        repeat(20) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 35)
            SystemClock.sleep(300)
            if (node(text) != null) {
                device.waitForIdle(); SystemClock.sleep(200)
                node(text)?.let { return it }
            }
        }
        capture("missing"); error("滚动后未找到 $text")
    }
    private fun tap(text: String) { find(text).click(); SystemClock.sleep(250) }
    private fun capture(name: String) {
        val folder = File(context.filesDir, "navigation-return-test").apply { mkdirs() }
        device.takeScreenshot(File(folder, "$name.png")); device.dumpWindowHierarchy(File(folder, "$name.xml"))
    }
    private fun assertReturned(text: String, y: Int) {
        SystemClock.sleep(1200)
        val returned = await(text)
        assertTrue("返回 $text 应在原位置，之前 $y、之后 ${returned.visibleCenter.y}", abs(returned.visibleCenter.y - y) <= 8)
    }
    private fun role(mode: String, test: () -> Unit) {
        Configurator.getInstance().setWaitForIdleTimeout(150).setWaitForSelectorTimeout(800)
        val fixture = JSONObject(File(context.filesDir, "navigation-connection.json").readText())
        require(fixture.getJSONObject(mode).getString("address") == "https://10.0.2.2:8881")
        val vault = Vault(context)
        val saved = listOf("parent", "robot", "identity", "pin").associateWith { vault.get(it) }
        try {
            vault.save(mode, fixture.getJSONObject(mode)); vault.save("identity", JSONObject().put("mode", mode))
            if (mode == "robot") vault.setPin("123456")
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { test() }
        } finally { saved.forEach { (key, value) -> if (value == null) vault.remove(key) else vault.save(key, value) } }
    }

    @Test fun parentLibraryBookReviewAndSettingsReturnToOriginalPosition() = role("parent") {
        await("家长管理"); tap("资源库")
        await("搜索书名 / 标签")
        var input = device.findObject(By.desc("搜索书名 / 标签"))
        while (input.className != "android.widget.EditText") input = input.parent
        input.click(); input.text = "导航测试书"; device.pressBack(); SystemClock.sleep(250)
        tap("全部"); tap("图书")
        val book = find("导航测试书 05"); val bookY = book.visibleCenter.y
        book.click(); await("资源详情"); tap("编辑工作草稿"); await("录入图书"); tap("2 校对")
        val page = find("朗读顺序 20 · 印刷页码 20"); val pageY = page.visibleCenter.y
        page.click(); await("校对本页"); device.pressBack()
        assertReturned("朗读顺序 20 · 印刷页码 20", pageY)
        capture("book-review-return")
        device.pressBack(); await("资源详情"); device.pressBack()
        assertReturned("导航测试书 05", bookY)
        capture("library-return")
        repeat(20) { if (node("搜索书名 / 标签") == null) device.swipe(device.displayWidth / 2, device.displayHeight / 3, device.displayWidth / 2, device.displayHeight * 3 / 4, 35) }
        await("导航测试书"); await("图书")
        tap("设置")
        val entry = find("本机身份"); val settingsY = entry.visibleCenter.y
        entry.click(); await("返回本机已有授权身份，不会复制其他手机的权限。")
        device.pressBack(); assertReturned("本机身份", settingsY)
        capture("parent-settings-return")
    }

    @Test fun robotManagementSettingsReturnToOriginalPosition() = role("robot") {
        SystemClock.sleep(1000)
        node("Got it")?.click(); SystemClock.sleep(300)
        val x = device.displayWidth * .9f; val y = device.displayHeight * .08f
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            inst.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(2300); touch(MotionEvent.ACTION_UP)
        await("家长管理")
        device.findObject(By.clazz("android.widget.EditText")).text = "123456"
        tap("进入"); await("机器人管理"); tap("小伙伴设置")
        val entry = find("服务器连接"); val yBefore = entry.visibleCenter.y
        entry.click(); await("服务地址"); device.pressBack()
        assertReturned("服务器连接", yBefore)
        capture("robot-settings-return")
    }
}
