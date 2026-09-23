package org.familyrobot.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

fun sha256(data:ByteArray)=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
class ApiHttpException(val status:Int,message:String):IOException(message)
class ProtocolMismatchException:IOException("服务协议不兼容，请升级到匹配版本的App和家庭服务")
class Api(val connection:JSONObject) {
    private val address=connection.getString("address").trimEnd('/')
    private val pin=connection.getString("certificateSha256").lowercase()
    private val client:OkHttpClient
    private val identityLock=Any()
    @Volatile private var identityCheckedAt=Long.MIN_VALUE
    init {
        if(connection.optInt("protocolVersion",1)!=1)throw ProtocolMismatchException()
        require(address.startsWith("https://") && pin.matches(Regex("[0-9a-f]{64}"))) { "连接材料需要 HTTPS 和完整证书指纹" }
        val trust=object:X509TrustManager {
            override fun checkClientTrusted(c:Array<out X509Certificate>?,a:String?)=throw java.security.cert.CertificateException()
            override fun checkServerTrusted(c:Array<out X509Certificate>?,a:String?) {
                val cert=c?.firstOrNull() ?: throw java.security.cert.CertificateException()
                cert.checkValidity()
                if(sha256(cert.encoded)!=pin) throw java.security.cert.CertificateException("证书与配对材料不一致")
            }
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
        }
        val ssl=SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),SecureRandom()) }
        client=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust)
            .hostnameVerifier { _,session -> (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { sha256(it.encoded)==pin }==true }
            .connectTimeout(4,TimeUnit.SECONDS).readTimeout(100,TimeUnit.SECONDS).callTimeout(110,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
    fun cancel()=client.dispatcher.cancelAll()
    fun raw(path:String,method:String="GET",body:RequestBody?=null,headers:Map<String,String> = emptyMap(),timeoutMs:Long?=null):ByteArray {
        if(path!="/health")synchronized(identityLock) {
            val now=android.os.SystemClock.elapsedRealtime()
            if(identityCheckedAt==Long.MIN_VALUE || now-identityCheckedAt>=15000)checkIdentity()
        }
        val builder=Request.Builder().url(address+path).method(method,body)
        connection.optString("token").takeIf { it.isNotEmpty() }?.let { builder.header("Authorization","Bearer $it") }
        headers.forEach { (key,value) -> builder.header(key,value) }
        val call=client.newCall(builder.build())
        timeoutMs?.let { call.timeout().timeout(it,TimeUnit.MILLISECONDS) }
        call.execute().use {
            val bytes=it.body?.bytes() ?: byteArrayOf()
            if(!it.isSuccessful) {
                val reason=runCatching { JSONObject(String(bytes)).optString("detail") }.getOrDefault("")
                throw ApiHttpException(it.code,"${it.code}：${reason.take(160)}")
            }
            if(it.header("X-Content-SHA256")?.let { hash -> hash!=sha256(bytes) }==true) throw IOException("音频校验失败")
            return bytes
        }
    }
    fun json(path:String,method:String="GET",body:JSONObject?=null,timeoutMs:Long?=null)=JSONObject(String(raw(path,method,body?.toString()?.toRequestBody("application/json".toMediaType()),timeoutMs=timeoutMs)))
    fun array(path:String,timeoutMs:Long?=null)=org.json.JSONArray(String(raw(path,timeoutMs=timeoutMs)))
    fun upload(path:String,name:String,data:ByteArray,type:String="application/octet-stream",headers:Map<String,String> = emptyMap(),timeoutMs:Long?=null)=JSONObject(String(raw(path,"POST",MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file",name,data.toRequestBody(type.toMediaType())).build(),headers,timeoutMs)))
    fun checkIdentity() {
        val health=json("/health",timeoutMs=5000)
        if(health.optInt("protocolVersion",-1)!=1)throw ProtocolMismatchException()
        check(health.getString("serviceId")==connection.getString("serviceId")) { "服务身份不匹配" }
        identityCheckedAt=android.os.SystemClock.elapsedRealtime()
    }
}
