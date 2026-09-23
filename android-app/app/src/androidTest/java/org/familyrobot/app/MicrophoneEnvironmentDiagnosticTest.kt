package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** 仅诊断真实录音/VAD活动：不保存音频或文字，不调用ASR，不把安静与否判成产品通过。 */
@RunWith(AndroidJUnit4::class)
class MicrophoneEnvironmentDiagnosticTest {
    @Test fun observeVadWithoutAnyRobotPlayback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val identity=vault.get("identity")
        val report=File(context.filesDir,"microphone-environment.jsonl").apply { writeText("") }
        val audio=context.getSystemService(AudioManager::class.java)
        val voiceEvents=AtomicInteger();val segments=AtomicInteger();val keywords=AtomicInteger();val errors=AtomicInteger()
        val generation=AtomicLong(1);val began=SystemClock.elapsedRealtime()
        fun record(event:String,data:JSONObject=JSONObject())=synchronized(report) {
            report.appendText(data.put("event",event).put("elapsedMs",SystemClock.elapsedRealtime()-began).toString()+"\n")
        }
        vault.save("identity",JSONObject().put("mode","setup"))
        lateinit var input:AudioInput
        var cleanupInput:AudioInput?=null
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use {
                input=AudioInput(context,{ keywords.incrementAndGet() },{ bytes,_,echo ->
                    val pcm=ByteBuffer.wrap(bytes,44,bytes.size-44).order(ByteOrder.LITTLE_ENDIAN)
                    var sum=0.0;var peak=0
                    val count=pcm.remaining()/2
                    while(pcm.remaining()>=2) { val value=pcm.short.toInt();sum+=value.toDouble()*value;peak=maxOf(peak,kotlin.math.abs(value)) }
                    val number=segments.incrementAndGet()
                    record("vad-segment",JSONObject().put("number",number).put("durationMs",count/16)
                        .put("rms",sqrt(sum/count.coerceAtLeast(1))/32768).put("peak",peak/32768.0).put("containsFeedback",echo))
                    input.capture=AudioInput.Capture(generation.incrementAndGet(),true)
                },{ errors.incrementAndGet();record("audio-error") },{ voiceEvents.incrementAndGet() })
                cleanupInput=input
                input.enabled=true;input.capture=AudioInput.Capture(generation.get(),true);input.start()
                val readyBy=SystemClock.elapsedRealtime()+15000
                while(!input.recording && SystemClock.elapsedRealtime()<readyBy)Thread.sleep(50)
                assertTrue("真实麦克风必须启动",input.recording)
                val started=SystemClock.elapsedRealtime()
                while(SystemClock.elapsedRealtime()-started<90000) {
                    record("sample",JSONObject().put("recording",input.recording).put("aecEnabled",input.aecAvailable)
                        .put("musicActive",audio.isMusicActive).put("musicVolume",audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                        .put("voiceEvents",voiceEvents.get()).put("segments",segments.get()).put("keywordEvents",keywords.get()))
                    Thread.sleep(5000)
                }
                record("diagnostic-complete",JSONObject().put("diagnosticOnly",true).put("durationMs",SystemClock.elapsedRealtime()-started)
                    .put("segments",segments.get()).put("voiceEvents",voiceEvents.get()).put("keywordEvents",keywords.get()).put("audioErrors",errors.get()))
            }
        } finally {
            var released=true
            cleanupInput?.let { recorder ->
                recorder.close()
                val closedBy=SystemClock.elapsedRealtime()+2000
                while(recorder.recording && SystemClock.elapsedRealtime()<closedBy)Thread.sleep(25)
                released=!recorder.recording
                record("microphone-released",JSONObject().put("recording",recorder.recording))
            }
            if(identity==null)vault.remove("identity") else vault.save("identity",identity)
            assertTrue("诊断结束释放录音",released)
        }
    }
}
