package org.familyrobot.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID

/** 仅测试包使用；原配置加密保存在本机，ACK 后才删除，进程被杀后可重试。 */
internal class StabilityConfiguration(context: Context) {
    private val vault = Vault(context)
    private val robot = vault.get("robot")!!
    private val api = Api(vault.get("parent")!!)
    private val path = "/v1/robots/${robot.getString("deviceId")}/config"
    private val key = "stability-backup-${robot.getString("serviceId")}-${robot.getString("deviceId")}"
    fun current(): JSONObject = api.json(path).getJSONObject("config")
    fun saveOriginal() {
        check(vault.get(key) == null) { "上次长测配置尚未恢复，先执行恢复" }
        vault.save(key, current())
    }
    fun apply(value: JSONObject) {
        val current = api.json(path)
        val id = UUID.randomUUID().toString()
        api.json(path, "POST", JSONObject().put("requestId", id)
            .put("expectedVersion", current.getInt("version")).put("config", value))
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (api.json("/v1/commands/$id").getString("state") != "applied") {
            check(SystemClock.elapsedRealtime() < deadline) { "恢复配置未收到机器人ACK" }
            Thread.sleep(100)
        }
    }
    fun restore(): Boolean {
        val original = vault.get(key) ?: return false
        apply(original)
        vault.remove(key)
        return true
    }
}
