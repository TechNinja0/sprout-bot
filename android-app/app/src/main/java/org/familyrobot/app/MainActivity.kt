package org.familyrobot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity:ComponentActivity() {
    lateinit var vault:Vault
    private var runtime:RobotRuntime?=null
    private var foreground=false
    private var robotManagement=false
    private var preview:MediaPlayer?=null
    private var themeMode by mutableStateOf("dark")
    private var bookPreviewJob:Job?=null
    private var bookPreviewApi:Api?=null
    fun stopPreview(){bookPreviewApi?.cancel();bookPreviewApi=null;bookPreviewJob?.cancel();bookPreviewJob=null;preview?.release();preview=null;File(cacheDir,"preview.audio").delete()}
    private fun chooseTheme(value:String){themeMode=if(value=="light")"light" else "dark";getSharedPreferences("appearance",MODE_PRIVATE).edit().putString("theme",themeMode).apply()}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);WindowCompat.setDecorFitsSystemWindows(window,false);vault=Vault(this)
        themeMode=getSharedPreferences("appearance",MODE_PRIVATE).getString("theme","dark") ?: "dark"
        setContent { RobotTheme(themeMode) { SideEffect { WindowCompat.getInsetsController(window,window.decorView).apply { isAppearanceLightStatusBars=themeMode=="light";isAppearanceLightNavigationBars=themeMode=="light" };@Suppress("DEPRECATION") run { window.statusBarColor=if(themeMode=="light")0xFFF5F7F5.toInt() else 0xFF101F23.toInt();window.navigationBarColor=window.statusBarColor } };Root() } }
    }
    override fun onStart() { super.onStart();foreground=true;if(!robotManagement)runtime?.resumeForeground() }
    override fun onStop() { foreground=false;runtime?.background();stopPreview();super.onStop() }
    override fun onDestroy() { runtime?.close();super.onDestroy() }
    private fun playPreview(data:ByteArray,volume:Float=1f) {
        if(!foreground)return
        stopPreview();val file=File(cacheDir,"preview.audio");file.writeBytes(data)
        preview=MediaPlayer().apply { setDataSource(file.absolutePath);setOnPreparedListener { it.setVolume(volume.coerceIn(0f,1f),volume.coerceIn(0f,1f));it.start() };setOnCompletionListener { it.release();preview=null;file.delete() };prepareAsync() }
    }
    private suspend fun playBookAudio(bytes:ByteArray)=suspendCancellableCoroutine<Unit>{ continuation->
        if(!foreground){continuation.cancel();return@suspendCancellableCoroutine}
        val file=File.createTempFile("book-preview-",".audio",cacheDir);file.writeBytes(bytes)
        val player=MediaPlayer();preview=player
        fun release(){if(preview===player)preview=null;runCatching{player.release()};file.delete()}
        continuation.invokeOnCancellation{release()}
        try{player.setDataSource(file.absolutePath)
            player.setOnPreparedListener{if(continuation.isActive)it.start()}
            player.setOnCompletionListener{release();if(continuation.isActive)continuation.resume(Unit)}
            player.setOnErrorListener{_,_,_->release();if(continuation.isActive)continuation.resumeWithException(IllegalStateException("试听播放失败，请重试"));true}
            player.prepareAsync()
        }catch(e:Exception){release();if(continuation.isActive)continuation.resumeWithException(e)}
    }
    @Composable private fun Root() {
        var mode by remember { mutableStateOf(vault.get("identity")?.optString("mode") ?: "setup") }
        var manage by remember { mutableStateOf(false) }
        var unlock by remember { mutableStateOf(false) }
        var pin by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf("") }
        SideEffect { robotManagement=manage || unlock }
        fun change(next:String) { robotManagement=false;runtime?.close();runtime=null;vault.save("identity",JSONObject().put("mode",next));mode=next;manage=false }
        val robotConnection=remember(mode) { vault.get("robot") }
        val stateConnection=remember(mode){vault.get(mode)}
        val stateOwner=mode+":"+stateConnection?.optString("address").orEmpty()+":"+stateConnection?.optString("deviceId").orEmpty()
        PageNavigationScope(stateOwner) {
        if(mode=="robot" && robotConnection!=null) {
            val robot=remember(robotConnection) { RobotRuntime(this,vault,robotConnection).also { runtime=it;if(foreground)it.resumeForeground() } }
            DisposableEffect(robot) { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);onDispose { robot.close();if(runtime===robot)runtime=null;window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
            DisposableEffect(robot.faceMode,manage,unlock) {
                val original=window.attributes.screenBrightness
                window.attributes=window.attributes.apply { screenBrightness=if(!manage && !unlock && robot.faceMode in setOf("standby","rest","muted","blocked"))0.15f else original }
                onDispose { window.attributes=window.attributes.apply { screenBrightness=original } }
            }
            DisposableEffect(manage) {
                val controller=WindowCompat.getInsetsController(window,window.decorView)
                if(manage)controller.show(WindowInsetsCompat.Type.systemBars()) else { controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;controller.hide(WindowInsetsCompat.Type.systemBars()) }
                onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
            }
            BackHandler { if(manage) { manage=false;robot.resumeForeground() } }
            if(manage) RobotManagement(robot,{ manage=false;robot.resumeForeground() },{ change(it) })
            else RobotFace(robot.faceMode,robot.mouth,robot.config.optBoolean("reducedMotion"),{ robot.pause() },{ robot.background();unlock=true },{ robot.touch() },interact={ robot.touch(it) },expressionIntensity=if(robot.config.optJSONObject("interaction")?.optString("expressionIntensity")=="normal")1f else .6f,feedback=robot.faceFeedback)
        } else if(mode=="parent" && vault.get("parent")!=null) Parent(vault.get("parent")!!,{ change("setup") })
        else Setup { role -> change(role) }
        if(unlock)AlertDialog(onDismissRequest={ unlock=false;pin="";runtime?.resumeForeground() },title={ Text("家长管理") },text={ Column {
            Text("请输入管理 PIN。连续5次失败后等待5分钟。")
            OutlinedTextField(pin,{ pin=it },label={ Text("PIN") },visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
            Text(pinError,color=MaterialTheme.colorScheme.error)
            TextButton(onClick={pinError="请在家庭电脑生成管理员恢复材料，在本机重新登记并设置 PIN。恢复不会显示或猜测旧 PIN。"}){Text("忘记 PIN")}
            if(pinError.startsWith("请在家庭电脑"))TextButton(onClick={unlock=false;pin="";change("setup")}){Text("使用管理员恢复材料")}

        } },confirmButton={ TextButton(onClick={ if(vault.verifyPin(pin)) { unlock=false;manage=true;pin="";pinError="" } else pinError="PIN 不正确或仍在锁定时间内" }) { Text("进入") } },dismissButton={ TextButton(onClick={ unlock=false;pin="";runtime?.resumeForeground() }) { Text("取消") } })
        }
    }
    @Composable private fun Setup(done:(String)->Unit) { SetupScreen(this,vault,done) }
    @Composable private fun RobotManagement(robot:RobotRuntime,close:()->Unit,switch:(String)->Unit) {
        val scope=rememberCoroutineScope();val api=remember { Api(robot.connection) }
        var message by remember { mutableStateOf("") };var pairing by remember { mutableStateOf("") };var requests by remember { mutableStateOf(JSONArray()) }
        var pairingImage by remember { mutableStateOf<Bitmap?>(null) };var showPairing by remember { mutableStateOf(false) }
        var pairingDeadline by remember { mutableLongStateOf(0L) };var creatingPairing by remember { mutableStateOf(false) }
        var devices by remember { mutableStateOf(JSONArray()) };var pin by remember { mutableStateOf("") };var oldPin by remember { mutableStateOf("") };var repeatPin by remember { mutableStateOf("") }
        val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { message="权限已更新，返回机器人后生效" }
        val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null)contentResolver.openOutputStream(uri)?.use { it.write(pairing.toByteArray()) } }
        fun run(action:suspend ()->Unit) { scope.launch { try { action() } catch(e:Exception) { message=e.message ?: "操作失败" } } }
        fun generatePairing() {
            if(creatingPairing)return
            creatingPairing=true;message=""
            run {
                try {
                    val started=android.os.SystemClock.elapsedRealtime()
                    val p=withContext(Dispatchers.IO) { api.json("/v1/pairing","POST",JSONObject()) }
                    val material=parentPairingMaterial(robot.connection,p)
                    val bitmap=withContext(Dispatchers.Default) { pairingQrBitmap(material) }
                    pairing=material;pairingImage=bitmap
                    pairingDeadline=started+p.getInt("expiresIn")*1000L;showPairing=true
                } finally { creatingPairing=false }
            }
        }
        if(showPairing && pairingImage!=null)PairingCodeDialog(pairingImage!!,pairingDeadline,creatingPairing,message,
            onRegenerate={ generatePairing() },onDismiss={
                showPairing=false
                run { requests=withContext(Dispatchers.IO) { api.array("/v1/pairing/pending") } }
            })
        var selectedOffline by remember{mutableStateOf<Pair<String,String>?>(null)}
        var selectedRole by remember{mutableStateOf("robot")}
        var revokeDevice by remember { mutableStateOf<JSONObject?>(null) }
        revokeDevice?.let{d->AlertDialog(onDismissRequest={revokeDevice=null},title={Text("解除家长绑定？")},text={Text("${d.optString("name")}将失去管理权限，需要重新扫码才能绑定。")},confirmButton={TextButton(onClick={revokeDevice=null;run{withContext(Dispatchers.IO){api.json("/v1/devices/${d.getString("id")}","DELETE");devices=api.array("/v1/devices")};message="已解除绑定"}}){Text("解除绑定")}},dismissButton={TextButton(onClick={revokeDevice=null}){Text("取消")}})}
        var page by remember { mutableStateOf("首页") }
        var chat by remember { mutableStateOf(false) }
        LaunchedEffect(page){if(page=="家长与配对")run{withContext(Dispatchers.IO){requests=api.array("/v1/pairing/pending");devices=api.array("/v1/devices")}}}
        var pageHistory by remember{mutableStateOf(listOf<String>())}
        fun go(name:String,backwards:Boolean=false){stopPreview();message="";pageHistory=if(name=="首页")emptyList() else if(backwards)pageHistory.dropLast(1) else if(name!=page)pageHistory+page else pageHistory;page=name}
        fun back(){if(page=="首页")close() else go(pageHistory.lastOrNull() ?: "首页",true)}
        BackHandler { if(chat){chat=false;back()} else back() }
        if(chat){DebugChat(robot.connection,back={chat=false;back()},play={playPreview(it)},title="检查与调试",tabs={DiagnosticTabs(true){if(!it)chat=false}},online=robot.online);return}
        if(page=="服务器连接"){RobotConnectionScreen(robot,{back()},{switch("setup")});return}
        if(page=="主题颜色"){AppearancePage(themeMode,::chooseTheme){back()};return}
        if(page in listOf("唤醒与互动","AI 提示词","声音","隐私与权限")){DeviceSettingsScreen(robot.connection,true,page,{back()},{playPreview(it)});return}
        Page(if(page=="首页")"机器人管理" else if(page=="设置")"小伙伴设置" else if(page=="离线详情")selectedOffline?.second ?: "离线内容" else page,message,creatingPairing,pageKey="robot/$page/${if(page=="离线详情")selectedOffline?.first.orEmpty() else ""}",onBack={back()},header=if(page=="检查与调试")({DiagnosticTabs(false){chat=it}}) else null) {
            when(page){
                "首页" -> {
                    ConnectionCard(robot.online,if(robot.online)"家庭电脑在线，连接正常。" else "暂时无法访问家庭电脑，在线对话不可用。"){go("服务器连接")}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("当前状态：${statusLabel(robot.state.name.lowercase())}",fontSize=13.sp);Text("管理中已暂停采集",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    SectionHeading("常用功能")
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        FeatureTile("家长与配对","扫码绑定 · 管理家长","qr",Modifier.weight(1f)){go("家长与配对")}
                        FeatureTile("检查与调试","设备自检 · 会话调试","settings",Modifier.weight(1f)){go("检查与调试")}
                    }
                    SectionHeading("使用与设置")
                    DesignGroup{
                        DesignRow("使用教程","唤醒、对话与常用手势","book"){go("使用教程")}
                        DesignRow("离线内容","播放已下载的故事和儿歌","download"){go("离线内容")}
                        DesignRow("小伙伴设置","唤醒词、AI 提示词、声音与设备","settings",false){go("设置")}
                    }
                }
                "设置" -> {
                    SectionHeading("个性与外观")
                    DesignGroup{
                        DesignRow("主题颜色",if(themeMode=="light")"浅色" else "深色"){go("主题颜色")}
                        for(name in listOf("唤醒与互动","AI 提示词","声音","隐私与权限"))DesignRow(name,divider=name!="隐私与权限"){go(name)}
                    }
                    SectionHeading("安全与设备")
                    DesignGroup{DesignRow("修改 PIN","进入机器人管理时验证"){go("修改 PIN")};DesignRow("家长设备","查看与解除绑定","qr"){go("家长与配对")};DesignRow("诊断与维护","本机检查与脱敏报告"){go("检查与调试")};DesignRow("离线副本与空间","查看完整下载内容","download",false){go("离线内容")}}
                    SectionHeading("本机信息")
                    InfoRow("当前身份","机器人");InfoRow("应用版本",BuildConfig.VERSION_NAME)
                    SectionHeading("身份与服务")
                    DesignGroup{DesignRow("服务器连接","状态与服务地址","server"){go("服务器连接")};DesignRow("切换身份","选择机器人或家长模式","bot",false){go("切换身份")}}
                }
                "使用教程" -> RobotTutorial()
                "检查与调试" -> {
                    var consent by remember{mutableStateOf(false)}
                    Text("目标：这台机器人手机。临时收音最多 3 秒、识别、取新帧、播放半秒测试音；总计不超过 30 秒。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(consent,{consent=it},enabled=!robot.selfCheckRunning);Text("我已了解临时采集范围")}
                    FullAction(if(robot.selfCheckRunning)"正在自检…" else "开始本机自检",enabled=consent&&!robot.selfCheckRunning){robot.startSelfCheck()}
                    if(robot.selfCheckRunning)FullAction("停止自检",secondary=true){robot.cancelSelfCheck()}
                    if(robot.selfCheckMessage.isNotBlank())Text(robot.selfCheckMessage,fontSize=13.sp)
                    robot.selfCheckReport?.let { report->DesignGroup{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){HardwareReportSummary(report);if(robot.selfCheckTranscript.isNotBlank())Text("临时识别：${robot.selfCheckTranscript}");var heard by remember(report){mutableStateOf(false)};if(report.optString("audioOutput")=="played-unconfirmed")Row(verticalAlignment=Alignment.CenterVertically){Checkbox(heard,{heard=it});Text("我在现场听到了测试音")}}} }
                    Text("不会保存本次录音、画面和识别文字；离开设备检查页会停止采集。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FullAction("检查麦克风与相机权限",secondary=true){permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA))}
                    FullAction("测试昵称与唤醒回应",secondary=true){close();robot.wake()}
                    SectionHeading("运行记录")
                    InfoRow("当前状态",if(robot.selfCheckRunning)"设备检查中" else "管理中暂停采集")
                    InfoRow("最近唤醒",mapOf("touch" to "轻点屏幕","keyword" to "唤醒词","local-control" to "本机控制","none" to "暂无记录")[robot.lastWakeSource] ?: robot.lastWakeSource)
                    InfoRow("最近声音反馈",mapOf("none" to "暂无记录","playing" to "已触发播放","visual-only" to "仅表情回应","muted-output" to "静音")[robot.lastFeedbackResult] ?: robot.lastFeedbackResult)
                    DetailDisclosure("查看详细诊断"){DiagnosticsPanel(robot.connection,robot.selfCheckReport)}
                    DisposableEffect(robot){onDispose{robot.cancelSelfCheck()}}
                }
                "家长与配对" -> {
                    Text("让家长手机扫描这里生成的二维码，在这台机器人上确认后完成绑定。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FullAction(if(creatingPairing)"正在生成二维码…" else "生成家长配对材料",enabled=!creatingPairing&&robot.online){generatePairing()}
                    if(pairing.isNotEmpty()){FullAction("显示配对二维码",secondary=true){showPairing=true};TextButton(onClick={export.launch("family-pairing.json")}){Text("保存配对文件（备用）")}}
                    if(!robot.online)Text("家庭服务连接恢复后才能生成配对二维码。",color=MaterialTheme.colorScheme.error)
                    SectionHeading("待确认请求")
                    if(requests.length()==0)Text("暂无待确认请求",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    for(i in 0 until requests.length()){val p=requests.getJSONObject(i);DesignGroup{Column(Modifier.padding(16.dp)){Text(p.getString("name"));Row{for(approved in listOf(true,false))TextButton(onClick={run{withContext(Dispatchers.IO){api.json("/v1/pairing/${p.getString("id")}/decision","POST",JSONObject().put("approved",approved));requests=api.array("/v1/pairing/pending");devices=api.array("/v1/devices")};message="配对已处理"}}){Text(if(approved)"亲自确认" else "拒绝")}}}}}
                    SectionHeading("已绑定家长")
                    val parents=(0 until devices.length()).map{devices.getJSONObject(it)}.filter{it.optString("role")=="parent"&&it.optInt("revoked")==0}
                    if(parents.isEmpty())Text("还没有绑定家长手机",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else DesignGroup{parents.forEachIndexed{index,d->DesignRow(d.optString("name"),"查看并解除绑定","bot",index<parents.lastIndex){revokeDevice=d}}}
                    FullAction("刷新待确认配对与设备",secondary=true){run{withContext(Dispatchers.IO){requests=api.array("/v1/pairing/pending");devices=api.array("/v1/devices")}}}
                }
                "修改 PIN" -> {
                    Text("修改后，下次进入机器人管理时使用新 PIN。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    for((label,value,setter) in listOf(Triple("当前 PIN",oldPin,{v:String->oldPin=v}),Triple("新 PIN",pin,{v:String->pin=v}),Triple("确认新 PIN",repeatPin,{v:String->repeatPin=v})))OutlinedTextField(value,{setter(it.filter(Char::isDigit).take(12))},label={Text(label)},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                    FullAction("保存新 PIN"){when{!pin.matches(Regex("[0-9]{6,12}"))->message="新 PIN 需要 6–12 位数字";pin!=repeatPin->message="两次输入的新 PIN 不一致";pin==oldPin->message="新 PIN 不能与当前 PIN 相同";!vault.verifyPin(oldPin)->message="当前 PIN 不正确或仍在锁定时间内";else->{vault.setPin(pin);pin="";oldPin="";repeatPin="";message="PIN 已更新"}}}
                }
                "切换身份" -> {
                    Text("同一台手机一次只运行一种身份。已有绑定信息会保留。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    DesignGroup{for((value,label) in listOf("robot" to "机器人身份","parent" to "家长身份"))Row(Modifier.fillMaxWidth().clickable{selectedRole=value}.padding(15.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selectedRole==value,{selectedRole=value});Column{Text(label);Text(if(value=="robot")"显示表情、倾听与陪伴" else "管理资源与使用安排",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                    FullAction("切换到所选身份"){if(selectedRole=="robot")close() else if(vault.get("parent")!=null)switch("parent") else switch("setup")}
                }
                "离线详情" -> {selectedOffline?.let{(rid,title)->
                    InfoRow("内容名称",title);InfoRow("离线状态","完整可用")
                    FullAction("返回小伙伴并播放"){close();robot.playResource(rid)}
                    Text("播放仍遵守已设置的使用时段与时长限制。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }}
                "离线内容" -> {
                    Text("已完整下载的内容，无需连接家庭服务器也能播放。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(robot.offlineItems().isEmpty())Text("暂无完整下载内容，请在家长资源库安排下载")
                    for((rid,title) in robot.offlineItems())SectionLink(title,"已完整下载"){selectedOffline=rid to title;go("离线详情")}
                }
            }
        }
    }
    @Composable private fun Parent(connection:JSONObject,disconnect:()->Unit) {
        val api=remember { Api(connection) };val scope=rememberCoroutineScope()
        var tab by rememberSaveable { mutableStateOf("首页") };var message by remember { mutableStateOf("") };var busy by remember { mutableStateOf(false) }
        var config by remember { mutableStateOf(JSONObject()) };var version by remember { mutableIntStateOf(0) }
        var resourceSearch by rememberNavigationValue("parent/resources/search")
        var resourceFilter by rememberNavigationValue("parent/resources/filter","all")
        var resourceDownloads by remember { mutableStateOf(JSONArray()) }
        var items by remember { mutableStateOf(JSONArray()) };var selected by remember { mutableStateOf<String?>(null) }
        var data by remember { mutableStateOf(JSONArray()) };var models by remember { mutableStateOf(JSONObject()) }
        var summary by remember { mutableStateOf(JSONObject()) }
        var refreshVersion by remember { mutableIntStateOf(0) }
        var editing by remember { mutableStateOf(false) }
        var savedConfig by remember{mutableStateOf("")};var pendingTab by remember{mutableStateOf<String?>(null)};var reloadParent by remember{mutableStateOf(false)};var revokeParent by remember{mutableStateOf(false)}
        val rid=connection.getString("robotId")
        var updateInfo by remember{mutableStateOf<AppUpdateInfo?>(null)};var updatePrompt by remember{mutableStateOf(false)}
        var updating by remember{mutableStateOf(false)};var updateProgress by remember{mutableStateOf(-1)}
        // 每次进入家长端静默检查一次升级；失败不打扰。伙伴端无任何升级提示。
        LaunchedEffect(Unit) { try { AppUpdater.check(api)?.let { updateInfo=it;updatePrompt=true } } catch(_:Exception) {} }
        fun startUpdate() {
            val info=updateInfo ?: return
            if(updating)return
            updating=true;updateProgress=-1
            scope.launch {
                try {
                    val file=AppUpdater.download(this@MainActivity,api,info) { done,total->
                        updateProgress=if(total>0)(done*100/total).toInt() else -1
                    }
                    updating=false;updatePrompt=false
                    if(!AppUpdater.install(this@MainActivity,file))message="升级包已下载；允许安装未知应用后，可在检查更新中重试安装"
                } catch(e:CancellationException) { updating=false;throw e }
                catch(e:Exception) { updating=false;message="升级下载失败："+(e.message ?: e.javaClass.simpleName) }
            }
        }
        var routeHistory by rememberSaveable{mutableStateOf(listOf<String>())};var pendingBack by remember{mutableStateOf(false)}
        fun performSelect(name:String,backwards:Boolean=false) {
            routeHistory=if(name in listOf("首页","设置","记录"))emptyList() else if(backwards)routeHistory.dropLast(1) else if(name!=tab)routeHistory+tab else routeHistory
            stopPreview();refreshVersion++;data=JSONArray();summary=JSONObject();config=JSONObject();message="";selected=null;tab=name }
        fun dirty()=config.length()>0 && savedConfig.isNotEmpty() && config.toString()!=savedConfig
        fun selectTab(name:String,backwards:Boolean=false){if(busy)return;if(dirty()){pendingTab=name;pendingBack=backwards}else performSelect(name,backwards)}
        fun run(action:suspend ()->Unit) { if(busy)return;scope.launch { busy=true;try { action() } catch(e:CancellationException) { throw e } catch(e:Exception) { message=e.message ?: "请求失败" } finally { busy=false } } }
        suspend fun refresh() {
            val targetTab=tab;val requestVersion=++refreshVersion
            val result=withContext(Dispatchers.IO) { JSONObject().apply { when(targetTab) {
                "资源库" -> {put("items",api.json("/v1/resources").getJSONArray("items"));put("data",api.array("/v1/downloads"))}
                "记忆" -> { put("data",api.array("/v1/memories"));put("summary",JSONObject().put("memoryActions",api.array("/v1/memory-actions"))) }
                "清单" -> { put("data",api.array("/v1/playlists"));put("items",api.json("/v1/resources").getJSONArray("items")) }
                "摘要" -> { val value=api.json("/v1/usage/summary");put("summary",value);put("data",value.getJSONArray("days")) }
                "首页","维护","备份","绑定","离线内容" -> { put("data",api.array("/v1/devices"));put("models",api.json("/v1/models")) }
                else -> { put("configuration",api.json("/v1/robots/$rid/config"));if(targetTab=="英语计划")put("data",api.array("/v1/playlists"));if(targetTab=="声音")put("models",api.json("/v1/models")) }
            } } }
            if(targetTab!=tab || requestVersion!=refreshVersion)return
            data=result.optJSONArray("data") ?: JSONArray()
            if(targetTab=="资源库")resourceDownloads=data
            result.optJSONArray("items")?.let { items=it }
            result.optJSONObject("summary")?.let { summary=it }
            result.optJSONObject("models")?.let { models=it }
            result.optJSONObject("configuration")?.let { config=it.getJSONObject("config");version=it.getInt("version");savedConfig=config.toString() }
            }
        LaunchedEffect(tab,selected) { if(selected==null && tab !in listOf("设置","主题颜色","记录","调试","连接","唤醒与互动","AI 提示词","知识库","检查更新")) { busy=true;try { refresh() } catch(e:CancellationException) { throw e } catch(e:Exception) { message=e.message ?: "连接失败" } finally { busy=false } } }
        pendingTab?.let{target->AlertDialog(onDismissRequest={pendingTab=null},title={Text("有未保存修改")},text={Text("离开会放弃当前修改。可以继续编辑并保存后再离开。")},confirmButton={TextButton(onClick={pendingTab=null;performSelect(target,pendingBack)}){Text("放弃并离开")}},dismissButton={TextButton(onClick={pendingTab=null}){Text("继续编辑")}})}
        if(reloadParent)AlertDialog(onDismissRequest={reloadParent=false},title={Text("重新读取设置？")},text={Text("当前未保存的修改将被已生效配置替换。")},confirmButton={TextButton(onClick={reloadParent=false;run{refresh()}}){Text("重新读取")}},dismissButton={TextButton(onClick={reloadParent=false}){Text("保留修改")}})
        if(revokeParent)AlertDialog(onDismissRequest={revokeParent=false},title={Text("解除本手机绑定？")},text={Text("解除后需要在机器人上重新生成配对二维码才能恢复管理。")},confirmButton={TextButton(onClick={revokeParent=false;run{withContext(Dispatchers.IO){api.json("/v1/devices/${connection.getString("deviceId")}","DELETE")};vault.remove("parent");disconnect()}}){Text("解除绑定")}},dismissButton={TextButton(onClick={revokeParent=false}){Text("取消")}})
        if(updatePrompt && updateInfo!=null)AlertDialog(onDismissRequest={ if(!updating)updatePrompt=false },title={Text("发现新版本")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("新版本 ${updateInfo!!.versionName}（当前 ${BuildConfig.VERSION_NAME}）")
            if(updateInfo!!.sizeBytes>0)Text("安装包约 ${updateInfo!!.sizeBytes/1048576} MB",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(updateInfo!!.notes.isNotBlank())Text(updateInfo!!.notes,fontSize=13.sp)
            if(updating)Text(if(updateProgress>=0)"正在下载 ${updateProgress}%…" else "正在下载…",color=MaterialTheme.colorScheme.primary)
        }},confirmButton={TextButton(enabled=!updating,onClick={startUpdate()}){Text(if(updating)"下载中…" else "立即升级")}},dismissButton={TextButton(enabled=!updating,onClick={updatePrompt=false;message="已取消本次升级，可稍后在设置中检查更新"}){Text("暂不升级")}})
        if(selected!=null) { if(editing)ResourceEditor(api,selected!!,rid) { editing=false } else ResourceOverview(api,selected!!,rid,{editing=true},{selected=null});return }
        fun back(){selectTab(routeHistory.lastOrNull() ?: if(tab in listOf("成长","声音","隐私","记忆","摘要","维护","备份","绑定","唤醒与互动","AI 提示词","记录保存","主题颜色","调试","本机身份","检查更新"))"设置" else "首页",true)}
        BackHandler(enabled=tab!="首页"){back()}
        if(tab=="知识库"){KnowledgeScreen(connection){back()};return}
        if(tab=="主题颜色"){AppearancePage(themeMode,::chooseTheme){back()};return}
        if(tab in listOf("唤醒与互动","AI 提示词")){DeviceSettingsScreen(connection,false,tab,{back()},{playPreview(it)});return}
        if(tab=="导入任务"){ImportJobsScreen(connection,{back()},{selected=it;editing=true});return}
        if(tab=="英语计划"){ParentPlansScreen(connection){back()};return}
        if(tab=="离线内容"){ParentStorageScreen(connection,{back()},{selected=it});return}
        if(tab=="连接"){ParentConnectionScreen(connection,{back()},disconnect);return}
        if(tab=="调试"){ParentDiagnosticsScreen(connection,{back()},{playPreview(it)});return}
        if(tab=="清单" || tab=="记忆"){ParentCollectionScreen(connection,tab,{back()},{selectTab(it)});return}
        if(tab=="记录"){RecordsScreen(connection,back={selectTab("首页")},play={playPreview(it)},bottom={ParentNavigation(tab){selectTab(it)}},openUsage={selectTab("摘要")},openHistorySettings={selectTab("记录保存")});return}
        var recovery by remember { mutableStateOf("") }
        var restoreUri by remember{mutableStateOf<Uri?>(null)}
        val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null){restoreUri=uri;message="已选择备份文件，请填写管理员恢复凭据后校验恢复"} }
        fun restoreSelected(){val uri=restoreUri ?: return;run {
            val bytes=withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { readLimited(it,32*1024*1024+1) } }
            require(bytes.size<=32*1024*1024) { "手机恢复限制32MB；请使用电脑恢复工具" }
            val result=withContext(Dispatchers.IO) { api.upload("/v1/backup/restore","restore.zip",bytes,"application/zip",mapOf("X-Recovery" to recovery.trim())) }
            recovery="";restoreUri=null;message="已恢复 ${result.getInt("restoredDrafts")} 个资源草稿、${result.optInt("restoredKnowledgeDrafts")} 条知识草稿，请审核后发布"
        } }
        val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if(uri!=null)run { val bytes=withContext(Dispatchers.IO) { api.raw("/v1/backup") };contentResolver.openOutputStream(uri)!!.use { it.write(bytes) };message="备份已保存；请保管在私有位置" } }
        Page(mapOf("首页" to "家长管理","成长" to "孩子与成长","隐私" to "隐私与权限","摘要" to "使用摘要","维护" to "诊断与服务能力","备份" to "数据与备份","绑定" to "绑定与恢复","离线内容" to "空间与离线副本","记录保存" to "对话记录保存")[tab] ?: tab,message,busy,onBack=if(tab in listOf("首页","设置"))null else ({back()}),bottom=if(tab in listOf("首页","设置","资源库"))({ParentNavigation(tab){selectTab(it)}}) else null) {
            if(tab !in listOf("首页","设置"))TextButton(onClick={if(dirty())reloadParent=true else run{refresh()}},enabled=!busy){Text("刷新") }
            when(tab) {
                "首页" -> ParentDashboard(connection){selectTab(it)}
                "设置" -> {
                    SectionHeading("小伙伴的个性")
                    DesignGroup {
                        DesignRow("主题颜色",if(themeMode=="light")"浅色" else "深色"){selectTab("主题颜色")}
                        DesignRow("唤醒与互动","叫“小蜜桃”或“小蜜蜂”也可以",icon="mic"){selectTab("唤醒与互动")}
                        DesignRow("AI 提示词","编辑默认提示词 · 试聊 · 恢复默认",icon="chat"){selectTab("AI 提示词")}
                        DesignRow("声音","中文、英文、故事 · 试听与音量"){selectTab("声音")}
                        DesignRow("孩子与成长","年龄基准、英语阶段与表达",divider=false){selectTab("成长")}
                    }
                    SectionHeading("使用与数据")
                    DesignGroup {
                        DesignRow("隐私与权限","相机、静音、原创故事与动态"){selectTab("隐私")}
                        DesignRow("知识库","儿童百科、家庭知识与发布审核",icon="book"){selectTab("知识库")}
                        DesignRow("偏好与记忆","待审核、已批准、儿童撤回"){selectTab("记忆")}
                        DesignRow("对话记录保存","保留时长与删除",icon="chat"){selectTab("记录保存")}
                        DesignRow("数据与备份","备份、恢复、空间管理",icon="download"){selectTab("备份")}
                        DesignRow("使用摘要","互动时长与内容类别",divider=false){selectTab("摘要")}
                    }
                    SectionHeading("设备与服务")
                    DesignGroup {
                        DesignRow("诊断与服务能力","分项状态与脱敏报告"){selectTab("维护")}
                        DesignRow("检查与调试","文字、语音与回复回放",icon="chat"){selectTab("调试")}
                        DesignRow("绑定与恢复","管理家长手机、找回 PIN"){selectTab("绑定")}
                        DesignRow("检查更新","当前 ${BuildConfig.VERSION_NAME} · 升级与安装",icon="download"){selectTab("检查更新")}
                        DesignRow("本机身份","切换已授权身份或解除绑定",icon="bot",divider=false){selectTab("本机身份")}
                    }
                }
                "电脑目录导入" -> {
                    Text("在家庭电脑的数据目录 inbox 下，为每本书创建一个子目录，放入图片、PDF 或文本。")
                    DesignGroup{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text("1. 电脑执行管理员目录导入命令")
                        Text("python -m robot_service.cli --data runtime import-directory 书本目录名")
                        Text("2. 回到资源库刷新，找到生成的草稿")
                        Text("3. 逐页校对正文、页序和发音，试听后发布")
                    }}
                    Text("导入不会自动发布。请使用家庭电脑的项目 Python 环境；目录名相对于 inbox。")
                    FullAction("返回资源库"){selectTab("资源库")}
                }
                "阅读与识物指南" -> {RobotTutorial()}
                "本机身份" -> {
                    Text("返回本机已有授权身份，不会复制其他手机的权限。")
                    DesignGroup {
                        DesignRow("家长身份","当前身份 · 已授权"){selectTab("首页")}
                        DesignRow("机器人身份",if(vault.get("robot")!=null)"已有授权" else "尚未配置",icon="bot",divider=false){
                            if(vault.get("robot")!=null){vault.save("identity",JSONObject().put("mode","robot"));recreate()}else disconnect()
                        }
                    }
                    FullAction("连接其他家庭服务",secondary=true){disconnect()}
                }
                "检查更新" -> {
                    Text("升级包由家庭电脑构建后自动发布；只在家长端检查和安装，小伙伴端不受打扰。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    SectionHeading("本机信息")
                    InfoRow("当前身份","家长");InfoRow("当前版本",BuildConfig.VERSION_NAME)
                    updateInfo?.let { info->
                        SectionHeading("可用新版本")
                        DesignGroup{Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("新版本 ${info.versionName}")
                            if(info.notes.isNotBlank())Text(info.notes,fontSize=13.sp)
                            Text("安装包约 ${info.sizeBytes/1048576} MB · 经家庭服务加密通道下载并校验",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }}
                        if(updating)Text(if(updateProgress>=0)"正在下载 ${updateProgress}%…" else "正在下载…",color=MaterialTheme.colorScheme.primary)
                        FullAction(if(updating)"下载中…" else "下载并安装新版本",enabled=!updating){startUpdate()}
                    }
                    FullAction(if(updateInfo==null)"检查更新" else "重新检查更新",secondary=true,enabled=!busy&&!updating){run{
                        val found=AppUpdater.check(api)
                        if(found==null) { updateInfo=null;message="已是最新版本" } else { updateInfo=found;updatePrompt=true }
                    }}
                    Text("首次安装或扫码下载请使用家庭电脑上的下载官网；家长端升级使用已配对的加密通道。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }

                "资源库" -> {
                    var title by remember { mutableStateOf("") };var creating by remember{mutableStateOf(false)};var kind by remember{mutableStateOf("book")};
                    val selection=remember(resourceFilter){JSONObject().put("filter",resourceFilter)}
                    Input("搜索书名 / 标签",resourceSearch){resourceSearch=it}
                    Dropdown(selection,"filter","筛选",listOf("all" to "全部","book" to "图书","story" to "故事","song" to "儿歌","dialogue" to "英语短句","draft" to "草稿","published" to "已发布","unlisted" to "已下架","favorite" to "收藏","downloaded" to "已下载")){resourceFilter=it}
                    FullAction("添加资源"){if(kind=="all")kind="book";creating=true}
                    val downloaded=(0 until resourceDownloads.length()).map{resourceDownloads.getJSONObject(it)}.filter{it.optString("state")=="downloaded"}.map{it.optString("resource_id")}.toSet()
                    val visible=(0 until items.length()).map{items.getJSONObject(it)}.filter{item->val meta=item.getJSONObject("metadata");meta.toString().contains(resourceSearch,true) && when(resourceFilter){"all"->true;"favorite"->meta.optBoolean("favorite");"downloaded"->item.getString("id") in downloaded;"draft","published","unlisted"->item.optString("status")==resourceFilter;else->item.getString("kind")==resourceFilter}}

                    if(visible.isEmpty())EmptyState("暂无符合条件的资源","添加资源后，完成校对与试听再发布给小伙伴。")
                    else DesignGroup{visible.forEachIndexed{index,item->val meta=item.getJSONObject("metadata");DesignRow(meta.getString("title"),"${resourceStatus(item.optString("status"))} · ${item.optInt("pageCount")} 页 · ${if(meta.optString("language")=="en")"英文" else "中文"}",icon="book",divider=index<visible.lastIndex){selected=item.getString("id")}}}
                    SectionHeading("录入与维护")
                    DesignGroup{
                        DesignRow("导入任务","刷新、取消与失败重试",icon="download"){selectTab("导入任务")}
                        DesignRow("电脑目录导入","电脑生成草稿，手机审核发布",icon="book"){selectTab("电脑目录导入")}
                        DesignRow("阅读与识物指南","选书、续读、页章定位和视觉追问",icon="book"){selectTab("阅读与识物指南")}
                        DesignRow("播放清单","组合已审核内容",icon="book",divider=false){selectTab("清单")}
                    }
                    if(creating)AlertDialog(onDismissRequest={if(!busy)creating=false},title={Text("创建资源草稿")},text={Column{Row(Modifier.horizontalScroll(rememberScrollState())){for((key,label) in listOf("book" to "图书","story" to "故事","song" to "儿歌","dialogue" to "英语短句"))FilterChip(kind==key,{kind=key},label={Text(label)})};Input("新资源名称",title){title=it.take(200)}}},confirmButton={TextButton(enabled=title.isNotBlank()&&!busy,onClick={run{val result=withContext(Dispatchers.IO){api.json("/v1/resources","POST",JSONObject().put("kind",kind).put("draft",JSONObject().put("title",title.trim())))};creating=false;editing=true;selected=result.getString("id")}}){Text("创建草稿")}},dismissButton={TextButton(enabled=!busy,onClick={creating=false}){Text("取消")}})
                }

                "成长","使用安排","声音","隐私","记录保存" -> if(config.length()>0 && !busy) {
                    when(tab) {
                        "记录保存" -> {val history=config.optJSONObject("history") ?: JSONObject().put("enabled",false).put("days",7).also{config.put("history",it)};Toggle(history,"enabled","保存后续陪伴对话");Dropdown(history,"days","保留时间",listOf("7" to "7天","30" to "30天","90" to "90天"));Text("默认关闭，不补录过去会话。记录可删除，语音输入不保存。历史回答按文本重新朗读。");SectionLink("查看对话记录"){selectTab("记录")}}
                        "成长" -> { val p=config.getJSONObject("profile");NumberField(p,"ageAtBaseline","基准年龄（3—17）");JsonField(p,"baseline","年龄基准日期 YYYY-MM-DD");Dropdown(p,"englishLevel","英语阶段",listOf("beginner" to "零基础","basic" to "基础","intermediate" to "进阶"));Dropdown(p,"expression","表达方式",listOf("adaptive" to "随年龄适配","simple" to "保持简单"));Text("年龄随日期增长；英语阶段由家长观察后调整，不因生日自动升级。") }
                        "使用安排" -> { val p=config.getJSONObject("policy");NumberField(p,"dailyMinutes","每日使用分钟，0表示不设额度");NumberField(p,"mediaMinutes","连续媒体播放上限分钟");Toggle(p,"manualBlocked","立即停用");Dropdown(p,"timezone","家庭时区",listOf("Asia/Shanghai" to "中国标准时间","Europe/London" to "英国时间","America/New_York" to "美国东部时间"));val intervals=p.getJSONArray("intervals")
                            for(i in 0 until intervals.length()) { val interval=intervals.getJSONObject(i);Text("禁用时段 ${i+1}");JsonField(interval,"start","开始 HH:mm");JsonField(interval,"end","结束 HH:mm");var days by remember(interval) { mutableStateOf((0 until interval.getJSONArray("days").length()).map { interval.getJSONArray("days").getInt(it) }.toSet()) };Row(Modifier.horizontalScroll(rememberScrollState())) { for(d in 1..7)FilterChip(d in days,{ days=if(d in days)days-d else days+d;interval.put("days",JSONArray(days.sorted())) },label={ Text(listOf("一","二","三","四","五","六","日")[d-1]) }) };Action("删除这个禁用时段") { intervals.remove(i);config=JSONObject(config.toString()) } }
                            Action("添加禁用时段",enabled=intervals.length()<14) { intervals.put(JSONObject().put("days",JSONArray((1..7).toList())).put("start","20:00").put("end","09:00"));config=JSONObject(config.toString()) }
                            Action("临时放行15分钟") { p.put("overrideUntil",System.currentTimeMillis()/1000.0+890);message="请点击保存应用；临时放行不会取消手动停用" }
                        }
                        "声音" -> {
                            val v=config.getJSONObject("voice")
                            VoiceControls(v,models)
                            var auditionText by remember { mutableStateOf("小伙伴，早上好。我们先把玩具收好，再一起读故事。") }
                            OutlinedTextField(auditionText,{ auditionText=it.take(600) },label={ Text("试听文字") },modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5)
                            Row { for((story,label) in listOf(false to "试听回答语气",true to "试听故事语气"))Action(label,enabled=!busy && auditionText.isNotBlank()) { run {
                                val audio=withContext(Dispatchers.IO) { api.raw("/v1/speech/preview","POST",JSONObject().put("text",auditionText).put("voice",v).put("story",story).toBody()) }
                                playPreview(audio)
                            } } }
                            Text("试听使用当前未保存的设置。保存后，机器人回答使用回答语气；原创故事使用故事语气。书库朗读使用资源自己的声音设置。固定离线提示音需在电脑重新生成。",fontSize=13.sp)
                            SectionLink("唤醒与互动","昵称、唤醒回应、灵敏度与触摸表演"){selectTab("唤醒与互动")}
                        }
                        "隐私" -> { Toggle(config,"cameraAllowed","允许会话内看图");Toggle(config,"muted","麦克风静音");Toggle(config,"reducedMotion","减少表情动态");Toggle(config,"originalStories","允许原创故事");Text("所有音视频和对话模型在家庭电脑运行。云端文本、音频、图像出站均关闭。原稿保存在资源库；会话相机画面不保存。") }
                    }
                    FullAction("保存并等待机器人应用",enabled=!busy) { run {
                        val command=UUID.randomUUID().toString()
                        withContext(Dispatchers.IO) { api.json("/v1/robots/$rid/config","POST",JSONObject().put("requestId",command).put("expectedVersion",version).put("config",config)) }
                        message="已发送，等待机器人确认"
                        var state="pending"
                        repeat(12) { if(state=="pending") { delay(1000);state=withContext(Dispatchers.IO) { api.json("/v1/commands/$command").getString("state") } } }
                        message=when(state) { "applied" -> "机器人已应用配置";"pending","expired" -> "未确认，输入保留；请核对当前版本后重试";else -> "配置状态：$state" };if(state=="applied")refresh()
                    } }
                }
                "摘要" -> { Text("使用记录按本机实际互动及播放累计，不记录逐句聊天。空闲等待不计入额度。")
                    if(data.length()==0)EmptyState("暂无使用记录")
                    for(i in 0 until data.length()) { val day=data.getJSONObject(i);InfoRow(day.getString("day"),"${day.getInt("seconds")/60} 分钟") }
                    Text("待审核偏好：${summary.optInt("pendingMemories")} 条")
                    val categories=summary.optJSONArray("readCategories") ?: JSONArray()
                    val kinds=mapOf("book" to "图书","story" to "故事","song" to "儿歌","dialogue" to "英语短句")
                    for(i in 0 until categories.length()) { val item=categories.getJSONObject(i);Text("${kinds[item.getString("kind")] ?: item.getString("kind")}：已有阅读记录 ${item.getInt("resources")} 项") }
                    val failures=summary.optJSONObject("serviceFailures") ?: JSONObject()
                    Text("当前机器人登记期间：问答失败 ${failures.optInt("turn")}，媒体失败 ${failures.optInt("media")}，下载失败 ${failures.optInt("download")}，连接中断 ${failures.optInt("network")} 次")
                    Text("内容类别按仍保留的播放进度统计，删除后不再计入；失败次数离线后需联网同步。时长与次数不代表学习效果。",fontSize=12.sp)
                }
                "维护","备份","绑定","离线内容" -> {
                    if(tab=="维护"){
                        DiagnosticsPanel(connection)
                        DesignGroup{
                            DesignRow("检查与调试","本手机试聊；机器人硬件需到本机检查",icon="chat"){selectTab("调试")}
                            DesignRow("空间与离线副本","下载状态与清理",icon="download"){selectTab("离线内容")}
                            DesignRow("连接与故障恢复","查看身份验证和服务器连接",divider=false){selectTab("连接")}
                        }
                    }
                    var downloads by remember { mutableStateOf(JSONArray()) }
                    if(tab=="离线内容")Action("查看机器人离线资源状态") { run { downloads=withContext(Dispatchers.IO) { api.array("/v1/downloads") } } }
                    for(i in 0 until downloads.length()) { val d=downloads.getJSONObject(i);Text("${d.optString("resource_id")} · ${d.optString("state")}",fontSize=12.sp) }
                    if(tab=="备份") {
                    SectionLink("空间与离线副本","手机下载和家庭原稿分开管理"){selectTab("离线内容")}
                    SectionHeading("资源库备份")
                    Text("备份含私人原稿和正文，请保管在私有位置，不作为公开诊断附件。")
                    FullAction("导出资源库备份") { backup.launch("family-library.zip") }
                    OutlinedTextField(recovery,{ recovery=it },label={ Text("电脑管理员恢复凭据") },visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    FullAction(if(restoreUri==null)"选择备份文件" else "已选择备份文件 · 重新选择",secondary=true,enabled=!busy){restore.launch(arrayOf("application/zip","application/octet-stream"))}
                    FullAction("校验并恢复为待审核草稿",enabled=!busy&&restoreUri!=null&&recovery.isNotBlank()){restoreSelected()}
                    Text("备份不包含设备凭据和生成音频。恢复后重新审核草稿、发布并生成音频；已删除内容不被旧备份复活。")
                    }
                    if(tab=="绑定") {
                    SectionLink("已绑定家长手机","在机器人 PIN 管理页配对新手机、撤销旧手机"){message="请在机器人手机进入：机器人管理 → 家长与配对。家长手机不能确认自己的配对请求。"}
                    SectionHeading("忘记 PIN 或手机丢失")
                    Text("手机丢失：在机器人管理页撤销旧家长绑定并重新配对。忘记 PIN：在家庭电脑生成恢复材料，在机器人本机重新登记。")
                    FullAction("撤销本机家长凭据并退出",secondary=true) { revokeParent=true }
                    Action("切换身份 / 连接设置",action=disconnect)
                    }
                }
            }
        }
    }
    @Composable private fun ResourceEditor(api:Api,rid:String,robotId:String,back:()->Unit) {
        val scope=rememberCoroutineScope();var item by remember { mutableStateOf<JSONObject?>(null) };var message by remember { mutableStateOf("") };var busy by remember { mutableStateOf(false) }
        var jobs by remember { mutableStateOf(JSONArray()) };var voiceModels by remember { mutableStateOf(JSONObject()) };var globalVoice by remember { mutableStateOf<JSONObject?>(null) }
        var editingPage by remember{mutableStateOf(false)};var pageIndex by remember { mutableIntStateOf(0) };var source by remember { mutableStateOf<Bitmap?>(null) };var delete by remember { mutableStateOf(false) }
        var purpose by rememberSaveable { mutableStateOf("pages") };var targetPage by rememberSaveable { mutableStateOf("") };var capturePath by rememberSaveable { mutableStateOf("") }
        var importing by remember { mutableStateOf(false) };var cancelImport by remember { mutableStateOf(false) }
        var captureBatch by rememberSaveable { mutableStateOf(false) };var capturedPages by rememberSaveable { mutableStateOf(listOf<String>()) }
        var pageToDelete by remember{mutableStateOf<String?>(null)};var lastPreviewedDraft by remember{mutableStateOf("")};var auditionConfirmed by remember{mutableStateOf(false)};var scopeConfirmed by remember{mutableStateOf(false)};var auditionPage by remember{mutableIntStateOf(0)};var previewing by remember{mutableStateOf(false)}
        var stage by rememberSaveable{mutableIntStateOf(0)};var savedDraft by remember{mutableStateOf("")};var leaveEditor by remember{mutableStateOf(false)}
        var editorRoute by rememberSaveable{mutableStateOf("main")};var editorHistory by rememberSaveable{mutableStateOf(listOf<String>())}
        fun invalidateAudition(){auditionConfirmed=false;scopeConfirmed=false;lastPreviewedDraft="";item?.getJSONObject("draft")?.put("auditioned",false)}
        fun draftChanged(){stopPreview();invalidateAudition();item=item?.let{JSONObject(it.toString())}}
        fun movePage(id:String,targetId:String){
            val draft=item?.getJSONObject("draft")?:return
            val pages=draft.getJSONArray("pages")
            val selected=pages.optJSONObject(pageIndex)?.optString("id")
            val auditioned=pages.optJSONObject(auditionPage)?.optString("id")
            if(!moveBookPage(draft,id,targetId))return
            val reordered=draft.getJSONArray("pages")
            pageIndex=(0 until reordered.length()).firstOrNull{reordered.getJSONObject(it).optString("id")==selected}?:0
            auditionPage=(0 until reordered.length()).firstOrNull{reordered.getJSONObject(it).optString("id")==auditioned}?:0
            draftChanged()
        }
        fun openEditor(route:String){stopPreview();if(route!=editorRoute){editorHistory=editorHistory+editorRoute;editorRoute=route};message=""}
        fun showReview(){stopPreview();stage=1;editingPage=false;editorRoute="main";editorHistory=emptyList();message=""}
        fun exitEditor(){stopPreview();if(item?.getJSONObject("draft")?.toString()!=savedDraft && item!=null)leaveEditor=true else back()}
        fun editorBack(){
            stopPreview();message=""
            if(editorRoute!="main"){editorRoute=editorHistory.lastOrNull() ?: "main";editorHistory=editorHistory.dropLast(1)}
            else if(editingPage){editingPage=false;source=null}
            else exitEditor()
        }
        fun run(action:suspend ()->Unit) { scope.launch { busy=true;try { action() } catch(e:CancellationException) { throw e } catch(e:Exception) { message=e.message ?: "操作失败" } finally { busy=false } } }
        suspend fun refresh() { val result=withContext(Dispatchers.IO) { Triple(api.json("/v1/resources/$rid"),api.array("/v1/jobs"),api.json("/v1/models")) };item=result.first;globalVoice=item!!.optJSONObject("globalVoice");savedDraft=item!!.getJSONObject("draft").toString();jobs=result.second;voiceModels=result.third;source=null }
        suspend fun save(saveApi:Api=api) {
            val current=item ?: return
            val body=JSONObject().put("expectedVersion",current.getInt("draft_version")).put("draft",JSONObject(current.getJSONObject("draft").toString()))
            item=withContext(Dispatchers.IO) { saveApi.json("/v1/resources/$rid","PUT",body) }
            val saved=item!!.getJSONObject("draft");savedDraft=saved.toString()
            if(saved.optString("voiceSource","custom")=="shared")globalVoice=JSONObject(saved.getJSONObject("voice").toString())
        }
        fun confirmPage(){run{
            val page=item!!.getJSONObject("draft").getJSONArray("pages").getJSONObject(pageIndex)
            require(bookPageCanConfirm(page)){"请补齐正文，或将空白页标记为不朗读"}
            stopPreview();page.put("reviewed",true);invalidateAudition();save()
            val pages=item!!.getJSONObject("draft").getJSONArray("pages")
            val next=((pageIndex+1 until pages.length())+(0 until pageIndex)).firstOrNull{!pages.getJSONObject(it).optBoolean("reviewed")}
            if(next!=null){pageIndex=next;source=null;editingPage=true;message="本页已保存并确认，继续校对下一页"}
            else{editingPage=false;stage=2;message="全部页面已校对，确认收录范围后即可发布；试听可选"}
        }}
        fun audition(index:Int,forPublish:Boolean){
            if(previewing||busy)return
            val current=item?:return;val draft=current.getJSONObject("draft")
            val originalAudio=forPublish&&draft.optString("audioAsset").isNotBlank()
            val page=draft.getJSONArray("pages").optJSONObject(index)
            if(!originalAudio&&(page==null||page.optBoolean("skip")||page.optString("text").isBlank())){message="本页没有可试听的正文，或已设为不朗读";return}
            val pageId=page?.getString("id").orEmpty()
            stopPreview();auditionConfirmed=false;lastPreviewedDraft="";draft.put("auditioned",false);previewing=true
            val audioApi=Api(api.connection);bookPreviewApi=audioApi
            bookPreviewJob=scope.launch{
                try{
                    // shared 保存时会解析最新声音；版本、声音及确认快照必须取保存后的值。
                    message="正在保存试听草稿"
                    save(audioApi)
                    val saved=item!!;val copy=JSONObject(saved.getJSONObject("draft").toString())
                    val snapshot=bookAuditionKey(copy);val version=saved.getInt("draft_version")
                    if(originalAudio){message="正在试听原录音";val data=withContext(Dispatchers.IO){audioApi.raw("/v1/assets/${copy.getString("audioAsset")}")};playBookAudio(data)}
                    else {
                        val plan=withContext(Dispatchers.IO){audioApi.json("/v1/resources/$rid/speech-plan?expectedVersion=$version")}
                        val segments=bookPreviewSegments(plan,pageId,forPublish)
                        require(segments.isNotEmpty()){"本页没有可试听的正式朗读分段"}
                        for((part,segment) in segments.withIndex()){
                            val savedPages=copy.getJSONArray("pages")
                            val position=(0 until savedPages.length()).firstOrNull{savedPages.getJSONObject(it).optString("id")==segment.optString("pageId")}
                            val label=segment.optString("label").takeIf{it.isNotBlank()}?.let{" · $it"}.orEmpty()
                            message="正在试听第 ${(position?:index)+1} 页$label · ${part+1}/${segments.size} 段"
                            val segmentId=Uri.encode(segment.getString("id"))
                            val data=withContext(Dispatchers.IO){audioApi.raw("/v1/resources/$rid/speech-preview/$segmentId?expectedVersion=$version")}
                            ensureActive();playBookAudio(data)
                        }
                    }
                    if(bookAuditionKey(item!!.getJSONObject("draft"))==snapshot){
                        if(forPublish)lastPreviewedDraft=snapshot
                        message=if(forPublish)"试听完成，可重播或选择其他页。" else "本页试听完成；确认正文和读音后，点击保存并确认本页。"
                    }
                }catch(e:CancellationException){message="试听已停止";throw e}
                catch(e:Exception){message="试听失败：${e.message}"}
                finally{previewing=false;bookPreviewJob=null;if(bookPreviewApi===audioApi)bookPreviewApi=null}
            }
        }
        fun regenerateAndAudition(index:Int,forPublish:Boolean){
            if(previewing||busy)return
            val draft=item?.getJSONObject("draft")?:return
            if(draft.optString("audioAsset").isNotBlank())return
            val page=draft.getJSONArray("pages").optJSONObject(index)?:return
            if(page.optBoolean("skip")||page.optString("text").isBlank())return
            val variant=page.optInt("synthesisVariant",0)
            if(variant>=1_000_000){message="本页重新生成次数已达上限";return}
            page.put("synthesisVariant",variant+1);draftChanged();audition(index,forPublish)
        }
        DisposableEffect(Unit){onDispose{stopPreview()}}
        suspend fun awaitJob(jobId:String):JSONObject {
            try {
                return withTimeout(210_000) {
                    var result:JSONObject
                    do {
                        if(cancelImport)withContext(Dispatchers.IO) { api.json("/v1/jobs/$jobId/cancel","POST",JSONObject()) }
                        result=withContext(Dispatchers.IO) { api.json("/v1/jobs/$jobId") }
                        if(result.getString("state") in setOf("queued","processing"))delay(700) else break
                    } while(true)
                    result
                }
            } catch(e:TimeoutCancellationException) { error("解析仍在服务端进行，已停止后续上传。请稍后刷新任务状态，再继续录入。") }

        }
        fun importUris(uris:List<Uri>,selectedPurpose:String,selectedTarget:String="") { if(uris.isNotEmpty())run {
            require(uris.size<=300) { "每批最多300个文件" }
            importing=true;cancelImport=false
            var failures=0;var imported=0
            try {
                save()
                for((index,uri) in uris.withIndex()) {
                    if(cancelImport)break
                    message="正在录入 ${index+1}/${uris.size}；完成后请校对顺序和原文"
                    val filename=contentResolver.query(uri,null,null,null,null)?.use { c -> if(c.moveToFirst())c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else "source.txt" } ?: "source.txt"
                    val uploaded=try {
                        withContext(Dispatchers.IO) {
                            val bytes=contentResolver.openInputStream(uri)!!.use { input -> readLimited(input,32*1024*1024+1) }
                            require(bytes.size<=32*1024*1024) { "单个文件限制32MB" }
                            api.upload("/v1/resources/$rid/assets?purpose=$selectedPurpose&expectedVersion=${item!!.getInt("draft_version")}&targetPageId=${Uri.encode(selectedTarget)}",filename,bytes)
                        }
                    } catch(e:CancellationException) { throw e } catch(e:Exception) {
                        // 网络中断时不能确定是否已上传；停止队列，避免重复或覆盖版本。
                        throw IllegalStateException("第${index+1}个文件上传未确认：${e.message}。已停止后续上传，请刷新后核对。")
                    }
                    if(uri.authority=="$packageName.capture")uri.lastPathSegment?.let { name -> File(File(cacheDir,"book-capture"),File(name).name).delete() }
                    if(uploaded.has("jobId")) {
                        val task=awaitJob(uploaded.getString("jobId"));val state=task.getString("state")
                        if(state=="stale")error("草稿已被其他操作修改，已停止批量录入；请刷新后核对。")
                        if(state=="needs_review") { imported++;failures+=JSONObject(task.optString("result","{}")).optInt("failedPages") }
                        else failures++
                    } else imported++
                    refresh()
                }
                message="${if(cancelImport)"已停止" else "录入完成"}：$imported 个文件已处理，$failures 项需检查失败原因；内容保留为草稿。"
            } finally { importing=false;refresh() }
        } }
        LaunchedEffect(rid) { busy=true;try { refresh() } catch(e:Exception) { message=e.message ?: "资源读取失败" } finally { busy=false } }
        BackHandler(enabled=busy) { message="正在处理素材，请先停止导入或等待处理完成。" }
        BackHandler(enabled=!busy) { editorBack() }
        val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)importUris(listOf(uri),purpose,targetPage) }
        val multiple=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importUris(uris,"pages") }
        val capture=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val path=capturePath;capturePath=""
            if(success && path.isNotBlank()) {
                val file=File(path);val uri=FileProvider.getUriForFile(this,"$packageName.capture",file)
                if(captureBatch)capturedPages=capturedPages+path else importUris(listOf(uri),purpose,targetPage)
            } else { if(path.isNotBlank())File(path).delete();message="已取消拍照，没有修改书页。" }
        }
        fun launchCapture() {
            val directory=File(cacheDir,"book-capture").apply { mkdirs() }
            directory.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>86_400_000 }?.forEach { it.delete() }
            val file=File.createTempFile("page-",".jpg",directory);capturePath=file.absolutePath
            try { capture.launch(FileProvider.getUriForFile(this,"$packageName.capture",file)) }
            catch(e:Exception) { file.delete();capturePath="";message="无法启动相机：${e.message}" }
        }
        val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if(allowed)launchCapture() else { message="未获得相机权限；可以从相册导入图片。" } }
        fun takePhoto(selectedPurpose:String,selectedTarget:String="",batch:Boolean=false) {
            captureBatch=batch;purpose=selectedPurpose;targetPage=selectedTarget
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)launchCapture()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        fun addTextPage(){
            val current=item ?: return;val pages=current.getJSONObject("draft").getJSONArray("pages")
            pages.put(JSONObject().put("id",UUID.randomUUID().toString()).put("text","").put("reviewed",false).put("synthesisVariant",0).put("breakBefore",false))
            pageIndex=pages.length()-1;stage=1;editingPage=true;editorRoute="main";editorHistory=emptyList();item=JSONObject(current.toString());message=""
        }
        val editorTitle=when(editorRoute){"cover"->"录入封面";"import"->"录入正文";"audio"->"导入已有音频";"jobs"->"本书导入任务";else->if(stage==1)if(editingPage)"校对本页" else "逐页校对" else if(stage==2)"试听与发布" else if(item?.optString("kind")=="book")"录入图书" else "编辑资源草稿"}
        val editorPageId=if(editingPage)item?.getJSONObject("draft")?.getJSONArray("pages")?.optJSONObject(pageIndex)?.optString("id").orEmpty() else "list"
        val editorPageKey="book/$rid/editor/$editorRoute/$stage/$editorPageId"
        val editorScroll=rememberPageScroll(editorPageKey,!busy&&item!=null)
        var reviewViewport by remember{mutableStateOf(Rect.Zero)}
        val currentDraft=item?.getJSONObject("draft")
        val publishReady=currentDraft?.let{bookReadyToPublish(item!!.getString("kind"),it)&&(it.optString("voiceSource","custom")=="shared"||it.getJSONObject("voice").optDouble("speed",1.0) in 0.7..1.3)}==true
        val auditionValid=currentDraft?.let{bookAuditionKey(it)==lastPreviewedDraft}==true
        val pageMessage=if(editorRoute=="main"&&(stage==2||stage==1&&editingPage)&&message.contains("试听"))"" else message
        Page(editorTitle,pageMessage,busy,onBack={if(!busy)editorBack()},scroll=editorScroll,pageKey=editorPageKey,onScrollViewport={reviewViewport=it},
            header={EditorSteps(stage,{stopPreview();stage=it;editingPage=false;editorRoute="main";editorHistory=emptyList();message=""},!busy&&!previewing)},
            bottom={if(editorRoute=="main"){
                when(stage){
                    0->BookEntryActions(item!=null&&!busy&&!previewing,save={run{save();message="草稿已保存"}},next={showReview()})
                    1->if(editingPage)BookFooter("保存并确认本页","保存，稍后校对",!busy&&!previewing,
                        primaryEnabled=!busy&&!previewing&&currentDraft?.getJSONArray("pages")?.optJSONObject(pageIndex)?.let{bookPageCanConfirm(it)}==true,
                        onPrimary={confirmPage()},onSecondary={run{save();editingPage=false;message="草稿已保存，可稍后继续校对"}})
                    else BookFooter("试听与发布","保存草稿",!busy&&!previewing,onPrimary={stage=2},onSecondary={run{save();message="草稿已保存"}})
                    2->BookFooter("发布给机器人","保存草稿",!busy&&!previewing,
                        primaryEnabled=!busy&&!previewing&&publishReady&&scopeConfirmed,
                        onPrimary={run{
                            val draft=item!!.getJSONObject("draft")
                            draft.put("auditioned",auditionValid&&auditionConfirmed)
                            require(bookReadyToPublish(item!!.getString("kind"),draft)){"请完成校对与收录范围确认"}
                            save();withContext(Dispatchers.IO){api.json("/v1/resources/$rid/publish","POST",JSONObject().put("expectedVersion",item!!.getInt("draft_version")).put("requestId",UUID.randomUUID().toString()))}
                            Toast.makeText(this@MainActivity,"发布成功",Toast.LENGTH_SHORT).show();back()
                        }},onSecondary={run{save();message="草稿已保存"}})
                }
            }}) {
            if(importing)FullAction("停止后续导入",secondary=true,enabled=!cancelImport) { cancelImport=true;message="正在停止导入，已处理的页面会保留。" }
            val current=item
            if(current!=null && !busy) {
                val draft=current.getJSONObject("draft");val pages=draft.getJSONArray("pages")
                if(editorRoute=="cover"){
                    Text(if(draft.optString("coverAsset").isBlank())"为图书添加封面，方便小伙伴识别和查找。" else "已录入封面，可以拍摄或选择图片替换。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    DesignGroup{
                        DesignRow("拍摄封面","对准整张封面，保持文字清晰","camera"){takePhoto("cover")}
                        DesignRow("从相册选择封面","选择一张已有图片","image",false){purpose="cover";targetPage="";import.launch(arrayOf("image/*"))}
                    }
                    Text("封面只用于找书，不会作为正文朗读。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }else if(editorRoute=="import"){
                    Text("选择一种方式录入正文",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    DesignGroup{
                        DesignRow("单页拍摄","拍摄一页后识别为待校对草稿","camera"){takePhoto("pages")}
                        DesignRow("连续拍摄书页","按书页顺序拍摄，确认后统一导入","camera"){capturedPages=emptyList();takePhoto("pages",batch=true)}
                        DesignRow("批量选择书页图片","从相册选择多张书页图片","image"){multiple.launch(arrayOf("image/*"))}
                        DesignRow("导入 PDF / 文本","支持 PDF、TXT 和 Markdown","book"){purpose="pages";targetPage="";import.launch(arrayOf("application/pdf","text/plain","text/markdown","text/x-markdown"))}
                        DesignRow("手工添加文字页","粘贴文字，或为无字绘本编写讲述稿","chat",false){addTextPage()}
                    }
                    Text("每批最多 300 个文件，单个文件不超过 32 MB。图片按选择器返回的顺序导入，请在校对页检查页序。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick={openEditor("jobs")}){Text("查看导入任务")}
                }else if(editorRoute=="audio"){
                    Text("导入已有故事录音或儿歌，播放时保留原声音色与情感。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    DesignGroup{DesignRow(if(draft.optString("audioAsset").isBlank())"选择音频文件" else "替换音频文件","MP3 / M4A / WAV · 单个文件最多 32 MB","music",false){purpose="audio";targetPage="";import.launch(arrayOf("audio/*"))}}
                    if(draft.optString("audioAsset").isNotBlank())FullAction("试听已上传原音频",secondary=true){run{val audio=withContext(Dispatchers.IO){api.raw("/v1/assets/${draft.getString("audioAsset")}")};playPreview(audio)}}
                    Text("导入后仍需在「试听与发布」中确认，不会自动发布或播放。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }else if(editorRoute=="jobs"){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("本书导入进度",Modifier.weight(1f));TextButton(onClick={run{save();refresh()}}){Text("刷新导入结果")}}
                    var count=0
                    for(i in 0 until jobs.length()){val task=jobs.getJSONObject(i)
                        if(task.getString("resource_id")==rid){
                            count++;val state=task.getString("state")
                            DesignGroup{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text("素材 $count · ${resourceStatus(state)}")
                                if(!task.isNull("error"))Text(task.getString("error"),fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                                if(state in setOf("failed","interrupted","stale","cancelled"))TextButton(onClick={run{save();withContext(Dispatchers.IO){api.json("/v1/jobs/${task.getString("id")}/retry","POST",JSONObject())};refresh()}}){Text("重试导入")}
                                if(state in setOf("queued","processing"))TextButton(onClick={run{withContext(Dispatchers.IO){api.json("/v1/jobs/${task.getString("id")}/cancel","POST",JSONObject())};refresh()}}){Text("取消导入")}
                            }}
                        }
                    }
                    if(count==0)EmptyState("还没有导入任务","拍摄书页或选择文件后，可在这里查看处理结果。")
                    Text("OCR 只生成待校对草稿；已完成的页面会保留，失败项目可以单独重试。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FullAction("查看已录入书页"){showReview()}
                    FullAction("继续录入",secondary=true){editorRoute="import";editorHistory=listOf("main");message=""}
                }else if(stage==0){
                    val taskCount=(0 until jobs.length()).count{jobs.getJSONObject(it).getString("resource_id")==rid}
                    BookEntryForm(current,taskCount,open={openEditor(it)},review={showReview()})
                }
                if(stage==1 && editorRoute=="main"){
                    val reviewed=(0 until pages.length()).count{pages.getJSONObject(it).optBoolean("reviewed")}
                    if(!editingPage){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("$reviewed / ${pages.length()} 页已校对",Modifier.weight(1f));TextButton(onClick={stage=0;openEditor("import")}){Text("补页")}}
                        for(warning in bookPageOrderWarnings(pages))Text(warning,color=MaterialTheme.colorScheme.error)
                        Text("列表从上到下就是朗读顺序。长按任一书页拖动调整，靠近上下边缘可继续滚动；轻点进入校对。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("印刷页码仅用于对照原书，修改页码不会自动排序。调整后请保存草稿；已发布图书需重新发布后生效。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(draft.optString("audioAsset").isNotBlank())Text("本书使用整段原录音，调整书页顺序不会重排录音内容。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(pages.length()==0)EmptyState("还没有录入书页","先补页，或为无字绘本编写讲述稿。")
                        else DesignGroup{BookPageOrderList(pages,editorScroll,reviewViewport,!busy&&!previewing&&!importing,
                            onMove={id,target->movePage(id,target)},
                            onOpen={id->stopPreview();pageIndex=(0 until pages.length()).first{pages.getJSONObject(it).getString("id")==id};source=null;editingPage=true})}
                    }else if(pages.length()>0){
                        pageIndex=pageIndex.coerceIn(0,pages.length()-1);val page=pages.getJSONObject(pageIndex)
                        fun changed(){page.put("reviewed",false);draftChanged()}
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("朗读顺序 ${pageIndex+1} / ${pages.length()} · ${if(page.optBoolean("reviewed"))"已校对" else "待校对"}",Modifier.weight(1f),fontSize=13.sp);TextButton(onClick={stopPreview();editingPage=false}){Text("书页列表")}}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(enabled=pageIndex>0&&!previewing,onClick={pageIndex--;source=null}){Text("上一页")};TextButton(enabled=pageIndex<pages.length()-1&&!previewing,onClick={pageIndex++;source=null}){Text("下一页")}}
                        key(page.optString("id")){
                            DetailDisclosure("源稿对照"){
                                if(page.optString("sourceAsset").isBlank())Text("本页来自文本或手工录入，没有源图。",Modifier.padding(16.dp),fontSize=13.sp)
                                else Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                                    FullAction("查看本页源图",secondary=true){run{source=withContext(Dispatchers.IO){val bytes=api.raw("/v1/assets/${page.getString("sourceAsset")}/page/${page.optInt("sourcePage")}?rotation=${page.optInt("sourceRotation")}");BitmapFactory.decodeByteArray(bytes,0,bytes.size)}}}
                                    source?.let{Image(it.asImageBitmap(),"原稿对照",Modifier.fillMaxWidth().heightIn(max=480.dp))}
                                }
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                Column(Modifier.weight(1f)){FormField("印刷页码（可空）",page.optString("label")){page.put("label",it);changed()}}
                                Column(Modifier.weight(1f)){FormField("章节（可空）",page.optString("chapter")){page.put("chapter",it);changed()}}
                            }
                            Text("印刷页码只作原书标记，不改变朗读顺序；可返回书页列表长按拖动调整。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            FormField("准备朗读的正文",page.optString("text"),5){page.put("text",it);changed()}
                            val names=mapOf("BLANK" to "未识别到正文，请核对是否空白页","DUPLICATE" to "与其他页正文相同，请检查是否重复","LOW_CONFIDENCE" to "部分文字识别置信度较低","OCR_FAILED" to "识别失败，可重试或重拍")
                            val warnings=page.optJSONArray("qualityWarnings")?:JSONArray()
                            for(i in 0 until warnings.length())Text(names[warnings.getString(i)]?:warnings.getString(i),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                            BookCheck("本页不朗读",page.optBoolean("skip")){page.put("skip",it);changed()}
                            BookCheck("本页开始新场景（连续朗读时不与前页合成）",page.optBoolean("breakBefore",false)){page.put("breakBefore",it);draftChanged()}
                            Text("空白页、版权页保留页序，也需要确认。页眉、脚注是否朗读，请直接编辑正文。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            DetailDisclosure("发音纠正"){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){PronunciationEditor(page){changed()}}}
                            if(draft.optString("audioAsset").isBlank()){
                                val canAudition=!busy&&!page.optBoolean("skip")&&page.optString("text").isNotBlank()
                                FullAction(if(previewing)"停止试听" else "试听本页",secondary=true,enabled=previewing||canAudition){if(previewing)stopPreview()else audition(pageIndex,false)}
                                FullAction("重新生成并试听",secondary=true,enabled=!previewing&&canAudition){regenerateAndAudition(pageIndex,false)}
                            }
                            else Text("当前使用原录音，无法按正文定位播放；请在发布页试听整段原录音。",fontSize=12.sp)
                            if(message.contains("试听"))Text(message,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                            DetailDisclosure("重新识别与替换"){
                                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                                    if(page.optString("sourceAsset").isNotBlank())for((rotate,label)in listOf(false to "重新识别本页",true to "旋转90°并识别"))FullAction(label,secondary=true,enabled=!previewing){run{
                                        save();val result=withContext(Dispatchers.IO){api.json("/v1/resources/$rid/pages/${page.getString("id")}/extract","POST",JSONObject().put("expectedVersion",item!!.getInt("draft_version")).put("rotation",if(rotate)(page.optInt("sourceRotation")+90)%360 else page.optInt("sourceRotation")))}
                                        val task=awaitJob(result.getString("jobId"));refresh();invalidateAudition();message="本页识别：${task.getString("state")}；请重新校对，其他页面保留。"
                                    }}
                                    else Text("本页没有源图，可重新拍摄替换。",fontSize=12.sp)
                                    FullAction("重拍替换本页",secondary=true,enabled=!previewing){takePhoto("pages",page.getString("id"))}
                                    Text("仅替换本页；识别失败不会用空结果覆盖正文。",fontSize=12.sp)
                                }
                            }
                            DetailDisclosure("页序与删除"){
                                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                                    for((delta,label)in listOf(-1 to "向前移动",1 to "向后移动"))FullAction(label,secondary=true,enabled=pageIndex+delta in 0 until pages.length()&&!previewing){movePage(page.getString("id"),pages.getJSONObject(pageIndex+delta).getString("id"))}
                                    TextButton(onClick={stopPreview();pageToDelete=page.getString("id")}){Text("删除本页",color=MaterialTheme.colorScheme.error)}
                                }
                            }
                        }
                    }
                }
                if(stage==2 && editorRoute=="main"){
                    val reviewed=(0 until pages.length()).count{pages.getJSONObject(it).optBoolean("reviewed")}
                    InfoRow("校对进度","$reviewed / ${pages.length()} 页")
                    if(reviewed<pages.length()||(current.optString("kind")=="book"&&pages.length()==0)){
                        Text("还有页面未确认，请完成逐页校对后发布。",color=MaterialTheme.colorScheme.error)
                        TextButton(onClick={showReview()}){Text("去校对")}
                    }
                    SectionHeading("收录范围")
                    BookSelect("范围",if(draft.optBoolean("complete"))"complete" else "excerpt",listOf("complete" to "完整收录","excerpt" to "节选")){draft.put("complete",it=="complete");draftChanged()}
                    if(!draft.optBoolean("complete"))FormField("节选范围（开始播放前朗读）",draft.optString("excerpt")){draft.put("excerpt",it.take(200));draftChanged()}
                    SectionHeading("朗读设置")
                    val hasAudio=draft.optString("audioAsset").isNotBlank()
                    if(hasAudio)DesignGroup{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("使用原录音");Text("保留原声音色、情感和语速。正文发音纠正不改变原录音。",fontSize=13.sp)}}
                    else {
                        BookSelect("声音来源",draft.optString("voiceSource","custom"),listOf("shared" to "跟随机器人声音","custom" to "本书独立声音")){draft.put("voiceSource",it);draftChanged()}
                        if(draft.optString("voiceSource","custom")=="shared"){
                            Text("与机器人保持一致",fontSize=14.sp)
                            Text("保存草稿时同步机器人当前声音；已发布的声音保持不变。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }else {
                            Text(if(bookVoiceMatchesGlobal(draft.getJSONObject("voice"),globalVoice))"本书独立声音，当前与机器人设置一致" else "本书独立声音，与机器人设置不同",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            FullAction("拷贝机器人当前声音",secondary=true,enabled=globalVoice!=null&&!previewing){run{
                                val latest=withContext(Dispatchers.IO){api.json("/v1/resources/$rid").getJSONObject("globalVoice")}
                                globalVoice=latest;draft.put("voice",JSONObject(latest.toString()));draftChanged();message="已拷贝机器人当前声音，保存后应用到本书"
                            }}
                            BookVoiceForm(draft.getJSONObject("voice"),voiceModels,draft.optString("language")){key,value->draft.getJSONObject("voice").put(key,value);draftChanged()}
                        }
                        BookSelect("阅读方式",draft.optString("readingMode","follow_pages"),listOf("follow_pages" to "跟书朗读","continuous" to "连续故事")){draft.put("readingMode",it);draftChanged()}
                        Text("跟书朗读保留页间停顿，方便对照书页；连续故事可合并关联短页，听起来更连贯，仍支持翻页。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SectionHeading("试听与确认")
                    val playable=(0 until pages.length()).filter{pages.getJSONObject(it).let{p->!p.optBoolean("skip")&&p.optString("text").isNotBlank()}}
                    if(!hasAudio&&playable.isNotEmpty()){
                        if(auditionPage !in playable)auditionPage=playable.first()
                        BookSelect("选择试听页",auditionPage.toString(),playable.map{it.toString() to "第 ${it+1} 页${pages.getJSONObject(it).optString("label").takeIf{v->v.isNotBlank()}?.let{v->" · 页码 $v"}?:""}"}){stopPreview();auditionPage=it.toInt()}
                    }
                    FullAction(if(previewing)"停止试听" else if(hasAudio)"试听原录音" else "试听所选页",secondary=true,enabled=previewing||hasAudio||playable.isNotEmpty()){
                        if(previewing)stopPreview()else audition(auditionPage,true)
                    }
                    if(!hasAudio){
                        FullAction("重新生成并试听",secondary=true,enabled=!previewing&&!busy&&playable.isNotEmpty()){regenerateAndAudition(auditionPage,true)}
                        Text(if(draft.optString("readingMode","follow_pages")=="continuous")"试听包含所选页及同组关联页；重新生成只更新该组，其他组保留。发布复用试听音频。" else "试听与发布使用相同分段；重新生成只更新所选页所在组，其他组保留。发布复用试听音频。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(message.contains("试听"))Text(message,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                    Text(if(hasAudio)"试听原录音可检查声音效果，也可直接确认收录范围后发布。" else "试听可选，用于检查声音和读音；不试听也可在完成校对、确认收录范围后发布。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    BookCheck("已试听并确认声音（可选）",auditionConfirmed&&auditionValid,enabled=auditionValid&&!previewing){auditionConfirmed=it;draft.put("auditioned",it)}
                    BookCheck("已核对完整 / 节选范围",scopeConfirmed,enabled=!previewing){scopeConfirmed=it}
                    Text("内容或声音修改后会清除旧试听记录，建议重新试听。发布生成固定版本，不会自动点播。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(current.getString("status")=="published")SectionLink("查看已发布资源","播放、下载和下架等操作在资源详情中进行"){exitEditor()}
                }
            }
        }
        pageToDelete?.let{id->AlertDialog(onDismissRequest={pageToDelete=null},title={Text("删除本页？")},text={Text("将从工作草稿移除这页，当前发布版本不受影响。")},confirmButton={TextButton(onClick={pageToDelete=null;val current=item!!;val draft=current.getJSONObject("draft");val pages=draft.getJSONArray("pages");draft.put("pages",JSONArray((0 until pages.length()).map{pages.getJSONObject(it)}.filter{it.getString("id")!=id}));draft.put("auditioned",false);auditionConfirmed=false;scopeConfirmed=false;editingPage=false;item=JSONObject(current.toString())}){Text("删除")}},dismissButton={TextButton(onClick={pageToDelete=null}){Text("取消")}})}
        if(leaveEditor)AlertDialog(onDismissRequest={leaveEditor=false},title={Text("保存草稿修改？")},text={Text("保存草稿不会覆盖当前发布版；离开可选择保存或放弃。")},confirmButton={TextButton(onClick={leaveEditor=false;run{save();back()}}){Text("保存后离开")}},dismissButton={Row{TextButton(onClick={leaveEditor=false;back()}){Text("放弃修改")};TextButton(onClick={leaveEditor=false}){Text("继续编辑")}}})
        if(captureBatch && capturePath.isBlank())AlertDialog(
            onDismissRequest={ },title={ Text("已拍摄 ${capturedPages.size} 页") },
            text={ Text("按拍摄顺序录入，之后可逐页校对。相机返回后选择继续拍摄或开始导入。") },
            confirmButton={ TextButton(enabled=capturedPages.isNotEmpty(),onClick={
                val uris=capturedPages.map { FileProvider.getUriForFile(this,"$packageName.capture",File(it)) }
                capturedPages=emptyList();captureBatch=false;importUris(uris,"pages")
            }) { Text("导入已拍书页") } },
            dismissButton={ Column {
                TextButton(enabled=capturedPages.size<300,onClick={ takePhoto("pages",batch=true) }) { Text("继续拍摄") }
                TextButton(onClick={ capturedPages.forEach { File(it).delete() };capturedPages=emptyList();captureBatch=false }) { Text("取消并删除本批照片") }
            } }
        )
        if(delete)AlertDialog(onDismissRequest={ delete=false },title={ Text("删除此资源？") },text={ Text("将删除服务器正文、原稿和生成音频，并记录删除墓碑；离线设备将在联网后清理。") },confirmButton={ TextButton(onClick={ delete=false;run { withContext(Dispatchers.IO) { api.json("/v1/resources/$rid","DELETE") };back() } }) { Text("删除") } },dismissButton={ TextButton(onClick={ delete=false }) { Text("保留") } })
    }
}

@Composable fun Page(title:String,message:String,busy:Boolean,onBack:(()->Unit)?=null,bottom:(@Composable ()->Unit)?=null,header:(@Composable ()->Unit)?=null,scroll:ScrollState?=null,onScrollViewport:((Rect)->Unit)?=null,pageKey:String=title,contentReady:Boolean=!busy,content:@Composable ColumnScope.()->Unit) {
    val pageScroll=scroll ?: rememberPageScroll(pageKey,contentReady)
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)).navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=20.dp,top=9.dp,bottom=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(onBack!=null)IconButton(onClick=onBack,modifier=Modifier.size(44.dp).semantics{contentDescription="返回"}){UiIcon("back",color=MaterialTheme.colorScheme.onSurface)}
            Text(title,fontSize=21.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer){Box(Modifier.size(34.dp),contentAlignment=Alignment.Center){UiIcon("bot",Modifier.size(16.dp))}}
        }
        header?.let{Box(Modifier.padding(start=20.dp,end=20.dp,bottom=18.dp)){it()}}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).onGloballyPositioned{onScrollViewport?.invoke(it.boundsInRoot())}.verticalScroll(pageScroll).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(message.isNotEmpty())Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){Text(message,Modifier.padding(14.dp),fontSize=13.sp)}
            CompositionLocalProvider(LocalPageStateKey provides pageKey){content()};Spacer(Modifier.height(24.dp))
        }
        bottom?.invoke()
    } }
}
@Composable fun Action(text:String,enabled:Boolean=true,action:()->Unit) { Button(onClick=action,enabled=enabled,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),modifier=Modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) { Text(text) } }
@Composable fun Input(label:String,value:String,change:(String)->Unit) { FormField(label,value,change=change) }
@Composable fun JsonField(json:JSONObject,key:String,label:String,lines:Int=1) { var text by remember(json,key) { mutableStateOf(json.optString(key)) };FormField(label,text,lines){text=it;json.put(key,it)} }
@Composable fun NumberField(json:JSONObject,key:String,label:String) { var text by remember(json,key) { mutableStateOf(json.optInt(key).toString()) };Input(label,text) { text=it;json.put(key,it.toIntOrNull() ?: it) } }
@Composable fun DecimalField(json:JSONObject,key:String,label:String) { var text by remember(json,key) { mutableStateOf(json.optDouble(key).toString()) };Input(label,text) { text=it;json.put(key,it.toDoubleOrNull() ?: it) } }
@Composable fun Toggle(json:JSONObject,key:String,label:String) { var checked by remember(json,key) { mutableStateOf(json.optBoolean(key)) };Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Switch(checked,{ checked=it;json.put(key,it) });Spacer(Modifier.width(10.dp));Text(label) } }
@Composable fun Choice(json:JSONObject,key:String,label:String,options:List<Pair<String,String>>) { var selected by remember(json,key) { mutableStateOf(json.optString(key)) };Text(label);Row(Modifier.horizontalScroll(rememberScrollState())) { for((value,name) in options) { FilterChip(selected==value,{ selected=value;json.put(key,value) },label={ Text(name) });Spacer(Modifier.width(6.dp)) } } }

fun readLimited(input:java.io.InputStream,limit:Int):ByteArray {
    val out=java.io.ByteArrayOutputStream();val buf=ByteArray(16384)
    while(out.size()<limit) { val count=input.read(buf,0,minOf(buf.size,limit-out.size()));if(count<0)break;out.write(buf,0,count) };return out.toByteArray()
}

@Composable fun StringListField(json:JSONObject,key:String,label:String) {
    var text by remember(json,key) { mutableStateOf((json.optJSONArray(key) ?: JSONArray()).let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }) }
    OutlinedTextField(text,{ text=it;json.put(key,JSONArray(it.lines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() })) },label={ Text(label) },modifier=Modifier.fillMaxWidth(),maxLines=5)
}
