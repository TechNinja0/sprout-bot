package org.familyrobot.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/** 最多保留当前段和下一段；推测加载失败只在消费该段时抛出。 */
internal suspend fun <T> playWithLookahead(
    count: Int,
    load: suspend (Int) -> T,
    play: suspend (Int, T) -> Boolean,
): Boolean = supervisorScope {
    fun prepare(index: Int) = async {
        try { Result.success(load(index)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure<T>(error) }
    }
    if (count == 0) return@supervisorScope true
    var pending = prepare(0)
    try {
        for (index in 0 until count) {
            val audio = pending.await().getOrThrow()
            if (index + 1 < count) pending = prepare(index + 1)
            if (!play(index, audio)) return@supervisorScope false
        }
        true
    } finally {
        pending.cancel()
    }
}
