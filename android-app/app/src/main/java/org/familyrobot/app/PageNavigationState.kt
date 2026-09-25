package org.familyrobot.app

import android.os.Bundle
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** 只保存轻量浏览状态，不保存凭据、网络任务或未提交的业务草稿。 */
class PageNavigationMemory {
    val positions = mutableMapOf<String, Int>()
    val values = mutableMapOf<String, MutableState<String>>()

    companion object {
        val StateSaver = Saver<PageNavigationMemory, Bundle>(
            save = { memory -> Bundle().apply {
                putBundle("positions", Bundle().apply { memory.positions.forEach { (key, value) -> putInt(key, value) } })
                putBundle("values", Bundle().apply { memory.values.forEach { (key, value) -> putString(key, value.value) } })
            } },
            restore = { bundle -> PageNavigationMemory().apply {
                bundle.getBundle("positions")?.let { saved -> saved.keySet().forEach { positions[it] = saved.getInt(it) } }
                bundle.getBundle("values")?.let { saved -> saved.keySet().forEach { values[it] = mutableStateOf(saved.getString(it).orEmpty()) } }
            } },
        )
    }
}

private val LocalPageMemory = staticCompositionLocalOf<PageNavigationMemory?> { null }
val LocalPageStateKey = staticCompositionLocalOf { "" }

@Composable
fun PageNavigationScope(owner: String, content: @Composable () -> Unit) {
    // 换身份或家庭时重新建立状态域，不能把另一身份的位置、筛选带过来。
    key(owner) {
        val memory = rememberSaveable(saver = PageNavigationMemory.StateSaver) { PageNavigationMemory() }
        CompositionLocalProvider(LocalPageMemory provides memory, content = content)
    }
}

@Composable
fun rememberNavigationValue(key: String, initial: String = ""): MutableState<String> {
    val memory = LocalPageMemory.current
    val fallback = rememberSaveable(key) { mutableStateOf(initial) }
    return remember(memory, key) { memory?.values?.getOrPut(key) { mutableStateOf(initial) } ?: fallback }
}

@Composable
fun rememberPageScroll(key: String, ready: Boolean): ScrollState {
    val provided = LocalPageMemory.current
    val fallback = rememberSaveable(saver = PageNavigationMemory.StateSaver) { PageNavigationMemory() }
    val memory = provided ?: fallback
    val scroll = remember(memory, key) { ScrollState(memory.positions[key] ?: 0) }
    var restored by remember(scroll) { mutableStateOf(false) }
    val latestReady by rememberUpdatedState(ready)
    DisposableEffect(scroll) {
        onDispose {
            if (latestReady && restored) memory.positions[key] = scroll.value
        }
    }
    LaunchedEffect(scroll, ready) {
        restored = false
        if (!ready) return@LaunchedEffect
        // 等内容完成测量再恢复；加载占位的短高度不能覆盖离开时的位置。
        withFrameNanos { }
        withFrameNanos { }
        scroll.scrollTo(memory.positions[key] ?: 0)
        restored = true
        snapshotFlow { scroll.value }.collect { memory.positions[key] = it }
    }
    return scroll
}
