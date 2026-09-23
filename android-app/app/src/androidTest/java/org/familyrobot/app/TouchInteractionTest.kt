package org.familyrobot.app

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.familyrobot.core.FaceTouch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 实际Compose输入链路，验证单击延迟、拖动、双击、管理长按互斥。 */
@RunWith(AndroidJUnit4::class)
class TouchInteractionTest {
    @Test fun gesturesAreExclusiveAndFacesRender() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val vault=Vault(context);val identity=vault.get("identity")
        vault.save("identity",JSONObject().put("mode","setup"))
        val device=UiDevice.getInstance(instrumentation)
        val touches=CopyOnWriteArrayList<FaceTouch>()
        val stops=AtomicInteger();val manages=AtomicInteger()
        val mode=mutableStateOf("standby")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    WindowCompat.getInsetsController(activity.window,activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
                    activity.setContent {
                        RobotFace(mode.value,.6f,false,{ stops.incrementAndGet() },{ manages.incrementAndGet() },{},
                            interact={ touches.add(it);mode.value=when(it) { FaceTouch.TAP -> "waking";FaceTouch.PET -> "pet";FaceTouch.TICKLE -> "tickle" } })
                    }
                }
                Thread.sleep(700)
                val w=device.displayWidth.toFloat();val h=device.displayHeight.toFloat();val u=minOf(w,h)
                val cx=w/2;val cy=h*.46f
                fun gesture(points:List<Pair<Float,Float>>,step:Long=80,hold:Long=0) {
                    val at=SystemClock.uptimeMillis()
                    fun send(action:Int,point:Pair<Float,Float>) {
                        val event=MotionEvent.obtain(at,SystemClock.uptimeMillis(),action,point.first,point.second,0)
                        instrumentation.sendPointerSync(event);event.recycle()
                    }
                    send(MotionEvent.ACTION_DOWN,points.first())
                    for(point in points.drop(1)) { Thread.sleep(step);send(MotionEvent.ACTION_MOVE,point) }
                    Thread.sleep(if(hold>0)hold else 40)
                    send(MotionEvent.ACTION_UP,points.last())
                }
                gesture(listOf(cx to cy));Thread.sleep(550)
                assertEquals(listOf(FaceTouch.TAP),touches.toList())
                touches.clear()
                gesture(listOf(cx to cy));Thread.sleep(90);gesture(listOf(cx to cy));Thread.sleep(550)
                assertEquals(1,stops.get());assertTrue(touches.isEmpty())
                val forehead=cy-u*.16f
                gesture(listOf((cx-u*.16f) to forehead,(cx+u*.1f) to forehead));Thread.sleep(100)
                assertEquals(listOf(FaceTouch.PET),touches.toList());touches.clear()
                val cheek=cy+u*.15f
                gesture(listOf((cx+u*.2f) to cheek,(cx+u*.32f) to cheek,(cx+u*.13f) to cheek));Thread.sleep(100)
                assertEquals(listOf(FaceTouch.TICKLE),touches.toList());touches.clear()
                gesture(listOf(w*.92f to h*.08f),hold=2150);Thread.sleep(300)
                assertEquals(1,manages.get());assertTrue(touches.isEmpty());assertEquals(1,stops.get())
                gesture(listOf(w*.05f to h*.75f));Thread.sleep(550);assertTrue(touches.isEmpty())
                val output=File(context.filesDir,"touch-gallery").apply { mkdirs() }
                for(face in listOf("standby","waking","listening","hearing","thinking","pet","tickle","speaking","muted")) {
                    scenario.onActivity { mode.value=face };Thread.sleep(330)
                    assertTrue(device.takeScreenshot(File(output,"$face.png")))
                }
            }
        } finally { if(identity==null)vault.remove("identity") else vault.save("identity",identity) }
    }
}
