package org.familyrobot.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

class Vault(context: Context) {
    private val prefs=context.getSharedPreferences("vault",Context.MODE_PRIVATE)
    private val alias="family-robot-v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generateKey()
        }
        return store.getKey(alias,null) as SecretKey
    }
    fun save(name:String,value:JSONObject) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val data=cipher.iv+cipher.doFinal(value.toString().toByteArray())
        check(prefs.edit().putString(name,Base64.encodeToString(data,Base64.NO_WRAP)).commit())
    }
    fun get(name:String):JSONObject? {
        val raw=prefs.getString(name,null) ?: return null
        return try {
            val data=Base64.decode(raw,Base64.NO_WRAP)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,data.copyOfRange(0,12))) }
            JSONObject(String(cipher.doFinal(data.copyOfRange(12,data.size))))
        } catch (_:Exception) { null }
    }
    fun remove(name:String) { prefs.edit().remove(name).commit() }
    fun hasPin()=get("pin")!=null
    fun setPin(pin:String) {
        require(pin.matches(Regex("[0-9]{6,12}"))) { "管理 PIN 需要6—12位数字" }
        val salt=ByteArray(16).also { SecureRandom().nextBytes(it) }
        save("pin",JSONObject().put("salt",Base64.encodeToString(salt,2)).put("hash",Base64.encodeToString(derive(pin,salt),2)).put("failures",0).put("until",0L))
    }
    private fun derive(pin:String,salt:ByteArray)=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(),salt,120000,256)).encoded
    fun verifyPin(pin:String):Boolean {
        val record=get("pin") ?: return false
        if (record.optLong("until")>System.currentTimeMillis()) return false
        val ok=MessageDigest.isEqual(derive(pin,Base64.decode(record.getString("salt"),2)),Base64.decode(record.getString("hash"),2))
        val failures=if(ok)0 else record.optInt("failures")+1
        record.put("failures",failures).put("until",if(failures>=5)System.currentTimeMillis()+300000 else 0L)
        save("pin",record);return ok
    }
}

fun robotScope(connection:JSONObject)=sha256((connection.getString("serviceId")+":"+connection.getString("deviceId")).toByteArray()).take(32)
fun robotKey(connection:JSONObject,name:String)="robot-"+robotScope(connection)+"-"+name
