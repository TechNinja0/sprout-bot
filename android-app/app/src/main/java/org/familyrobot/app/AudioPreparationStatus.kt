package org.familyrobot.app

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun AudioPreparationStatus(api:Api,rid:String,revision:String) {
    var status by remember(rid,revision) { mutableStateOf<JSONObject?>(null) }
    var error by remember(rid,revision) { mutableStateOf("") }
    var retrying by remember(rid,revision) { mutableStateOf(false) }
    var refresh by remember(rid,revision) { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(rid,revision,refresh) {
        while(true) {
            try {
                status=withContext(Dispatchers.IO) { api.json("/v1/resources/$rid/audio-preparation") }
                error=""
                if(status?.optString("state") !in listOf("queued","processing"))break
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { error="暂时无法获取音频准备状态";break }
            delay(2000)
        }
    }
    val current=status
    val state=current?.optString("state")
    InfoRow("朗读音频",when(state) {
        "ready" -> "已就绪 · 可连贯播放"
        "queued","processing" -> "准备中 ${current.optInt("completed")}/${current.optInt("total")} 段"
        "failed" -> "准备失败 · 已生成的段落保留"
        "cancelled" -> "已停止准备"
        else -> if(error.isNotEmpty())error else "正在查询"
    })
    if(state=="queued" || state=="processing")Text("家庭电脑正在提前准备朗读音频，完成后首次阅读无需等待合成。现在也可以播放。")
    if(state=="failed") {
        Text(current?.optString("error").orEmpty())
        Action("重试音频准备",!retrying) {
            scope.launch {
                retrying=true
                try {
                    withContext(Dispatchers.IO) {
                        api.json("/v1/resources/$rid/audio-preparation/retry?revisionId=${current!!.getString("revisionId")}","POST",JSONObject())
                    }
                    refresh++
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { error="重试失败，请刷新资源详情后再试" }
                finally { retrying=false }
            }
        }
    }
    if(error.isNotEmpty() && current!=null)Text(error)
    if(error.isNotEmpty())Action("刷新音频状态",!retrying) { refresh++ }
}
