package org.familyrobot.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** 真机原生VAD引擎及合成语音回归；不计入儿童或外放回声验收。 */
@RunWith(AndroidJUnit4::class)
class SpeechDetectorTest {
    @Test fun speechSilenceNoiseAndLongUtterance() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        SpeechDetector(context.assets).use { detector ->
            repeat(100) { frame ->
                val noise=FloatArray(512) { i -> (sin((frame*512+i)*2.0*Math.PI*440/16000)*0.2).toFloat() }
                val result=detector.accept(noise)
                assertFalse("纯音不能作为发言",result.speech);assertNull(result.completed)
            }
            detector.reset()
            val bytes=File(context.filesDir,"test-speech.wav").readBytes()
            val pcm=ByteBuffer.wrap(bytes,44,bytes.size-44).order(ByteOrder.LITTLE_ENDIAN)
            val samples=FloatArray((bytes.size-44)/2) { pcm.short/32768f }
            var detected=false;var completed:FloatArray?=null;var ending=0
            val padded=samples+FloatArray(16000*2)
            for(start in padded.indices step 512) {
                val result=detector.accept(padded.copyOfRange(start,minOf(start+512,padded.size)))
                detected=detected || result.speech
                if(result.completed!=null) { completed=result.completed;ending=start;break }
            }
            assertTrue("真实VAD发现合成语音",detected);assertNotNull("1.2秒句末静音后成句",completed)
            assertTrue("语音主体保留",completed!!.size>16000)
            assertTrue("末尾静音有界",ending<samples.size+16000*2)
            detector.reset();var longCompleted:FloatArray?=null
            // 使用同一段原创语音连续拼接，验证不会沿用旧18秒截断；每段间隔均短于1.2秒。
            val voiced=samples.sliceArray(0 until samples.size.coerceAtMost(16000*2))
            for(frame in 0 until 1100) {
                val result=detector.accept(FloatArray(512) { voiced[(frame*512+it)%voiced.size] })
                if(result.completed!=null) { longCompleted=result.completed;break }
            }
            assertNotNull("最长发言必须有界成句",longCompleted)
            assertTrue("支持超过20秒发言",longCompleted!!.size>20*16000)
            assertTrue("上传不超过30秒",longCompleted!!.size<=30*16000)
        }
    }
}
