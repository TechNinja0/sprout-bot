package org.familyrobot.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val DarkRobotColors=darkColorScheme(primary=Color(0xFF9DE3CB),onPrimary=Color(0xFF102923),primaryContainer=Color(0xFF213E38),onPrimaryContainer=Color(0xFFD1F6E8),background=Color(0xFF101F23),onBackground=Color(0xFFE9F2EE),surface=Color(0xFF1A2C30),onSurface=Color(0xFFE9F2EE),surfaceVariant=Color(0xFF21363A),onSurfaceVariant=Color(0xFFAAC0B9),outlineVariant=Color(0xFF304449),outline=Color(0xFF506762),error=Color(0xFFFFB2A8),secondary=Color(0xFFB0CCC1),onSecondary=Color(0xFF18342C),secondaryContainer=Color(0xFF29473E),onSecondaryContainer=Color(0xFFD1F6E8),surfaceContainerLowest=Color(0xFF0A191D),surfaceContainerLow=Color(0xFF14262A),surfaceContainer=Color(0xFF1A2C30),surfaceContainerHigh=Color(0xFF21363A),surfaceContainerHighest=Color(0xFF294044))
private val LightRobotColors=lightColorScheme(primary=Color(0xFF196956),onPrimary=Color.White,primaryContainer=Color(0xFFE9F3ED),onPrimaryContainer=Color(0xFF174C3D),background=Color(0xFFF5F7F5),onBackground=Color(0xFF182E2D),surface=Color.White,onSurface=Color(0xFF182E2D),surfaceVariant=Color(0xFFE9F0EB),onSurfaceVariant=Color(0xFF586C67),outlineVariant=Color(0xFFE1E9E4),outline=Color(0xFF788B84),error=Color(0xFFAB352E),secondary=Color(0xFF49665B),onSecondary=Color.White,secondaryContainer=Color(0xFFD5EADF),onSecondaryContainer=Color(0xFF174C3D),surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF1F6F2),surfaceContainer=Color(0xFFEAF1EC),surfaceContainerHigh=Color(0xFFE3ECE6),surfaceContainerHighest=Color(0xFFDBE7DF))
@Composable fun RobotTheme(mode:String,content:@Composable ()->Unit) { MaterialTheme(colorScheme=if(mode=="light")LightRobotColors else DarkRobotColors,typography=Typography(bodyLarge=TextStyle(fontSize=14.sp,lineHeight=22.sp),bodyMedium=TextStyle(fontSize=13.sp,lineHeight=21.sp),bodySmall=TextStyle(fontSize=12.sp,lineHeight=19.sp),titleLarge=TextStyle(fontSize=21.sp,lineHeight=28.sp,fontWeight=FontWeight.Medium),titleMedium=TextStyle(fontSize=15.sp,lineHeight=23.sp,fontWeight=FontWeight.Medium),labelLarge=TextStyle(fontSize=14.sp,fontWeight=FontWeight.Medium)),shapes=Shapes(extraSmall=RoundedCornerShape(13.dp),small=RoundedCornerShape(13.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp)),content=content) }
@Composable fun AppearancePage(mode:String,choose:(String)->Unit,back:()->Unit) {
    Page("主题颜色","",false,onBack=back) {
        Text("选择这台手机的外观，即时生效并保留。两台手机可以各选各的。",color=MaterialTheme.colorScheme.onSurfaceVariant)
        DesignGroup{for((value,title,sub) in listOf(Triple("light","浅色","明亮背景 · 深绿强调色"),Triple("dark","深色","当前深色风格 · 薄荷绿强调色"))) {
            Row(Modifier.fillMaxWidth().clickable{choose(value)}.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title);Text(sub,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};RadioButton(mode==value,{choose(value)})}
            if(value=="light")HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        }}
        Text("陪伴表情保持柔和、无文字，不会因主题切换开始采集或播放。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun UiIcon(name:String,modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(20.dp)) {
        val u=size.width/24f
        fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(color,Offset(x*u,y*u),Offset(x2*u,y2*u),1.5f*u,StrokeCap.Round)
        fun box(x:Float,y:Float,w:Float,h:Float)=drawRoundRect(color,Offset(x*u,y*u),androidx.compose.ui.geometry.Size(w*u,h*u),androidx.compose.ui.geometry.CornerRadius(2*u),style=Stroke(1.5f*u))
        when(name) {
            "back" -> {line(19f,12f,5f,12f);line(5f,12f,10f,7f);line(5f,12f,10f,17f)}
            "chevron" -> {line(9f,6f,15f,12f);line(15f,12f,9f,18f)}
            "bot" -> {box(5f,7f,14f,13f);line(12f,3f,12f,7f);line(2f,11f,2f,16f);line(22f,11f,22f,16f);line(9f,11f,9f,13f);line(15f,11f,15f,13f);line(9f,17f,15f,17f)}
            "home" -> {line(3f,11f,12f,3f);line(12f,3f,21f,11f);box(6f,11f,12f,10f);box(10f,15f,4f,6f)}
            "chat" -> {box(3f,4f,18f,14f);line(7f,18f,7f,22f);line(7f,22f,12f,18f)}
            "check" -> {line(5f,12f,10f,17f);line(10f,17f,20f,6f)}
            "send" -> {line(12f,20f,12f,4f);line(12f,4f,6f,10f);line(12f,4f,18f,10f)}
            "mic" -> {box(9f,3f,6f,12f);line(5f,12f,5f,16f);line(5f,16f,12f,20f);line(12f,20f,19f,16f);line(19f,16f,19f,12f);line(12f,20f,12f,23f)}
            "camera" -> {box(3f,6f,18f,15f);box(8f,3f,8f,3f);drawCircle(color,4f*u,Offset(12f*u,13f*u),style=Stroke(1.5f*u))}
            "image" -> {box(3f,3f,18f,18f);drawCircle(color,1.5f*u,Offset(8f*u,8f*u));line(3f,18f,10f,11f);line(10f,11f,16f,17f);line(15f,16f,19f,12f);line(19f,12f,21f,14f)}
            "music" -> {line(10f,18f,10f,5f);line(10f,5f,20f,3f);line(20f,3f,20f,16f);drawCircle(color,3f*u,Offset(7f*u,18f*u),style=Stroke(1.5f*u));drawCircle(color,3f*u,Offset(17f*u,16f*u),style=Stroke(1.5f*u))}
            "book" -> {box(3f,4f,18f,16f);line(12f,4f,12f,20f)}
            "download" -> {line(12f,3f,12f,15f);line(7f,10f,12f,15f);line(12f,15f,17f,10f);line(4f,16f,4f,21f);line(4f,21f,20f,21f);line(20f,21f,20f,16f)}
            "qr" -> {box(3f,3f,6f,6f);box(15f,3f,6f,6f);box(3f,15f,6f,6f);line(15f,15f,21f,15f);line(15f,15f,15f,21f);line(19f,19f,21f,21f)}
            "settings" -> {for(y in listOf(6f,12f,18f)){line(3f,y,21f,y)};box(7f,4f,3f,4f);box(14f,10f,3f,4f);box(8f,16f,3f,4f)}
            else -> {box(3f,4f,18f,14f);line(7f,21f,17f,21f);line(12f,18f,12f,21f)}
        }
    }
}
@Composable fun SectionHeading(text:String) { Text(text,Modifier.padding(top=12.dp,start=2.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun DesignGroup(content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) { Column(content=content) }
}
@Composable fun DesignRow(title:String,subtitle:String="",icon:String="settings",divider:Boolean=true,click:()->Unit) {
    Column { Row(Modifier.fillMaxWidth().clickable(onClick=click).padding(horizontal=15.dp,vertical=17.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
        UiIcon(icon);Column(Modifier.weight(1f)){Text(title,fontSize=14.sp);if(subtitle.isNotBlank())Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};UiIcon("chevron",Modifier.size(16.dp),MaterialTheme.colorScheme.onSurfaceVariant)
    };if(divider)HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant) }
}
@Composable fun SectionLink(title:String,subtitle:String="",click:()->Unit) { DesignGroup { DesignRow(title,subtitle,divider=false,click=click) } }
@Composable fun InfoRow(label:String,value:String) {
    Column { Row(Modifier.fillMaxWidth().padding(vertical=14.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {Text(label,Modifier.weight(.4f),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,Modifier.weight(.6f),fontSize=13.sp,textAlign=androidx.compose.ui.text.style.TextAlign.End)};HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant) }
}
@Composable fun DetailDisclosure(title:String,content:@Composable ColumnScope.()->Unit) {
    var expanded by remember{mutableStateOf(false)}
    Column {TextButton(onClick={expanded=!expanded},modifier=Modifier.semantics{contentDescription=title},contentPadding=PaddingValues(0.dp)){Text((if(expanded)"▾  " else "▸  ")+title,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(expanded)DesignGroup {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)} }
}
@Composable fun FullAction(title:String,secondary:Boolean=false,enabled:Boolean=true,click:()->Unit) {
    if(secondary)OutlinedButton(click,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=enabled,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Text(title)}
    else Button(click,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=enabled,shape=RoundedCornerShape(14.dp)){Text(title)}
}
@Composable fun DiagnosticTabs(chat:Boolean,select:(Boolean)->Unit) {
    Surface(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(13.dp)) { Row(Modifier.padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        for((value,title) in listOf(false to "设备检查",true to "调试"))Surface(Modifier.weight(1f).heightIn(min=40.dp).clickable{select(value)},shape=RoundedCornerShape(10.dp),color=if(value==chat)MaterialTheme.colorScheme.surface else Color.Transparent){Box(contentAlignment=Alignment.Center){Text(title,color=if(value==chat)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)}}
    } }
}
@Composable fun FeatureTile(title:String,subtitle:String,icon:String,modifier:Modifier=Modifier,click:()->Unit) {
    Surface(modifier.clickable(onClick=click),color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Column(Modifier.padding(horizontal=15.dp,vertical=18.dp)){UiIcon(icon,Modifier.size(25.dp));Spacer(Modifier.height(15.dp));Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium);Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
}
@Composable fun ConnectionCard(online:Boolean,detail:String,checking:Boolean=false,click:(()->Unit)?=null) {
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val foreground=if(checking)MaterialTheme.colorScheme.primary else if(online)Color(if(dark)0xFF9DE3B9 else 0xFF19734F) else MaterialTheme.colorScheme.error
    val container=if(checking)MaterialTheme.colorScheme.primaryContainer else if(online)Color(if(dark)0xFF193C30 else 0xFFE7F4EB) else Color(if(dark)0xFF422A2A else 0xFFFCEDEA)
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=container) { Column(Modifier.padding(20.dp)) {
        Text("●  服务器连接",color=foreground,fontSize=12.sp)
        Text(if(checking)"正在检查连接…" else if(online)"已连接家庭服务器" else "连接失败",Modifier.padding(top=13.dp,bottom=8.dp),color=foreground,fontSize=23.sp,fontWeight=FontWeight.Medium)
        Text(detail,fontSize=13.sp,lineHeight=21.sp)
        HorizontalDivider(Modifier.padding(top=18.dp),color=foreground.copy(alpha=.16f))
        Row(Modifier.fillMaxWidth().heightIn(min=42.dp),verticalAlignment=Alignment.CenterVertically){Text(if(checking)"正在验证服务身份" else if(online)"连接正常 · 家庭电脑" else "离线内容仍可播放",fontSize=12.sp,color=foreground,modifier=Modifier.weight(1f));if(click!=null)TextButton(click){Text("查看连接");UiIcon("chevron",Modifier.size(15.dp))}else UiIcon(if(online)"check" else "server",color=foreground)}
    } }
}
@Composable fun Dropdown(json:JSONObject,key:String,label:String,options:List<Pair<String,String>>,changed:(String)->Unit={}) {
    var expanded by remember { mutableStateOf(false) };var value by remember(json,key) { mutableStateOf(json.optString(key)) }
    Text(label,fontSize=13.sp)
    Box { OutlinedButton(onClick={ expanded=true },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.outlinedButtonColors(containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) { Text(options.firstOrNull { it.first==value }?.second ?: "请选择");Spacer(Modifier.weight(1f));Text("⌄") };DropdownMenu(expanded,{ expanded=false }) { options.forEach { (id,name) -> DropdownMenuItem(text={ Text(name) },onClick={ value=id;json.put(key,if(json.opt(key) is Number)id.toInt() else id);expanded=false;changed(id) }) } } }
}
@Composable fun RobotTutorial() {
    Text("了解怎样和小伙伴相处，需要时展开查看。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    DesignGroup {
    for((title,body) in listOf(
        "叫醒与暂停" to "说设置中的唤醒词或“你好＋唤醒词”。轻点也能叫醒；双击或说“停止”暂停，说“继续”接着播放。",
        "读书与换内容" to "可说“读《书名》”“下一页”“上一章”“从头读”。同名会询问版本；只朗读家长审核发布的正文。",
        "看眼前物品" to "“这个是什么”使用新画面；“它怎么用”追问本次会话的对象。看不清或有多个物体时先澄清，不沿用失败的旧画面。",
        "隐私与休息" to "“别看了”关闭本轮相机。静音和禁用时段停止采集；后台、锁屏和来电暂停，不自动续播。没有新输入约30秒安静退出。",
        "家长管理" to "长按脸部右上角2秒并输入PIN。可生成家长配对二维码、配置唤醒词和提示词、检查设备及管理离线内容。"
    )) { var open by remember { mutableStateOf(false) };Column{DesignRow(title,icon="book",divider=false){open=!open};if(open)Text(body,Modifier.padding(start=16.dp,end=16.dp,bottom=18.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);if(title!="家长管理")HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)} }
    }
}
fun statusLabel(value:String)=mapOf("SELF_CHECK" to "设备检查中","MANAGEMENT" to "本机正在管理设置","THERMAL" to "设备温度较高，已暂停","blocked" to "暂停使用","recognizing" to "正在识别","opening" to "正在回应唤醒","follow_up" to "等待继续说话","closing" to "准备休息","self_check" to "设备检查中","standby" to "空闲待机","listening" to "正在倾听","thinking" to "正在思考","speaking" to "正在回答","playing" to "正在播放","paused" to "已暂停","loading" to "准备播放","idle" to "空闲待机","ALLOWED" to "可使用","MANUAL" to "家长已停用","SCHEDULED" to "禁用时段","QUOTA" to "今日额度已用完","UNTRUSTED_TIME" to "时间待校验","muted" to "麦克风静音")[value] ?: value

@Composable fun ParentNavigation(current:String,select:(String)->Unit){
    Surface(color=MaterialTheme.colorScheme.surface){Column{
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(10.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){
            for((name,icon) in listOf("首页" to "home","记录" to "chat","设置" to "settings"))Surface(
                Modifier.weight(1f).clickable{select(name)},shape=RoundedCornerShape(14.dp),
                color=if((if(current=="资源库")"首页" else current)==name)MaterialTheme.colorScheme.primaryContainer else Color.Transparent){
                Column(Modifier.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally){UiIcon(icon);Text(name,fontSize=11.sp)}
            }
        }
    }}
}

@Composable fun EmptyState(title:String,detail:String="") {
    DesignGroup { Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
        UiIcon("book",Modifier.size(28.dp));Text(title);if(detail.isNotBlank())Text(detail,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
}
@Composable fun EditorSteps(stage:Int,select:(Int)->Unit,enabled:Boolean=true){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        listOf("1 录入","2 校对","3 试听与发布").forEachIndexed{index,label->Column(Modifier.weight(1f).clickable(enabled=enabled){select(index)}){
            Text(label,Modifier.padding(vertical=12.dp),fontSize=12.sp,color=if(stage==index)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(thickness=2.dp,color=if(stage==index)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
        }}
    }
}
fun resourceStatus(value:String)=mapOf("draft" to "草稿","published" to "已发布","unlisted" to "已下架","complete" to "已下载","completed" to "已下载","ready" to "已下载","downloaded" to "已完整下载","removed" to "已清理手机副本","pending_removal" to "等待机器人同步清理","downloading" to "下载中","failed" to "下载失败","queued" to "等待处理","processing" to "正在处理","needs_review" to "待校对","cancelled" to "已取消","interrupted" to "已中断","stale" to "草稿版本已更新")[value] ?: value

@Composable fun PronunciationEditor(page:JSONObject,onChanged:()->Unit={}){
    var words by remember(page){mutableStateOf(page.optJSONObject("pronunciation") ?: JSONObject())}
    var original by remember(page){mutableStateOf("")};var spoken by remember(page){mutableStateOf("")}
    Text("发音纠正",fontWeight=FontWeight.Medium)
    val keys=words.keys().asSequence().toList()
    for(word in keys)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("$word → ${words.getString(word)}",Modifier.weight(1f));TextButton(onClick={words.remove(word);words=JSONObject(words.toString());page.put("pronunciation",words);page.put("reviewed",false);onChanged()}){Text("删除")}}
    OutlinedTextField(original,{original=it.take(80)},label={Text("原文中的词")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(spoken,{spoken=it.take(160)},label={Text("希望读成的文字")},modifier=Modifier.fillMaxWidth())
    Action(if(words.has(original.trim()))"更新此词读音" else "添加发音纠正",original.isNotBlank()&&spoken.isNotBlank()){words.put(original.trim(),spoken.trim());words=JSONObject(words.toString());page.put("pronunciation",words);page.put("reviewed",false);onChanged();original="";spoken=""}
    Text("只影响朗读，不替换校对正文。修改后请重新校对与试听。",fontSize=12.sp)
}

@Composable fun FormField(label:String,value:String,lines:Int=1,change:(String)->Unit){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(label,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value,change,modifier=Modifier.fillMaxWidth().semantics{contentDescription=label},minLines=lines,maxLines=if(lines>1)12 else 2,
            colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surface,unfocusedContainerColor=MaterialTheme.colorScheme.surface,unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant),shape=RoundedCornerShape(13.dp))
    }
}
