package org.familyrobot.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FullUiRegressionTest {
    private val inst=InstrumentationRegistry.getInstrumentation();private val context=inst.targetContext
    private val device=UiDevice.getInstance(inst);private val vault=Vault(context)
    init { Configurator.getInstance().setWaitForIdleTimeout(200).setWaitForSelectorTimeout(2000) }
    private val out=File(context.filesDir,"full-ui-regression").apply{mkdirs()}
    private fun capture(name:String){device.takeScreenshot(File(out,"$name.png"));device.dumpWindowHierarchy(File(out,"$name.xml"))}
    private fun find(text:String):UiObject2{
        fun match()=device.findObject(By.text(text)) ?: device.findObject(By.desc(text))
        repeat(10){match()?.let{return it};device.findObject(By.scrollable(true))?.scroll(Direction.UP,.9f);Thread.sleep(100)}
        repeat(20){match()?.let{return it};device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.65f);Thread.sleep(120)}
        capture("missing");error("未找到 $text")
    }
    private fun tap(text:String){find(text).click();Thread.sleep(350)}
    private fun back(){device.pressBack();Thread.sleep(350)}
    private fun await(label:String,seconds:Int=15,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+seconds*1000;while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(150)};capture("failure");assertTrue(label,condition())}
    private fun parent():ActivityScenario<MainActivity>{check(vault.get("parent")!!.getString("address").endsWith(":8876")){"仅允许隔离验收服务"};vault.save("identity",JSONObject().put("mode","parent"));return ActivityScenario.launch(Intent(context,MainActivity::class.java))}
    private fun fields()=device.findObjects(By.clazz("android.widget.EditText"))
    @Test fun parentEveryRouteAndCollectionMutations(){
        parent().use{
            await("家长首页"){device.hasObject(By.text("家长管理"))};capture("parent-home")
            tap("查看连接");find("身份验证");capture("parent-connection");back();tap("设置");capture("parent-settings")
            val routes=listOf("孩子与成长" to "基准年龄（3—17）","声音" to "中文声音","隐私与权限" to "允许会话内看图","对话记录保存" to "保存后续陪伴对话","使用摘要" to "待审核偏好：0 条","数据与备份" to "导出资源库备份","绑定与恢复" to "忘记 PIN 或手机丢失","本机身份" to "机器人身份","唤醒与互动" to "新的唤醒词（2—6个汉字）","AI 提示词" to "默认提示词（最多4000字）")
            for((entry,marker) in routes){tap(entry);find(marker);capture("parent-$entry");back()}
            tap("诊断与服务能力");tap("刷新脱敏诊断");find("服务分项能力");capture("parent-maintenance");back()
            tap("检查与调试");find("机器人硬件检查需在机器人手机上");capture("parent-checks");tap("调试");find("当前调试会话");capture("parent-debug");back()
            tap("数据与备份");tap("空间与离线副本");find("刷新下载状态");capture("parent-storage");back();back()
            tap("偏好与记忆");find("此分类暂无偏好");capture("memories-empty");tap("添加明确偏好");fields()[0].text="喜欢小猫";tap("添加为待审核");find("喜欢小猫");tap("喜欢小猫");capture("memory-review");tap("修改并批准");tap("已批准");find("喜欢小猫");tap("喜欢小猫");tap("删除偏好");find("确认删除？");tap("取消");back();back()
            tap("主题颜色");tap("浅色");capture("theme-light");back();capture("parent-settings-light");tap("主题颜色");tap("深色");capture("theme-dark");back();back()
            tap("播放清单");capture("playlists-empty");tap("创建播放清单");find("没有已发布资源");fields()[0].text="未保存清单";back();find("有未保存修改");tap("继续编辑");back();tap("放弃并离开");tap("英语计划");find("还没有英语计划");assertFalse(find("添加计划").parent.isEnabled);capture("plans-empty");back();back()
            tap("使用安排");find("每日使用分钟，0表示不设额度");capture("schedule");tap("添加禁用时段");find("禁用时段 1");capture("schedule-interval");back();find("有未保存修改");tap("放弃并离开")
            tap("记录");find("陪伴对话");find("调试会话");capture("records");tap("使用记录");find("使用摘要");back()
        }
    }
    @Test fun resourceEditorStagesAndDirtyBack(){
        parent().use{
            tap("资源库");capture("library-empty");tap("导入任务");find("还没有导入任务");capture("import-jobs-empty");back();tap("电脑目录导入");find("返回资源库");capture("desktop-import");back();tap("阅读与识物指南");tap("读书与换内容");capture("book-guide");back();tap("添加资源");find("新资源名称");fields()[0].text="回归图书";tap("创建草稿");find("名称");find("语言");capture("book-entry")
            tap("语言"); // label itself is not the dropdown button, click its value
            tap("中文");tap("英文");find("英文");capture("book-language")
            tap("书目信息与来源");find("作者");capture("book-metadata");back();find("保存草稿修改？");tap("继续编辑")
            tap("2 校对");tap("手工添加文字页");find("校对原文");capture("book-proofread");tap("3 试听与发布");find("校对进度");find("去校对");capture("book-publish-blocked");assertFalse(find("发布给机器人").parent.isEnabled)
            back();find("保存草稿修改？");tap("保存后离开");find("资源详情");capture("resource-detail");back()
        }
    }
    @Test fun publishedPlaylistEditAndRecordsDetail(){
        val connection=vault.get("parent")!!;check(connection.getString("address").endsWith(":8876"));val api=Api(connection)
        val book=api.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject().put("title","已发布回归图书").put("complete",true).put("auditioned",true).put("pages",JSONArray().put(JSONObject().put("id","page-a").put("text","小猫回家了。").put("reviewed",true)))))
        api.json("/v1/resources/${book.getString("id")}/publish","POST",JSONObject().put("expectedVersion",book.getInt("draft_version")).put("requestId",UUID.randomUUID().toString()))
        parent().use{
            tap("播放清单");tap("创建播放清单");fields()[0].text="回归清单";tap("已发布回归图书"); // Text is not toggled unless checkbox row supports it; use checkbox explicitly
            device.findObject(By.clazz("android.widget.CheckBox"))?.let{if(!it.isChecked)it.click()}
            tap("保存清单");find("回归清单");capture("playlists-populated");tap("回归清单");fields()[0].text="修改后清单";tap("保存清单");find("修改后清单");tap("修改后清单");tap("删除清单");tap("取消");capture("playlist-edit");back();tap("英语计划");tap("添加计划");find("开始时间 HH:mm");capture("plan-editor");back();back();back()
            tap("资源库");tap("已发布回归图书");find("在机器人上播放");tap("清理手机副本");find("清理手机副本？");capture("resource-remove-confirm");tap("取消");back();back()
        }
    }
    @Test fun bookAuditionThenPublishAndPlanSave(){
        val connection=vault.get("parent")!!;check(connection.getString("address").endsWith(":8876"));val api=Api(connection)
        val book=api.json("/v1/resources","POST",JSONObject().put("kind","book").put("draft",JSONObject().put("title","试听发布回归").put("complete",true).put("pages",JSONArray().put(JSONObject().put("id","page-b").put("text","小猫回家了。").put("reviewed",true)))))
        parent().use{scenario->
            tap("资源库");tap("试听发布回归");tap("编辑工作草稿");tap("3 试听与发布");assertFalse(find("发布给机器人").parent.isEnabled)
            tap("试听当前页");await("实际试听合成成功",100){device.hasObject(By.text("请确认正文、页序和发音后勾选试听确认"))}
            find("已试听并确认可发布");val checks=device.findObjects(By.clazz("android.widget.CheckBox"));check(checks.isNotEmpty());checks.last().click();tap("发布给机器人")
            await("固定发布版生成",30){api.json("/v1/resources/${book.getString("id")}").optString("status")=="published"};capture("book-published")
            back();back();back()
            api.json("/v1/playlists","POST",JSONObject().put("name","计划回归清单").put("resources",JSONArray().put(book.getString("id"))))
            lateinit var robot:RobotRuntime
            scenario.onActivity{robot=RobotRuntime(it,vault,vault.get("robot")!!);robot.resumeForeground();(RobotRuntime::class.java.getDeclaredField("input").apply{isAccessible=true}.get(robot) as AudioInput).close()}
            try{
                await("计划测试机器人在线"){robot.online};tap("播放清单");tap("英语计划");tap("添加计划");find("开始时间 HH:mm");fields()[0].text="18:25";fields()[1].text="5";tap("保存计划")
                await("保存计划已ACK",30){device.hasObject(By.text("机器人已应用计划"))};find("18:25 · 5 分钟");capture("plan-saved");tap("18:25 · 5 分钟");tap("删除计划");tap("删除")
                await("删除计划已ACK",30){device.hasObject(By.text("还没有英语计划"))};capture("plan-deleted")
            }finally{scenario.onActivity{robot.close()}}
        }
    }
    @Test fun robotRemainingSettingsAndSetup(){
        check(vault.get("robot")!!.getString("address").endsWith(":8876"));vault.save("identity",JSONObject().put("mode","robot"));vault.setPin("258369")
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{
            Thread.sleep(1500);val t=SystemClock.uptimeMillis()
            fun touch(action:Int){val e=android.view.MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,device.displayWidth*.9f,device.displayHeight*.1f,0);inst.sendPointerSync(e);e.recycle()}
            touch(0);Thread.sleep(2200);touch(1);await("PIN解锁"){device.hasObject(By.text("进入"))};fields()[0].text="258369";tap("进入");tap("小伙伴设置")
            for((entry,marker) in listOf("声音" to "中文声音","隐私与权限" to "允许会话内使用相机","唤醒与互动" to "新的唤醒词（2—6个汉字）")){tap(entry);find(marker);capture("robot-$entry");back()}
            tap("AI 提示词");tap("预览内容");find("返回编辑");capture("prompt-preview");tap("返回编辑");tap("历史版本");find("提示词版本");capture("prompt-versions");back();back()
            tap("切换身份");find("家长身份");capture("robot-identity");back();back();tap("离线内容");capture("robot-offline");back();tap("使用教程");for(title in listOf("叫醒与暂停","读书与换内容","看眼前物品","隐私与休息","家长管理")){tap(title);capture("tutorial-$title");tap(title)}
        }
        vault.save("identity",JSONObject().put("mode","setup"));ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{
            find("首次设置");capture("setup-role");tap("这是家长手机");find("绑定家长手机");assertFalse(find("验证并连接").parent.isEnabled);capture("setup-parent");back();tap("这是机器人手机");find("设置6—12位管理 PIN");capture("setup-robot");back();tap("返回已登记机器人")
        }
    }

    @Test fun parentReadOnlyFinalPass(){
        parent().use{
            tap("设置")
            for((entry,marker) in listOf("孩子与成长" to "基准年龄（3—17）","声音" to "中文声音","隐私与权限" to "允许会话内看图","对话记录保存" to "保存后续陪伴对话","使用摘要" to "使用摘要","绑定与恢复" to "忘记 PIN 或手机丢失","本机身份" to "机器人身份","唤醒与互动" to "新的唤醒词（2—6个汉字）")){tap(entry);find(marker);capture("final-parent-$entry");back()}
            tap("数据与备份");tap("空间与离线副本");find("刷新下载状态");capture("final-storage");back();find("数据与备份");back();tap("诊断与服务能力");tap("空间与离线副本");back();find("诊断与服务能力");back()
            tap("主题颜色");tap("浅色");back();capture("final-settings-light");tap("检查与调试");capture("final-checks-light");tap("调试");capture("final-chat-light");back();tap("主题颜色");tap("深色");back();capture("final-settings-dark");back()
            tap("资源库");capture("final-library");tap("阅读与识物指南");back();find("资源库");back();tap("记录");tap("使用记录");back();find("陪伴对话");capture("final-records")
        }
    }

    @Test fun backupRestoreAndPrivateExports(){
        val secret=File(context.filesDir,"test-recovery.txt").readText().trim()
        val folder=File(context.cacheDir,"book-capture").apply{mkdirs()};val outputs=mutableListOf<File>()
        var backup:File?=null
        val monitor=object:android.app.Instrumentation.ActivityMonitor(){
            override fun onStartActivity(intent:Intent):android.app.Instrumentation.ActivityResult?{
                if(intent.action==Intent.ACTION_CREATE_DOCUMENT){
                    val file=File(folder,"export-${outputs.size}"+if(intent.type=="application/zip")".zip" else ".json");outputs.add(file);if(intent.type=="application/zip")backup=file
                    val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.capture",file)
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,Intent().setData(uri))
                }
                if(intent.action==Intent.ACTION_OPEN_DOCUMENT){val file=backup ?: error("先导出备份");val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.capture",file);return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,Intent().setData(uri))}
                return null
            }
        }
        inst.addMonitor(monitor)
        try{parent().use{
            tap("设置");tap("数据与备份");tap("导出资源库备份");await("备份已写入系统返回URI"){backup?.length()?.let{it>0}==true};assertTrue(java.util.zip.ZipFile(backup!!).use{it.entries().hasMoreElements()})
            tap("选择备份文件");find("电脑管理员恢复凭据");fields()[0].text=secret;tap("校验并恢复为待审核草稿");await("恢复已验证",30){device.hasObject(By.textContains("个待审核草稿"))};capture("backup-restored");back()
            tap("偏好与记忆");tap("导出偏好记录");await("偏好导出"){outputs.size>=2&&outputs.last().length()>0};assertTrue(JSONObject(outputs.last().readText()).has("memories"));back()
            tap("诊断与服务能力");tap("刷新脱敏诊断");tap("保存脱敏诊断");await("脱敏报告导出"){outputs.size>=3&&outputs.last().length()>0};val diagnostic=outputs.last().readText();assertFalse(diagnostic.contains(secret));assertFalse(diagnostic.contains(vault.get("parent")!!.getString("token")));back();back()
        }}finally{inst.removeMonitor(monitor);File(context.filesDir,"test-recovery.txt").delete();outputs.forEach{it.delete()}}
    }

}
