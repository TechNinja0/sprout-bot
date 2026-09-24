package org.familyrobot.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

private fun newKnowledge() = JSONObject().put("question", "").put("answer", "")
    .put("aliases", JSONArray()).put("briefAnswer", "").put("detailAnswer", "")
    .put("category", "生活常识").put("kind", "encyclopedia")
    .put("minAge", 3).put("maxAge", 18).put("source", "").put("sourceUrl", "")

private fun knowledgeState(state: String) = when (state) {
    "enabled" -> "已启用"; "disabled" -> "已停用"; else -> "草稿"
}

/** 一个家庭共用的知识卡；编辑、预览不会改变当前发布版本。 */
@Composable
fun KnowledgeScreen(connection: JSONObject, back: () -> Unit) {
    val api = remember(connection) { Api(connection) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var page by rememberSaveable { mutableStateOf("list") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf(JSONArray()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var id by rememberSaveable { mutableStateOf("") }
    var version by rememberSaveable { mutableIntStateOf(0) }
    var state by rememberSaveable { mutableStateOf("draft") }
    var draftText by rememberSaveable { mutableStateOf(newKnowledge().toString()) }
    var savedText by rememberSaveable { mutableStateOf(draftText) }
    var aliases by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("standard") }
    val draft = remember(draftText) { JSONObject(draftText) }
    fun dirty() = draftText != savedText
    fun run(action: suspend () -> Unit) {
        if (busy) return
        focus.clearFocus(); keyboard?.hide()
        scope.launch {
            busy = true; message = ""
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作失败，输入已保留，请稍后重试" }
            finally { busy = false }
        }
    }
    fun adopt(row: JSONObject) {
        id = row.getString("id"); version = row.getInt("version"); state = row.getString("state")
        draftText = row.getJSONObject("draft").toString(); savedText = draftText
        val list = row.getJSONObject("draft").getJSONArray("aliases")
        aliases = (0 until list.length()).joinToString("\n") { list.getString(it) }
    }
    fun update(key: String, value: Any) { draftText = JSONObject(draftText).put(key, value).toString(); reviewed = false }
    fun validate(publish: Boolean) {
        require(draft.getString("question").isNotBlank()) { "请填写孩子会问的问题" }
        require(draft.getString("category").isNotBlank()) { "请填写分类" }
        require(draft.optInt("minAge") in 3..18 && draft.optInt("maxAge") in 3..18 && draft.optInt("minAge") <= draft.optInt("maxAge")) { "年龄范围请填写 3—18，最小年龄不能超过最大年龄" }
        val items = draft.getJSONArray("aliases")
        require(items.length() <= 20 && (0 until items.length()).all { items.getString(it).length <= 120 }) { "相似问法最多 20 条，每条不超过 120 字" }
        val url = draft.optString("sourceUrl")
        require(url.isBlank() || url.matches(Regex("https?://\\S+"))) { "来源链接需要以 http:// 或 https:// 开头" }
        if (publish) {
            require(draft.getString("answer").isNotBlank()) { "请填写标准讲解" }
            require(draft.getString("kind") == "family" || draft.getString("source").isNotBlank()) { "儿童百科发布前需要填写来源说明" }
        }
    }
    suspend fun save() {
        validate(false)
        val body = JSONObject().put("draft", JSONObject(draftText))
        val row = withContext(Dispatchers.IO) {
            if (version == 0) api.json("/v1/knowledge", "POST", body.put("id", id))
            else api.json("/v1/knowledge/$id", "PUT", body.put("expectedVersion", version))
        }
        adopt(row)
    }
    fun leave() {
        if (busy) return
        when (page) {
            "preview", "tryDraft" -> { page = "edit"; message = "" }
            "edit" -> if (dirty()) confirm = "leave" else { page = "list"; refresh++; message = "" }
            "try" -> { page = "list"; message = "" }
            else -> back()
        }
    }
    BackHandler { leave() }
    DisposableEffect(api) { onDispose { api.cancel() } }
    LaunchedEffect(query, filter, refresh, page) {
        if (page != "list") return@LaunchedEffect
        loading = true
        try {
            delay(220)
            val result = withContext(Dispatchers.IO) { api.json("/v1/knowledge?q=${URLEncoder.encode(query, "UTF-8")}&state=$filter", timeoutMs = 8000) }
            rows = result.getJSONArray("items"); total = result.getInt("total"); message = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "读取失败，请重试" }
        finally { loading = false }
    }
    if (confirm.isNotEmpty()) AlertDialog(
        onDismissRequest = { if (!busy) confirm = "" },
        title = { Text(if (confirm == "leave") "放弃未保存的修改？" else "删除这条知识？") },
        text = { Text(if (confirm == "leave") "已发布内容不受影响。也可以返回编辑，先保存草稿。" else "删除后立即停止使用。内置百科也不会在重启后重新出现。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            if (confirm == "leave") { confirm = ""; page = "list"; message = ""; refresh++ }
            else run {
                withContext(Dispatchers.IO) { api.json("/v1/knowledge/$id?expectedVersion=$version", "DELETE") }
                confirm = ""; page = "list"; refresh++
            }
        }) { Text(if (confirm == "leave") "放弃修改" else "确认删除") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirm = "" }) { Text("继续编辑") } }
    )
    if (page == "try" || page == "tryDraft") {
        KnowledgeTry(api, if (page == "tryDraft") draft else null, ::leave)
        return
    }
    val title = when (page) { "edit" -> if (version == 0) "添加知识" else "编辑知识"; "preview" -> "预览与发布"; else -> "知识库" }
    Page(title, message, busy || (page == "list" && loading), onBack = ::leave, bottom = if (page == "edit" || page == "preview") ({
        Surface(shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page == "edit") {
                    OutlinedButton(onClick = { run { save(); message = "草稿已保存；孩子继续使用已发布版本" } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("保存草稿") }
                    Button(onClick = { run { validate(true); reviewed = false; mode = "standard"; page = "preview" } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("预览发布") }
                } else {
                    OutlinedButton(onClick = ::leave, enabled = !busy, modifier = Modifier.weight(1f)) { Text("返回编辑") }
                    Button(onClick = { run {
                        validate(true)
                        if (version == 0 || dirty()) save()
                        val result = withContext(Dispatchers.IO) { api.json("/v1/knowledge/$id/publish", "POST", JSONObject().put("expectedVersion", version).put("reviewed", true)) }
                        adopt(result); page = "edit"; message = "已发布，孩子现在可以使用这条讲解"
                    } }, enabled = reviewed && !busy, modifier = Modifier.weight(1f)) { Text("发布并启用") }
                }
            }
        }
    }) else null) {
        when (page) {
            "list" -> {
                Text("把可靠的解释，变成孩子听得懂的知识。", fontSize = 18.sp)
                Text("全家共用 · 只使用已发布内容 · 内置百科可修改或停用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        id = UUID.randomUUID().toString().replace("-", ""); version = 0; state = "draft"
                        draftText = newKnowledge().toString(); savedText = draftText; aliases = ""; message = ""; page = "edit"
                    }, modifier = Modifier.weight(1f)) { Text("添加知识") }
                    OutlinedButton(onClick = { page = "try"; message = "" }, modifier = Modifier.weight(1f)) { Text("试问知识库") }
                }
                Input("搜索问题、分类或讲解", query) { query = it.take(120) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf("" to "全部", "enabled" to "已启用", "draft" to "草稿", "disabled" to "已停用")) {
                        FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (loading) "正在读取…" else if (total > 200) "共 $total 条，仅显示前 200 条，请搜索缩小范围" else "共 $total 条", modifier = Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { refresh++ }) { Text("刷新") }
                }
                if (rows.length() == 0 && !loading) EmptyState("暂无匹配知识", "添加家庭知识，或换一个搜索词。")
                else DesignGroup {
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val label = "${knowledgeState(row.getString("state"))} · ${row.getString("category")} · ${if (row.getString("origin") == "builtin") "内置百科" else "家长添加"}" + if (row.optBoolean("hasChanges")) " · 有未发布修改" else ""
                        DesignRow(row.getString("question"), label, icon = "book", divider = i < rows.length() - 1) { run {
                            val item = withContext(Dispatchers.IO) { api.json("/v1/knowledge/${row.getString("id")}") }
                            adopt(item); page = "edit"
                        } }
                    }
                }
                Text("问法明确匹配时直接讲解；相近问题会先确认。可以补充孩子常用的问法，提高命中率。", fontSize = 13.sp)
            }
            "edit" -> {
                Text("${knowledgeState(state)} · ${if (state == "enabled") "编辑草稿不影响正在使用的讲解" else "预览并发布后才会用于孩子问答"}", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                SectionHeading("孩子会怎么问")
                KnowledgeField("核心问题 *", draft.getString("question"), 120, enabled = !busy) { update("question", it) }
                KnowledgeField("标准讲解 *", draft.getString("answer"), 550, 5, enabled = !busy) { update("answer", it) }
                Text("先直接回答，再解释原因、举个生活例子。内容会原样讲给孩子听，请使用自然口语。", fontSize = 12.sp)
                DetailDisclosure("相似问法与不同详略") {
                    KnowledgeField("相似问法（每行一条，最多20条）", aliases, 2400, 3, enabled = !busy) {
                        aliases = it; update("aliases", JSONArray(it.lines().map(String::trim).filter(String::isNotEmpty)))
                    }
                    KnowledgeField("简短讲解（可选）", draft.getString("briefAnswer"), 150, 2, enabled = !busy) { update("briefAnswer", it) }
                    KnowledgeField("详细讲解（可选）", draft.getString("detailAnswer"), 580, 5, enabled = !busy) { update("detailAnswer", it) }
                    Text("没有简短版时取标准讲解第一句；没有详细版时使用标准讲解。孩子说“再详细讲讲”可切换。", fontSize = 12.sp)
                }
                SectionHeading("适用范围与来源")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf("encyclopedia" to "儿童百科", "family" to "家庭知识")) FilterChip(draft.getString("kind") == key, { update("kind", key) }, label = { Text(label) }, enabled = !busy)
                }
                Text(if (draft.getString("kind") == "family") "适合家庭约定、物品位置等。避免填写门锁密码等敏感信息。" else "百科知识需要填写来源说明，方便核对和纠错。", fontSize = 12.sp)
                KnowledgeField("分类", draft.getString("category"), 30, enabled = !busy) { update("category", it) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((key, label) in listOf("minAge" to "最小年龄", "maxAge" to "最大年龄")) {
                        OutlinedTextField(draft.optString(key), { update(key, it.filter(Char::isDigit).take(2)) }, label = { Text(label) }, modifier = Modifier.weight(1f), singleLine = true, enabled = !busy)
                    }
                }
                KnowledgeField("来源说明${if (draft.getString("kind") == "encyclopedia") " *" else "（可选）"}", draft.getString("source"), 200, 2, enabled = !busy) { update("source", it) }
                KnowledgeField("来源链接（可选）", draft.getString("sourceUrl"), 500, enabled = !busy) { update("sourceUrl", it) }
                TextButton(onClick = { run { validate(true); page = "tryDraft" } }, enabled = !busy) { Text("用当前草稿试问") }
                if (version > 0) {
                    if (state == "enabled") FullAction("停用这条知识", secondary = true, enabled = !busy) { run {
                        // 停用只影响发布状态，尚未保存的输入继续保留。
                        val result = withContext(Dispatchers.IO) { api.json("/v1/knowledge/$id/disable", "POST", JSONObject().put("expectedVersion", version)) }
                        version = result.getInt("version"); state = "disabled"; message = "已停用；再次预览发布可恢复"
                    } }
                    TextButton(enabled = !busy, onClick = { confirm = "delete" }) { Text("删除这条知识", color = MaterialTheme.colorScheme.error) }
                }
            }
            "preview" -> {
                Text(draft.getString("question"), fontSize = 21.sp)
                Text("孩子会听到下面的原文。这里预览的是当前草稿。", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf("standard" to "标准", "brief" to "简短", "detail" to "详细")) FilterChip(mode == key, { mode = key }, label = { Text(label) })
                }
                val answer = when (mode) {
                    "brief" -> draft.getString("briefAnswer").ifBlank { draft.getString("answer").split(Regex("(?<=[。！？.!?])")).first() }
                    "detail" -> draft.getString("detailAnswer").ifBlank { draft.getString("answer") }
                    else -> draft.getString("answer")
                }
                DesignGroup { Text(answer, Modifier.padding(20.dp), fontSize = 18.sp, lineHeight = 29.sp) }
                Text("适用 ${draft.optInt("minAge")}—${draft.optInt("maxAge")} 岁 · ${draft.getString("category")}", fontSize = 13.sp)
                Text("来源：${draft.getString("source").ifBlank { "家长提供" }}", fontSize = 13.sp)
                if (draft.getString("sourceUrl").isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer { Text(draft.getString("sourceUrl"), fontSize = 12.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(reviewed, { reviewed = it }, modifier = Modifier.semantics { contentDescription = "已核对知识内容" }); Text("我已核对事实、表达和适用年龄", fontSize = 14.sp)
                }
                Text("发布后立即替换之前的讲解，家庭里的孩子共用这一版本。", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun KnowledgeField(label: String, value: String, limit: Int, lines: Int = 1, enabled: Boolean = true, change: (String) -> Unit) {
    OutlinedTextField(value, { change(it.take(limit)) }, enabled = enabled, label = { Text(label) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }, minLines = lines, maxLines = maxOf(lines, 8), supportingText = { Text("${value.length}/$limit") })
}

@Composable
private fun KnowledgeTry(api: Api, draft: JSONObject?, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var text by rememberSaveable { mutableStateOf(draft?.optString("question") ?: "") }
    var age by rememberSaveable { mutableStateOf("5") }
    var mode by rememberSaveable { mutableStateOf("auto") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<JSONObject?>(null) }
    Page(if (draft == null) "试问知识库" else "试问当前草稿", message, busy, onBack = { if (!busy) back() }) {
        Text(if (draft == null) "仅检索已启用知识，查看孩子实际会得到的讲解。" else "只检索当前未保存的草稿，不会发布给孩子。", fontSize = 13.sp)
        KnowledgeField("试问一个问题", text, 1000, 2) { text = it }
        Input("孩子年龄（3—18）", age) { age = it.filter(Char::isDigit).take(2) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf("auto" to "随年龄", "standard" to "标准", "brief" to "简短", "detail" to "详细")) FilterChip(mode == key, { mode = key }, label = { Text(label) })
        }
        FullAction("查看回答", enabled = !busy && text.isNotBlank() && (age.toIntOrNull() ?: 0) in 3..18) {
            focus.clearFocus(); keyboard?.hide()
            scope.launch {
                busy = true; message = ""; answer = null
                try {
                    val body = JSONObject().put("text", text).put("age", age.toInt()).put("mode", mode)
                    if (draft != null) body.put("draft", draft)
                    answer = withContext(Dispatchers.IO) { api.json("/v1/knowledge/preview", "POST", body, timeoutMs = 8000) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "试问失败，请重试" }
                finally { busy = false }
            }
        }
        answer?.let { value ->
            SectionHeading(when (value.getString("status")) { "matched" -> "命中知识"; "clarify", "ambiguous" -> "需要确认问法"; else -> "暂未找到可靠讲解" })
            DesignGroup { Text(value.getString("text"), Modifier.padding(20.dp), fontSize = 18.sp, lineHeight = 29.sp) }
            value.optJSONObject("knowledge")?.let { Text("来源：${it.optString("source")}", fontSize = 13.sp) }
            val candidates = value.optJSONArray("candidates") ?: JSONArray()
            if (candidates.length() > 0) {
                Text("可能想问（点击试问）", fontSize = 13.sp)
                for (i in 0 until candidates.length()) TextButton(onClick = { text = candidates.getJSONObject(i).getString("question") }) { Text(candidates.getJSONObject(i).getString("question")) }
            }
            Text("${value.optDouble("lookupMs")} ms 本地检索 · 不包含网络和朗读时间", fontSize = 12.sp)
        }
    }
}
