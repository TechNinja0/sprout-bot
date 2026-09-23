package org.familyrobot.core

/** 所有时间均由调用者提供单调毫秒，便于真实时钟与测试时钟使用相同规则。 */
enum class SessionState { STANDBY, OPENING, LISTENING, RECOGNIZING, THINKING, SPEAKING, FOLLOW_UP, CLOSING, MUTED, BLOCKED }
enum class ActivityMode { CONVERSATION, VISION, SONG, STORY, BOOK }
enum class Affect { NEUTRAL, HAPPY, LAUGHING, HURT, CRYING }

class Session {
    var state = SessionState.STANDBY; private set
    var activity = ActivityMode.CONVERSATION; private set
    var generation = 0L; private set
    var camera = false; private set
    var cameraPermitted = true; private set
    var mediaPlaying = false; private set
    var mediaPaused = false; private set
    var startedAt = 0L; private set
    var lastInputAt = 0L; private set
    var followUpAt: Long? = null; private set
    private var closingUntil = 0L
    var closingPrompt="rest";private set
    var mediaStartedAt: Long? = null; private set
    var allowed = true; private set
    var muted = false; private set
    val active get() = state !in setOf(SessionState.STANDBY, SessionState.BLOCKED, SessionState.MUTED, SessionState.CLOSING)
    val microphone get() = allowed && !muted
    val billable get() = mediaPlaying || state in setOf(SessionState.OPENING, SessionState.LISTENING, SessionState.RECOGNIZING, SessionState.THINKING, SessionState.SPEAKING)

    fun wake(now: Long, cameraAllowed: Boolean): Long? {
        if (!allowed || muted) return null
        generation++
        if (mediaPlaying) { mediaPlaying = false; mediaPaused = true }
        startedAt = now; lastInputAt = now; followUpAt = null
        cameraPermitted = cameraAllowed; camera = cameraAllowed
        state = SessionState.OPENING
        return generation
    }
    fun openingFinished(ticket: Long, now: Long = lastInputAt) {
        if (valid(ticket) && state == SessionState.OPENING) { state = SessionState.LISTENING; followUpAt = now }
    }
    fun discardOpeningEcho(ticket:Long, waitingSince:Long, userAt:Long) {
        if(!valid(ticket))return
        generation++;state=SessionState.LISTENING
        followUpAt=waitingSince;lastInputAt=userAt
    }
    fun voiceStarted(now: Long) {
        if (state !in setOf(SessionState.OPENING, SessionState.LISTENING, SessionState.FOLLOW_UP) || !allowed || muted) return
        lastInputAt = now; followUpAt = null; state = SessionState.LISTENING
    }
    fun recognizing(now: Long): Long? {
        if (!active || !allowed) return null
        generation++; lastInputAt = now; state = SessionState.RECOGNIZING; followUpAt = null
        return generation
    }
    fun thinking(ticket: Long) { if (valid(ticket)) { state = SessionState.THINKING; followUpAt = null } }
    fun speaking(ticket: Long) { if (valid(ticket)) { state = SessionState.SPEAKING; followUpAt = null } }
    fun playbackEnded(ticket: Long, now: Long) {
        if (!valid(ticket)) return
        mediaPlaying = false; state = SessionState.FOLLOW_UP; followUpAt = now
    }
    fun valid(ticket: Long) = ticket == generation && allowed && !muted && (active || mediaPlaying || state == SessionState.CLOSING)
    fun startMedia(now: Long, mode: ActivityMode) {
        if (!allowed || muted) return
        activity = mode; mediaPlaying = true; mediaPaused = false
        if (mediaStartedAt == null) mediaStartedAt = now
        state = SessionState.SPEAKING; followUpAt = null
    }
    fun pause(now: Long) {
        if (state == SessionState.CLOSING) { stop();return }
        generation++
        if (mediaPlaying) mediaPaused = true
        mediaPlaying = false
        if (active) { state = SessionState.FOLLOW_UP; followUpAt = now }
    }
    fun resume(now: Long): Boolean {
        if (!allowed || muted || !mediaPaused) return false
        if (!active) { startedAt = now; lastInputAt = now }
        mediaPlaying = true; mediaPaused = false; state = SessionState.SPEAKING; followUpAt = null
        return true
    }
    fun restorePausedMedia() { mediaPaused=true;mediaPlaying=false }
    fun closeCamera() { camera = false; cameraPermitted = false }
    fun stop() {
        generation++; camera = false; followUpAt = null
        if (mediaPlaying) mediaPaused = true
        mediaPlaying = false; mediaStartedAt = null
        state = if (!allowed) SessionState.BLOCKED else if (muted) SessionState.MUTED else SessionState.STANDBY
    }
    fun configure(allowedNow: Boolean, mutedNow: Boolean, cameraAllowed: Boolean) {
        allowed = allowedNow; muted = mutedNow
        if (!cameraAllowed) closeCamera()
        if (!allowed || muted) stop()
        else if (state == SessionState.BLOCKED || state == SessionState.MUTED) state = SessionState.STANDBY
        // 启用相机配置不自动打开，只在下一次唤醒时开启。
    }
    fun tick(now: Long, maxMediaMs: Long = 1_800_000) {
        if (!allowed || muted) return
        if (now - lastInputAt >= 30_000) camera = false
        if (mediaPlaying && mediaStartedAt?.let { now-it >= maxMediaMs } == true) { stop(); return }
        if (state == SessionState.CLOSING && now >= closingUntil) { stop();return }
        if (active && now-startedAt >= 600_000) {
            camera = false; followUpAt = null
            if (!mediaPlaying) { generation++;state=SessionState.CLOSING;closingPrompt="rest";closingUntil=now+4000 }
            else state = SessionState.STANDBY // 媒体继续，问答关闭；再次唤醒先暂停。
        }
        if (followUpAt?.let { now-it >= 30_000 } == true && !mediaPlaying) {
            generation++;camera=false;followUpAt=null;state=SessionState.CLOSING
            closingPrompt="chime";closingUntil=now+400
        }
    }
}
