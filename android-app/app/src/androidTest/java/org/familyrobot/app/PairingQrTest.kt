package org.familyrobot.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingQrTest {
    private fun material():String = parentPairingMaterial(
        JSONObject().put("protocolVersion",1).put("address","https://192.168.1.20:8765")
            .put("serviceId","service-测试家庭-1234567890")
            .put("certificateSha256","ab".repeat(32))
            .put("token","must-not-leave-robot").put("deviceId","private-device")
            .put("recoverySecret","private-recovery").put("purpose","recover"),
        JSONObject().put("invite","short-lived-invitation-"+"x".repeat(43)).put("expiresIn",120)
    )

    @Test fun qrRoundTripPreservesCompleteInvitationAtPhoneDisplaySize() {
        val material=material()
        val original=pairingQrBitmap(material)
        val display=Bitmap.createScaledBitmap(original,360,360,false)
        try {
            assertEquals(material,decodePairingQr(original))
            assertEquals(material,decodePairingQr(display))
            assertEquals("pair",parseConnectionMaterial(decodePairingQr(display)).getString("purpose"))
        } finally { display.recycle();original.recycle() }
    }

    @Test fun qrContainsOnlyInvitationFieldsAndNeverRobotCredentials() {
        val value=JSONObject(material())
        assertEquals(setOf("protocolVersion","address","serviceId","certificateSha256","invite","purpose","expiresIn"),value.keys().asSequence().toSet())
        assertFalse(material().contains("must-not-leave-robot"))
        assertEquals(120,value.getInt("expiresIn"))
    }

    @Test fun unrelatedAndIncompleteQrContentsAreRejected() {
        for(value in listOf("","https://example.org","[]","{}","broken JSON",
            JSONObject(material()).put("invite",JSONObject.NULL).toString(),
            JSONObject(material()).put("invite",123).toString())) {
            assertTrue("Invalid connection material must be rejected",runCatching { parseConnectionMaterial(value) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
