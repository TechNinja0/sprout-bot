package org.familyrobot.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CameraInput(private val context:Context,private val owner:LifecycleOwner,private val failure:()->Unit) {
    data class Frame(val jpeg:ByteArray,val at:Long)
    @Volatile private var latest:Frame?=null
    @Volatile var ready=false;private set
    private val executor=Executors.newSingleThreadExecutor()
    private var provider:ProcessCameraProvider?=null
    @Volatile private var wanted=false
    @Volatile private var generation=0L
    fun setEnabled(enabled:Boolean) {
        val ticket=synchronized(this) {
            if(enabled==wanted)return
            wanted=enabled
            if(!enabled) { latest=null;ready=false }
            ++generation
        }
        if(!enabled) { provider?.unbindAll();return }
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(!wanted || ticket!=generation)return@addListener
            try {
                provider=future.get()
                val analysis=ImageAnalysis.Builder().setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        if(!wanted || ticket!=generation || SystemClock.elapsedRealtime()-(latest?.at ?: 0)<700)return@setAnalyzer
                        val capturedAt=SystemClock.elapsedRealtime()
                        val padded=image.toBitmap()
                        val cropped=Bitmap.createBitmap(padded,0,0,padded.width,padded.height,Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) },true)
                        val scale=640f/maxOf(cropped.width,cropped.height)
                        val scaled=Bitmap.createScaledBitmap(cropped,(cropped.width*scale).toInt(),(cropped.height*scale).toInt(),true)
                        val bytes=ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,75,bytes)
                        synchronized(this) { if(wanted && ticket==generation) { latest=Frame(bytes.toByteArray(),capturedAt);ready=true } }
                        if(scaled!==cropped)scaled.recycle();if(cropped!==padded)cropped.recycle();padded.recycle()
                    } catch(_:Exception) { if(ticket==generation)latest=null }
                    finally { image.close() }
                }
                val selector=if(provider!!.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider!!.bindToLifecycle(owner,selector,analysis)
            } catch(_:Exception) { wanted=false;latest=null;ready=false;failure() }
        },ContextCompat.getMainExecutor(context))
    }
    fun fresh():Frame?=latest?.takeIf { wanted && SystemClock.elapsedRealtime()-it.at<=1000 }
    fun close() { setEnabled(false);executor.shutdown() }
}
