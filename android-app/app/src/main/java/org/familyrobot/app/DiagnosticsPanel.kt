package org.familyrobot.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 只导出白名单报告；不合并连接材料、配置、UI错误或临时识别原文。 */
@Composable fun DiagnosticsPanel(connection:JSONObject, selfCheck:JSONObject?=null) {
    val context=LocalContext.current
    val api=remember(connection) { Api(connection) }
    val scope=rememberCoroutineScope()
    var report by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot=report?.toString(2)
        if(uri!=null && snapshot!=null)scope.launch {
            message=try {
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(snapshot.toByteArray()) } }
                "脱敏诊断已保存"
            } catch(_:Exception) { "诊断保存失败，请检查目标位置" }
        }
    }
    Button(enabled=!busy,onClick={
        busy=true
        scope.launch {
            try {
                val service=withContext(Dispatchers.IO) { api.json("/v1/diagnostics",timeoutMs=5000) }
                fun granted(p:String)=ContextCompat.checkSelfPermission(context,p)==PackageManager.PERMISSION_GRANTED
                report=service.put("exportingApp",JSONObject().put("version",BuildConfig.VERSION_NAME).put("androidApi",android.os.Build.VERSION.SDK_INT)
                    .put("microphonePermission",granted(Manifest.permission.RECORD_AUDIO)).put("cameraPermission",granted(Manifest.permission.CAMERA)))
                    .apply { selfCheck?.let { put("localSelfCheck",JSONObject(it.toString())) } }
                message="诊断已更新；硬件采集结果仅来自本机明确执行的自检"
            } catch(_:Exception) { report=null;message="无法获取诊断，请检查家庭服务连接" }
            finally { busy=false }
        }
    }) { Text(if(busy)"正在读取诊断…" else "刷新脱敏诊断") }
    if(message.isNotEmpty())Text(message)
    report?.let { value ->
        val robot=value.getJSONObject("robot")
        val states=mapOf("standby" to "待机","opening" to "正在叫醒","listening" to "倾听","recognizing" to "识别语音","thinking" to "思考","speaking" to "说话或播放","follow_up" to "等待续问","closing" to "结束会话","muted" to "静音","blocked" to "暂停使用","self_check" to "本机自检")
        Text("机器人${if(robot.optBoolean("online"))"在线" else "离线"} · 配置已生效 ${robot.optInt("appliedVersion")} / 目标 ${robot.optInt("configVersion")} · ${states[robot.optString("state")] ?: "状态未知"}")
        val capabilities=value.getJSONObject("modelAvailability")
        SectionHeading("服务分项能力")
        for((key,name) in listOf("llm" to "对话","vlm" to "视觉理解","asr" to "语音识别","tts" to "语音合成","ocr" to "书页识字"))InfoRow(name,if(capabilities.optBoolean(key))"条件就绪" else "未就绪")
        Text("可用存储：${value.optLong("storageFreeBytes")/1024/1024} MB")
        Button(onClick={export.launch("family-robot-diagnostics.json")}) { Text("保存脱敏诊断") }
    }
    Text("报告含版本、生成时间、权限、最近心跳和失败计数；不含服务地址、凭据、孩子档案、书稿或原始音视频。模型可用不等于识别效果通过。")
}

@Composable fun HardwareReportSummary(report:JSONObject) {
    val labels=mapOf("captured" to "已完成短时收音","insufficient-samples" to "采样不足，请检查麦克风",
        "permission-denied" to "系统权限未授权","disabled-by-parent" to "家长已关闭","fresh-frame" to "已取得新画面",
        "no-frame" to "未收到画面","responded" to "已返回识别结果","no-text" to "未识别到文字","unavailable" to "服务暂不可用",
        "skipped" to "未执行","played-unconfirmed" to "播放进度已完成，请现场确认听感","muted-output" to "当前静音，未播音",
        "focus-denied" to "声音被其他应用占用","write-failed" to "音频输出失败","no-playback-progress" to "播放进度未完成","blocked" to "当前限制使用")
    Text("本机检测：${when(report.optString("status")) { "completed" -> "已结束";"cancelled" -> "已取消";else -> "未完成" }}")
    for((key,name) in listOf("microphone" to "麦克风","asr" to "语音识别","camera" to "相机","audioOutput" to "播音")) {
        Text("$name：${labels[report.optString(key)] ?: "未执行"}")
    }
    if(report.has("sampleCount"))Text("已采集 ${report.optInt("sampleCount")/16} 毫秒；系统回声消除${if(report.optBoolean("aecEnabled"))"已启用" else "未启用"}，实际效果另需测试。")
}
