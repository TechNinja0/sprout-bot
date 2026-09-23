package org.familyrobot.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject

/** PIN管理页显式启动；复用唯一收音入口，至多3秒音频及一帧相机，退出立即释放。 */
class HardwareSelfCheck(
    private val context:Context,private val scope:CoroutineScope,private val input:AudioInput,
    private val camera:CameraInput,connection:JSONObject,private val allowed:()->Boolean,
    private val cameraAllowed:()->Boolean,private val volume:()->Float
) {
    var running by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    var transcript by mutableStateOf("");private set
    var report by mutableStateOf<JSONObject?>(null);private set
    private val api=Api(connection)
    private val manager=context.getSystemService(AudioManager::class.java)
    private var job:Job?=null
    private var captureMic=false
    private var captureCamera=false
    @Volatile private var player:AudioTrack?=null
    private var focus:AudioFocusRequest?=null
    private fun has(permission:String)=ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED
    fun tick() {
        if(!running)return
        if(!allowed()) { cancel("使用限制、静音、温控或页面状态变化，自检已停止");return }
        input.enabled=captureMic && has(Manifest.permission.RECORD_AUDIO)
        input.capture=AudioInput.Capture(0,false)
        camera.setEnabled(captureCamera && cameraAllowed() && has(Manifest.permission.CAMERA))
    }
    fun start() {
        if(job?.isCompleted==false)return
        transcript="";report=null
        if(!allowed()) { message="当前不可自检：请检查使用时段、静音及设备温度";return }
        running=true;input.diagnosticOnly=true
        val result=JSONObject().put("generatedAt",System.currentTimeMillis()/1000.0)
        job=scope.launch {
            try {
                withTimeout(30000) {
                    message="收音自检：请在3秒内说一句话。音频仅临时发送到家庭服务器。"
                    if(!has(Manifest.permission.RECORD_AUDIO))result.put("microphone","permission-denied")
                    else {
                        input.beginDiagnostic();captureMic=true;input.start();tick()
                        val deadline=SystemClock.elapsedRealtime()+8000
                        while(input.diagnosticSamples()<48000 && SystemClock.elapsedRealtime()<deadline) { delay(100);tick();ensureActive() }
                        captureMic=false;input.enabled=false
                        val captured=input.finishDiagnostic()
                        result.put("microphone",if(captured.samples>=40000)"captured" else "insufficient-samples")
                            .put("sampleCount",captured.samples).put("rms",captured.rms).put("aecEnabled",input.aecAvailable)
                        if(captured.samples>0) {
                            message="正在检查家庭服务器语音识别…"
                            try {
                                transcript=withContext(Dispatchers.IO) { api.upload("/v1/speech/recognize","diagnostic.wav",AudioInput.wav(captured.pcm),"audio/wav",timeoutMs=10000).optString("text").take(600) }
                                result.put("asr",if(transcript.isBlank())"no-text" else "responded")
                            } catch(e:CancellationException) { throw e }
                            catch(_:Exception) { result.put("asr","unavailable") }
                            finally { captured.pcm.fill(0) }
                        } else result.put("asr","skipped")
                    }
                    message="相机自检：等待当前新画面，不保存图片。"
                    if(!cameraAllowed())result.put("camera","disabled-by-parent")
                    else if(!has(Manifest.permission.CAMERA))result.put("camera","permission-denied")
                    else {
                        val since=SystemClock.elapsedRealtime();captureCamera=true;tick()
                        while(camera.fresh()?.let { it.at>=since }!=true && SystemClock.elapsedRealtime()-since<4000) { delay(100);tick();ensureActive() }
                        result.put("camera",if(camera.fresh()?.let { it.at>=since }==true)"fresh-frame" else "no-frame")
                    }
                    captureCamera=false;camera.setEnabled(false)
                    message="播音自检：播放半秒提示音；请留意当前耳机或扬声器。"
                    result.put("audioOutput",testOutput())
                }
                result.put("status","completed");report=result
                message="自检完成。采样和播放进度不代表远场识别、回声消除或人工听感通过。"
            } catch(e:CancellationException) {
                result.put("status","cancelled");report=result
                if(e is TimeoutCancellationException)message="自检超时，已释放采集设备"
            } catch(_:Exception) { result.put("status","failed");report=result;message="自检失败，已释放采集设备" }
            finally {
                // cancel已同步释放并交还设备；迟到的ASR结束不能再关闭新儿童会话的采集。
                if(running) { release();running=false }
            }
        }
    }
    private suspend fun testOutput():String {
        if(manager.getStreamVolume(AudioManager.STREAM_MUSIC)==0 || manager.isStreamMute(AudioManager.STREAM_MUSIC) || volume()<=0f)return "muted-output"
        if(!allowed())return "blocked"
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { if(it<0)scope.launch { cancel("音频焦点被占用，自检已停止") } }.build()
        if(manager.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return "focus-denied"
        focus=request
        val level=volume().coerceIn(0f,1f)
        return withContext(Dispatchers.IO) {
            val samples=ShortArray(8000) { i -> (kotlin.math.sin(2*Math.PI*440*i/16000)*2200*minOf(1.0,i/320.0,(7999-i)/320.0)).toInt().toShort() }
            val output=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples.size*2).setTransferMode(AudioTrack.MODE_STATIC).build()
            player=output
            try {
                output.setVolume(level)
                if(output.write(samples,0,samples.size,AudioTrack.WRITE_BLOCKING)!=samples.size)return@withContext "write-failed"
                ensureActive();output.play()
                val deadline=SystemClock.elapsedRealtime()+1500
                while(output.playbackHeadPosition<samples.size && SystemClock.elapsedRealtime()<deadline) { delay(20) }
                if(output.playbackHeadPosition>=samples.size)"played-unconfirmed" else "no-playback-progress"
            } finally { player=null;runCatching { output.stop() };output.release();samples.fill(0) }
        }
    }
    fun cancel(reason:String="自检已停止",clearText:Boolean=false) {
        if(running) { message=reason;job?.cancel();api.cancel();release();running=false }
        if(clearText)transcript=""
    }
    private fun release() {
        captureMic=false;captureCamera=false;input.enabled=false;input.cancelDiagnostic();camera.setEnabled(false)
        player?.let { runCatching { it.setVolume(0f);it.stop() } }
        focus?.let { manager.abandonAudioFocusRequest(it) };focus=null
    }
}
