package org.familyrobot.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun realPhoneConnectionPinAndBackground() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val material=File(context.filesDir,"test-connection.json").readText()
        val connection=JSONObject(material)
        Api(connection).checkIdentity()
        val wrong=JSONObject(material).put("certificateSha256","0".repeat(64))
        assertTrue(runCatching { Api(wrong).checkIdentity() }.isFailure)
        val device=UiDevice.getInstance(instrumentation)
        // 与新手势契约一致：真实按住超过2秒，不能用150个swipe步骤冒充长按。
        fun openManagement() {
            val began=android.os.SystemClock.uptimeMillis()
            fun send(action:Int) {
                val event=android.view.MotionEvent.obtain(began,android.os.SystemClock.uptimeMillis(),action,device.displayWidth*.9f,device.displayHeight*.1f,0)
                instrumentation.sendPointerSync(event);event.recycle()
            }
            send(android.view.MotionEvent.ACTION_DOWN)
            Thread.sleep(2150)
            send(android.view.MotionEvent.ACTION_UP)
        }
        fun recording():Boolean {
            val ops=device.executeShellCommand("cmd appops get ${context.packageName} RECORD_AUDIO")
            assertTrue("AppOps必须返回实际录音状态",ops.contains("RECORD_AUDIO:") && !ops.contains("Unknown command"))
            return ops.contains("(running)") || ops.contains("running=true")
        }
        val vault=Vault(context)
        for(key in listOf("identity","robot","parent","pin","robot-config","robot-version","clock"))vault.remove(key)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("连接家庭机器人")),10000))
        val edits=device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(2,edits.size)
        edits[0].text=material;edits[1].text="258369"
        if(!device.hasObject(By.text("验证并连接")))device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,1f)
        val button=device.wait(Until.findObject(By.text("验证并连接")),3000)
        assertNotNull(button);button.click()
        assertTrue(device.wait(Until.gone(By.text("连接家庭机器人")),15000))
        Thread.sleep(4000)
        assertNotNull(vault.get("robot"))
        // API配对作为实机联调夹具；不把它计作双手机扫码人工验收。
        val rc=vault.get("robot")!!;val robotApi=Api(rc)
        val invite=robotApi.json("/v1/pairing","POST",JSONObject()).getString("invite")
        val pc=JSONObject(rc.toString());pc.remove("token");val anon=Api(pc)
        val claim=anon.json("/v1/pairing/claim","POST",JSONObject().put("invite",invite).put("name","自动化测试家长"))
        robotApi.json("/v1/pairing/${claim.getString("pairId")}/decision","POST",JSONObject().put("approved",true))
        val token=java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID().toString()
        val paired=anon.json("/v1/pairing/${claim.getString("pairId")}/complete","POST",JSONObject().put("claim",claim.getString("claim")).put("token",token))
        pc.put("token",token).put("deviceId",paired.getString("deviceId")).put("robotId",paired.getString("robotId"));vault.save("parent",pc)
        val parentApi=Api(pc);val config=parentApi.json("/v1/robots/${rc.getString("deviceId")}/config")
        config.getJSONObject("config").getJSONObject("policy").put("intervals",org.json.JSONArray())
        parentApi.json("/v1/robots/${rc.getString("deviceId")}/config","POST",JSONObject().put("requestId",java.util.UUID.randomUUID().toString()).put("expectedVersion",config.getInt("version")).put("config",config.getJSONObject("config")))
        Thread.sleep(5000)
        assertTrue("前台本地唤醒实际占用麦克风",recording())
        assertTrue(vault.verifyPin("258369"));assertFalse(vault.verifyPin("000000"))
        device.takeScreenshot(File(context.filesDir,"smoke-face.png"))
        // 纯脸没有正文文本；实际Android系统隐私图标不属于产品UI。
        assertTrue(device.findObjects(By.pkg(context.packageName).text(java.util.regex.Pattern.compile(".+"))).isEmpty())
        openManagement()
        assertTrue(device.wait(Until.hasObject(By.text("家长管理")),4000))
        device.findObject(By.clazz("android.widget.EditText")).text="258369"
        device.findObject(By.text("进入")).click()
        assertTrue(device.wait(Until.hasObject(By.text("机器人管理")),5000))
        device.takeScreenshot(File(context.filesDir,"smoke-management.png"))
        device.pressHome();Thread.sleep(600)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("机器人管理")),5000));Thread.sleep(500)
        assertFalse("管理页恢复前台不能重新录音",recording())
        device.findObject(By.text("测试唤醒")).click();Thread.sleep(2500)
        var active=JSONObject()
        for(attempt in 0..8) {
            val devices=robotApi.array("/v1/devices")
            active=(0 until devices.length()).map { devices.getJSONObject(it) }.first { it.getString("role")=="robot" }.getJSONObject("status")
            if(active.optBoolean("camera") && active.optBoolean("microphone"))break
            Thread.sleep(1000)
        }
        assertTrue("实际麦克风应打开",active.optBoolean("microphone"))
        assertTrue("唤醒后应产生真实新相机帧",active.optBoolean("camera"))
        // 完整音频下载、实际播放、暂停后持久进度及撤回同步。
        val draft=JSONObject().put("title","红色小猫").put("language","en").put("complete",true).put("auditioned",true)
            .put("pages",org.json.JSONArray().put(JSONObject().put("id","page-one").put("reviewed",true)
                .put("text","Hello, little red cat. The cat has a red ball. Look at the ball. It is red. The little cat is happy. Hello, little red cat. The cat has a red ball. Look at the ball. It is red.")))
        val book=parentApi.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",draft))
        val bookId=book.getString("id")
        parentApi.json("/v1/resources/$bookId/publish","POST",JSONObject().put("requestId",java.util.UUID.randomUUID().toString()).put("expectedVersion",1))
        fun control(action:String) = parentApi.json("/v1/robots/${rc.getString("deviceId")}/control","POST",JSONObject().put("requestId",java.util.UUID.randomUUID().toString()).put("action",action).put("resourceId",bookId))
        control("download")
        var downloaded=false
        for(attempt in 0..60) {
            val downloads=parentApi.array("/v1/downloads")
            downloaded=(0 until downloads.length()).map { downloads.getJSONObject(it) }.any { it.optString("resource_id")==bookId && it.optString("state")=="downloaded" }
            if(downloaded)break
            Thread.sleep(1000)
        }
        assertTrue("完整离线资源下载："+vault.get(robotKey(rc,"download-error")),downloaded)
        val cache=File(context.filesDir,"library/${robotScope(rc)}/$bookId");val manifest=JSONObject(File(cache,"manifest.json").readText());val hashes=manifest.getJSONObject("hashes")
        for(name in hashes.keys())assertEquals(hashes.getString(name),sha256(File(cache,name).readBytes()))
        control("play")
        val playingAudio=context.getSystemService(android.media.AudioManager::class.java)
        for(attempt in 0..80) { if(playingAudio.isMusicActive)break;Thread.sleep(250) }
        assertTrue("机器人应实际开始播放",playingAudio.isMusicActive)
        Thread.sleep(5000);control("pause");Thread.sleep(3500)
        val progress=parentApi.json("/v1/resources/$bookId/progress")
        assertTrue("进度必须来自实际音频播出",progress.optInt("offset_ms")>0)
        assertNotNull(vault.get(robotKey(rc,"reading")))
        // 主机脚本仅移除USB端口映射，服务器仍运行；手机实际经历断网。
        if(InstrumentationRegistry.getArguments().getString("offline")=="true") {
            val signal=File(context.filesDir,"offline-test-state");signal.writeText("ready")
            for(attempt in 0..40) { if(signal.readText().trim()=="disconnected")break;Thread.sleep(250) }
            assertEquals("disconnected",signal.readText().trim());Thread.sleep(6000)
            openManagement()
            assertTrue(device.wait(Until.hasObject(By.text("家长管理")),4000));device.findObject(By.clazz("android.widget.EditText")).text="258369";device.findObject(By.text("进入")).click()
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("播放：红色小猫"))
            // 强制模拟心跳尚未发现断网，验证传输失败也能使用已校验完整缓存。
            instrumentation.runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first() as MainActivity
                val runtime=MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(activity) as RobotRuntime
                @Suppress("UNCHECKED_CAST")
                val online=RobotRuntime::class.java.getDeclaredField("online\$delegate").apply { isAccessible=true }.get(runtime) as androidx.compose.runtime.MutableState<Boolean>
                online.value=true
            }
            device.findObject(By.text("播放：红色小猫")).click()
            for(attempt in 0..40) { if(playingAudio.isMusicActive)break;Thread.sleep(100) }
            val audio=context.getSystemService(android.media.AudioManager::class.java)
            var offlineDiagnostic=""
            var offlineRuntime:RobotRuntime?=null
            instrumentation.runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
                val runtime=activity?.let { MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible=true }.get(it) as? RobotRuntime }
                offlineRuntime=runtime
                offlineDiagnostic=runtime?.let { "state=${it.state}, online=${it.online}, foreground=${it.foreground}, diagnostic=${it.diagnostic}" } ?: "no resumed robot"
            }
            assertTrue("断网后播放完整缓存：$offlineDiagnostic",audio.isMusicActive)
            // 与20次音频回归一致，从第二次真实触屏注入开始计时；
            // UiDevice.click返回前的同步等待不能充当音频实际停止时刻。
            fun tap() {
                val down=android.os.SystemClock.uptimeMillis()
                for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                    val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,device.displayWidth/2f,device.displayHeight/2f,0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event,false));event.recycle()
                    if(action==android.view.MotionEvent.ACTION_DOWN)Thread.sleep(20)
                }
            }
            tap();Thread.sleep(80)
            val started=android.os.SystemClock.elapsedRealtime();tap()
            while(audio.isMusicActive && android.os.SystemClock.elapsedRealtime()-started<500)Thread.sleep(10)
            val stopped=android.os.SystemClock.elapsedRealtime()-started
            assertFalse("双击必须停止实际音频",audio.isMusicActive)
            var timing=JSONObject().put("elapsedMs",stopped)
            instrumentation.runOnMainSync {
                val robot=offlineRuntime!!
                timing.put("callbackDelayMs",robot.lastPauseStartedMs-started)
                    .put("pauseDurationMs",robot.lastPauseFinishedMs-robot.lastPauseStartedMs)
                    .put("lastPersistenceMs",robot.lastPersistenceDurationMs)
            }
            File(context.filesDir,"stop-latency.json").writeText(timing.toString())
            assertTrue("必须确实触发暂停，不能把自然播完当成双击生效",timing.getLong("callbackDelayMs")>=0)
            assertTrue("双击停止目标300ms",stopped<=300)
            signal.writeText("reconnect")
            for(attempt in 0..40) { if(signal.readText().trim()=="connected")break;Thread.sleep(250) }
            assertEquals("connected",signal.readText().trim());Thread.sleep(4000)
        }
        parentApi.json("/v1/resources/$bookId/unlist","POST",JSONObject())
        for(attempt in 0..8) { if(!cache.exists())break;Thread.sleep(1000) }
        assertFalse("联网撤回后离线文件应清理",cache.exists())
        assertEquals(409,runCatching { robotApi.json("/v1/resources/$bookId/manifest");200 }.getOrElse { it.message!!.substringBefore(":").substringBefore("：").toInt() })
        device.pressHome();Thread.sleep(1500)
        assertFalse("后台不能继续录音",recording())
        // 切换同APK家长身份并实际打开首页、资源管理页。
        vault.save("identity",JSONObject().put("mode","parent"))
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("家庭小伙伴")),10000))
        device.findObject(By.text("资源库")).click()
        assertTrue(device.wait(Until.hasObject(By.text("创建草稿")),5000))
        device.takeScreenshot(File(context.filesDir,"smoke-parent.png"))
        verifyParentTabs(device,context)
        File(context.filesDir,"test-connection.json").delete()
    }
}
