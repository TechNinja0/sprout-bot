package org.familyrobot.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class QuietInterval(val days: Set<Int>, val start: LocalTime, val end: LocalTime)
data class UsagePolicy(
    val zone: ZoneId = ZoneId.of("Asia/Shanghai"),
    val intervals: List<QuietInterval> = listOf(QuietInterval((1..7).toSet(), LocalTime.of(20,0), LocalTime.of(9,0))),
    val dailyMinutes: Int = 0,
    val mediaMinutes: Int = 30,
    val manualBlocked: Boolean = false,
    val overrideUntilMs: Long = 0
)
enum class Permission { ALLOWED, SCHEDULED, QUOTA, MANUAL, UNTRUSTED_TIME }

object PolicyEngine {
    fun permission(policy: UsagePolicy, nowMs: Long, usedMs: Long, trusted: Boolean = true): Permission {
        if (!trusted) return Permission.UNTRUSTED_TIME
        if (policy.manualBlocked) return Permission.MANUAL
        if (policy.overrideUntilMs > nowMs && policy.overrideUntilMs-nowMs <= 900_000) return Permission.ALLOWED
        val local = Instant.ofEpochMilli(nowMs).atZone(policy.zone)
        val minute = local.toLocalTime()
        val today = local.dayOfWeek.value
        val yesterday = local.minusDays(1).dayOfWeek.value
        for (interval in policy.intervals) {
            val blocked = when {
                interval.start == interval.end -> today in interval.days // 相同起止表示整天。
                interval.start < interval.end -> today in interval.days && minute >= interval.start && minute < interval.end
                else -> (today in interval.days && minute >= interval.start) || (yesterday in interval.days && minute < interval.end)
            }
            if (blocked) return Permission.SCHEDULED
        }
        if (policy.dailyMinutes > 0 && usedMs >= policy.dailyMinutes * 60_000L) return Permission.QUOTA
        return Permission.ALLOWED
    }
}

/** 计费按单调时钟；每次 tick 持久化调用方的 totals，崩溃误差限于 tick 间隔。 */
class UsageLedger(val totals: MutableMap<String, Long> = mutableMapOf()) {
    private var lastMono: Long? = null
    private var lastWall: Long? = null
    private var lastZone: ZoneId? = null
    private var wasBillable = false
    var trusted = true; private set
    fun tick(mono: Long, wall: Long, zone: ZoneId, billable: Boolean) {
        val pm = lastMono; val pw = lastWall
        if (pm != null && pw != null) {
            val elapsed = mono-pm
            if (elapsed < 0 || kotlin.math.abs((wall-pw)-elapsed) > 120_000 || lastZone != zone) trusted = false
            if (trusted && wasBillable && elapsed > 0) {
                var cursor: Long = pw
                val end = pw+elapsed
                while (cursor < end) {
                    val local = Instant.ofEpochMilli(cursor).atZone(zone)
                    val boundary = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val until = minOf(end,boundary)
                    val key = local.toLocalDate().toString()
                    totals[key] = (totals[key] ?: 0) + until-cursor
                    cursor = until
                }
            }
        }
        lastMono=mono; lastWall=wall; lastZone=zone; wasBillable=billable
    }
    fun revalidate() { trusted=true; lastMono=null; lastWall=null; wasBillable=false }
    fun used(wall: Long, zone: ZoneId) = totals[Instant.ofEpochMilli(wall).atZone(zone).toLocalDate().toString()] ?: 0
}

object Growth {
    fun age(baselineAge: Int, baseline: LocalDate, today: LocalDate): Int =
        (baselineAge + java.time.Period.between(baseline,today).years).coerceAtLeast(baselineAge)
    fun style(age: Int, simple: Boolean) = when {
        simple || age <= 6 -> "短句，具体例子，一次一个问题"
        age <= 8 -> "简短因果解释和简单推理"
        else -> "兴趣讨论和逐步探索"
    }
}
