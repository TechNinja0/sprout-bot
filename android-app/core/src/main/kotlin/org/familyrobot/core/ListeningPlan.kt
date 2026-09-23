package org.familyrobot.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class ListeningPlan(val id:String,val playlistId:String,val days:Set<Int>,val time:LocalTime,val minutes:Int,val enabled:Boolean)

/** 仅跨到计划分钟的实时边界触发；首次启动、跳时、错过/后台均不补播。 */
class ListeningScheduler {
    private var previous:Long?=null
    fun due(now:Long,zone:ZoneId,plans:List<ListeningPlan>):List<ListeningPlan> {
        val before=previous;previous=now
        if(before==null || now-before !in 1..2000)return emptyList()
        val current=Instant.ofEpochMilli(now).atZone(zone)
        val old=Instant.ofEpochMilli(before).atZone(zone)
        if(current.minute==old.minute && current.hour==old.hour && current.toLocalDate()==old.toLocalDate())return emptyList()
        if(current.second>1)return emptyList()
        return plans.filter { it.enabled && current.dayOfWeek.value in it.days && current.toLocalTime().hour==it.time.hour && current.minute==it.time.minute }
    }
}
