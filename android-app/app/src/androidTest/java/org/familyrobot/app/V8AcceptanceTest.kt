package org.familyrobot.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID

/** Physical-device UI and real local service. Does not claim a two-phone or child acoustic trial. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class V8AcceptanceTest {
    init { Configurator.getInstance().setWaitForIdleTimeout(200).setWaitForSelectorTimeout(2000) }
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private val vault=Vault(context)
    private val out=File(context.filesDir,"v8-acceptance").apply{mkdirs()}
    private fun waitFor(label:String,seconds:Int=20,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+seconds*1000;while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(150)};capture("failure");assertTrue(label,condition())}
    private fun capture(name:String){device.takeScreenshot(File(out,"$name.png"));device.dumpWindowHierarchy(File(out,"$name.xml"))}
    private fun find(text:String):UiObject2 {
        repeat(14){device.findObjects(By.text(text)).firstOrNull{it.className!="android.widget.EditText"}?.let{return it};val scroller=device.findObject(By.scrollable(true));scroller?.scroll(Direction.UP,.85f);Thread.sleep(130)}
        repeat(22){device.findObjects(By.text(text)).firstOrNull{it.className!="android.widget.EditText"}?.let{return it};device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.6f);Thread.sleep(200)}
        capture("missing-control");error("未找到：$text")
    }
    private fun tap(text:String){(device.findObject(By.desc(text)) ?: find(text)).click();Thread.sleep(350)}
    private fun role(mode:String):ActivityScenario<MainActivity>{vault.save("identity",JSONObject().put("mode",mode));return ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))}
    private fun runtime(activity:MainActivity)=MainActivity::class.java.getDeclaredField("runtime").apply{isAccessible=true}.get(activity) as RobotRuntime
    private fun manage(){
        val start=SystemClock.uptimeMillis()
        fun send(action:Int){val event=android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,device.displayWidth*.9f,device.displayHeight*.1f,0);instrumentation.sendPointerSync(event);event.recycle()}
        send(android.view.MotionEvent.ACTION_DOWN);Thread.sleep(2150);send(android.view.MotionEvent.ACTION_UP)
        assertTrue(device.wait(Until.hasObject(By.text("进入")),5000));device.findObject(By.clazz("android.widget.EditText")).text="258369";tap("进入")
        assertTrue(device.wait(Until.hasObject(By.text("机器人管理")),5000))
    }
    private fun event(name:String){File(out,"events.jsonl").appendText(JSONObject().put("event",name).put("atMs",SystemClock.elapsedRealtime()).toString()+"\n")}

    @Test fun a_seedIsolatedFamily(){
        val material=JSONObject(File(context.filesDir,"test-connection.json").readText())
        val existing=vault.get("robot")
        if(existing?.optString("serviceId")==material.getString("serviceId") && vault.get("parent")!=null)return
        val anon=Api(material);val registered=anon.json("/v1/register","POST",JSONObject().put("invite",material.getString("invite")).put("name","V8华为验收"))
        val robot=JSONObject(material.toString()).put("deviceId",registered.getString("deviceId")).put("token",registered.getString("token"))
        val robotApi=Api(robot);val invitation=robotApi.json("/v1/pairing","POST",JSONObject())
        val claim=anon.json("/v1/pairing/claim","POST",JSONObject().put("invite",invitation.getString("invite")).put("name","V8家长验收"))
        robotApi.json("/v1/pairing/${claim.getString("pairId")}/decision","POST",JSONObject().put("approved",true))
        val token=UUID.randomUUID().toString()+UUID.randomUUID().toString()
        val result=anon.json("/v1/pairing/${claim.getString("pairId")}/complete","POST",JSONObject().put("claim",claim.getString("claim")).put("token",token))
        val parent=JSONObject(material.toString()).put("deviceId",result.getString("deviceId")).put("robotId",robot.getString("deviceId")).put("token",token)
        vault.save("robot",robot);vault.save("parent",parent);vault.setPin("258369");event("isolated-family-created")
    }

    @Test fun b_robotThemeWakePromptsAndQr(){
        role("robot").use{scenario->
            lateinit var robot:RobotRuntime;scenario.onActivity{robot=runtime(it)}
            waitFor("真实机器人在线"){robot.online};manage()
            tap("小伙伴设置");tap("主题颜色");tap("浅色")
            assertEquals("light",context.getSharedPreferences("appearance",0).getString("theme",""));capture("robot-theme-light")
            device.pressBack();tap("唤醒与互动")
            waitFor("唤醒设置已加载"){device.hasObject(By.text("新的唤醒词（2—6个汉字）"))}
            tap("小蜜桃");tap("保存并应用")
            waitFor("唤醒词真正安装",30){robot.appliedWakeName=="小蜜桃" && device.hasObject(By.textContains("配置已确认"))};assertFalse(robot.micActive);capture("robot-wake-applied")
            device.pressBack();tap("AI 提示词")
            waitFor("提示词编辑器"){device.hasObject(By.clazz("android.widget.EditText"))}
            device.findObject(By.clazz("android.widget.EditText")).text="你叫{{robot_name}}，用一句中文简短回答问题。"
            tap("预览内容");assertTrue(device.wait(Until.hasObject(By.textContains("你叫小蜜桃")),5000));tap("返回编辑");tap("保存并应用")
            waitFor("提示词已ACK",30){robot.config.optJSONObject("prompts")?.optString("daily")?.contains("一句中文")==true && device.hasObject(By.textContains("已保存；新会话"))};capture("robot-prompts-applied")
            device.pressBack();device.pressBack();tap("家长与配对");tap("生成家长配对材料")
            assertTrue(device.wait(Until.hasObject(By.textContains("家长手机")),6000));capture("robot-pairing-qr")
            event("robot-theme-wake-prompts-pairing-ui")
        }
    }

    @Test fun c_parentThemeNavigationAndPersistence(){
        role("parent").use{
            assertTrue(device.wait(Until.hasObject(By.text("家长管理")),10000));capture("parent-light")
            tap("设置");tap("主题颜色");tap("深色");assertEquals("dark",context.getSharedPreferences("appearance",0).getString("theme",""));capture("parent-theme-dark")
            device.pressBack();tap("唤醒与互动");waitFor("双端共享昵称"){device.hasObject(By.textContains("当前配置：小蜜桃"))};device.pressBack()
            for((entry,label) in listOf("孩子与成长" to "基准年龄（3—17）","隐私与权限" to "允许会话内看图","对话记录保存" to "保存后续陪伴对话","声音" to "中文声音")){tap(entry);find(label);capture("parent-"+entry);device.pressBack()}
            tap("首页");tap("资源库");find("添加资源");device.pressBack();tap("使用安排");find("每日使用分钟，0表示不设额度");device.pressBack();tap("记录");find("暂无记录");capture("parent-records-empty")
        }
        role("parent").use{assertEquals("dark",context.getSharedPreferences("appearance",0).getString("theme",""));event("parent-navigation-theme-relaunch")}
    }

    @Test fun d_debugRealModelAndReplay(){
        role("parent").use{scenario->
            tap("设置");tap("检查与调试");tap("调试");waitFor("调试输入框"){device.hasObject(By.clazz("android.widget.EditText"))}
            device.findObject(By.clazz("android.widget.EditText")).text="你好，用一句话介绍自己。";tap("发送")
            waitFor("真实模型回答",100){device.hasObject(By.text("播放 / 再次播放"))};capture("debug-real-reply")
            fun playing():Boolean{var result=false;scenario.onActivity{val media=MainActivity::class.java.getDeclaredField("preview").apply{isAccessible=true}.get(it) as? android.media.MediaPlayer;result=runCatching{media?.isPlaying==true}.getOrDefault(false)};return result}
            waitFor("真实TTS自动播放",100){playing()};tap("播放 / 再次播放");waitFor("再次播放",20){playing()}
            tap("会话记录");find("朗读回答");capture("debug-history");device.pressBack();tap("切换语音输入");tap("点击录音")
            waitFor("真实调试录音"){device.hasObject(By.text("结束录音"))};Thread.sleep(1000);device.pressHome();Thread.sleep(1200)
            val ops=device.executeShellCommand("cmd appops get ${context.packageName} RECORD_AUDIO")
            assertFalse("后台释放麦克风",ops.contains("(running)")||ops.contains("running=true"));event("debug-model-tts-replay-microphone-background")
        }
    }

    @Test fun e_scanCameraOpenClose(){
        role("setup").use{
            tap("这是机器人手机");tap("扫描二维码");assertTrue(device.wait(Until.hasObject(By.textContains("将机器人上的二维码")),7000));Thread.sleep(1500);capture("scan-camera")
            device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("连接家庭机器人")),5000));event("scanner-camera-open-close-no-crash")
        }
    }
    @Test fun f_parentControlAndOptInRecords(){
        val parent=Api(vault.get("parent")!!);val rc=vault.get("robot")!!;val configPath="/v1/robots/${rc.getString("deviceId")}/config"
        var rid=""
        role("parent").use{scenario->
            lateinit var robot:RobotRuntime
            scenario.onActivity{robot=RobotRuntime(it,vault,rc);robot.resumeForeground();(RobotRuntime::class.java.getDeclaredField("input").apply{isAccessible=true}.get(robot) as AudioInput).close()}
            try{
                waitFor("控制用例真实运行时在线"){robot.online}
                val config=parent.json(configPath)
                config.getJSONObject("config").apply{put("cameraAllowed",false);put("muted",false);getJSONObject("history").put("enabled",true);getJSONObject("policy").put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false)}
                val command=UUID.randomUUID().toString();parent.json(configPath,"POST",JSONObject().put("requestId",command).put("expectedVersion",config.getInt("version")).put("config",config.getJSONObject("config")))
                waitFor("真实运行时应用记录配置"){parent.json("/v1/commands/$command").optString("state")=="applied"}
                val resource=parent.json("/v1/resources","POST",JSONObject().put("kind","song").put("draft",JSONObject().put("title","V8原创播放验收")))
                rid=resource.getString("id")
                val pcm=ByteArray(16000*2*60) // Bounded original silent fixture; AudioTrack progress verifies playback, not listening quality.
                parent.upload("/v1/resources/$rid/assets?purpose=audio&expectedVersion=1","original.wav",AudioInput.wav(pcm),"audio/wav")
                var value=parent.json("/v1/resources/$rid");value.getJSONObject("draft").put("complete",true)
                value=parent.json("/v1/resources/$rid","PUT",JSONObject().put("expectedVersion",value.getInt("draft_version")).put("draft",value.getJSONObject("draft")))
                value.getJSONObject("draft").put("auditioned",true)
                value=parent.json("/v1/resources/$rid","PUT",JSONObject().put("expectedVersion",value.getInt("draft_version")).put("draft",value.getJSONObject("draft")))
                parent.json("/v1/resources/$rid/publish","POST",JSONObject().put("requestId",UUID.randomUUID().toString()).put("expectedVersion",value.getInt("draft_version")))
                val downloadCommand=UUID.randomUUID().toString()
                parent.json("/v1/robots/${rc.getString("deviceId")}/control","POST",JSONObject().put("requestId",downloadCommand).put("action","download").put("resourceId",rid))
                waitFor("实际离线副本下载完成",40){robot.offlineItems().any{it.first==rid}}
                val removeCommand=UUID.randomUUID().toString()
                parent.json("/v1/robots/${rc.getString("deviceId")}/control","POST",JSONObject().put("requestId",removeCommand).put("action","remove_download").put("resourceId",rid))
                waitFor("清理回执与下载状态一致",25){val rows=parent.array("/v1/downloads");(0 until rows.length()).any{rows.getJSONObject(it).let{d->d.optString("resource_id")==rid&&d.optString("state")=="removed"}}}
                assertFalse(robot.offlineItems().any{it.first==rid})
                tap("资源库");tap("全部");tap("儿歌");tap("V8原创播放验收");tap("在机器人上播放");tap("确认")
                fun progressing():Boolean{var ok=false;scenario.onActivity{val track=RobotRuntime::class.java.getDeclaredField("track").apply{isAccessible=true}.get(robot) as? android.media.AudioTrack;ok=track!=null && track.playState==android.media.AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition>0};return ok}
                waitFor("实际AudioTrack推进",40){progressing()};device.pressBack();device.pressBack()
                waitFor("家长首页真实播放状态"){device.hasObject(By.text("暂停"))};capture("parent-playing");tap("暂停")
                waitFor("暂停回执反映到首页"){device.hasObject(By.text("继续播放"))};assertFalse(progressing());Thread.sleep(3500);tap("继续播放");waitFor("继续播放真实推进"){progressing()};capture("parent-resumed")
                scenario.onActivity{robot.stop()}
                val answer=Api(rc).json("/v1/turns","POST",JSONObject().put("sessionId",UUID.randomUUID().toString()).put("text","天空通常是什么颜色？"))
                assertTrue(answer.optString("text").isNotBlank());tap("记录");waitFor("启用后陪伴记录可见"){device.hasObject(By.textContains("天空通常是什么颜色"))};capture("companion-record")
                tap("清空");tap("删除");waitFor("记录清空"){device.hasObject(By.text("暂无记录"))}
                event("real-runtime-parent-play-pause-resume-history-clear-no-acoustic-claim")
            }finally{scenario.onActivity{robot.close()};if(rid.isNotBlank())runCatching{parent.json("/v1/resources/$rid","DELETE")}}
        }
    }

    @Test fun g_realTtsToAsrAndScope(){
        val api=Api(vault.get("parent")!!)
        val voice=api.json("/v1/robots/${vault.get("robot")!!.getString("deviceId")}/config").getJSONObject("config").getJSONObject("voice").put("quality","standard")
        val wav=api.raw("/v1/debug/speech","POST",JSONObject().put("text","你好，小伙伴，今天天气很好。").put("voice",voice).toBody())
        val reply=api.upload("/v1/debug/recognize","generated-original.wav",wav,"audio/wav")
        assertTrue("真实TTS到ASR识别返回非空文字",reply.optString("text").isNotBlank());event("real-tts-16khz-asr-no-child-acoustic-claim")
    }

    @Test fun h_serverLossIsVisibleAndRecovers(){
        val flag=File(context.filesDir,"v8-network-state")
        try{role("parent").use{
            waitFor("服务先在线"){device.hasObject(By.text("已连接家庭服务器"))}
            flag.writeText("disconnect")
            waitFor("断开服务后显示红色状态",20){device.hasObject(By.text("连接失败"))};capture("server-offline")
            flag.writeText("reconnect")
            waitFor("连接恢复",20){device.hasObject(By.text("已连接家庭服务器"))};capture("server-recovered");event("server-unresponsive-status-and-recovery")
        }}finally{flag.writeText("done")}
    }

}
