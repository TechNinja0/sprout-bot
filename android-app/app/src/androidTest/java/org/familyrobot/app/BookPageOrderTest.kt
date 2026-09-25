package org.familyrobot.app

import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookPageOrderTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private fun draft(count: Int) = JSONObject().put("auditioned", true).put("pages", JSONArray().apply {
        repeat(count) { put(JSONObject().put("id", "p$it").put("label", (it + 1).toString()).put("text", "page-$it")
            .put("reviewed", true).put("sourceAsset", "asset-$it").put("skip", it == 2)
            .put("pronunciation", JSONObject().put("字", "音")).put("breakBefore", it == 1)) }
    })
    private fun ids(draft: JSONObject): List<String> = draft.getJSONArray("pages").let { p -> (0 until p.length()).map { p.getJSONObject(it).getString("id") } }

    @Test fun movingPreservesWholePagesAndInvalidatesOnlyRealChanges() {
        val data = draft(4)
        val originals = data.getJSONArray("pages").let { p -> (0 until p.length()).associate { p.getJSONObject(it).getString("id") to p.getJSONObject(it).toString() } }
        val audition = bookAuditionKey(data)
        assertFalse(moveBookPage(data, "p0", "p0")); assertTrue(data.getBoolean("auditioned"))
        assertFalse(moveBookPage(data, "missing", "p1")); assertTrue(data.getBoolean("auditioned"))
        assertTrue(moveBookPage(data, "p0", "p3"))
        assertEquals(listOf("p1", "p2", "p3", "p0"), ids(data))
        assertFalse(data.getBoolean("auditioned")); assertNotEquals(audition, bookAuditionKey(data))
        assertTrue(moveBookPage(data, "p0", "p1")); assertEquals(listOf("p0", "p1", "p2", "p3"), ids(data))
        data.getJSONArray("pages").let { p -> repeat(p.length()) { assertEquals(originals[p.getJSONObject(it).getString("id")], p.getJSONObject(it).toString()) } }
    }

    @Test fun warningsFollowCurrentOrderAndPageLabelsNeverSort() {
        val data = draft(3); val pages = data.getJSONArray("pages")
        pages.getJSONObject(0).put("label", "第 3 页"); pages.getJSONObject(2).put("label", "1")
        assertEquals(listOf("p0", "p1", "p2"), ids(data)); assertEquals(2, bookPageOrderWarnings(pages).size)
        moveBookPage(data, "p2", "p0"); moveBookPage(data, "p1", "p0")
        assertTrue(bookPageOrderWarnings(data.getJSONArray("pages")).isEmpty())
        pages.getJSONObject(1).put("chapter", "第二章"); pages.getJSONObject(2).put("label", "扉页")
        assertTrue(bookPageOrderWarnings(pages).isEmpty())
    }

    private class Harness(count: Int, create: (Int) -> JSONObject) {
        var data by mutableStateOf(create(count))
        var viewport by mutableStateOf(Rect.Zero)
        val scroll = ScrollState(0)
        var moves = 0
        var opened = ""
    }
    private fun withList(count: Int, theme: String = "light", enabled: Boolean = true, test: (Harness) -> Unit) {
        Configurator.getInstance().setWaitForIdleTimeout(100).setWaitForSelectorTimeout(1000)
        val h = Harness(count, ::draft)
        ActivityScenario.launch<MainActivity>(Intent(inst.targetContext, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                RobotTheme(theme) {
                    Page("逐页校对", "", false, scroll = h.scroll, onScrollViewport = { h.viewport = it }, bottom = {
                        BookFooter("试听与发布", "保存草稿", true, onPrimary = {}, onSecondary = {})
                    }) {
                        DesignGroup { BookPageOrderList(h.data.getJSONArray("pages"), h.scroll, h.viewport, enabled,
                            onMove = { id, target -> if (moveBookPage(h.data, id, target)) { h.moves++; h.data = JSONObject(h.data.toString()) } },
                            onOpen = { h.opened = it }) }
                    }
                }
            } }
            assertTrue(device.wait(Until.hasObject(By.textContains("page-0")), 5000))
            inst.waitForIdleSync(); SystemClock.sleep(150)
            test(h)
        }
    }
    private fun row(id: Int): android.graphics.Rect {
        if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.clearCache()
        var node = device.findObject(By.textContains("page-$id")) ?: error("找不到 page-$id")
        while (!node.isClickable && node.parent != null) node = node.parent
        return node.visibleBounds
    }
    private fun drag(from: Point, to: Point, cancel: Boolean = false, hold: Long = 0) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Int, y: Int) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(inst.uiAutomation.injectInputEvent(e, true)); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, from.x, from.y)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 200)
        for (i in 1..30) {
            event(MotionEvent.ACTION_MOVE, from.x + (to.x - from.x) * i / 30, from.y + (to.y - from.y) * i / 30)
            SystemClock.sleep(24)
        }
        if (hold > 0) SystemClock.sleep(hold)
        event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, to.x, to.y)
        inst.waitForIdleSync(); SystemClock.sleep(200)
    }

    @Test fun longPressMovesBothDirectionsInBothThemesAndTapStillOpensPage() {
        for (theme in listOf("light", "dark")) withList(5, theme) { h ->
            val start = row(0); val end = row(2)
            drag(Point(start.centerX(), start.centerY()), Point(end.centerX(), end.centerY() + 12))
            assertEquals(listOf("p1", "p2", "p0", "p3", "p4"), ids(h.data)); assertEquals(1, h.moves); assertEquals("", h.opened)
            val back = row(0); val first = row(1)
            drag(Point(back.centerX(), back.centerY()), Point(first.centerX(), first.centerY() - 12))
            assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), ids(h.data)); assertEquals(2, h.moves)
            val folder = File(inst.targetContext.filesDir, "page-order-test").apply { mkdirs() }
            device.takeScreenshot(File(folder, "$theme.png"))
            device.findObject(By.textContains("page-2")).click(); inst.waitForIdleSync()
            assertEquals("p2", h.opened)
        }
    }

    @Test fun cancelledDragLeavesDraftUntouched() = withList(5) { h ->
        val before = h.data.toString(); val start = row(0); val end = row(3)
        drag(Point(start.centerX(), start.centerY()), Point(end.centerX(), end.centerY()), cancel = true)
        assertEquals(before, h.data.toString()); assertEquals(0, h.moves); assertEquals("", h.opened)
        drag(Point(start.centerX(), start.centerY()), Point(start.centerX(), start.centerY() + 5))
        assertEquals(before, h.data.toString()); assertEquals(0, h.moves)
    }

    @Test fun ordinarySwipeScrollsWithoutChangingOrder() = withList(40) { h ->
        val before = h.data.toString()
        device.swipe(device.displayWidth / 2, h.viewport.bottom.toInt() - 40, device.displayWidth / 2, h.viewport.top.toInt() + 40, 30)
        device.waitForIdle(); inst.waitForIdleSync()
        assertTrue(h.scroll.value > 0); assertEquals(before, h.data.toString()); assertEquals(0, h.moves)
    }

    @Test fun disabledListCannotReorder() = withList(5, enabled = false) { h ->
        // Disabled rows have no clickable ancestor; use text bounds directly.
        val from = device.findObject(By.textContains("page-0")).visibleCenter
        val to = device.findObject(By.textContains("page-2")).visibleCenter
        drag(from, to)
        assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), ids(h.data)); assertEquals(0, h.moves)
    }

    @Test fun holdingNearEdgesScrollsWithoutLiftingFinger() = withList(40) { h ->
        val start = row(0)
        val bottom = h.viewport.bottom.toInt() - 12
        drag(Point(start.centerX(), start.centerY()), Point(start.centerX(), bottom), hold = 1800)
        val movedIndex = ids(h.data).indexOf("p0")
        assertTrue("应移动到初始屏幕以外，实际位置 $movedIndex", movedIndex > 10)
        assertTrue(h.scroll.value > 0); assertEquals(1, h.moves)
        val moved = row(0)
        drag(Point(moved.centerX(), moved.centerY()), Point(moved.centerX(), h.viewport.top.toInt() + 12), hold = 3500)
        assertEquals("p0", ids(h.data).first()); assertEquals(0, h.scroll.value); assertEquals(2, h.moves)
    }
}
