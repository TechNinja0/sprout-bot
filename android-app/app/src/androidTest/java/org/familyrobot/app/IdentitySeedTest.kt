package org.familyrobot.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** 仅设置已登记测试身份，供主机真正终止进程后重复冷启动。 */
@RunWith(AndroidJUnit4::class)
class IdentitySeedTest {
    @Test fun selectRegisteredTestIdentity() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val mode=InstrumentationRegistry.getArguments().getString("role")!!
        require(mode in setOf("robot","parent"))
        val vault=Vault(context);assertNotNull(vault.get(mode))
        vault.save("identity",JSONObject().put("mode",mode))
    }
}
