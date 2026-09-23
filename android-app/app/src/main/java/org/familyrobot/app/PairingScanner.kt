package org.familyrobot.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera frames stay in memory. Closing the dialog unbinds this camera use case. */
@Composable
fun PairingScanner(owner:LifecycleOwner,onDismiss:()->Unit,onResult:(String)->Unit) {
    val context=LocalContext.current
    val latestResult by rememberUpdatedState(onResult)
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf("将机器人上的二维码完整放入画面，会自动识别") }
    DisposableEffect(owner) {
        val active=AtomicBoolean(true)
        val delivered=AtomicBoolean(false)
        val executor=Executors.newSingleThreadExecutor()
        val main=ContextCompat.getMainExecutor(context)
        val future=ProcessCameraProvider.getInstance(context)
        var provider:ProcessCameraProvider?=null
        val analysis=ImageAnalysis.Builder()
            .setTargetResolution(Size(1280,960))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var lastFrame=0L
        analysis.setAnalyzer(executor) { image ->
            try {
                val now=SystemClock.elapsedRealtime()
                if(!active.get() || delivered.get() || now-lastFrame<150)return@setAnalyzer
                lastFrame=now
                val original=image.toBitmap()
                val upright=Bitmap.createBitmap(original,0,0,original.width,original.height,
                    Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) },true)
                val text=runCatching { decodePairingQr(upright) }.getOrNull()
                val valid=text?.let { runCatching { parseConnectionMaterial(it) }.isSuccess }==true
                main.execute {
                    if(active.get()) {
                        frame=upright
                        if(valid && delivered.compareAndSet(false,true))latestResult(text!!)
                        else if(text!=null)message="这不是连接二维码，请扫描机器人管理页显示的二维码"
                    }
                }
                if(original!==upright)original.recycle()
            } catch(_:Exception) {
                main.execute { if(active.get())message="暂时无法读取画面，请调整距离或关闭后重试" }
            } finally { image.close() }
        }
        future.addListener({
            if(active.get())try {
                val camera=future.get();provider=camera
                val selector=if(camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                camera.bindToLifecycle(owner,selector,analysis)
            } catch(_:Exception) { message="无法打开相机，请检查相机权限，或关闭后导入连接文件" }
        },main)
        onDispose {
            active.set(false)
            analysis.clearAnalyzer()
            provider?.unbind(analysis)
            executor.shutdown()
        }
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier=Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("扫描配对二维码",style=MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black),contentAlignment=Alignment.Center) {
                    frame?.let { Image(it.asImageBitmap(),"扫码取景画面",Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
                }
                Text(message,modifier=Modifier.padding(vertical=16.dp))
                Button(onClick=onDismiss) { Text("取消扫码") }
            }
        }
    }
}
