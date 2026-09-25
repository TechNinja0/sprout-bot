package org.familyrobot.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** UI 确认绑定具体草稿，不能被一次旧试听沿用。 */
fun bookAuditionKey(draft:JSONObject)=JSONObject(draft.toString()).put("auditioned",false).toString()
fun bookVoiceMatchesGlobal(voice:JSONObject,globalVoice:JSONObject?):Boolean {
    if(globalVoice==null)return false
    val strings=mapOf("quality" to "high","zh" to "default","en" to "default","story" to "default","style" to "neutral","storyStyle" to "default","instruction" to "")
    return strings.all{(key,default)->voice.optString(key,default)==globalVoice.optString(key,default)}&&
        mapOf("speed" to 1.0,"volume" to 0.5).all{(key,default)->
            val value=(voice.opt(key)?:default).toString().toDoubleOrNull()
            value!=null&&value==(globalVoice.opt(key)?:default).toString().toDoubleOrNull()
        }
}
fun bookPageCanConfirm(page:JSONObject)=page.optBoolean("skip")||page.optString("text").isNotBlank()
fun bookReadyToPublish(kind:String,draft:JSONObject):Boolean {
    val pages=draft.getJSONArray("pages");val audio=draft.optString("audioAsset").isNotBlank()
    return (kind!="book"||pages.length()>0)&&(kind!="song"||audio)&&
        (audio||(0 until pages.length()).any{pages.getJSONObject(it).let{p->!p.optBoolean("skip")&&p.optString("text").isNotBlank()}})&&
        (0 until pages.length()).all{pages.getJSONObject(it).let{p->p.optBoolean("reviewed")&&bookPageCanConfirm(p)}}&&
        (draft.optBoolean("complete")||draft.optString("excerpt").isNotBlank())
}
fun bookSpokenChunks(page:JSONObject):List<String>{
    if(page.optBoolean("skip"))return emptyList()
    var text=page.optString("text");val words=page.optJSONObject("pronunciation")?:JSONObject()
    for(word in words.keys().asSequence().filter{it.isNotEmpty()}.sortedByDescending{it.length})text=text.replace(word,words.getString(word))
    // 接口单段最多 600 字；按 Unicode 字符分段，整页顺序播放，不静默截断。
    val points=text.codePoints().toArray()
    return points.toList().chunked(550).map{String(it.toIntArray(),0,it.size)}.filter{it.isNotBlank()}
}
/** 使用服务端正式分段及顺序；发布试听可包含连续模式下同组的关联书页。 */
fun bookPreviewSegments(plan:JSONObject,pageId:String,forPublish:Boolean):List<JSONObject>{
    val rows=plan.getJSONArray("segments")
    val segments=(0 until rows.length()).map{rows.getJSONObject(it)}
    val selected=segments.filter{it.optString("pageId")==pageId}
    if(!forPublish||plan.optString("readingMode","follow_pages")!="continuous")return selected
    val groups=selected.map{it.optString("groupId")}.filter{it.isNotBlank()}.toSet()
    return segments.filter{it.optString("pageId")==pageId||it.optString("groupId") in groups}
}
@Composable fun BookSelect(label:String,value:String,options:List<Pair<String,String>>,change:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    Text(label,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(13.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),colors=ButtonDefaults.outlinedButtonColors(containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface)){
        Text(options.firstOrNull{it.first==value}?.second?:value);Spacer(Modifier.weight(1f));Text("⌄")
    };DropdownMenu(expanded,{expanded=false}){options.forEach{(id,title)->DropdownMenuItem(text={Text(title)},onClick={expanded=false;change(id)})}}}
}
@Composable fun BookCheck(label:String,checked:Boolean,enabled:Boolean=true,change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=change),verticalAlignment=Alignment.CenterVertically){Checkbox(checked,null,enabled=enabled,modifier=Modifier.padding(end=10.dp));Text(label,Modifier.weight(1f),fontSize=14.sp)}
}
@Composable fun BookFooter(primary:String,secondary:String,enabled:Boolean,primaryEnabled:Boolean=enabled,onPrimary:()->Unit,onSecondary:()->Unit){
    Surface(color=MaterialTheme.colorScheme.background){Column{HorizontalDivider();Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Box(Modifier.weight(1f)){FullAction(secondary,secondary=true,enabled=enabled,click=onSecondary)}
        Box(Modifier.weight(1.35f)){FullAction(primary,enabled=primaryEnabled,click=onPrimary)}
    }}}
}
@Composable fun BookVoiceForm(voice:JSONObject,models:JSONObject,language:String,change:(String,Any)->Unit){
    val info=models.optJSONObject("ttsInfo")
    val available=info?.optJSONArray("voices")?:models.optJSONArray("voices")
    val voices=mutableListOf("default" to "按语言默认")
    if(available!=null)for(i in 0 until available.length())available.getJSONObject(i).let{v->
        if(language=="bilingual"||v.optString("language")==language||v.optString("language").isBlank())voices.add(v.getString("id") to v.getString("name"))
    }
    val selected=voice.optString("story","default");if(voices.none{it.first==selected})voices.add(selected to "已保存的声音 · $selected")
    BookSelect("朗读声音",selected,voices){change("story",it)}
    if(info?.optBoolean("supportsStyle")==true){
        val styles=info.optJSONArray("styles")
        val choices=if(styles==null)emptyList()else (0 until styles.length()).map{styles.getJSONObject(it).let{v->v.getString("id") to v.getString("name")}}
        val options=(listOf("default" to "跟随回答语气","neutral" to "自然平和")+choices).distinctBy{it.first}
        BookSelect("情感 / 朗读语气",voice.optString("storyStyle","default"),options){change("storyStyle",it)}
        if(voice.optString("storyStyle","default")=="default"&&voice.optString("style","neutral")=="neutral")Text("跟随回答语气：自然平和",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }else Text("当前声音引擎不支持情感设置，已有配置会保留。",fontSize=12.sp)
    var speed by remember{mutableStateOf(voice.opt("speed")?.toString()?:"1.0")}
    LaunchedEffect(voice.opt("speed")){
        val stored=voice.opt("speed")?.toString()?:"1.0"
        if(speed!=stored&&speed.toDoubleOrNull()!=stored.toDoubleOrNull())speed=stored
    }
    FormField("语速倍率（0.7—1.3，1.0 为原速）",speed){speed=it;change("speed",it.toDoubleOrNull()?:it)}
    if(voice.optDouble("speed",1.0) !in 0.7..1.3)Text("请输入 0.7—1.3 之间的语速",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
    if(info?.optBoolean("supportsInstruction")==true)FormField("补充语气描述（最多200字，可留空）",voice.optString("instruction"),2){change("instruction",it.take(200))}
    if(info?.optJSONArray("qualities")!=null)BookSelect("人声音质",voice.optString("quality","high"),listOf("standard" to "标准","high" to "高品质")){change("quality",it)}
    else Text("当前声音引擎未提供可选音质。",fontSize=12.sp)
    Text((if(info?.optString("speedMode")=="atempo")"按语速倍率变速、不变调，短音频的实际时长可能有少量偏差。" else "语速倍率：0.7 倍更慢，1.3 倍更快。")+"标准音质减少传输与存储，高品质保留原始采样率。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(info!=null&&!info.optBoolean("ready"))Text("朗读服务尚未就绪，恢复后可试听。",color=MaterialTheme.colorScheme.error)
}
