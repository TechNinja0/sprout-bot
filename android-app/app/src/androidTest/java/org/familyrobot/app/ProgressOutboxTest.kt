package org.familyrobot.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** 真机Keystore持久化与真实异步时序；HTTP应答由测试拦截，不冒充网络验收。 */
@RunWith(AndroidJUnit4::class)
class ProgressOutboxTest {
    @Test fun delayedAckRetryAndRecreationKeepNewestProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = Vault(context)
        val identity = vault.get("identity")
        val connection = JSONObject().put("address", "https://127.0.0.1:9")
            .put("certificateSha256", "0".repeat(64)).put("serviceId", "progress-test")
            .put("deviceId", UUID.randomUUID().toString())
        val pendingKey = robotKey(connection, "progress-pending")
        val sequenceKey = robotKey(connection, "progress-sequence")
        // 模拟旧进程已确认序号领先墙钟；无需修改手机系统时间。
        val priorSequence = System.currentTimeMillis() + 86_400_000
        vault.save(sequenceKey, JSONObject().put("value", priorSequence))
        vault.save("identity", JSONObject().put("mode", "setup"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockFirst = AtomicBoolean(true)
        val offline = AtomicBoolean(false)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var robot: RobotRuntime
                fun create() = scenario.onActivity { activity ->
                    robot = RobotRuntime(activity, vault, connection)
                    val api = RobotRuntime::class.java.getDeclaredField("api")
                        .apply { isAccessible = true }.get(robot) as Api
                    val field = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
                    val client = field.get(api) as OkHttpClient
                    field.set(api, client.newBuilder().addInterceptor { chain ->
                        assertTrue(chain.request().url.encodedPath.endsWith("/progress"))
                        if (blockFirst.compareAndSet(true, false)) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS)) { "测试应答未释放" }
                        }
                        if (offline.get()) throw IOException("测试传输失败")
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK").body("{\"accepted\":true}".toResponseBody()).build()
                    }.build())
                }
                fun queue(id: String, position: Int) = scenario.onActivity {
                    RobotRuntime::class.java.getDeclaredMethod("queueProgress", String::class.java,
                        String::class.java, String::class.java, Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(robot, id, "revision", "segment", position)
                }
                fun flush() = CoroutineScope(Dispatchers.Main).async {
                    suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                        RobotRuntime::class.java.getDeclaredMethod("flushProgress", kotlin.coroutines.Continuation::class.java)
                            .apply { isAccessible = true }.invoke(robot, continuation)
                    }
                }
                create()
                try {
                    queue("book", 100)
                    val first = vault.get(pendingKey)!!.getJSONObject("book").getLong("seq")
                    assertTrue(first > priorSequence)
                    val delayed = flush()
                    assertTrue("旧进度请求确实已发出", entered.await(3, TimeUnit.SECONDS))
                    queue("book", 200)
                    val newer = vault.get(pendingKey)!!.getJSONObject("book").getLong("seq")
                    release.countDown()
                    runBlocking { withTimeout(3000) { delayed.await() } }
                    assertEquals("旧ACK不能删除新位置", 200, vault.get(pendingKey)!!.getJSONObject("book").getInt("offsetMs"))
                    assertTrue(newer > first)
                    offline.set(true)
                    val failed = flush()
                    runBlocking { withTimeout(3000) { failed.await() } }
                    assertEquals("传输失败保留原序号待重试", newer, vault.get(pendingKey)!!.getJSONObject("book").getLong("seq"))
                    scenario.onActivity { robot.close() }
                    create()
                    scenario.onActivity {
                        val restoredQueue = RobotRuntime::class.java.getDeclaredField("progressPending")
                            .apply { isAccessible = true }.get(robot) as JSONObject
                        assertEquals("未确认位置从Keystore存储恢复", 200, restoredQueue.getJSONObject("book").getInt("offsetMs"))
                        assertEquals(newer, restoredQueue.getJSONObject("book").getLong("seq"))
                    }
                    queue("book", 300)
                    val restored = vault.get(pendingKey)!!.getJSONObject("book").getLong("seq")
                    assertTrue("重建运行对象后序号继续递增", restored > newer)
                    offline.set(false)
                    val retried = flush()
                    runBlocking { withTimeout(3000) { retried.await() } }
                    assertFalse("最新ACK后清空待同步项", vault.get(pendingKey)!!.has("book"))
                    scenario.onActivity { robot.close() }
                    create()
                    queue("book", 400)
                    assertTrue("队列已清空仍保留已确认序号", vault.get(pendingKey)!!.getJSONObject("book").getLong("seq") > restored)
                    repeat(128) { queue("other-$it", it) }
                    assertEquals(128, vault.get(pendingKey)!!.length())
                    assertFalse("容量满后淘汰最旧进度", vault.get(pendingKey)!!.has("book"))
                    assertEquals(1, vault.get(robotKey(connection, "service-failures"))!!.getInt("progress-overflow"))
                    File(context.filesDir, "progress-outbox-result.json").writeText(JSONObject()
                        .put("lateAckPreservesNewest", true).put("retryRetainsSequence", true)
                        .put("runtimeRecreationMonotonic", true).put("bounded128", true)
                        .put("scope", "HTTP拦截应答；运行对象重建，不是OS或进程重启").toString())
                } finally {
                    release.countDown()
                    scenario.onActivity { robot.close() }
                }
            }
        } finally {
            for (key in listOf("progress-pending", "progress-sequence", "service-failures", "usage")) {
                vault.remove(robotKey(connection, key))
            }
            File(context.filesDir, "library/${robotScope(connection)}").deleteRecursively()
            if (identity == null) vault.remove("identity") else vault.save("identity", identity)
        }
    }
}
