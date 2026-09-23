package org.familyrobot.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.k2fsa.sherpa.onnx.*
import org.familyrobot.core.WakeAudioBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 唯一 AudioRecord 所有者；待机音频只进入本地 KWS，完整语音只在会话打开后发送。 */
class AudioInput(private val context:Context,private val keyword:(String)->Unit,private val utterance:(ByteArray,Long,Boolean)->Unit,private val error:(String)->Unit,private val voiceDetected:(Long)->Unit) {
    data class Capture(val ticket:Long,val enabled:Boolean)
    @Volatile var enabled=false
    @Volatile var capture=Capture(0,false)
    @Volatile var feedbackPlaying=false
    @Volatile var cameraFeedbackPlaying=false
    @Volatile var cameraFeedbackEndedAt=0L
    @Volatile var feedbackEndedAt=0L
    @Volatile var customKeywords=""
    @Volatile var running=true
    @Volatile var recording=false;private set
    @Volatile var aecAvailable=false;private set
    @Volatile var diagnosticOnly=false
    private val diagnosticLock=Any()
    private var diagnosticPcm:java.io.ByteArrayOutputStream?=null
    private var diagnosticEnergy=0.0
    data class DiagnosticCapture(val pcm:ByteArray,val rms:Double) { val samples get()=pcm.size/2 }
    fun beginDiagnostic()=synchronized(diagnosticLock) { diagnosticOnly=true;diagnosticPcm=java.io.ByteArrayOutputStream();diagnosticEnergy=0.0 }
    fun finishDiagnostic():DiagnosticCapture=synchronized(diagnosticLock) {
        val bytes=diagnosticPcm?.toByteArray() ?: byteArrayOf();diagnosticPcm=null
        DiagnosticCapture(bytes,if(bytes.isEmpty())0.0 else kotlin.math.sqrt(diagnosticEnergy/(bytes.size/2)))
    }
    fun diagnosticSamples()=synchronized(diagnosticLock) { (diagnosticPcm?.size() ?: 0)/2 }
    fun cancelDiagnostic()=synchronized(diagnosticLock) { diagnosticPcm=null;diagnosticEnergy=0.0;diagnosticOnly=false }
    private var thread:Thread?=null
    fun start() {
        if(thread!=null)return
        thread=Thread({ loop() },"robot-single-microphone").apply { start() }
    }
    private fun loop() {
        var kws:KeywordSpotter?=null
        var detector:SpeechDetector?=null
        try {
            val dir=File(context.filesDir,"kws").apply { mkdirs() }
            for(name in context.assets.list("models/kws").orEmpty()) {
                val dest=File(dir,name)
                if(!dest.exists() || name=="keywords.txt")context.assets.open("models/kws/$name").use { src -> dest.outputStream().use { src.copyTo(it) } }
            }
            fun path(name:String)=File(dir,name).absolutePath
            kws=KeywordSpotter(config=KeywordSpotterConfig(modelConfig=OnlineModelConfig(
                transducer=OnlineTransducerModelConfig(encoder=path("encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),decoder=path("decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),joiner=path("joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx")),
                tokens=path("tokens.txt"),numThreads=2,modelType="zipformer2"),keywordsFile=path("keywords.txt"),keywordsThreshold=0.25f))
            detector=SpeechDetector(context.assets)
            while(running) {
                if(!enabled) { Thread.sleep(80);continue }
                if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) { enabled=false;error("麦克风权限未授权");continue }
                val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                val recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(6400,min*2))
                var echo:AcousticEchoCanceler?=null;var noise:NoiseSuppressor?=null
                val words=customKeywords
                val stream=kws.createStream(words)
                try {
                    check(recorder.state==AudioRecord.STATE_INITIALIZED)
                    if(AcousticEchoCanceler.isAvailable())echo=AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled=true }
                    if(NoiseSuppressor.isAvailable())noise=NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled=true }
                    aecAvailable=echo?.enabled==true
                    recorder.startRecording();recording=true
                    val buffer=ShortArray(1600);val leadIn=WakeAudioBuffer();var wasCapturing=false;var lastKeyword=0L
                    var captureTicket=-1L;var completedTicket=-1L
                    var prefix=FloatArray(0);var containsFeedback=false
                    detector.reset()
                    while(running && enabled && words==customKeywords) {
                        val n=recorder.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
                        if(n<=0)break
                        if(diagnosticOnly) {
                            synchronized(diagnosticLock) {
                                diagnosticPcm?.let { out ->
                                    val count=minOf(n,(96000-out.size())/2)
                                    for(i in 0 until count) { val value=buffer[i].toInt();out.write(value and 255);out.write((value shr 8) and 255);diagnosticEnergy+=(value/32768.0)*(value/32768.0) }
                                }
                            }
                            detector.reset();kws.reset(stream);leadIn.clear();wasCapturing=false
                            continue
                        }
                        val samples=FloatArray(n) { buffer[it]/32768f };leadIn.offer(samples);stream.acceptWaveform(samples,16000)
                        while(kws.isReady(stream))kws.decode(stream)
                        val found=kws.getResult(stream).keyword
                        if(found.isNotEmpty()) {
                            kws.reset(stream)
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(now-lastKeyword>1200) {
                                lastKeyword=now;keyword(found)
                                // 倾听中的重复昵称不能清掉首句；其他命令由新会话代际取消旧收音。
                                if(!capture.enabled) { detector.reset();if(found=="wake")leadIn.arm() else leadIn.clear() }
                            }
                        }
                        val plan=capture
                        if(!plan.enabled || completedTicket==plan.ticket) {
                            if(wasCapturing)detector.reset()
                            wasCapturing=false;continue
                        }
                        if(plan.ticket!=captureTicket || !wasCapturing) {
                            detector.reset();captureTicket=plan.ticket;containsFeedback=false
                            // 预卷交给ASR补首句，不让唤醒词本身触发“用户正在继续讲话”。
                            val history=leadIn.takeForFirstUtterance()
                            prefix=history?.copyOf((history.size-samples.size).coerceAtLeast(0)) ?: FloatArray(0)
                        }
                        wasCapturing=true
                        val echoWindow=feedbackPlaying || cameraFeedbackPlaying || android.os.SystemClock.elapsedRealtime()-feedbackEndedAt<250 || android.os.SystemClock.elapsedRealtime()-cameraFeedbackEndedAt<250
                        containsFeedback=containsFeedback || echoWindow
                        val result=detector.accept(samples)
                        if(result.speech && !echoWindow && !containsFeedback)voiceDetected(plan.ticket)
                        result.completed?.let { values ->
                            completedTicket=plan.ticket;wasCapturing=false
                            val all=(prefix+values).take(30*16000)
                            val bytes=ByteBuffer.allocate(all.size*2).order(ByteOrder.LITTLE_ENDIAN)
                            for(value in all)bytes.putShort((value.coerceIn(-1f,1f)*32767).toInt().toShort())
                            utterance(wav(bytes.array()),plan.ticket,containsFeedback)
                        }
                    }
                } finally {
                    recording=false;runCatching { recorder.stop() };recorder.release();echo?.release();noise?.release();stream.release()
                }
            }
        } catch(_:Exception) { error("麦克风或唤醒模型不可用，请在管理页检查权限和模型") }
        finally { recording=false;kws?.release();detector?.close();thread=null }
    }
    fun close() { enabled=false;running=false }
    companion object {
        fun wav(pcm:ByteArray):ByteArray {
            val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            h.putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
            return h.array()+pcm
        }
    }
}
