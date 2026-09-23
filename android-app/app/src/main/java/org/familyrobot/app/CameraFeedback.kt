package org.familyrobot.app

import android.media.*
import android.os.SystemClock
import kotlinx.coroutines.*

/** 原创120ms升/降双音。独立短音轨不改写正文播放进度，强制停止立即静音。 */
class CameraFeedback(private val scope:CoroutineScope,private val volume:()->Float,private val permitted:()->Boolean,
    private val outputAllowed:()->Boolean,private val acquireFocus:()->Boolean) {
    private var job:Job?=null
    @Volatile private var player:AudioTrack?=null
    @Volatile var playing=false;private set
    var endedAt=0L;private set
    var lastEvent="none";private set
    var lastResult="none";private set
    var playCount=0;private set
    private var observed=false
    private var pending:Boolean?=null
    private var pendingUntil=0L
    fun observe(ready:Boolean,quietSlot:Boolean) {
        val now=SystemClock.elapsedRealtime()
        if(!permitted()) { reset();return }
        if(ready!=observed) {
            observed=ready;pending=ready;pendingUntil=now+2000
            // 连续启停不能让已排队的“启用”在关闭后再响。
            cancelTrack()
        }
        val event=pending ?: return
        if(now>=pendingUntil) { pending=null;lastEvent=if(event)"on" else "off";lastResult="speech-priority";return }
        if(!quietSlot || job?.isCompleted==false)return
        pending=null;lastEvent=if(event)"on" else "off"
        if(!outputAllowed() || volume()<=0f) { lastResult="muted-output";return }
        if(!acquireFocus()) { lastResult="focus-denied";return }
        val level=volume().coerceIn(0f,1f)
        playing=true;lastResult="playing"
        job=scope.launch {
            try {
                val completed=withContext(Dispatchers.IO) {
                    val samples=ShortArray(1920) { i ->
                        val frequency=if((i<960)==event)660 else 440
                        val envelope=kotlin.math.sin(Math.PI*(i%960)/960).let { it*it }
                        (kotlin.math.sin(2*Math.PI*frequency*i/16000)*3200*envelope).toInt().toShort()
                    }
                    val track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(samples.size*2).setTransferMode(AudioTrack.MODE_STATIC).build()
                    player=track
                    try {
                        ensureActive();track.setVolume(level)
                        check(track.write(samples,0,samples.size,AudioTrack.WRITE_BLOCKING)==samples.size)
                        ensureActive();track.play()
                        withTimeout(1000) { while(track.playbackHeadPosition<samples.size)delay(10) }
                        true
                    } finally { if(player===track)player=null;runCatching { track.stop() };track.release() }
                }
                if(completed) { lastResult="played";playCount++ }
            } catch(e:CancellationException) { if(e is TimeoutCancellationException)lastResult="output-timeout" else throw e }
            catch(_:Exception) { lastResult="output-failed" }
            finally { playing=false;endedAt=SystemClock.elapsedRealtime() }
        }
    }
    private fun cancelTrack() { job?.cancel();player?.let { runCatching { it.setVolume(0f);it.stop() } };playing=false }
    fun cancelPending() { pending=null;cancelTrack() }
    fun reset() { observed=false;cancelPending() }
}
