package org.familyrobot.app

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.familyrobot.core.Permission
import org.familyrobot.core.SessionState
import org.familyrobot.core.UsageLedger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** 由宿主脚本分阶段调用；仅限已登记的测试身份，不更改手机系统时间。 */
@RunWith(AndroidJUnit4::class)
class PolicyBoundaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = Vault(context)
    private val connection = vault.get("robot")!!
    private val api = Api(vault.get("parent")!!)
    private val robotId = connection.getString("deviceId")
    private val backup = File(context.filesDir, "policy-boundary-backup.json")
    private val marker = File(context.filesDir, "policy-boundary-state")
    private val results = File(context.filesDir, "policy-boundary-results.jsonl")
    private val network = File(context.filesDir, "policy-boundary-network.jsonl")
    private var observed: RobotRuntime? = null

    private fun waitFor(label: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!predicate() && SystemClock.elapsedRealtime() - start < timeout) Thread.sleep(25)
        assertTrue("$label；online=${observed?.online}，foreground=${observed?.foreground}，state=${observed?.state}，diagnostic=${observed?.diagnostic}", predicate())
    }

    private fun runtime(activity: MainActivity): RobotRuntime {
        val robot = MainActivity::class.java.getDeclaredField("runtime")
            .apply { isAccessible = true }.get(activity) as RobotRuntime
        observed = robot
        val clientApi = RobotRuntime::class.java.getDeclaredField("api")
            .apply { isAccessible = true }.get(robot) as Api
        val clientField = Api::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = clientField.get(clientApi) as okhttp3.OkHttpClient
        clientField.set(clientApi, client.newBuilder().addInterceptor { chain ->
            val row = JSONObject().put("wallTimeMs", System.currentTimeMillis())
                .put("path", chain.request().url.encodedPath.replace(Regex("[a-f0-9]{24,}"), "<id>"))
            try {
                val response = chain.proceed(chain.request())
                row.put("status", response.code)
                response
            } catch (error: Exception) {
                row.put("error", error.javaClass.simpleName)
                throw error
            } finally { synchronized(network) { network.appendText(row.toString() + "\n") } }
        }.build())
        return robot
    }

    private fun configure(edit: (JSONObject) -> Unit) {
        val current = api.json("/v1/robots/$robotId/config")
        val config = current.getJSONObject("config")
        edit(config)
        val id = UUID.randomUUID().toString()
        api.json("/v1/robots/$robotId/config", "POST", JSONObject()
            .put("requestId", id).put("expectedVersion", current.getInt("version")).put("config", config))
        waitFor("配置实际生效") { api.json("/v1/commands/$id").getString("state") == "applied" }
    }

    private fun record(event: String, value: Long = 0) {
        results.appendText(JSONObject().put("event", event).put("value", value)
            .put("wallTimeMs", System.currentTimeMillis()).toString() + "\n")
    }

    @Test fun realQuietBoundaryAndQuota() {
        check(!backup.exists()) { "上次测试配置尚未恢复，先运行 restoreConfiguration" }
        val zone = ZoneId.of("Asia/Shanghai")
        check(ZonedDateTime.now(zone).hour < 23 || ZonedDateTime.now(zone).minute < 50) {
            "本用例不跨午夜运行；午夜分段另由核心边界测试验证"
        }
        vault.save("identity", JSONObject().put("mode", "robot"))
        results.writeText("")
        marker.writeText("starting")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var robot: RobotRuntime? = null
            scenario.onActivity { robot = runtime(it) }
            fun onRobot(action: (RobotRuntime) -> Unit) = scenario.onActivity { action(robot!!) }
            val audio = context.getSystemService(AudioManager::class.java)
            waitFor("机器人联网") { robot!!.online }
            backup.writeText(api.json("/v1/robots/$robotId/config").getJSONObject("config").toString())
            try {
                configure {
                    it.getJSONObject("policy").put("intervals", JSONArray()).put("manualBlocked", false)
                        .put("dailyMinutes", 0).put("mediaMinutes", 30).put("overrideUntil", 0)
                        .put("timezone", zone.id)
                    it.put("muted", false).put("cameraAllowed", true).put("listeningPlans", JSONArray())
                    it.getJSONObject("voice").put("volume", 0.12)
                }
                val resource = api.json("/v1/resources", "POST", JSONObject().put("kind", "song")
                    .put("draft", JSONObject().put("title", "时段额度原创测试音轨")))
                val rid = resource.getString("id")
                File(context.filesDir, "policy-boundary-resource").writeText(rid)
                api.upload("/v1/resources/$rid/assets?purpose=audio&expectedVersion=1", "original.mp3",
                    File(context.filesDir, "test-audio.mp3").readBytes(), "audio/mpeg")
                var current = api.json("/v1/resources/$rid")
                current.getJSONObject("draft").put("complete", true)
                current = api.json("/v1/resources/$rid", "PUT", JSONObject()
                    .put("expectedVersion", current.getInt("draft_version")).put("draft", current.getJSONObject("draft")))
                current.getJSONObject("draft").put("auditioned", true)
                current = api.json("/v1/resources/$rid", "PUT", JSONObject()
                    .put("expectedVersion", current.getInt("draft_version")).put("draft", current.getJSONObject("draft")))
                api.json("/v1/resources/$rid/publish", "POST", JSONObject()
                    .put("expectedVersion", current.getInt("draft_version")).put("requestId", UUID.randomUUID().toString()))
                val playlist = api.json("/v1/playlists", "POST", JSONObject().put("name", "时段额度验证")
                    .put("resources", JSONArray(List(30) { rid }))).getString("id")
                File(context.filesDir, "policy-boundary-playlist").writeText(playlist)
                val start = ZonedDateTime.now(zone).plusSeconds(100).withSecond(0).withNano(0)
                val end = start.plusMinutes(1)
                configure {
                    it.getJSONObject("policy").put("intervals", JSONArray().put(JSONObject()
                        .put("days", JSONArray(listOf(start.dayOfWeek.value)))
                        .put("start", start.toLocalTime().toString()).put("end", end.toLocalTime().toString())))
                }
                onRobot { it.playPlaylist(playlist) }
                waitFor("跨边界前真实媒体播放") { audio.isMusicActive }
                waitFor("禁用边界关闭全部采集与播放", 110000) {
                    robot!!.state == SessionState.BLOCKED && !robot!!.micActive && !robot!!.cameraActive && !audio.isMusicActive
                }
                val stopDelay = System.currentTimeMillis() - start.toInstant().toEpochMilli()
                assertTrue("实际分钟边界停止误差", stopDelay in 0..1500)
                record("quiet-boundary-stop-delay-ms", stopDelay)
                marker.writeText("disconnect")
                waitFor("宿主已移除USB网络") { marker.readText().trim() == "offline" }
                onRobot { it.wake() }
                Thread.sleep(500)
                assertEquals(SessionState.BLOCKED, robot!!.state)
                assertFalse(audio.isMusicActive)
                record("offline-quiet-refuses-wake")
                waitFor("禁用结束恢复本地待机", 70000) { robot!!.state == SessionState.STANDBY && robot!!.micActive }
                assertTrue(System.currentTimeMillis() >= end.toInstant().toEpochMilli())
                Thread.sleep(1000)
                assertFalse("解禁不自动续播", audio.isMusicActive)
                assertFalse(robot!!.cameraActive)
                record("offline-quiet-end-no-autoplay")
                marker.writeText("reconnect")
                waitFor("宿主恢复USB网络") { marker.readText().trim() == "online" }
                waitFor("重新联网") { robot!!.online }
                val ledger = RobotRuntime::class.java.getDeclaredField("ledger")
                    .apply { isAccessible = true }.get(robot) as UsageLedger
                val used = ledger.used(System.currentTimeMillis(), zone)
                val quotaMinutes = (used / 60000 + 2).toInt()
                check(quotaMinutes <= 240) { "测试身份今日累计过高，请换新测试身份后重新运行，不清除既有账本" }
                configure {
                    it.getJSONObject("policy").put("intervals", JSONArray()).put("dailyMinutes", quotaMinutes)
                }
                val budget = quotaMinutes * 60000L - ledger.used(System.currentTimeMillis(), zone)
                val began = SystemClock.elapsedRealtime()
                record("quota-budget-ms", budget)
                onRobot { it.playPlaylist(playlist) }
                waitFor("额度测试真实播放") { audio.isMusicActive }
                var lastQuotaSample = 0L
                var lastAudibleAt = SystemClock.elapsedRealtime()
                val audibleTracks = mutableSetOf<Long>()
                waitFor("真实使用耗尽额度", budget + 5000) {
                    if (SystemClock.elapsedRealtime() - lastQuotaSample >= 1000) {
                        var sample = JSONObject()
                        onRobot { currentRobot ->
                            fun field(name: String) = RobotRuntime::class.java.getDeclaredField(name)
                                .apply { isAccessible = true }.get(currentRobot)
                            val track = field("track") as? android.media.AudioTrack
                            val job = field("job") as? kotlinx.coroutines.Job
                            sample = JSONObject().put("event", "quota-sample")
                                .put("elapsedMs", SystemClock.elapsedRealtime() - began)
                                .put("usedMs", ledger.used(System.currentTimeMillis(), zone))
                                .put("targetMs", quotaMinutes * 60000L)
                                .put("configuredMinutes", currentRobot.config.getJSONObject("policy").getInt("dailyMinutes"))
                                .put("state", currentRobot.state.name).put("microphone", currentRobot.micActive)
                                .put("musicActive", audio.isMusicActive).put("foreground", currentRobot.foreground)
                                .put("keywordWakeCount", currentRobot.keywordWakeCount)
                                .put("keywordAction", currentRobot.lastKeywordAction)
                                .put("playbackSerial", field("playbackSerial"))
                                .put("trackPresent", track != null)
                                .put("trackHead", runCatching { track?.playbackHeadPosition }.getOrNull())
                                .put("trackState", runCatching { track?.playState }.getOrNull())
                                .put("jobActive", job?.isActive).put("jobCancelled", job?.isCancelled)
                                .put("queueSize", (field("queue") as java.util.ArrayDeque<*>).size)
                                .put("online", currentRobot.online)
                                .put("wallTimeMs", System.currentTimeMillis())
                        }
                        results.appendText(sample.toString() + "\n")
                        if (sample.getBoolean("musicActive") && sample.optLong("trackHead") > 0) {
                            lastAudibleAt = SystemClock.elapsedRealtime()
                            audibleTracks.add(sample.getLong("playbackSerial"))
                        } else if (sample.getString("state") != SessionState.BLOCKED.name) {
                            // 防止“清单已卡住但仍在计费”被误判为额度验收通过。
                            assertTrue("额度计时过程中音轨不得静默卡住超过3秒",
                                SystemClock.elapsedRealtime() - lastAudibleAt <= 3000)
                        }
                        lastQuotaSample = SystemClock.elapsedRealtime()
                    }
                    robot!!.state == SessionState.BLOCKED && !robot!!.micActive && !audio.isMusicActive
                }
                assertTrue("不能提前耗尽额度", SystemClock.elapsedRealtime() - began >= budget - 500)
                assertTrue("额度阶段必须实际跨越至少两条音轨", audibleTracks.size >= 2)
                record("quota-audible-track-count", audibleTracks.size.toLong())
                assertTrue(ledger.used(System.currentTimeMillis(), zone) >= quotaMinutes * 60000L)
                Thread.sleep(1500) // 等待正常持久化周期；不直接修改账本。
                record("quota-exhausted-ms", SystemClock.elapsedRealtime() - began)
            } finally { onRobot { it.stop() } }
        }
    }

    @Test fun quotaAfterProcessRestart() {
        check(backup.exists())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var robot: RobotRuntime? = null
            scenario.onActivity { robot = runtime(it) }
            waitFor("离线新进程保持额度禁用") { robot!!.state == SessionState.BLOCKED }
            val reason = RobotRuntime::class.java.getDeclaredField("lastPolicy")
                .apply { isAccessible = true }.get(robot)
            assertEquals("必须是持久化额度生效，不能用其他禁用原因冒充", Permission.QUOTA, reason)
            scenario.onActivity { robot!!.wake() }
            Thread.sleep(1500)
            assertEquals(SessionState.BLOCKED, robot!!.state)
            assertFalse(robot!!.micActive)
            assertFalse(robot!!.cameraActive)
            assertFalse(context.getSystemService(AudioManager::class.java).isMusicActive)
            record("offline-process-restart-quota-retained")
        }
    }

    @Test fun restoreConfiguration() {
        if (!backup.exists()) return
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var robot: RobotRuntime? = null
            scenario.onActivity { robot = runtime(it) }
            waitFor("恢复时联网") { robot!!.online }
            val original = JSONObject(backup.readText())
            configure { value ->
                value.keys().asSequence().toList().forEach { value.remove(it) }
                original.keys().forEach { key -> value.put(key, original.get(key)) }
            }
            File(context.filesDir, "policy-boundary-playlist").takeIf { it.exists() }?.let {
                api.json("/v1/playlists/${it.readText()}", "DELETE"); it.delete()
            }
            File(context.filesDir, "policy-boundary-resource").takeIf { it.exists() }?.let {
                api.json("/v1/resources/${it.readText()}/unlist", "POST", JSONObject()); it.delete()
            }
            backup.delete()
            marker.delete()
            record("configuration-restored")
        }
    }
}
