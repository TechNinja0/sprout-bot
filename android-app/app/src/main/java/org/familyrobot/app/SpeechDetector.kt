package org.familyrobot.app

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** 仅在会话听取时运行；不以音量或普通噪声作为有效发言。 */
class SpeechDetector(assets:AssetManager) : AutoCloseable {
    private val vad=Vad(assets,VadModelConfig(
        sileroVadModelConfig=SileroVadModelConfig(model="models/vad/silero_vad.onnx",threshold=0.5f,minSilenceDuration=1.2f,minSpeechDuration=0.25f,windowSize=512,maxSpeechDuration=30f),
        sampleRate=16000,numThreads=1))
    data class Result(val speech:Boolean,val completed:FloatArray?)
    fun accept(samples:FloatArray):Result {
        vad.acceptWaveform(samples)
        val speech=vad.isSpeechDetected()
        val completed=if(vad.empty())null else vad.front().samples.take(30*16000).toFloatArray().also { vad.pop() }
        if(completed!=null)vad.reset()
        return Result(speech,completed)
    }
    fun reset()=vad.reset()
    override fun close()=vad.release()
}
