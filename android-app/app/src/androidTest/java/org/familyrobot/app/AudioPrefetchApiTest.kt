package org.familyrobot.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 模拟器真实 OkHttp 调度与取消；响应由拦截器提供，不代替局域网/扬声器验收。 */
@RunWith(AndroidJUnit4::class)
class AudioPrefetchApiTest {
    private fun api(handler:(okhttp3.Interceptor.Chain)->Response):Api {
        val api=Api(JSONObject().put("address","https://127.0.0.1:9")
            .put("certificateSha256","0".repeat(64)).put("serviceId","audio-prefetch-test"))
        Api::class.java.getDeclaredField("identityCheckedAt").apply { isAccessible=true }.set(api,SystemClock.elapsedRealtime())
        val field=Api::class.java.getDeclaredField("client").apply { isAccessible=true }
        val client=field.get(api) as OkHttpClient
        field.set(api,client.newBuilder().addInterceptor { handler(it) }.build())
        return api
    }
    private fun response(chain:okhttp3.Interceptor.Chain,code:Int,body:String,hash:String?=null):Response {
        val builder=Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body.toResponseBody())
        if(hash!=null)builder.header("X-Content-SHA256",hash)
        return builder.build()
    }
    @Test fun cancellingLookaheadCancelsOnlyItsCall() = runBlocking {
        val started=CountDownLatch(1)
        val stopped=CountDownLatch(1)
        val api=api { chain ->
            if(chain.request().url.encodedPath=="/next") {
                started.countDown()
                val deadline=SystemClock.elapsedRealtime()+5000
                while(!chain.call().isCanceled() && SystemClock.elapsedRealtime()<deadline)Thread.sleep(10)
                assertTrue("暂停取消了对应HTTP请求",chain.call().isCanceled())
                stopped.countDown()
                throw IOException("cancelled")
            }
            response(chain,200,"current",sha256("current".toByteArray()))
        }
        try {
            val job=launch { api.audio("/next") }
            assertTrue(withContext(Dispatchers.IO) { started.await(3,TimeUnit.SECONDS) })
            assertEquals("current",String(api.audio("/current")))
            job.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { stopped.await(3,TimeUnit.SECONDS) })
            assertEquals("current",String(api.audio("/current")))
        } finally { api.cancel() }
    }
    @Test fun checksumAndRevocationErrorsArePreserved() = runBlocking {
        val api=api { chain ->
            if(chain.request().url.encodedPath=="/revoked")response(chain,409,"{\"detail\":\"资源已下架\"}")
            else response(chain,200,"broken","0".repeat(64))
        }
        try {
            try { api.audio("/revoked");fail("撤回不能回退播放") }
            catch(error:ApiHttpException) { assertEquals(409,error.status) }
            try { api.audio("/broken");fail("损坏音频不能播放") }
            catch(error:IOException) { assertTrue(error.message.orEmpty().contains("校验")) }
        } finally { api.cancel() }
    }
}
