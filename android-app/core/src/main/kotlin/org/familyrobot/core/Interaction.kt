package org.familyrobot.core

import kotlin.math.abs
import kotlin.math.hypot

enum class FaceTouch { TAP, PET, TICKLE }

/** 热区和绘制共用角色坐标；不依赖压力值，不把屏幕留白当作脸。 */
object FaceRegion {
    fun contains(x:Float,y:Float,width:Float,height:Float):Boolean {
        val unit=minOf(width,height)
        return abs(x-width/2)<=unit*.38f && y in (height*.46f-unit*.27f)..(height*.46f+unit*.34f)
    }
    fun forehead(y:Float,height:Float,unit:Float)=y<height*.46f-unit*.08f
    fun cheek(x:Float,y:Float,width:Float,height:Float):Boolean {
        val unit=minOf(width,height)
        return contains(x,y,width,height) && abs(x-width/2)>unit*.09f && y>height*.46f+unit*.05f
    }
    fun management(x:Float,y:Float,width:Float,height:Float)=x>width*.78f && y<height*.22f
}

/** 一次拖动只产生一种手势。阈值由UI传入真实density和系统touchSlop。 */
class FaceStroke(private val x:Float,private val y:Float,private val width:Float,private val height:Float,
                 private val slop:Float,private val distance:Float) {
    private var lastX=x
    private var lastY=y
    private var travel=0f
    private var direction=0
    private var reversals=0
    private var leg=0f
    var moved=false;private set
    private var inside=FaceRegion.contains(x,y,width,height)
    fun move(px:Float,py:Float) {
        inside=inside && FaceRegion.contains(px,py,width,height)
        if(hypot(px-x,py-y)>slop)moved=true
        val dx=px-lastX;val dy=py-lastY
        travel+=hypot(dx,dy)
        val next=if(dx>0)1 else if(dx<0)-1 else 0
        if(next!=0) {
            if(next==direction)leg+=abs(dx)
            else { if(leg>=slop)reversals++;direction=next;leg=abs(dx) }
        }
        lastX=px;lastY=py
    }
    fun result():FaceTouch? = when {
        !inside -> null
        !moved -> FaceTouch.TAP
        FaceRegion.cheek(x,y,width,height) && travel>=distance && reversals>=1 && leg>=slop -> FaceTouch.TICKLE
        FaceRegion.forehead(y,height,minOf(width,height)) && travel>=distance -> FaceTouch.PET
        else -> null
    }
}

class WakeReplies {
    private var index=0
    fun next(touch:FaceTouch?,mode:String,playful:Boolean):String? {
        if(mode=="visual")return null
        if(mode=="chime")return "chime"
        return when(touch) {
            FaceTouch.PET -> if(playful)"pet" else "wake_touch"
            FaceTouch.TICKLE -> if(playful)"tickle" else "wake_touch"
            FaceTouch.TAP -> "wake_touch"
            null -> listOf("wake","wake_listen","wake_here")[index++ % 3]
        }
    }
}

/** 只清理首轮开场回声；不改写普通续聊内容。 */
object OpeningTranscript {
    private val punctuation=Regex("[\\s，。！？、,.!?：:]+")
    fun clean(text:String,nickname:String,prompt:String?):String {
        var result=text.trim()
        val prefixes=listOfNotNull(nickname.takeIf { it.isNotBlank() },when(prompt) {
            "wake" -> "我在";"wake_listen" -> "我听着呢";"wake_here" -> "在呢你说"
            "camera_unavailable" -> "相机暂时用不了我们可以说话"
            "wake_touch" -> "嗯我在呢";"pet" -> "嘿嘿";"tickle" -> "哎呀好痒";else -> null
        })
        repeat(2) {
            val compact=result.replace(punctuation,"")
            val prefix=prefixes.firstOrNull { candidate ->
                val normalized=candidate.replace(punctuation,"")
                if(!compact.startsWith(normalized))false else {
                    var remaining=normalized.length
                    var boundary=0
                    while(boundary<result.length && remaining>0) {
                        if(!punctuation.matches(result[boundary].toString()))remaining--
                        boundary++
                    }
                    // “我在家里”可能是孩子的话，不能把它误裁成“家里”。
                    candidate==nickname || boundary==result.length || punctuation.matches(result[boundary].toString())
                }
            } ?: return result
            var letters=prefix.replace(punctuation,"").length
            var end=0
            while(end<result.length && letters>0) {
                if(!punctuation.matches(result[end].toString()))letters--
                end++
            }
            result=result.substring(end).trimStart { it.isWhitespace() || it in "，。！？、,.!?：:" }
        }
        return result
    }
}

/** 工作状态优先于旧表演，唤醒开场必须有独立映射。 */
fun interactionFace(state:SessionState,media:Boolean=false,song:Boolean=false,vision:Boolean=false,
                    performance:String="",opening:String="waking",voiceActive:Boolean=false):String = when {
    state==SessionState.BLOCKED -> "blocked"
    state==SessionState.MUTED -> "muted"
    state==SessionState.OPENING -> opening
    media -> if(song)"song" else "story"
    state==SessionState.SPEAKING -> "speaking"
    state==SessionState.RECOGNIZING -> "recognizing"
    state==SessionState.THINKING -> if(vision)"vision" else "thinking"
    voiceActive && state in setOf(SessionState.LISTENING,SessionState.FOLLOW_UP) -> "hearing"
    performance.isNotEmpty() && state in setOf(SessionState.STANDBY,SessionState.FOLLOW_UP,SessionState.LISTENING) -> performance
    state in setOf(SessionState.LISTENING,SessionState.FOLLOW_UP) -> "listening"
    state==SessionState.CLOSING -> "rest"
    else -> "standby"
}
