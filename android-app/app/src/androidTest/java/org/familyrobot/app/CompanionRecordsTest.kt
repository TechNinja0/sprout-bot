package org.familyrobot.app

import android.content.Intent
import android.os.SystemClock
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class CompanionRecordsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val out = File(context.filesDir, "records-ui").apply { mkdirs() }
    init { Configurator.getInstance().setWaitForIdleTimeout(300).setWaitForSelectorTimeout(1000) }
    private fun connections(): JSONObject = JSONObject(File(context.filesDir, "records-connection.json").readText()).also {
        for (role in listOf("parent", "robot")) check(it.getJSONObject(role).getString("address") == "https://10.0.2.2:8882") { "仅允许隔离记录测试服务" }
    }
    private fun fresh() { if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.clearCache() }
    private fun await(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            fresh()
            if (device.hasObject(By.text(text))) return
            Thread.sleep(150)
        }
        capture("failure")
        error("未显示：$text")
    }
    private fun tap(text: String) {
        fresh()
        repeat(10) {
            val found = device.findObject(By.text(text))
            if (found != null) {
                var node = found
                while (!node.isClickable && node.parent != null) node = node.parent
                node.click(); Thread.sleep(350); return
            }
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .6f)
            Thread.sleep(200); fresh()
        }
        error("未找到：$text")
    }
    private fun capture(name: String) {
        fresh(); device.takeScreenshot(File(out, "$name.png")); device.dumpWindowHierarchy(File(out, "$name.xml"))
    }

    @Test fun enableSavingFromRecordsThenReadNewCompanionTurn() {
        val data = connections(); val parent = data.getJSONObject("parent")
        val api = Api(parent); val robot = Api(data.getJSONObject("robot"))
        val rid = parent.getString("robotId")
        fun apply(enabled: Boolean) {
            robot.json("/v1/heartbeat", "POST", JSONObject())
            val current = api.json("/v1/robots/$rid/config")
            current.getJSONObject("config").getJSONObject("history").put("enabled", enabled).put("days", 7)
            val id = UUID.randomUUID().toString()
            api.json("/v1/robots/$rid/config", "POST", JSONObject().put("requestId", id).put("expectedVersion", current.getInt("version")).put("config", current.getJSONObject("config")))
            robot.json("/v1/commands/$id/ack", "POST", JSONObject().put("applied", true).put("version", current.getInt("version") + 1))
        }
        apply(false)
        api.json("/v1/records?kind=companion", "DELETE")
        api.json("/v1/records?kind=debug", "DELETE")
        fun ask(session: String) = robot.json("/v1/turns", "POST", JSONObject().put("sessionId", session).put("text", "为什么会下雨"))
        ask("records-before-enabled")
        assertEquals(0, api.array("/v1/records?kind=companion").length())
        val vault = Vault(context)
        val before = listOf("parent", "identity").associateWith { vault.get(it) }
        val running = AtomicBoolean(true); val failure = AtomicReference<Throwable?>(null)
        // 模拟机器人接收与确认配置；仍通过实际配置/命令 API，不直接改数据库。
        val worker = thread {
            try {
                while (running.get()) {
                    robot.json("/v1/heartbeat", "POST", JSONObject())
                    val commands = robot.array("/v1/commands")
                    for (i in 0 until commands.length()) {
                        val command = commands.getJSONObject(i)
                        check(command.getString("kind") == "config")
                        robot.json("/v1/commands/${command.getString("id")}/ack", "POST", JSONObject().put("applied", true).put("version", command.getInt("expected") + 1))
                    }
                    Thread.sleep(250)
                }
            } catch (e: Throwable) { failure.set(e) }
        }
        try {
            vault.save("parent", parent); vault.save("identity", JSONObject().put("mode", "parent"))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                await("家长管理"); tap("记录")
                await("陪伴对话保存未开启"); await("尚未保存陪伴对话"); capture("disabled")
                tap("记录保存设置"); await("保存后续陪伴对话")
                // Compose Switch 对外暴露 checkable 语义，不保证使用原生 Switch 类名。
                val toggle = device.wait(Until.findObject(By.checkable(true)), 5000) ?: error("缺少保存开关")
                assertFalse(toggle.isChecked); toggle.click()
                tap("保存并等待机器人应用"); await("机器人已应用配置")
                device.pressBack(); await("陪伴对话保存已开启 · 保留 7 天"); await("暂无陪伴对话")
                capture("enabled-empty")
                val response = ask("records-after-enabled")
                val rows = api.array("/v1/records?kind=companion")
                assertEquals(1, rows.length()); assertEquals("records-after-enabled", rows.getJSONObject(0).getString("session_id"))
                tap("刷新"); await("为什么会下雨"); capture("session-list")
                tap("为什么会下雨"); await("会话详情"); await("我：为什么会下雨")
                await("小伙伴：${response.getString("text")}"); capture("session-detail")
                device.pressBack(); await("对话记录"); tap("调试会话"); await("暂无调试记录")
                assertFalse(device.hasObject(By.text("陪伴对话保存未开启")))
                tap("陪伴对话"); await("为什么会下雨")
                apply(false); tap("刷新"); await("陪伴对话保存未开启"); await("为什么会下雨")
            }
            failure.get()?.let { throw AssertionError("机器人配置确认失败", it) }
        } finally {
            running.set(false); worker.join(5000)
            before.forEach { (key, value) -> if (value == null) vault.remove(key) else vault.save(key, value) }
        }
    }

    @Test fun failedReadDoesNotClaimThereAreNoRecordsAndCanRetry() {
        val connection = JSONObject(connections().getJSONObject("parent").toString()).put("token", "invalid-test-token")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                RobotTheme("light") { RecordsScreen(connection, back = {}, play = {}) }
            } }
            await("记录读取失败")
            assertFalse(device.hasObject(By.text("尚未保存陪伴对话")))
            assertFalse(device.hasObject(By.text("暂无陪伴对话")))
            capture("read-failed")
            connection.put("token", connections().getJSONObject("parent").getString("token"))
            tap("刷新"); await("陪伴对话保存未开启")
            assertFalse(device.hasObject(By.text("记录读取失败")))
        }
    }
}
