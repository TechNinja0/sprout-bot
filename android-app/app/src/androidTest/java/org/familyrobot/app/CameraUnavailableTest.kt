package org.familyrobot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.familyrobot.core.SessionState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 主机先撤销真实CAMERA权限；提示逻辑隔离环境语音，不计双讲/AEC。 */
@RunWith(AndroidJUnit4::class)
class CameraUnavailableTest {
    @Test fun permissionFailureSpeaksAndIncompatibleProtocolStopsWrites() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA))
        val vault=Vault(context);val identity=vault.get("identity")
        vault.save("identity",JSONObject().put("mode","setup"))
        val connection=JSONObject().put("address","https://127.0.0.1:9").put("certificateSha256","0".repeat(64)).put("serviceId","camera-permission-test").put("deviceId",UUID.randomUUID().toString())
        val config=JSONObject().put("cameraAllowed",true).put("muted",false).put("nickname","小伙伴").put("voice",JSONObject().put("volume",.2))
            .put("interaction",JSONObject().put("wakeFeedback","voice")).put("policy",JSONObject().put("intervals",JSONArray()).put("dailyMinutes",0).put("manualBlocked",false))
        vault.save(robotKey(connection,"robot-config"),config)
        vault.save(robotKey(connection,"clock"),JSONObject().put("boot",System.currentTimeMillis()-SystemClock.elapsedRealtime()))
        val report=JSONObject().put("acousticAcceptance",false)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                lateinit var robot:RobotRuntime
                scenario.onActivity { robot=RobotRuntime(it,vault,connection);robot.resumeForeground() }
                fun onRobot(action:()->Unit)=scenario.onActivity { action() }
                fun waitFor(label:String,check:()->Boolean) { val until=SystemClock.elapsedRealtime()+12000;while(!check() && SystemClock.elapsedRealtime()<until)Thread.sleep(30);assertTrue(label,check()) }
                try {
                    waitFor("相机拒绝不影响麦克风硬件可用") { robot.micActive }
                    onRobot { (RobotRuntime::class.java.getDeclaredField("input").apply { isAccessible=true }.get(robot) as AudioInput).close();robot.wake() }
                    waitFor("固定口头说明进入真实音轨") { robot.mouth>.01f }
                    assertEquals("camera_unavailable",RobotRuntime::class.java.getDeclaredField("openingReply").apply { isAccessible=true }.get(robot))
                    assertFalse(robot.cameraActive)
                    waitFor("说明后保留倾听会话") { robot.state==SessionState.LISTENING && robot.mouth==0f }
                    report.put("permissionDeniedVoicePrompt",true).put("microphoneHardwareAvailableBeforeIsolation",true)
                } finally { onRobot { robot.close() } }
            }
            // 模拟协议版本响应而非真实服务升级；错误版本不得发送后续写请求。
            var writes=0
            val api=Api(connection);val clientField=Api::class.java.getDeclaredField("client").apply { isAccessible=true }
            val initial=clientField.get(api) as okhttp3.OkHttpClient
            clientField.set(api,initial.newBuilder().addInterceptor { chain ->
                if(chain.request().method!="GET")writes++
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(JSONObject().put("serviceId",connection.getString("serviceId")).put("protocolVersion",2).toString().toResponseBody("application/json".toMediaType())).build()
            }.build())
            assertTrue(runCatching { api.json("/v1/register","POST",JSONObject()) }.exceptionOrNull() is ProtocolMismatchException)
            assertEquals(0,writes)
            assertTrue(runCatching { Api(JSONObject(connection.toString()).put("protocolVersion",2)) }.exceptionOrNull() is ProtocolMismatchException)
            report.put("incompatibleProtocolRejectsWrites",true)
        } finally {
            for(key in listOf("robot-config","clock","usage","service-failures","robot-version"))vault.remove(robotKey(connection,key))
            if(identity==null)vault.remove("identity") else vault.save("identity",identity)
            File(context.filesDir,"camera-unavailable.json").writeText(report.toString())
        }
    }
}
