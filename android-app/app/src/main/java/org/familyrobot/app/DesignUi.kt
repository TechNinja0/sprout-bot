package org.familyrobot.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val DarkRobotColors=darkColorScheme(primary=Color(0xFF9DE3CB),onPrimary=Color(0xFF102923),primaryContainer=Color(0xFF213E38),onPrimaryContainer=Color(0xFFD1F6E8),background=Color(0xFF101F23),onBackground=Color(0xFFE9F2EE),surface=Color(0xFF1A2C30),onSurface=Color(0xFFE9F2EE),surfaceVariant=Color(0xFF21363A),onSurfaceVariant=Color(0xFFAAC0B9),outline=Color(0xFF506762),error=Color(0xFFFFB2A8),secondary=Color(0xFFB0CCC1),onSecondary=Color(0xFF18342C),secondaryContainer=Color(0xFF29473E),onSecondaryContainer=Color(0xFFD1F6E8),surfaceContainerLowest=Color(0xFF0A191D),surfaceContainerLow=Color(0xFF14262A),surfaceContainer=Color(0xFF1A2C30),surfaceContainerHigh=Color(0xFF21363A),surfaceContainerHighest=Color(0xFF294044))
private val LightRobotColors=lightColorScheme(primary=Color(0xFF196956),onPrimary=Color.White,primaryContainer=Color(0xFFE9F3ED),onPrimaryContainer=Color(0xFF174C3D),background=Color(0xFFF5F7F5),onBackground=Color(0xFF182E2D),surface=Color.White,onSurface=Color(0xFF182E2D),surfaceVariant=Color(0xFFE9F0EB),onSurfaceVariant=Color(0xFF586C67),outline=Color(0xFF788B84),error=Color(0xFFAB352E),secondary=Color(0xFF49665B),onSecondary=Color.White,secondaryContainer=Color(0xFFD5EADF),onSecondaryContainer=Color(0xFF174C3D),surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF1F6F2),surfaceContainer=Color(0xFFEAF1EC),surfaceContainerHigh=Color(0xFFE3ECE6),surfaceContainerHighest=Color(0xFFDBE7DF))
@Composable fun RobotTheme(mode:String,content:@Composable ()->Unit) { MaterialTheme(colorScheme=if(mode=="light")LightRobotColors else DarkRobotColors,shapes=Shapes(medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp)),content=content) }
@Composable fun AppearancePage(mode:String,choose:(String)->Unit,back:()->Unit) {
    Page("主题颜色","",false,onBack=back) {
        Text("选择这台手机的外观，即时生效并保留。两台手机可以各选各的。",color=MaterialTheme.colorScheme.onSurfaceVariant)
        for((value,title,sub) in listOf(Triple("light","浅色","明亮背景 · 深绿强调色"),Triple("dark","深色","深色背景 · 薄荷绿强调色"))) {
            Card(Modifier.fillMaxWidth().clickable { choose(value) }) { Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) { RadioButton(mode==value,{ choose(value) });Column { Text(title);Text(sub,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) } } }
        }
    }
}
@Composable fun SectionLink(title:String,subtitle:String="",click:()->Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick=click),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,fontSize=16.sp,fontWeight=FontWeight.Medium);if(subtitle.isNotEmpty())Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) };Text("›",fontSize=24.sp) }
    }
}
@Composable fun ConnectionCard(online:Boolean,detail:String,click:(()->Unit)?=null) {
    val foreground=if(online)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=foreground.copy(alpha=.12f))) { Column(Modifier.padding(20.dp)) {
        Text(if(online)"● 家庭服务器已连接" else "● 家庭服务器未连接",color=foreground,fontSize=20.sp,fontWeight=FontWeight.Medium)
        Text(detail,Modifier.padding(top=8.dp),fontSize=13.sp)
        if(click!=null)TextButton(onClick=click) { Text("查看连接 ›") }
    } }
}
@Composable fun Dropdown(json:JSONObject,key:String,label:String,options:List<Pair<String,String>>) {
    var expanded by remember { mutableStateOf(false) };var value by remember(json,key) { mutableStateOf(json.optString(key)) }
    Text(label,fontSize=13.sp)
    Box { OutlinedButton(onClick={ expanded=true },modifier=Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first==value }?.second ?: "请选择");Spacer(Modifier.weight(1f));Text("⌄") };DropdownMenu(expanded,{ expanded=false }) { options.forEach { (id,name) -> DropdownMenuItem(text={ Text(name) },onClick={ value=id;json.put(key,if(json.opt(key) is Number)id.toInt() else id);expanded=false }) } } }
}
@Composable fun RobotTutorial() {
    for((title,body) in listOf(
        "叫醒与暂停" to "说设置中的唤醒词或“你好＋唤醒词”。轻点也能叫醒；双击或说“停止”暂停，说“继续”接着播放。",
        "读书与换内容" to "可说“读《书名》”“下一页”“上一章”“从头读”。同名会询问版本；只朗读家长审核发布的正文。",
        "看眼前物品" to "“这个是什么”使用新画面；“它怎么用”追问本次会话的对象。看不清或有多个物体时先澄清，不沿用失败的旧画面。",
        "隐私与休息" to "“别看了”关闭本轮相机。静音和禁用时段停止采集；后台、锁屏和来电暂停，不自动续播。没有新输入约30秒安静退出。",
        "家长管理" to "长按脸部右上角2秒并输入PIN。可生成家长配对二维码、配置唤醒词和提示词、检查设备及管理离线内容。"
    )) { var open by remember { mutableStateOf(false) };SectionLink(title,if(open)body else "点击查看") { open=!open } }
}
fun statusLabel(value:String)=mapOf("SELF_CHECK" to "设备检查中","MANAGEMENT" to "本机正在管理设置","THERMAL" to "设备温度较高，已暂停","blocked" to "暂停使用","recognizing" to "正在识别","opening" to "正在回应唤醒","follow_up" to "等待继续说话","closing" to "准备休息","self_check" to "设备检查中","standby" to "空闲待机","listening" to "正在倾听","thinking" to "正在思考","speaking" to "正在回答","playing" to "正在播放","paused" to "已暂停","loading" to "准备播放","idle" to "空闲待机","ALLOWED" to "可使用","MANUAL" to "家长已停用","SCHEDULED" to "禁用时段","QUOTA" to "今日额度已用完","UNTRUSTED_TIME" to "时间待校验","muted" to "麦克风静音")[value] ?: value

@Composable fun ParentNavigation(current:String,select:(String)->Unit){
    NavigationBar(containerColor=MaterialTheme.colorScheme.surface){for((name,symbol) in listOf("首页" to "⌂","记录" to "☷","设置" to "⚙"))NavigationBarItem(selected=current==name,onClick={select(name)},icon={Text(symbol,fontSize=20.sp)},label={Text(name)})}
}

@Composable fun PronunciationEditor(page:JSONObject){
    var words by remember(page){mutableStateOf(page.optJSONObject("pronunciation") ?: JSONObject())}
    var original by remember(page){mutableStateOf("")};var spoken by remember(page){mutableStateOf("")}
    Text("发音纠正",fontWeight=FontWeight.Medium)
    val keys=words.keys().asSequence().toList()
    for(word in keys)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("$word → ${words.getString(word)}",Modifier.weight(1f));TextButton(onClick={words.remove(word);words=JSONObject(words.toString());page.put("pronunciation",words);page.put("reviewed",false)}){Text("删除")}}
    OutlinedTextField(original,{original=it.take(80)},label={Text("原文中的词")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(spoken,{spoken=it.take(160)},label={Text("希望读成的文字")},modifier=Modifier.fillMaxWidth())
    Action(if(words.has(original.trim()))"更新此词读音" else "添加发音纠正",original.isNotBlank()&&spoken.isNotBlank()){words.put(original.trim(),spoken.trim());words=JSONObject(words.toString());page.put("pronunciation",words);page.put("reviewed",false);original="";spoken=""}
    Text("只影响朗读，不替换校对正文。修改后请重新校对与试听。",fontSize=12.sp)
}
