package org.familyrobot.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookReviewFlowTest {
    private val inst=InstrumentationRegistry.getInstrumentation();private val context=inst.targetContext
    private val device=UiDevice.getInstance(inst);private val dir=File(context.filesDir,"book-review-design").apply{mkdirs()}
    private fun fresh(){if(android.os.Build.VERSION.SDK_INT>=33)inst.uiAutomation.clearCache()}
    private fun find(text:String):UiObject2 {
        fun match():UiObject2?{fresh();return device.findObject(By.text(text))?:device.findObject(By.desc(text))}
        repeat(5){match()?.let{return it};Thread.sleep(150)}
        fun scroll(up:Boolean):Boolean {
            fresh()
            fun locate(n:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo?{
                if(n.isScrollable&&n.packageName==context.packageName)return n
                for(i in 0 until n.childCount)n.getChild(i)?.let{locate(it)?.let{return it}};return null
            }
            val n=inst.uiAutomation.rootInActiveWindow?.let{locate(it)}?:return false
            val moved=n.performAction(if(up)8192 else 4096);Thread.sleep(450);return moved
        }
        for(i in 0..12){if(!scroll(true))break;match()?.let{return it}}
        for(i in 0..16){if(!scroll(false))break;match()?.let{return it}}
        capture("missing");error("未找到 $text")
    }
    private fun tap(text:String){find(text).click();Thread.sleep(250)}
    private fun check(text:String){val node=find(text);(if(node.isCheckable)node else node.parent).click();Thread.sleep(250)}
    private fun edit(label:String,text:String){
        fun field():UiObject2{fresh();var node=device.findObject(By.desc(label));while(node.className!="android.widget.EditText")node=node.parent;return node}
        find(label);field().click();Thread.sleep(450)
        field().text=text;Thread.sleep(350)
        assertEquals("输入内容应保留",text,field().text)
        device.pressBack();Thread.sleep(300)
        assertEquals("收起键盘后应保留",text,field().text)
    }

    private fun capture(name:String){fresh();device.takeScreenshot(File(dir,"$name.png"));device.dumpWindowHierarchy(File(dir,"$name.xml"))}
    private fun await(label:String,condition:()->Boolean){val end=System.currentTimeMillis()+35000;while(System.currentTimeMillis()<end){fresh();if(condition())return;Thread.sleep(200)};capture("failed");error(label)}
    @Test fun draftGatesAndWholePageChunks(){
        val page=JSONObject().put("text","重重"+"兔".repeat(1200)+"🌱").put("pronunciation",JSONObject().put("重重","虫虫"))
        val chunks=bookSpokenChunks(page)
        assertTrue(chunks.size>1);assertEquals("虫虫"+"兔".repeat(1200)+"🌱",chunks.joinToString(""));assertTrue(chunks.all{it.codePointCount(0,it.length)<=600})
        val draft=JSONObject().put("pages",JSONArray().put(JSONObject().put("id","p").put("text","").put("reviewed",true))).put("complete",true).put("audioAsset","audio")
        assertFalse(bookReadyToPublish("book",draft));draft.getJSONArray("pages").getJSONObject(0).put("skip",true);assertTrue(bookReadyToPublish("book",draft))
        val key=bookAuditionKey(draft);draft.put("auditioned",true);assertEquals(key,bookAuditionKey(draft));draft.put("excerpt","changed");assertNotEquals(key,bookAuditionKey(draft))
    }
    @Test fun reviewAuditionAndPublishInBothThemes(){
        Configurator.getInstance().setWaitForIdleTimeout(250).setWaitForSelectorTimeout(1500)
        val connection=JSONObject(File(context.filesDir,"book-entry-connection.json").readText());check(connection.getString("address").endsWith(":8878"))
        val vault=Vault(context);val before=listOf("parent","identity").associateWith{vault.get(it)}
        val prefs=context.getSharedPreferences("appearance",0);val oldTheme=prefs.getString("theme",null);val api=Api(connection)
        try{
            vault.save("parent",connection);vault.save("identity",JSONObject().put("mode","parent"))
            for(theme in listOf("light","dark")){
                prefs.edit().putString("theme",theme).commit()
                val title="校对发布测试-$theme"
                val pages=JSONArray().put(JSONObject().put("id","one").put("text","重重"+"小兔回家。".repeat(130)).put("pronunciation",JSONObject().put("重重","虫虫")))
                    .put(JSONObject().put("id","blank").put("text",""))
                val resource=api.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject().put("title",title).put("complete",true).put("pages",pages)))
                val rid=resource.getString("id");fun detail()=api.json("/v1/resources/$rid")
                try{ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{
                    await("家长首页"){device.hasObject(By.text("家长管理"))};tap("资源库");tap(title);tap("编辑工作草稿");tap("2 校对")
                    find("0 / 2 页已校对");capture("$theme-list");tap("录入位置 1 · 无印刷页码")
                    find("准备朗读的正文");assertFalse(device.hasObject(By.text("重新识别本页")));capture("$theme-page")
                    tap("源稿对照");find("本页来自文本或手工录入，没有源图。");tap("源稿对照")
                    tap("试听本页");await("完整本页试听"){device.hasObject(By.textContains("本页试听完成"))}
                    assertEquals("draft",detail().getString("status"));assertFalse(detail().getJSONObject("draft").getJSONArray("pages").getJSONObject(0).optBoolean("reviewed"))
                    tap("保存并确认本页");await("跳到下一页"){device.hasObject(By.textContains("录入位置 2 / 2"))}
                    assertFalse(find("保存并确认本页").parent.isEnabled)
                    check("本页不朗读");tap("保存并确认本页");await("全部完成转到发布"){device.hasObject(By.text("试听与发布"))&&device.hasObject(By.text("校对进度"))}
                    find("2 / 2 页");capture("$theme-publish-top")
                    find("情感 / 朗读语气");find("补充语气描述（最多200字，可留空）");tap("高品质");tap("标准")
                    find("选择试听页");capture("$theme-publish-preview")
                    tap("试听所选页");await("发布页试听完成"){device.hasObject(By.textContains("试听完成，可重播"))}
                    assertFalse(find("发布给机器人").parent.isEnabled)
                    check("已试听并确认声音（可选）");check("已核对完整 / 节选范围");capture("$theme-confirmed");assertTrue(find("发布给机器人").parent.isEnabled)
                    // 改范围必须撤销旧试听和两项确认。
                    tap("完整收录");tap("节选");edit("节选范围（开始播放前朗读）","家庭选读第一章")
                    assertFalse(find("发布给机器人").parent.isEnabled)
                    tap("试听所选页");await("范围变更后重新试听"){device.hasObject(By.textContains("试听完成，可重播"))}
                    check("已试听并确认声音（可选）");check("已核对完整 / 节选范围")
                    tap("发布给机器人");await("发布固定版本"){detail().getString("status")=="published"}
                    val published=detail();assertFalse(published.isNull("published_id"));assertEquals("家庭选读第一章",published.getJSONObject("draft").getString("excerpt"));capture("$theme-published")
                    find("资源详情");tap("编辑工作草稿");tap("2 校对");tap("录入位置 1 · 无印刷页码");edit("准备朗读的正文","更正后的正文。")
                    find("录入位置 1 / 2 · 待校对");tap("保存，稍后校对")
                    await("保存修改撤销校对"){!detail().getJSONObject("draft").getJSONArray("pages").getJSONObject(0).getBoolean("reviewed")}
                    assertEquals(published.getString("published_id"),detail().getString("published_id"))
                }}finally{api.json("/v1/resources/$rid","DELETE")}
            }
        }finally{
            for((key,value)in before)if(value==null)vault.remove(key)else vault.save(key,value)
            prefs.edit().apply{if(oldTheme==null)remove("theme")else putString("theme",oldTheme)}.commit()
        }
    }
    @Test fun publishWithoutAuditionAndTransientSuccess(){
        Configurator.getInstance().setWaitForIdleTimeout(250)
        val connection=JSONObject(File(context.filesDir,"book-entry-connection.json").readText());kotlin.check(connection.getString("address").endsWith(":8878"))
        val vault=Vault(context);val before=listOf("parent","identity").associateWith{vault.get(it)};val api=Api(connection)
        var rid=""
        try{
            vault.save("parent",connection);vault.save("identity",JSONObject().put("mode","parent"))
            val created=api.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject().put("title","无需试听发布测试").put("complete",true)
                .put("pages",JSONArray().put(JSONObject().put("id","p").put("text","小兔回家。").put("reviewed",true)))))
            rid=created.getString("id")
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{
                await("首页"){device.hasObject(By.text("家长管理"))};tap("资源库");tap("无需试听发布测试");tap("编辑工作草稿");tap("3 试听与发布")
                assertFalse(find("发布给机器人").parent.isEnabled)
                check("已核对完整 / 节选范围")
                assertTrue("未试听也应可以发布",find("发布给机器人").parent.isEnabled)
                val publishButton=find("发布给机器人")
                inst.uiAutomation.executeAndWaitForEvent({publishButton.click()}, {event->
                    event.eventType==android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED&&event.text.any{it.toString().contains("发布成功")}
                },10000)
                await("无需试听发布成功"){api.json("/v1/resources/$rid").getString("status")=="published"}
                val result=api.json("/v1/resources/$rid");assertEquals("published",result.getString("status"));assertFalse(result.getJSONObject("draft").getBoolean("auditioned"))
                find("资源详情");find("编辑工作草稿");fresh();assertFalse(device.hasObject(By.text("3 试听与发布")))
                capture("optional-preview-published")
                Thread.sleep(3000);fresh();assertFalse(device.hasObject(By.text("发布成功")));assertFalse(device.hasObject(By.textContains("已发布固定版本；")))
                capture("optional-preview-toast-dismissed")
            }
        }finally{
            if(rid.isNotBlank())api.json("/v1/resources/$rid","DELETE")
            for((key,value)in before)if(value==null)vault.remove(key)else vault.save(key,value)
        }
    }

}
