package org.familyrobot.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable fun ParentDiagnosticsScreen(connection:JSONObject,back:()->Unit,play:(ByteArray)->Unit){
    var chat by remember{mutableStateOf(false)}
    BackHandler(onBack=back)
    if(chat)DebugChat(connection,back,play,title="检查与调试",tabs={DiagnosticTabs(true){chat=it}})
    else Page("检查与调试","",false,onBack=back,header={DiagnosticTabs(false){chat=it}}){
        EmptyState("机器人硬件检查需在机器人手机上","进入 PIN 管理 → 检查与调试")
        Text("家长手机的调试不会远程打开机器人麦克风或相机。")
        DetailDisclosure("查看最近脱敏诊断"){DiagnosticsPanel(connection)}
    }
}

/** List/detail navigation keeps unsaved edits local, and confirms destructive actions. */
@Composable fun ParentCollectionScreen(connection:JSONObject,kind:String,back:()->Unit,open:(String)->Unit){
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope();val context=LocalContext.current
    val memory=kind=="记忆";val endpoint=if(memory)"memories" else "playlists"
    var rows by remember{mutableStateOf(JSONArray())};var resources by remember{mutableStateOf(JSONArray())}
    var events by remember{mutableStateOf(JSONArray())};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    var draft by remember{mutableStateOf<JSONObject?>(null)};var baseline by remember{mutableStateOf("")}
    var filter by remember{mutableStateOf("pending")};var leaving by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf<String?>(null)}
    fun launch(block:suspend ()->Unit){if(busy)return;scope.launch{busy=true;try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "操作失败"}finally{busy=false}}}
    suspend fun refresh(){withContext(Dispatchers.IO){rows=api.array("/v1/$endpoint");if(memory)events=api.array("/v1/memory-actions") else resources=api.json("/v1/resources").getJSONArray("items")}}
    fun exit(){if(busy)return;if(draft==null)back() else if(draft.toString()!=baseline)leaving=true else{draft=null;message=""}}
    fun edit(row:JSONObject?){
        val d=if(memory){val body=row?.optJSONObject("body") ?: JSONObject();JSONObject().put("id",row?.optString("id") ?: "").put("content",body.optString("content")).put("source",body.optString("source","家长主动录入")).put("days",if(body.optDouble("expires")>0)kotlin.math.ceil((body.optDouble("expires")-System.currentTimeMillis()/1000.0)/86400).toInt().coerceAtLeast(1).toString() else "0")}
        else JSONObject().put("id",row?.optString("id") ?: "").put("name",row?.optString("name") ?: "").put("resources",row?.optJSONArray("resources") ?: JSONArray())
        draft=d;baseline=d.toString();message=""
    }
    suspend fun save(state:String="approved"){
        val d=draft ?: return;val id=d.optString("id")
        withContext(Dispatchers.IO){
            if(memory){val days=d.optString("days").toIntOrNull();require(days!=null&&days in 0..3650){"有效天数需为 0—3650 的整数"};val body=JSONObject().put("content",d.getString("content").trim()).put("expires",if(days==0)0.0 else System.currentTimeMillis()/1000.0+days*86400.0)
                if(id.isEmpty())api.json("/v1/memories","POST",body.put("source","家长主动录入")) else api.json("/v1/memories/$id","PUT",body.put("state",state))
            }else{val body=JSONObject().put("name",d.getString("name").trim()).put("resources",d.getJSONArray("resources"));api.json(if(id.isEmpty())"/v1/playlists" else "/v1/playlists/$id",if(id.isEmpty())"POST" else "PUT",body)}
        };draft=null;message="已保存";refresh()
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)launch{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)!!.use{it.write(JSONObject().put("memories",rows).put("withdrawals",events).toString(2).toByteArray())}};message="偏好记录已保存，请保管在私有位置"}}
    LaunchedEffect(kind){launch{refresh()}};DisposableEffect(api){onDispose{api.cancel()}};BackHandler{exit()}
    Page(if(draft!=null)if(memory)"审核偏好" else "编辑清单" else if(memory)"偏好与记忆" else "播放清单",message,busy,onBack={exit()}){
        val d=draft
        if(d==null){
            if(memory){Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){for((state,label) in listOf("pending" to "待审核","approved" to "已批准","suspended" to "停用待核对","rejected" to "已拒绝"))FilterChip(filter==state,{filter=state},label={Text(label)})}}
            val visible=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{!memory||it.optString("state")==filter}
            if(visible.isEmpty())EmptyState(if(memory)"此分类暂无偏好" else "没有播放清单",if(memory)"只保存经家长审核的明确偏好。" else "先在资源库发布内容，再创建播放清单。")
            else DesignGroup{visible.forEachIndexed{index,row->DesignRow(if(memory)row.getJSONObject("body").getString("content") else row.getString("name"),if(memory)row.getJSONObject("body").optString("source") else "${row.getJSONArray("resources").length()} 项已选内容",icon="book",divider=index<visible.lastIndex){edit(row)}}}
            FullAction(if(memory)"添加明确偏好" else "创建播放清单",enabled=!busy){edit(null)}
            if(memory){SectionHeading("孩子的撤回处理");if(events.length()==0)Text("暂无撤回记录")
                for(i in 0 until events.length()){val e=events.getJSONObject(i);InfoRow(when(e.optString("action")){"related_deleted"->"删除刚提出的偏好";"all_deleted"->"删除全部偏好";else->"已停用，等待核对"},"${e.optInt("count")} 项")}
                Text("待审、拒绝和停用的内容不用于跨会话个性化。")
                FullAction("导出偏好记录",secondary=true,enabled=!busy){export.launch("family-preferences.json")}
            }else{SectionHeading("主动播放");SectionLink("英语计划","默认关闭，按星期与时段安排"){open("英语计划")}}
        }else if(memory){
            JsonField(d,"content","明确偏好（最多300字）",3);Input("有效天数（0表示长期）",d.optString("days")){draft=JSONObject(d.toString()).put("days",it)};InfoRow("来源",d.optString("source"))
            Text("审核或修改会改变后续使用的偏好，不会保存孩子的心理标签。")
            FullAction(if(d.optString("id").isEmpty())"添加为待审核" else "修改并批准",enabled=!busy){launch{require(d.optString("content").trim().length in 1..300){"偏好需为 1—300 字"};save()}}
            if(d.optString("id").isNotEmpty()){FullAction("拒绝",secondary=true,enabled=!busy){launch{save("rejected")}};FullAction("删除偏好",secondary=true,enabled=!busy){confirm="delete"}}
        }else{
            JsonField(d,"name","清单名称");Text("清单只引用资源；下架或删除的内容不能继续点播。")
            val available=(0 until resources.length()).map{resources.getJSONObject(it)}.filter{it.optString("status")=="published"}
            if(available.isEmpty())EmptyState("没有已发布资源","先在资源库完成校对、试听和发布。")
            DesignGroup{for(r in available){val id=r.getString("id");val ids=d.getJSONArray("resources");val selected=(0 until ids.length()).map{ids.getString(it)}.toSet();Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(id in selected,{checked->draft=JSONObject(d.toString()).put("resources",JSONArray((if(checked)selected+id else selected-id).toList()))});Text(r.getJSONObject("metadata").getString("title"))}}}
            FullAction("保存清单",enabled=!busy){launch{require(d.optString("name").trim().isNotEmpty()&&d.getJSONArray("resources").length()>0){"请输入名称并选择至少一项已发布资源"};save()}}
            if(d.optString("id").isNotEmpty()){FullAction("播放这个清单",secondary=true,enabled=!busy){confirm="play"};FullAction("删除清单",secondary=true,enabled=!busy){confirm="delete"}}
        }
    }
    if(leaving)AlertDialog(onDismissRequest={leaving=false},title={Text("有未保存修改")},text={Text("离开会放弃当前编辑。")},confirmButton={TextButton(onClick={leaving=false;draft=null}){Text("放弃并离开")}},dismissButton={TextButton(onClick={leaving=false}){Text("继续编辑")}})
    confirm?.let{action->AlertDialog(onDismissRequest={confirm=null},title={Text(if(action=="play")"播放这个清单？" else "确认删除？")},text={Text(if(action=="play")"会结束机器人当前互动，播放前仍会检查使用限制。" else "删除后无法恢复；删除清单不会删除资源库原稿。")},confirmButton={TextButton(onClick={confirm=null;launch{val id=draft!!.getString("id");withContext(Dispatchers.IO){if(action=="play")api.json("/v1/robots/${connection.getString("robotId")}/control","POST",JSONObject().put("requestId",UUID.randomUUID().toString()).put("action","playlist").put("playlistId",id)) else if(memory)api.json("/v1/memories/$id","PUT",JSONObject().put("state","deleted")) else api.json("/v1/playlists/$id","DELETE")};if(action=="play")message="请求已发送，请在首页核对机器人状态" else{draft=null;message="已删除";refresh()}}}){Text("确认")}},dismissButton={TextButton(onClick={confirm=null}){Text("取消")}})}
}

@Composable fun ParentStorageScreen(connection:JSONObject,back:()->Unit,resource:(String)->Unit){
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope()
    var rows by remember{mutableStateOf(JSONArray())};var downloads by remember{mutableStateOf(JSONArray())};var free by remember{mutableStateOf<Long?>(null)}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    fun refresh(){if(busy)return;scope.launch{busy=true;try{withContext(Dispatchers.IO){rows=api.json("/v1/resources").getJSONArray("items");downloads=api.array("/v1/downloads");free=api.json("/v1/diagnostics").optLong("storageFreeBytes")}}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "无法读取下载状态"}finally{busy=false}}}
    LaunchedEffect(api){refresh()};DisposableEffect(api){onDispose{api.cancel()}};BackHandler(onBack=back)
    Page("空间与离线副本",message,busy,onBack=back){
        InfoRow("家庭服务剩余空间",free?.let{"${it/1024/1024} MB"} ?: "待获取")
        Text("手机下载和家庭电脑原稿分开管理。点击资源查看下载、播放或清理副本。")
        val published=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.optString("status")=="published"}
        if(published.isEmpty())EmptyState("暂无已发布资源","发布后可以下载到机器人，离线也能播放。")
        else DesignGroup{published.forEachIndexed{index,r->val d=(0 until downloads.length()).map{downloads.getJSONObject(it)}.firstOrNull{it.optString("resource_id")==r.getString("id")};DesignRow(r.getJSONObject("metadata").getString("title"),d?.let{resourceStatus(it.optString("state"))} ?: "未下载",icon="download",divider=index<published.lastIndex){resource(r.getString("id"))}}}
        FullAction("刷新下载状态",secondary=true,enabled=!busy){refresh()}
        Text("清理手机副本不会删除家庭电脑的原稿和发布版。离线机器人需联网后才能更新状态。")
    }
}

@Composable fun ParentPlansScreen(connection:JSONObject,back:()->Unit){
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope();val rid=connection.getString("robotId")
    var config by remember{mutableStateOf(JSONObject())};var lists by remember{mutableStateOf(JSONArray())};var version by remember{mutableIntStateOf(0)}
    var draft by remember{mutableStateOf<JSONObject?>(null)};var baseline by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};var leave by remember{mutableStateOf(false)};var deleting by remember{mutableStateOf(false)}
    fun launch(block:suspend ()->Unit){if(busy)return;scope.launch{busy=true;try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "请求失败"}finally{busy=false}}}
    suspend fun load(){withContext(Dispatchers.IO){val result=api.json("/v1/robots/$rid/config");config=result.getJSONObject("config");version=result.getInt("version");lists=api.array("/v1/playlists")}}
    suspend fun save(remove:Boolean=false){
        val d=draft ?: return
        if(!remove){require(d.optString("time").matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]"))){"请输入有效开始时间 HH:mm"};require(d.optInt("minutes") in 1..30){"播放时长需为 1—30 分钟"};require(d.getJSONArray("days").length()>0){"请选择星期"}}
        val proposed=JSONObject(config.toString());val plans=proposed.optJSONArray("listeningPlans") ?: JSONArray();val values=(0 until plans.length()).map{plans.getJSONObject(it)}.filter{it.optString("id")!=d.getString("id")}.toMutableList();if(!remove)values.add(d);proposed.put("listeningPlans",JSONArray(values))
        val command=UUID.randomUUID().toString();withContext(Dispatchers.IO){api.json("/v1/robots/$rid/config","POST",JSONObject().put("requestId",command).put("expectedVersion",version).put("config",proposed))}
        var state="pending";repeat(12){if(state=="pending"){delay(1000);state=withContext(Dispatchers.IO){api.json("/v1/commands/$command").getString("state")}}}
        if(state=="applied"){load();draft=null;message="机器人已应用计划"}else message="未确认应用（$state），编辑内容保留，请核对连接与配置版本后重试"
    }
    fun edit(plan:JSONObject?){draft=plan?.let{JSONObject(it.toString())} ?: JSONObject().put("id",UUID.randomUUID().toString().replace("-","")).put("playlistId",lists.getJSONObject(0).getString("id")).put("enabled",false).put("time","18:30").put("minutes",10).put("days",JSONArray((1..7).toList()));baseline=draft.toString();message=""}
    fun exit(){if(busy)return;if(draft==null)back() else if(draft.toString()!=baseline)leave=true else draft=null}
    LaunchedEffect(api){launch{load()}};DisposableEffect(api){onDispose{api.cancel()}};BackHandler{exit()}
    Page(if(draft==null)"英语计划" else "编辑英语计划",message,busy,onBack={exit()}){
        val d=draft
        if(d==null){Text("主动播放默认关闭。只在前台待机且时段、额度允许时开始，错过、重连或重启不补播。")
            val plans=config.optJSONArray("listeningPlans") ?: JSONArray()
            if(plans.length()==0)EmptyState("还没有英语计划") else DesignGroup{for(i in 0 until plans.length()){val p=plans.getJSONObject(i);DesignRow("${p.optString("time")} · ${p.optInt("minutes")} 分钟","${if(p.optBoolean("enabled"))"已启用" else "未启用"} · ${p.getJSONArray("days").length()} 天 / 周",divider=i<plans.length()-1){edit(p)}}}
            FullAction("添加计划",enabled=!busy&&lists.length()>0&&plans.length()<10){edit(null)};if(lists.length()==0)Text("请先创建一个播放清单。")
        }else{
            Toggle(d,"enabled","启用这条计划");Dropdown(d,"playlistId","播放清单",(0 until lists.length()).map{lists.getJSONObject(it).let{x->x.getString("id") to x.getString("name")}})
            val days=d.getJSONArray("days");val selected=(0 until days.length()).map{days.getInt(it)}.toSet()
            Row(Modifier.horizontalScroll(rememberScrollState())){for(day in 1..7)FilterChip(day in selected,{draft=JSONObject(d.toString()).put("days",JSONArray((if(day in selected)selected-day else selected+day).sorted()))},label={Text(listOf("一","二","三","四","五","六","日")[day-1])})}
            JsonField(d,"time","开始时间 HH:mm");NumberField(d,"minutes","本次最多播放（分钟，1—30）")
            Text("计划不能越过禁用时段、每日额度或手动停用。")
            FullAction("保存计划",enabled=!busy){launch{save()}}
            val plans=config.optJSONArray("listeningPlans") ?: JSONArray();if((0 until plans.length()).any{plans.getJSONObject(it).optString("id")==d.optString("id")})FullAction("删除计划",secondary=true,enabled=!busy){deleting=true}
        }
    }
    if(leave)AlertDialog(onDismissRequest={leave=false},title={Text("有未保存修改")},confirmButton={TextButton(onClick={leave=false;draft=null}){Text("放弃并离开")}},dismissButton={TextButton(onClick={leave=false}){Text("继续编辑")}})
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除英语计划？")},text={Text("删除安排不会删除播放清单和资源。")},confirmButton={TextButton(onClick={deleting=false;launch{save(true)}}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}

@Composable fun ImportJobsScreen(connection:JSONObject,back:()->Unit,resource:(String)->Unit){
    val api=remember(connection){Api(connection)};val scope=rememberCoroutineScope();var jobs by remember{mutableStateOf(JSONArray())};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    fun launch(block:suspend ()->Unit){if(busy)return;scope.launch{busy=true;try{block();jobs=withContext(Dispatchers.IO){api.array("/v1/jobs")}}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "读取失败"}finally{busy=false}}}
    LaunchedEffect(api){launch{}};DisposableEffect(api){onDispose{api.cancel()}};BackHandler(onBack=back)
    Page("导入任务",message,busy,onBack=back){
        Text("已完成的页保留；上传结果不明确时先刷新核对，避免重复导入。")
        FullAction("刷新任务",secondary=true,enabled=!busy){launch{}}
        if(jobs.length()==0)EmptyState("还没有导入任务")
        for(i in 0 until jobs.length()){val j=jobs.getJSONObject(i);DesignGroup{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("导入任务 ${i+1} · ${resourceStatus(j.optString("state"))}")
            if(!j.isNull("error"))Text(j.optString("error"),color=MaterialTheme.colorScheme.error)
            FullAction("查看并校对书稿",secondary=true){resource(j.getString("resource_id"))}
            if(j.optString("state") in listOf("failed","interrupted","stale","cancelled"))FullAction("重试导入",secondary=true,enabled=!busy){launch{withContext(Dispatchers.IO){api.json("/v1/jobs/${j.getString("id")}/retry","POST",JSONObject())}}}
            if(j.optString("state") in listOf("queued","processing"))FullAction("取消导入",secondary=true,enabled=!busy){launch{withContext(Dispatchers.IO){api.json("/v1/jobs/${j.getString("id")}/cancel","POST",JSONObject())}}}
        }}}
    }
}
