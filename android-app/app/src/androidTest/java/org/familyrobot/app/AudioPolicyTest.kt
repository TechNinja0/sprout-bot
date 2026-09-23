package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** 使用前一轮登记的测试身份；音频来自项目原创提示语，不使用家庭内容。 */
@RunWith(AndroidJUnit4::class)
class AudioPolicyTest {
    @Test fun compressedAudioFocusAndQuietPolicy() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=Vault(context);val rc=vault.get("robot")!!;val pc=vault.get("parent")!!
        val api=Api(pc);val robotId=rc.getString("deviceId")
        vault.save("identity",JSONObject().put("mode","robot"))
        var runtime:RobotRuntime?=null
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> runtime=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(activity) as RobotRuntime }
            fun onRobot(action:(RobotRuntime)->Unit) { scenario.onActivity { action(runtime!!) } }
            fun waitFor(label:String,timeout:Long=12000,predicate:()->Boolean) {
                val start=android.os.SystemClock.elapsedRealtime()
                while(!predicate() && android.os.SystemClock.elapsedRealtime()-start<timeout)Thread.sleep(100)
                assertTrue(label,predicate())
            }
            waitFor("机器人联网") { runtime!!.online }
            fun configure(edit:(JSONObject)->Unit) {
                val current=api.json("/v1/robots/$robotId/config");val config=current.getJSONObject("config");edit(config)
                val id=UUID.randomUUID().toString()
                api.json("/v1/robots/$robotId/config","POST",JSONObject().put("requestId",id).put("expectedVersion",current.getInt("version")).put("config",config))
                waitFor("配置实际ACK") { api.json("/v1/commands/$id").getString("state")=="applied" }
                Thread.sleep(300)
            }
            configure { it.getJSONObject("policy").put("intervals",JSONArray()).put("manualBlocked",false);it.put("muted",false) }
            val song=api.json("/v1/resources","POST",JSONObject().put("kind","song").put("draft",JSONObject().put("title","原创测试语音音轨")))
            val rid=song.getString("id")
            api.upload("/v1/resources/$rid/assets?purpose=audio&expectedVersion=1","original.mp3",File(context.filesDir,"test-audio.mp3").readBytes(),"audio/mpeg")
            val loaded=api.json("/v1/resources/$rid");loaded.getJSONObject("draft").put("complete",true).put("auditioned",true)
            val reviewed=api.json("/v1/resources/$rid","PUT",JSONObject().put("expectedVersion",loaded.getInt("draft_version")).put("draft",loaded.getJSONObject("draft")))
            reviewed.getJSONObject("draft").put("auditioned",true)
            val saved=api.json("/v1/resources/$rid","PUT",JSONObject().put("expectedVersion",reviewed.getInt("draft_version")).put("draft",reviewed.getJSONObject("draft")))
            api.json("/v1/resources/$rid/publish","POST",JSONObject().put("expectedVersion",saved.getInt("draft_version")).put("requestId",UUID.randomUUID().toString()))
            fun control(action:String) { api.json("/v1/robots/$robotId/control","POST",JSONObject().put("requestId",UUID.randomUUID().toString()).put("action",action).put("resourceId",rid)) }
            val audio=context.getSystemService(AudioManager::class.java)
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val stops=JSONArray()
            repeat(20) {
                onRobot { it.playResource(rid) }
                waitFor("双击测试实际播放") { audio.isMusicActive }
                Thread.sleep(150)
                fun tap() {
                    val down=android.os.SystemClock.uptimeMillis()
                    for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                        val event=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,device.displayWidth/2f,device.displayHeight/2f,0)
                        event.source=InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event,false));event.recycle()
                        if(action==MotionEvent.ACTION_DOWN)Thread.sleep(20)
                    }
                }
                tap();Thread.sleep(80)
                val began=android.os.SystemClock.elapsedRealtime()
                tap()
                while(audio.isMusicActive && android.os.SystemClock.elapsedRealtime()-began<500)Thread.sleep(5)
                val elapsed=android.os.SystemClock.elapsedRealtime()-began
                stops.put(elapsed)
                assertFalse("双击必须停止实际音频",audio.isMusicActive)
                assertTrue("双击停止须在300ms内，第${it+1}次实际${elapsed}ms",elapsed<=300)
                Thread.sleep(350)
            }
            control("play");waitFor("MP3真实播放") { audio.isMusicActive }
            waitFor("压缩音频实际波形驱动嘴形",5000) { runtime!!.mouth>.04f }
            val competing=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setOnAudioFocusChangeListener {}.build()
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(competing))
            waitFor("焦点丢失必须暂停实际音频",2000) { !audio.isMusicActive }
            audio.abandonAudioFocusRequest(competing)
            Thread.sleep(1200);assertFalse("焦点返回不可擅自恢复",audio.isMusicActive)
            control("resume");waitFor("主动续播") { audio.isMusicActive }
            configure { it.getJSONObject("policy").put("manualBlocked",true) }
            waitFor("禁用释放采集及播放") { !runtime!!.micActive && !runtime!!.cameraActive && !audio.isMusicActive }
            onRobot { it.wake() };Thread.sleep(500)
            assertEquals(org.familyrobot.core.SessionState.BLOCKED,runtime!!.state)
            assertEquals("blocked",runtime!!.faceMode)
            device.takeScreenshot(File(context.filesDir,"policy-blocked.png"))
            configure { it.getJSONObject("policy").put("manualBlocked",false);it.put("muted",true) }
            waitFor("静音关闭实际录音") { !runtime!!.micActive }
            waitFor("静音与限时表情不同") { runtime!!.faceMode=="muted" }
            device.takeScreenshot(File(context.filesDir,"policy-muted.png"))
            configure { it.put("muted",false) }
            waitFor("重新授权后恢复本地唤醒录音") { runtime!!.micActive }
            onRobot { it.wake() };waitFor("唤醒产生新帧") { runtime!!.cameraActive }
            configure { it.put("cameraAllowed",false) }
            waitFor("撤销相机配置实际关相机") { !runtime!!.cameraActive }
            onRobot { it.stop() }
            val playlist=api.json("/v1/playlists","POST",JSONObject().put("name","真实计时计划测试").put("resources",JSONArray(List(5) { rid }))).getString("id")
            val zone=java.time.ZoneId.of("Asia/Shanghai")
            val target=java.time.ZonedDateTime.now(zone).plusSeconds(70).withSecond(0).withNano(0)
            val plan=JSONObject().put("id",UUID.randomUUID().toString().replace("-","")).put("playlistId",playlist).put("enabled",true).put("days",JSONArray(listOf(target.dayOfWeek.value))).put("time",target.toLocalTime().toString()).put("minutes",1)
            configure { it.put("cameraAllowed",true).put("listeningPlans",JSONArray().put(plan)) }
            waitFor("实时分钟边界自动播放清单",80000) { audio.isMusicActive }
            waitFor("儿歌工作状态") { runtime!!.faceMode=="song" }
            val began=android.os.SystemClock.elapsedRealtime()
            waitFor("计划一分钟上限停止整张清单",65000) { !audio.isMusicActive && runtime!!.state==org.familyrobot.core.SessionState.STANDBY }
            val duration=android.os.SystemClock.elapsedRealtime()-began
            assertTrue("不能只播放单条后误算计划完成",duration>=50000)
            configure { it.put("listeningPlans",JSONArray()) }
            api.json("/v1/playlists/$playlist","DELETE")
            api.json("/v1/resources/$rid/unlist","POST",JSONObject())
            onRobot { it.stop() }
            File(context.filesDir,"audio-policy-result.json").writeText(JSONObject().put("mp3Playback",true).put("doubleTap20Ms",stops).put("waveformMouth",true).put("focusPause",true).put("blockedHardwareReleased",true).put("mutedHardwareReleased",true).put("cameraRevoked",true).put("scheduledPlaylistRealBoundary",true).put("scheduledLimitMs",duration).toString())
        }
    }
}
