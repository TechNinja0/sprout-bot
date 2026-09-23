package org.familyrobot.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.json.JSONObject

/** 选项来自服务能力目录；切换 TTS 引擎不需要重新编译音色列表。 */
@Composable
fun VoiceControls(voice:JSONObject,models:JSONObject,resource:Boolean=false) {
    val info=models.optJSONObject("ttsInfo")
    val voices=info?.optJSONArray("voices") ?: models.optJSONArray("voices")
    val legacy=listOf("zh-girl" to "中文女声（兼容）","zh-boy" to "中文男声（兼容）","en-us" to "英语（兼容）","en-gb" to "英语（兼容）")
    fun options(language:String?=null):List<Pair<String,String>> {
        val available=if(voices==null)legacy else (0 until voices.length()).map { voices.getJSONObject(it) }
            .filter { language==null || it.optString("language")==language }
            .map { it.getString("id") to it.getString("name") }
        val saved=legacy.filter { (id,_) -> listOf("zh","en","story").any { voice.optString(it)==id } }
        return (listOf("default" to "按语言默认")+available+saved).distinctBy { it.first }
    }
    for(key in listOf("zh","en","story"))if(!voice.has(key))voice.put(key,"default")
    if(!voice.has("style"))voice.put("style","gentle")
    if(!voice.has("storyStyle"))voice.put("storyStyle","storytelling")
    Text("本地语音：${info?.optString("model") ?: "读取声音能力中"}")
    if(info!=null && !info.optBoolean("ready"))Text("电脑上的语音模型尚未就绪，请完成本地模型安装。")
    if(resource)Choice(voice,"story","本资源朗读声音",options())
    else {
        Choice(voice,"zh","中文声音",options("zh"))
        Choice(voice,"en","英语声音",options("en"))
        Choice(voice,"story","原创故事声音",options())
    }
    if(info?.optBoolean("supportsStyle")==true) {
        val styles=info.optJSONArray("styles")
        val choices=if(styles==null)emptyList() else (0 until styles.length()).map { styles.getJSONObject(it).let { row -> row.getString("id") to row.getString("name") } }
        if(!resource)Choice(voice,"style","回答语气",choices)
        Choice(voice,"storyStyle",if(resource)"朗读语气" else "故事语气",choices)
    } else if(info!=null)Text("当前引擎支持音色和语速，不支持语气控制；已有语气设置会保留。")
    if(info?.optBoolean("supportsInstruction")==true) {
        JsonField(voice,"instruction","补充语气描述（最多200字，可留空）",2)
        Text("例如：耐心、温暖，像一起探索世界的朋友，清楚地读出每个字。")
    }
    DecimalField(voice,"speed",if(info?.optString("speedMode")=="instruction")"语速偏好 0.7—1.3（实际节奏由模型决定）" else "语速 0.7—1.3")
    if(!resource)DecimalField(voice,"volume","音量 0—1")
}
