package org.familyrobot.app

import android.content.Intent
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
class KnowledgeFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val vault = Vault(context)
    private val dir = File(context.filesDir, "knowledge-ui").apply { mkdirs() }
    init { Configurator.getInstance().setWaitForIdleTimeout(500).setWaitForSelectorTimeout(1000) }
    private fun fresh() { if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.clearCache() }
    private fun capture(name: String) { fresh(); device.takeScreenshot(File(dir, "$name.png")); device.dumpWindowHierarchy(File(dir, "$name.xml")) }
    private fun find(text: String): UiObject2 {
        fun match(): UiObject2? { fresh(); return device.findObject(By.desc(text)) ?: device.findObject(By.text(text).clazz("android.widget.TextView")) ?: device.findObject(By.text(text)) }
        match()?.let { return it }
        fun scroll(up: Boolean): Boolean {
            fun locate(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (n.isScrollable && n.packageName == context.packageName) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { locate(it)?.let { return it } }
                return null
            }
            val n = inst.uiAutomation.rootInActiveWindow?.let { locate(it) } ?: return false
            val moved = n.performAction(if (up) 8192 else 4096)
            Thread.sleep(700); fresh(); return moved
        }
        for (i in 0..15) { if (!scroll(true)) break; match()?.let { return it } }
        for (i in 0..19) { if (!scroll(false)) break; match()?.let { return it } }
        capture("missing"); error("未找到：$text")
    }
    private fun tap(text: String) {
        find(text); Thread.sleep(400)
        var node = find(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        node.click(); Thread.sleep(500)
    }
    private fun input(label: String, value: String) {
        var node = find(label)
        while (node.className != "android.widget.EditText") {
            node.findObject(By.clazz("android.widget.EditText"))?.let { node = it; break }
            node = node.parent ?: error("没有输入框 $label")
        }
        node.click(); node.text = value; Thread.sleep(150)
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 15000
        while (android.os.SystemClock.elapsedRealtime() < end) { fresh(); if (condition()) return; Thread.sleep(200) }
        capture("failure"); error(label)
    }
    private fun hideKeyboard() {
        fresh()
        if (inst.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) { device.pressBack(); Thread.sleep(300) }
    }

    @Test fun parentKnowledgeLifecycle() {
        val connection = JSONObject(File(context.filesDir, "knowledge-connection.json").readText())
        check(connection.getString("address").endsWith(":8879")) { "仅允许隔离服务" }
        val api = Api(connection)
        val stale = api.json("/v1/knowledge?q=测试积木").getJSONArray("items")
        for (i in 0 until stale.length()) { val row = stale.getJSONObject(i); api.json("/v1/knowledge/${row.getString("id")}?expectedVersion=${row.getInt("version")}", "DELETE") }
        val before = listOf("parent", "identity").associateWith { vault.get(it) }
        val preferences = context.getSharedPreferences("appearance", 0)
        val oldTheme = preferences.getString("theme", null)
        try {
            vault.save("parent", connection); vault.save("identity", JSONObject().put("mode", "parent"))
            for (theme in listOf("light", "dark")) {
                preferences.edit().putString("theme", theme).commit()
                val question = "测试积木放在哪里$theme"
                val answer = "积木在客厅的蓝色盒子里。玩完后放回原处，下一次就能很快找到。"
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                    waitFor("家长首页") { device.hasObject(By.text("家长管理")) }
                    tap("知识库"); waitFor("内置知识") { device.hasObject(By.text("共 30 条")) }
                    capture("$theme-list")
                    tap("试问知识库"); input("试问一个问题", "为什么会下雨"); hideKeyboard(); tap("查看回答")
                    waitFor("命中预设") { device.hasObject(By.text("命中知识")) }; capture("$theme-seed-answer")
                    tap("返回"); tap("添加知识")
                    input("核心问题 *", question)
                    input("标准讲解 *", answer)
                    capture("$theme-keyboard")
                    hideKeyboard(); tap("家庭知识")
                    tap("保存草稿")
                    waitFor("保存草稿") { device.hasObject(By.text("草稿已保存；孩子继续使用已发布版本")) }
                    val row = api.json("/v1/knowledge?q=$question").getJSONArray("items").getJSONObject(0)
                    val id = row.getString("id")
                    fun saved() = api.json("/v1/knowledge/$id")
                    fun response() = api.json("/v1/knowledge/preview", "POST", JSONObject().put("text", question))
                    assertEquals("miss", response().getString("status"))
                    // 旋转/重建后仍能继续编辑已保存草稿。
                    scenario.recreate(); find("标准讲解 *")
                    tap("用当前草稿试问"); tap("查看回答")
                    waitFor("草稿命中") { device.hasObject(By.text("命中知识")) }
                    assertTrue(device.hasObject(By.text(answer)))
                    tap("返回"); tap("预览发布")
                    tap("发布并启用")
                    assertEquals("draft", saved().getString("state"))
                    capture("$theme-publish")
                    tap("已核对知识内容"); tap("发布并启用")
                    waitFor("发布成功") { device.hasObject(By.text("已发布，孩子现在可以使用这条讲解")) }
                    assertEquals(answer, response().getString("text"))
                    input("标准讲解 *", "积木已移到卧室的白色盒子里。")
                    hideKeyboard(); tap("保存草稿")
                    waitFor("保存新草稿") { saved().getBoolean("hasChanges") }
                    assertEquals(answer, response().getString("text"))
                    tap("预览发布"); tap("已核对知识内容"); tap("发布并启用")
                    waitFor("重新发布") { response().getString("text").startsWith("积木已移") }
                    // 外部家长抢先保存后，本机输入必须保留，不能静默覆盖。
                    val other = saved()
                    api.json("/v1/knowledge/$id", "PUT", JSONObject().put("expectedVersion", other.getInt("version")).put("draft", other.getJSONObject("draft").put("answer", "另一个家长保留的草稿。")))
                    input("标准讲解 *", "本机还没有保存的讲解。")
                    hideKeyboard(); tap("保存草稿")
                    waitFor("版本冲突") { device.hasObject(By.textContains("知识已被其他家长修改")) }
                    find("标准讲解 *"); capture("$theme-conflict")
                    device.pressBack(); tap("继续编辑"); find("标准讲解 *")
                    device.pressBack(); tap("放弃修改")
                    input("搜索问题、分类或讲解", question); hideKeyboard(); tap(question)
                    tap("停用这条知识")
                    waitFor("停用") { response().getString("status") == "miss" }
                    tap("删除这条知识"); tap("确认删除")
                    waitFor("删除") { api.json("/v1/knowledge?q=$question").getInt("total") == 0 }
                    capture("$theme-empty")
                }
            }
        } finally {
            for ((key, value) in before) if (value == null) vault.remove(key) else vault.save(key, value)
            preferences.edit().apply { if (oldTheme == null) remove("theme") else putString("theme", oldTheme) }.commit()
        }
    }
}
