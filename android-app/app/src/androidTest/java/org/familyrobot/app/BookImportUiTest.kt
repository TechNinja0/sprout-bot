package org.familyrobot.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 系统相机/选图返回原创夹具，验证真实UI/URI上传/服务OCR；不作为实际纸书拍摄质量验收。 */
@RunWith(AndroidJUnit4::class)
class BookImportUiTest {
    init { Configurator.getInstance().setWaitForIdleTimeout(200).setWaitForSelectorTimeout(2000) }
    @Test fun sequentialCameraAndPickerImportThenSinglePageRetry() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation);val vault=Vault(context);val identity=vault.get("identity")
        val api=Api(vault.get("parent")!!);val captureCount=AtomicInteger();val pickerCount=AtomicInteger()
        val report=File(context.filesDir,"book-import-ui.jsonl").apply { writeText("") }
        fun record(event:String,body:JSONObject=JSONObject()) { report.appendText(body.put("event",event).put("atMs",SystemClock.elapsedRealtime()).toString()+"\n") }
        fun fixture(uri:Uri,text:String) {
            val bitmap=Bitmap.createBitmap(1200,700,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=64f }
            canvas.drawText(text,60f,180f,paint);canvas.drawText("The rabbit has a red ball.",60f,310f,paint)
            context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) };bitmap.recycle()
        }
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action==MediaStore.ACTION_IMAGE_CAPTURE) {
                    @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
                    fixture(uri,"Camera page ${captureCount.incrementAndGet()}")
                    return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent())
                }
                if(intent.action==Intent.ACTION_OPEN_DOCUMENT && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false)) {
                    pickerCount.incrementAndGet()
                    val folder=File(context.cacheDir,"book-capture").apply { mkdirs() }
                    val uris=(1..2).map { i -> FileProvider.getUriForFile(context,"${context.packageName}.capture",File(folder,"picker-$i.jpg")).also { fixture(it,"Picker page $i") } }
                    val clip=ClipData.newUri(context.contentResolver,"original fixtures",uris[0]);clip.addItem(ClipData.Item(uris[1]))
                    return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().apply { clipData=clip;addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
                }
                return null
            }
        }
        var rid=""
        fun waitFor(label:String,condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+45000
            while(!condition() && SystemClock.elapsedRealtime()<until)Thread.sleep(200)
            assertTrue(label,condition())
        }
        fun scroll(backward:Boolean):Boolean {
            fun locate(node:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo? {
                if(node.isScrollable && node.packageName==context.packageName)return node
                for(i in 0 until node.childCount)node.getChild(i)?.let { child -> locate(child)?.let { return it } }
                return null
            }
            val node=instrumentation.uiAutomation.rootInActiveWindow?.let { locate(it) } ?: return false
            val moved=node.performAction(if(backward)android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            var prior="";var stable=0
            for(attempt in 0..24) {
                Thread.sleep(120)
                val visible=try { device.findObjects(By.pkg(context.packageName).text(java.util.regex.Pattern.compile(".+"))).joinToString { it.text+":"+it.visibleBounds.toShortString() } }
                    catch(_:StaleObjectException) { prior="";stable=0;continue }
                stable=if(visible.isNotEmpty() && visible==prior)stable+1 else 0;prior=visible
                if(stable>=3)break
            }
            return moved
        }
        fun find(text:String):UiObject2 {
            device.waitForIdle()
            (device.findObject(By.text(text)) ?: device.findObject(By.desc(text)))?.let { return it }
            device.wait(Until.hasObject(By.clazz("android.widget.EditText")),5000)
            for(i in 0..20) { if(device.hasObject(By.text("录入图书")) || device.hasObject(By.text("家长管理")))break;if(!scroll(true))break }
            for(i in 0..28) {
                (device.findObject(By.text(text)) ?: device.findObject(By.desc(text)))?.let { return it }
                if(!scroll(false)) { Thread.sleep(300);(device.findObject(By.text(text)) ?: device.findObject(By.desc(text)))?.let { return it };break }
            }
            device.dumpWindowHierarchy(File(context.filesDir,"book-import-failure.xml"))
            device.takeScreenshot(File(context.filesDir,"book-import-failure.png"))
            error("界面按钮/字段：$text")
        }
        fun book()=api.json("/v1/resources/$rid")
        try {
            val created=api.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject().put("title","原创图书录入界面测试")
                .put("pages",JSONArray().put(JSONObject().put("id","manual-page").put("text","Already reviewed original page.").put("reviewed",true)))))
            rid=created.getString("id")
            vault.save("identity",JSONObject().put("mode","parent"));instrumentation.addMonitor(monitor)
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("家长管理")),10000))
            find("资源库").click();find("原创图书录入界面测试").click();find("编辑工作草稿").click()
            assertTrue(device.wait(Until.hasObject(By.text("录入图书")),10000));find("书目信息与来源").click();find("出版社")
            find("连续拍摄书页").click()
            assertTrue(device.wait(Until.hasObject(By.text("已拍摄 1 页")),10000));find("继续拍摄").click()
            assertTrue(device.wait(Until.hasObject(By.text("已拍摄 2 页")),10000));find("导入已拍书页").click()
            waitFor("两张相机夹具按序OCR") { book().getJSONObject("draft").getJSONArray("pages").length()==3 }
            waitFor("导入UI结束") { device.hasObject(By.textContains("录入完成：2 个文件")) }
            var pages=book().getJSONObject("draft").getJSONArray("pages")
            assertTrue(pages.getJSONObject(0).getBoolean("reviewed"));assertEquals("manual-page",pages.getJSONObject(0).getString("id"))
            assertTrue(pages.getJSONObject(1).getString("text").contains("Camera page 1"));assertTrue(pages.getJSONObject(2).getString("text").contains("Camera page 2"))
            assertFalse(pages.getJSONObject(1).getBoolean("reviewed"));record("camera-fixture-two-pages",JSONObject().put("captureCount",captureCount.get()))
            find("批量选择书页图片").click()
            waitFor("两张选图夹具按序OCR") { book().getJSONObject("draft").getJSONArray("pages").length()==5 }
            waitFor("选图UI结束") { device.hasObject(By.textContains("录入完成：2 个文件")) }
            pages=book().getJSONObject("draft").getJSONArray("pages")
            assertTrue(pages.getJSONObject(3).getString("text").contains("Picker page 1"));assertTrue(pages.getJSONObject(4).getString("text").contains("Picker page 2"))
            assertEquals(1,pickerCount.get());record("picker-fixture-two-pages")
            find("2 校对").click();find("录入位置 2 · 无印刷页码").click();find("重新识别本页").click()
            waitFor("单页重识别完成") { device.hasObject(By.textContains("本页识别：needs_review")) }
            pages=book().getJSONObject("draft").getJSONArray("pages")
            assertEquals(5,pages.length());assertTrue(pages.getJSONObject(0).getBoolean("reviewed"))
            assertTrue(pages.getJSONObject(1).getString("text").contains("Camera page 1"))
            assertEquals("draft",book().getString("status"));assertTrue(book().isNull("published_id"))
            device.takeScreenshot(File(context.filesDir,"book-import-editor.png"));record("page-retry-preserves-reviewed-neighbour")
        } finally {
            instrumentation.removeMonitor(monitor)
            device.pressHome()
            if(rid.isNotBlank())runCatching { api.json("/v1/resources/$rid","DELETE") }
            identity?.let { vault.save("identity",it) } ?: vault.remove("identity")
        }
    }
}
