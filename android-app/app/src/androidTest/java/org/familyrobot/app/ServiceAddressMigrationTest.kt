package org.familyrobot.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/** 显式运行的维护工具：只迁移同一受信服务的地址，不重新登记或修改 PIN。 */
@RunWith(AndroidJUnit4::class)
class ServiceAddressMigrationTest {
    @Test fun migrateExistingTrustedService() {
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("serviceAddress")
        assumeTrue("只有显式指定 serviceAddress 才迁移", !address.isNullOrBlank())
        val uri = URI(address!!)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty())
        val vault = Vault(InstrumentationRegistry.getInstrumentation().targetContext)
        val robot = checkNotNull(vault.get("robot")) { "需要已登记的机器人" }
        val originals = listOf("robot", "parent").mapNotNull { role ->
            vault.get(role)?.takeIf { it.getString("serviceId") == robot.getString("serviceId") }
                ?.let { role to it }
        }
        val replacements = originals.map { (role, old) ->
            val changed = JSONObject(old.toString()).put("address", address)
            val api = Api(changed)
            // 先通过原证书指纹、服务 ID 和现有令牌验证新地址，再保存。
            try {
                api.checkIdentity()
                val id = changed.getString(if (role == "robot") "deviceId" else "robotId")
                api.json("/v1/robots/$id/config", timeoutMs = 5000)
            } finally { api.cancel() }
            role to changed
        }
        try {
            replacements.forEach { (role, changed) -> vault.save(role, changed) }
            originals.forEach { (role, old) ->
                val saved = checkNotNull(vault.get(role))
                assertEquals(address, saved.getString("address"))
                saved.put("address", old.getString("address"))
                assertEquals(old.toString(), saved.toString())
            }
        } catch (error: Throwable) {
            originals.forEach { (role, old) -> vault.save(role, old) }
            throw error
        }
    }
}
