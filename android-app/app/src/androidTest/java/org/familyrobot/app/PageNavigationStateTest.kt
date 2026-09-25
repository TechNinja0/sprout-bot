package org.familyrobot.app

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageNavigationStateTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private class Harness {
        var owner by mutableStateOf("parent:test")
        var route by mutableStateOf("list")
        var loading by mutableStateOf(false)
        var count by mutableIntStateOf(80)
        lateinit var scroll: ScrollState
        lateinit var query: MutableState<String>
        fun attached() = ::scroll.isInitialized
    }
    private fun await(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < end) {
            inst.waitForIdleSync()
            var result = false; inst.runOnMainSync { result = check() }
            if (result) return
            SystemClock.sleep(40)
        }
        error("页面状态未在预期时间恢复")
    }
    private fun withPages(test: (Harness) -> Unit) {
        val h = Harness()
        ActivityScenario.launch<MainActivity>(Intent(inst.targetContext, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                RobotTheme("light") { PageNavigationScope(h.owner) {
                    // 两个条件分支模拟真实导航中上一页退出 composition。
                    if (h.route == "child") Page("子页", "", false) { Text("子页内容") }
                    else {
                        val scroll = rememberPageScroll(h.route, !h.loading)
                        val query = rememberNavigationValue("resources/query")
                        SideEffect { h.scroll = scroll; h.query = query }
                        Page("相同标题", "", h.loading, scroll = scroll, pageKey = h.route) {
                            Text("搜索条件：${query.value}")
                            if (!h.loading) repeat(h.count) { InfoRow("第 $it 项", "测试内容") }
                        }
                    }
                } }
            } }
            await { h.attached() && h.scroll.maxValue > 0 }
            SystemClock.sleep(100)
            test(h)
        }
    }
    private fun scrollDown(h: Harness): Int {
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 50)
        SystemClock.sleep(450)
        await { h.scroll.value > 0 && !h.scroll.isScrollInProgress }
        return h.scroll.value
    }

    @Test fun returningToDisposedListRestoresScrollAndQuery() = withPages { h ->
        inst.runOnMainSync { h.query.value = "小兔" }
        val position = scrollDown(h)
        inst.runOnMainSync { h.route = "child" }; inst.waitForIdleSync()
        inst.runOnMainSync { h.route = "list" }
        await { h.scroll.value == position && h.query.value == "小兔" }
    }

    @Test fun loadingPlaceholderAndRefreshDoNotOverwriteSavedPosition() = withPages { h ->
        val position = scrollDown(h)
        inst.runOnMainSync { h.route = "child" }; inst.waitForIdleSync()
        inst.runOnMainSync { h.loading = true; h.route = "list" }
        SystemClock.sleep(250); await { h.scroll.maxValue == 0 }
        inst.runOnMainSync { h.loading = false }
        await { h.scroll.value == position }
        // 原页刷新临时隐藏内容时也不能把旧位置覆盖成零。
        inst.runOnMainSync { h.loading = true }; SystemClock.sleep(250)
        inst.runOnMainSync { h.loading = false }
        await { h.scroll.value == position }
        inst.runOnMainSync { h.loading = true }; SystemClock.sleep(100)
        inst.runOnMainSync { h.count = 3; h.loading = false }
        await { h.scroll.value == 0 && h.scroll.maxValue == 0 }
    }

    @Test fun SameTitlesUseIndependentContentKeysAndRoles() = withPages { h ->
        val first = scrollDown(h)
        inst.runOnMainSync { h.route = "book-a/page-1" }
        await { h.scroll.value == 0 }
        val second = scrollDown(h)
        inst.runOnMainSync { h.route = "book-b/page-1" }
        await { h.scroll.value == 0 }
        inst.runOnMainSync { h.route = "book-a/page-1" }
        await { h.scroll.value == second }
        inst.runOnMainSync { h.route = "list" }
        await { h.scroll.value == first }
        inst.runOnMainSync { h.query.value = "家长筛选"; h.owner = "robot:test" }
        await { h.scroll.value == 0 && h.query.value.isEmpty() }
    }

    @Test fun saverRestoresOnlyLightweightNavigationValues() {
        val memory = PageNavigationMemory().apply {
            positions["book/a/list"] = 1200
            values["query"] = mutableStateOf("小兔")
        }
        val saved = with(PageNavigationMemory.StateSaver) { with(SaverScope { true }) { save(memory) } }!!
        val restored = PageNavigationMemory.StateSaver.restore(saved)!!
        assertEquals(1200, restored.positions["book/a/list"])
        assertEquals("小兔", restored.values["query"]?.value)
        assertEquals(setOf("positions", "values"), saved.keySet())
    }
}
