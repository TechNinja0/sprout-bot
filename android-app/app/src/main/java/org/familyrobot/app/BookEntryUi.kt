package org.familyrobot.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable fun BookEntryForm(current:JSONObject,taskCount:Int,open:(String)->Unit,review:()->Unit){
    val draft=current.getJSONObject("draft");val pages=draft.getJSONArray("pages")
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
        Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.primaryContainer){Text(if(current.optString("kind")=="book")"图书工作草稿" else "资源工作草稿",Modifier.padding(horizontal=10.dp,vertical=6.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.primary)}
        Text("${pages.length()} 页 · ${if(current.optString("published_id").isNotBlank()&&!current.isNull("published_id"))"已有发布版" else "尚未发布"}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    JsonField(draft,"title","名称")
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Column(Modifier.weight(1f)){JsonField(draft,"edition","版本 / 年级")}
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
            var expanded by remember{mutableStateOf(false)};var language by remember(draft){mutableStateOf(draft.optString("language"))}
            Text("语言",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Box{
                OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.outlinedButtonColors(containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                    Text(if(language=="en")"英文" else if(language=="bilingual")"中英双语" else "中文");Spacer(Modifier.weight(1f));Text("⌄")
                }
                DropdownMenu(expanded,{expanded=false}){for((id,label) in listOf("zh" to "中文","en" to "英文"))DropdownMenuItem(text={Text(label)},onClick={language=id;draft.put("language",id);expanded=false})}
            }
        }
    }
    DetailDisclosure("书目信息与来源"){
        StringListField(draft,"aliases","别名（每行一个）");StringListField(draft,"tags","标签（每行一个）")
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Column(Modifier.weight(1f)){NumberField(draft,"minAge","最小适龄")};Column(Modifier.weight(1f)){NumberField(draft,"maxAge","最大适龄")}
        }
        JsonField(draft,"isbn","ISBN");JsonField(draft,"author","作者");JsonField(draft,"publisher","出版社");JsonField(draft,"source","来源")
        Dropdown(draft,"englishLevel","适合英语阶段",listOf("beginner" to "零基础","basic" to "基础","intermediate" to "进阶"))
    }
    SectionHeading("素材与正文")
    DesignGroup{
        DesignRow(if(draft.optString("coverAsset").isBlank())"拍摄或导入封面" else "封面已录入 · 可替换","仅用于找书，不代替正文","image"){open("cover")}
        DesignRow("录入正文","拍摄书页、批量选图、PDF 或文本","book"){open("import")}
        DesignRow("逐页校对","${pages.length()} 页 · 页序、正文与发音","check"){review()}
        DesignRow(if(draft.optString("audioAsset").isBlank())"导入已有音频" else "音频已录入 · 可替换","MP3 / M4A / WAV · 保留原声","music",false){open("audio")}
    }
    TextButton(onClick={open("jobs")}){Text("查看导入任务 · $taskCount 项")}
    Text("录入内容保留为草稿，校对和试听后再发布。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun BookEntryActions(enabled:Boolean,save:()->Unit,next:()->Unit){
    Surface(color=MaterialTheme.colorScheme.background){Column{
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Box(Modifier.weight(1f)){FullAction("保存草稿",secondary=true,enabled=enabled,click=save)}
            Box(Modifier.weight(1.8f)){FullAction("下一步：逐页校对",enabled=enabled,click=next)}
        }
    }}
}
