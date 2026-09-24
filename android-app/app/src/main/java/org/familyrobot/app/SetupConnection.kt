package org.familyrobot.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

fun setupAddress(host:String,port:String):String {
    val number=port.trim().toIntOrNull()
    require(number!=null && number in 1..65535){"端口请输入 1—65535 之间的数字"}
    val input=host.trim()
    require(input.isNotEmpty() && !input.contains(Regex("[\\s/@?#]"))){"请输入电脑 IP 或主机名，不需要填写 https://"}
    val authority=if(input.contains(':')&&!input.startsWith('['))"[$input]" else input
    val url="https://$authority:$number".toHttpUrlOrNull()
    require(url!=null && url.username.isEmpty() && url.password.isEmpty()){ "电脑地址格式不正确" }
    return url.toString().trimEnd('/')
}

/** First connection trusts the household service at the user-entered address.
 * Only public health metadata is discovered here; subsequent requests use Api's
 * fixed certificate and service identity checks, including future reconnects.
 */
fun discoverSetupConnection(address:String):JSONObject {
    val observedCertificate=AtomicReference<X509Certificate>()
    val trust=object:X509TrustManager {
        override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?)=throw java.security.cert.CertificateException()
        override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?){
            val cert=chain?.firstOrNull() ?: throw java.security.cert.CertificateException()
            cert.checkValidity();observedCertificate.set(cert)
        }
        override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
    }
    val ssl=SSLContext.getInstance("TLS").apply{init(null,arrayOf(trust),SecureRandom())}
    val client=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust).hostnameVerifier{_,_->true}
        .followRedirects(false).followSslRedirects(false).connectTimeout(4,TimeUnit.SECONDS).callTimeout(6,TimeUnit.SECONDS).build()
    try {
        client.newCall(Request.Builder().url("$address/health").build()).execute().use{response->
            require(response.isSuccessful){"家庭服务未正常响应，请检查电脑服务是否启动"}
            val cert=observedCertificate.get() ?: error("未获取到服务器证书")
            val health=JSONObject(response.body?.byteStream()?.use{readLimited(it,16384).decodeToString()} ?: "{}")
            require(health.optInt("protocolVersion")==1){"家庭服务版本不兼容，请更新电脑端"}
            require(health.optBoolean("addressSetup") && health.optBoolean("automaticSetup")){"电脑端暂不支持地址连接，请更新家庭服务，或使用备用连接文件"}
            return JSONObject().put("address",address).put("serviceId",health.getString("serviceId"))
                .put("certificateSha256",sha256(cert.encoded)).put("protocolVersion",1)
        }
    } finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
}
