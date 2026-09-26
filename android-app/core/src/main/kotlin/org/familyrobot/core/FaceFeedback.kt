package org.familyrobot.core

enum class FaceSignal { SLEEP, WAKE, LISTEN, HEAR, RECOGNIZE, THINK, LOOK, SPEAK, WAIT, PAUSE, ERROR, MUTED, LOCK, PLAYFUL }

data class FaceFeedback(val mode:String, val title:String, val detail:String, val signal:FaceSignal)

/** 文字、轮廓和动效共同表达状态；减少动态效果时仍保留同样的含义。 */
fun faceFeedback(mode:String):FaceFeedback = when(mode) {
    "standby" -> FaceFeedback(mode,"休息中","叫我的名字，或轻触脸部唤醒",FaceSignal.SLEEP)
    "rest" -> FaceFeedback(mode,"准备休息","下次想聊天，再叫我",FaceSignal.SLEEP)
    "waking" -> FaceFeedback(mode,"我醒啦","正在回应你的呼唤",FaceSignal.WAKE)
    "listening" -> FaceFeedback(mode,"我在听","现在可以对我说话",FaceSignal.LISTEN)
    "hearing" -> FaceFeedback(mode,"听到你啦","你继续说，我听着",FaceSignal.HEAR)
    "recognizing" -> FaceFeedback(mode,"正在辨认语音","说完了，我来听懂这句话",FaceSignal.RECOGNIZE)
    "thinking" -> FaceFeedback(mode,"正在思考","正在想怎么回答你",FaceSignal.THINK)
    "vision" -> FaceFeedback(mode,"正在看一看","正在观察画面里的内容",FaceSignal.LOOK)
    "preparing" -> FaceFeedback(mode,"正在准备声音","准备好这一句就接着说",FaceSignal.WAIT)
    "loading" -> FaceFeedback(mode,"正在准备播放","正在加载下一段声音",FaceSignal.WAIT)
    "speaking" -> FaceFeedback(mode,"正在回答","双击屏幕可以暂停",FaceSignal.SPEAK)
    "story" -> FaceFeedback(mode,"正在讲故事","双击屏幕可以暂停",FaceSignal.SPEAK)
    "song" -> FaceFeedback(mode,"正在播放音乐","双击屏幕可以暂停",FaceSignal.SPEAK)
    "paused" -> FaceFeedback(mode,"已暂停","可以继续对我说话",FaceSignal.PAUSE)
    "fault" -> FaceFeedback(mode,"哎呀，遇到小状况了","请检查连接或到家长管理查看",FaceSignal.ERROR)
    "muted" -> FaceFeedback(mode,"麦克风已关闭","请在家长管理中开启",FaceSignal.MUTED)
    "blocked" -> FaceFeedback(mode,"现在需要休息","请在家长管理中查看使用安排",FaceSignal.LOCK)
    "locked_schedule" -> FaceFeedback(mode,"休息时间到了","现在是家长设置的休息时段",FaceSignal.LOCK)
    "locked_quota" -> FaceFeedback(mode,"今天先玩到这里","今日陪伴时长已用完 · 明天再来玩",FaceSignal.LOCK)
    else -> FaceFeedback(mode,when(mode) {
        "pet" -> "好舒服呀"; "tickle" -> "好痒呀"; "laugh" -> "哈哈哈"
        "hurt" -> "有点委屈"; "cry" -> "有点难过"; else -> "和你在一起"
    },"",FaceSignal.PLAYFUL)
}

/** 展示已经生效的使用限制，不另设锁定时段或改变家长的放行规则。 */
fun restrictedFaceFeedback(permission:Permission):FaceFeedback? = when(permission) {
    Permission.ALLOWED -> null
    Permission.SCHEDULED -> faceFeedback("locked_schedule")
    Permission.QUOTA -> faceFeedback("locked_quota")
    Permission.MANUAL -> faceFeedback("blocked").copy(title="陪伴已暂停",detail="家长设置了暂停，请找爸爸妈妈帮忙")
    Permission.UNTRUSTED_TIME -> faceFeedback("blocked").copy(title="时间需要校准",detail="请在家长管理中检查设备时间")
}

/** 只展示等待，不把慢请求判成死机，也不改变会话时限或取消正在播放的故事。 */
class FaceFeedbackTracker {
    private var phase=""
    private var ticket=Long.MIN_VALUE
    private var since=0L
    private var audioPosition:Long?=null
    private var audioAdvancedAt=0L
    private var failedTicket=Long.MIN_VALUE
    private var failure:String?=null

    fun fail(generation:Long,message:String) { failedTicket=generation;failure=message }
    fun clearFailure() { failedTicket=Long.MIN_VALUE;failure=null }
    fun beginAttempt() { clearFailure();phase="" }
    fun hasFailure(generation:Long)=failedTicket==generation && failure!=null

    fun present(mode:String,generation:Long,now:Long,playbackPosition:Long?=null):FaceFeedback {
        if(ticket!=generation || phase!=mode) { ticket=generation;phase=mode;since=now;audioPosition=null;audioAdvancedAt=now }
        if(failedTicket!=generation)clearFailure()
        if(hasFailure(generation) && faceFeedback(mode).signal !in setOf(FaceSignal.LOCK,FaceSignal.MUTED) && mode!="rest")
            return FaceFeedback("fault","这次没能完成",failure!!,FaceSignal.ERROR)
        val base=faceFeedback(mode)
        if(playbackPosition==null || playbackPosition!=audioPosition) { audioPosition=playbackPosition;audioAdvancedAt=now }
        if(base.signal==FaceSignal.SPEAK && playbackPosition!=null && now-audioAdvancedAt>=10000)
            return base.copy(title="播放暂未推进",detail="已等待 ${(now-audioAdvancedAt)/1000} 秒 · 双击可暂停",signal=FaceSignal.WAIT)
        val waiting=mode in setOf("recognizing","thinking","vision","preparing","loading")
        val seconds=((now-since).coerceAtLeast(0)/1000)
        if(!waiting || seconds<10)return base
        return base.copy(
            title=if(seconds>=30)"等待有点久" else base.title,
            detail="${base.title} · 已等待 ${seconds} 秒 · 双击可暂停",
            signal=FaceSignal.WAIT
        )
    }
}
