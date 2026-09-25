package org.familyrobot.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookEntryDesignTest {
    private val inst=InstrumentationRegistry.getInstrumentation();private val context=inst.targetContext
    private val device=UiDevice.getInstance(inst);private val vault=Vault(context)
    private val dir=File(context.filesDir,"book-entry-design").apply{mkdirs()}
    init{Configurator.getInstance().setWaitForIdleTimeout(1000).setWaitForSelectorTimeout(1200)}
    private fun capture(name:String){fresh();device.takeScreenshot(File(dir,"$name.png"));device.dumpWindowHierarchy(File(dir,"$name.xml"))}
    // 部分新系统保留已重组的 Compose 无障碍节点；查询前刷新缓存，避免把旧页面当成当前页面。
    private fun fresh(){if(android.os.Build.VERSION.SDK_INT>=33)inst.uiAutomation.clearCache()}
    private fun find(text:String):UiObject2{
        fun match():UiObject2?{fresh();return device.findObject(By.text(text))?:device.findObject(By.desc(text))}
        match()?.let{return it}
        fun scroll(up:Boolean):Boolean {
            fun locate(n:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo? {
                if(n.isScrollable&&n.packageName==context.packageName)return n
                for(i in 0 until n.childCount)n.getChild(i)?.let{locate(it)?.let{return it}}
                return null
            }
            val node=inst.uiAutomation.rootInActiveWindow?.let{locate(it)}?:return false
            val moved=node.performAction(if(up)8192 else 4096)
            Thread.sleep(1000)
            fresh()
            return moved
        }
        for(i in 0..10){if(!scroll(true))break;match()?.let{return it}}
        for(i in 0..12){if(!scroll(false))break;match()?.let{return it}}
        capture("missing");error("未找到 $text")
    }
    private fun tap(text:String){find(text).click();Thread.sleep(250)}
    private fun await(label:String,block:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+45000;while(android.os.SystemClock.elapsedRealtime()<end){fresh();if(block())return;Thread.sleep(200)};capture("failed");error(label)}
    private fun sandbox(block:(Api)->Unit){
        val connection=JSONObject(File(context.filesDir,"book-entry-connection.json").readText())
        check(connection.getString("address").endsWith(":8878")){"只允许隔离服务"}
        val before=listOf("parent","identity").associateWith{vault.get(it)}
        val appearance=context.getSharedPreferences("appearance",0);val theme=appearance.getString("theme",null)
        try{vault.save("parent",connection);vault.save("identity",JSONObject().put("mode","parent"));block(Api(connection))}
        finally{for((key,value)in before)if(value==null)vault.remove(key)else vault.save(key,value);appearance.edit().apply{if(theme==null)remove("theme")else putString("theme",theme)}.commit()}
    }
    @Test fun entryHierarchyAndRealImports()=sandbox{api->
        var pick="cover";var picks=0
        val monitor=object:Instrumentation.ActivityMonitor(){
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?{
                if(intent.action!=Intent.ACTION_OPEN_DOCUMENT)return null
                picks++
                val folder=File(context.cacheDir,"book-capture").apply{mkdirs()}
                val file=File(folder,when(pick){"cover"->"book-cover.jpg";"audio"->"book-audio.wav";else->"book-text.txt"})
                when(pick){
                    "cover"->{val bitmap=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);val paint=Paint().apply{color=Color.BLACK;textSize=45f};canvas.drawText("Original test book",30f,130f,paint);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)};bitmap.recycle()}
                    "audio"->file.writeBytes(AudioInput.wav(ByteArray(32000)))
                    else->file.writeText("The little rabbit found a red ball.")
                }
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.capture",file)
                return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        inst.addMonitor(monitor)
        try{for(theme in listOf("light","dark")){
            context.getSharedPreferences("appearance",0).edit().putString("theme",theme).commit()
            val name="录入布局测试-$theme"
            val created=api.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject().put("title",name)))
            val rid=created.getString("id")
            fun draft()=api.json("/v1/resources/$rid").getJSONObject("draft")
            try{ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{
                await("家长首页"){device.hasObject(By.text("家长管理"))};tap("资源库");tap(name);tap("编辑工作草稿")
                find("名称");find("拍摄或导入封面");capture("$theme-entry")
                assertFalse(device.hasObject(By.text("连续拍摄书页")))
                assertTrue(device.hasObject(By.text("保存草稿")))
                val titleBounds=find("1 录入").visibleBounds
                val fields=device.findObjects(By.clazz("android.widget.EditText"))
                assertEquals(2,fields.size)
                tap("中文");tap("英文")
                tap("书目信息与来源");find("出版社");capture("$theme-metadata");tap("书目信息与来源")
                assertEquals("步骤栏不能随内容滚走",titleBounds.top,find("1 录入").visibleBounds.top)
                assertTrue(device.hasObject(By.text("保存草稿")))
                tap("拍摄或导入封面");find("拍摄封面");capture("$theme-cover")
                pick="cover";tap("从相册选择封面")
                await("封面完成"){draft().optString("coverAsset").isNotEmpty()&&device.hasObject(By.textContains("录入完成"))}
                device.pressBack();find("封面已录入 · 可替换")
                assertEquals("en",draft().getString("language"))
                tap("录入正文");find("单页拍摄");find("连续拍摄书页");find("批量选择书页图片");capture("$theme-import")
                pick="text";tap("导入 PDF / 文本")
                await("文字导入完成"){draft().getJSONArray("pages").length()==1&&device.hasObject(By.textContains("录入完成"))}
                tap("查看导入任务");find("本书导入进度");find("素材 1 · 待校对");capture("$theme-jobs");tap("刷新导入结果");find("素材 1 · 待校对")
                device.pressBack();find("选择一种方式录入正文");device.pressBack();find("图书工作草稿")
                tap("导入已有音频");find("选择音频文件");capture("$theme-audio")
                pick="audio";tap("选择音频文件")
                await("音频导入完成"){draft().optString("audioAsset").isNotEmpty()&&device.hasObject(By.textContains("录入完成"))}
                find("试听已上传原音频");device.pressBack();find("音频已录入 · 可替换")
                tap("录入正文");tap("手工添加文字页");find("准备朗读的正文");capture("$theme-manual")
                device.pressBack();find("朗读顺序 2 · 无印刷页码")
                tap("保存草稿");await("保存两页"){draft().getJSONArray("pages").length()==2}
                tap("1 录入");find("图书工作草稿");capture("$theme-entry-imported")
                tap("下一步：逐页校对");find("朗读顺序 1 · 无印刷页码");capture("$theme-review")
                tap("3 试听与发布");find("校对进度");find("去校对");assertFalse(find("发布给机器人").parent.isEnabled)
            }}finally{api.json("/v1/resources/$rid","DELETE")}
        };assertEquals(6,picks)}finally{inst.removeMonitor(monitor)}
    }
    @Test fun cameraBatchAndImagePickerKeepPageOrder()=sandbox{
        BookImportUiTest().sequentialCameraAndPickerImportThenSinglePageRetry()
    }
}
