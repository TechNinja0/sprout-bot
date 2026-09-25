package org.familyrobot.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.json.JSONArray
import org.json.JSONObject

/** 页码是原书标签；数组顺序才是朗读顺序。整页移动，绝不交换或重写页内字段。 */
fun moveBookPage(draft: JSONObject, pageId: String, targetId: String): Boolean {
    val pages = draft.getJSONArray("pages")
    val rows = (0 until pages.length()).map { pages.getJSONObject(it) }.toMutableList()
    val from = rows.indexOfFirst { it.getString("id") == pageId }
    val to = rows.indexOfFirst { it.getString("id") == targetId }
    if (from < 0 || to < 0 || from == to) return false
    rows.add(to, rows.removeAt(from))
    draft.put("pages", JSONArray(rows)).put("auditioned", false)
    return true
}

/** 根据当前草稿即时提示，避免拖动后继续显示上次保存时的逆序警告。 */
fun bookPageOrderWarnings(pages: JSONArray): List<String> {
    val pattern = Regex("(?:第\\s*)?([0-9]{1,6})(?:\\s*页)?")
    val warnings = mutableListOf<String>()
    var previous: Pair<Int, String>? = null
    for (i in 0 until pages.length()) {
        val page = pages.getJSONObject(i)
        val current = pattern.matchEntire(page.optString("label").trim())?.let {
            it.groupValues[1].toInt() to page.optString("chapter")
        }
        val prior = previous
        if (current != null && prior != null && current.second == prior.second) {
            if (current.first > prior.first + 1) warnings.add("第 ${i + 1} 项前，印刷页码从 ${prior.first} 跳到 ${current.first}；请核对是否缺页或属于节选。")
            else if (current.first <= prior.first) warnings.add("第 ${i + 1} 项的印刷页码 ${current.first} 重复或逆序，请核对朗读顺序与章节。")
        }
        previous = current
    }
    return warnings
}

/** 拖动期间仅预览排序，松手提交一次；取消手势不改草稿。沿用页面滚动容器。 */
@Composable
fun BookPageOrderList(
    pages: JSONArray,
    scroll: ScrollState,
    viewport: Rect,
    enabled: Boolean,
    onMove: (String, String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val rows = (0 until pages.length()).map { pages.getJSONObject(it) }
    val ids = rows.map { it.getString("id") }
    val byId = rows.associateBy { it.getString("id") }
    var previewOrder by remember { mutableStateOf<List<String>?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val maxSpeed = with(LocalDensity.current) { 640.dp.toPx() }
    val latestViewport by rememberUpdatedState(viewport)
    val latestMove by rememberUpdatedState(onMove)
    val latestOpen by rememberUpdatedState(onOpen)
    val latestIds by rememberUpdatedState(ids)

    fun cancel() { dragging = null; previewOrder = null }
    fun updateOrder() {
        val id = dragging ?: return
        val order = previewOrder ?: return
        val from = order.indexOf(id)
        val center = dragTop + (bounds[id]?.height ?: return) / 2f
        val to = order.indices.lastOrNull { it > from && center > (bounds[order[it]]?.center?.y ?: Float.MAX_VALUE) }
            ?: order.indices.firstOrNull { it < from && center < (bounds[order[it]]?.center?.y ?: -Float.MAX_VALUE) }
            ?: return
        previewOrder = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    LaunchedEffect(ids, enabled) { cancel() }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (dragging != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previousFrame) / 1_000_000_000f).coerceAtMost(.05f)
            previousFrame = now
            val area = latestViewport
            val strength = when {
                area.height <= 0 -> 0f
                pointerY < area.top + edge -> -((area.top + edge - pointerY) / edge).coerceIn(0f, 1f)
                pointerY > area.bottom - edge -> ((pointerY - area.bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (strength != 0f) scroll.scrollBy(strength * maxSpeed * seconds)
            updateOrder()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        val visibleOrder = previewOrder?.takeIf { it.toSet() == ids.toSet() } ?: ids
        for ((index, id) in visibleOrder.withIndex()) key(id) {
            val page = byId.getValue(id)
            val active = dragging == id
            Box(Modifier.fillMaxWidth().zIndex(if (active) 1f else 0f)
                .onGloballyPositioned { bounds[id] = Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                .pointerInput(id, enabled) {
                    if (enabled) detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val rect = bounds[id]
                            if (rect != null) {
                                previewOrder = latestIds; dragTop = rect.top; pointerY = rect.top + offset.y; dragging = id
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, amount -> change.consume(); dragTop += amount.y; pointerY += amount.y; updateOrder() },
                        onDragCancel = { cancel() },
                        onDragEnd = {
                            val target = previewOrder?.indexOf(id)?.let { latestIds.getOrNull(it) }
                            cancel()
                            if (target != null && target != id) latestMove(id, target)
                        },
                    )
                }) {
                Surface(Modifier.fillMaxWidth().graphicsLayer { translationY = if (active) dragTop - (bounds[id]?.top ?: dragTop) else 0f },
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shadowElevation = if (active) 6.dp else 0.dp) {
                    Column(Modifier.clickable(enabled = enabled && dragging == null) { latestOpen(id) }
                        .semantics {
                            stateDescription = "第 ${index + 1} 项，共 ${ids.size} 项"
                            if (enabled && dragging == null) customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("向前移动") { latestMove(id, ids[index - 1]); true })
                                if (index < ids.lastIndex) add(CustomAccessibilityAction("向后移动") { latestMove(id, ids[index + 1]); true })
                            }
                        }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            UiIcon(if (page.optBoolean("reviewed")) "check" else "book")
                            Column(Modifier.weight(1f)) {
                                Text("朗读顺序 ${index + 1} · ${page.optString("label").takeIf { it.isNotBlank() }?.let { "印刷页码 $it" } ?: "无印刷页码"}", fontSize = 14.sp)
                                Text("${if (page.optBoolean("reviewed")) "已校对" else "待校对"}${if (page.optBoolean("skip")) " · 不朗读" else ""} · ${page.optString("text").take(25)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("≡", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (index < ids.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
