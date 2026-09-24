package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 真实开场外放诊断；仅记录固定事件，不录制现场音频，不把运行完成当声学通过。 */
@RunWith(AndroidJUnit4::class)
class AcousticPromptDiagnosticTest {
    @Test fun wakePlaybackWithLiveMicrophone() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context)
        vault.save("identity",JSONObject().put("mode","robot"))
        val config=StabilityConfiguration(context)
        val events=CopyOnWriteArrayList<JSONObject>()
        val cycles=JSONArray()
        val report=JSONObject().put("acousticAcceptance",false).put("cycles",cycles)
        val output=File(context.filesDir,"acoustic-prompt-diagnostic.json")
        val audio=context.getSystemService(AudioManager::class.java)
        val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        fun waitFor(label:String,condition:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+20000
            while(!condition() && SystemClock.elapsedRealtime()<end)Thread.sleep(50)
            check(condition()) { label }
        }
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            lateinit var robot:RobotRuntime
            scenario.onActivity { robot=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as RobotRuntime }
            waitFor("联网") { robot.online }
            val input=RobotRuntime::class.java.getDeclaredField("input").apply { isAccessible=true }.get(robot) as AudioInput
            val callback=AudioInput::class.java.getDeclaredField("keyword").apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST") val original=callback.get(input) as (String)->Unit
            callback.set(input,{ word:String ->
                events.add(JSONObject().put("atMs",SystemClock.elapsedRealtime()).put("action",if(word.startsWith("book_"))"book" else word))
                original(word)
            })
            config.saveOriginal()
            try {
                val value=config.current().put("cameraAllowed",true).put("muted",false).put("listeningPlans",JSONArray())
                value.getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false).put("overrideUntil",0)
                value.getJSONObject("voice").put("volume",0.12)
                value.getJSONObject("interaction").put("wakeFeedback","voice")
                config.apply(value)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,1,0)
                report.put("streamVolume",audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                repeat(20) { index ->
                    scenario.onActivity { robot.stop() }
                    waitFor("真实麦克风") { input.recording }
                    val row=JSONObject().put("cycle",index+1).put("startMs",SystemClock.elapsedRealtime())
                    var activeSamples=0
                    scenario.onActivity { robot.wake() }
                    repeat(60) { if(audio.isMusicActive)activeSamples++;Thread.sleep(100) }
                    scenario.onActivity { row.put("state",robot.state.name).put("camera",robot.cameraActive).put("feedback",robot.lastFeedbackResult) }
                    row.put("musicActiveSamples",activeSamples).put("aecAvailable",input.aecAvailable).put("recording",input.recording)
                    cycles.put(row)
                    output.writeText(report.put("events",JSONArray(events.toList())).toString(2))
                }
                report.put("diagnosticCompleted",true)
            } finally {
                try {
                    scenario.onActivity { robot.stop() }
                    report.put("configurationRestored",config.restore())
                } finally {
                    scenario.onActivity { robot.background() }
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
                    output.writeText(report.put("events",JSONArray(events.toList())).toString(2))
                }
            }
        }
    }
}
