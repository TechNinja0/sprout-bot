package org.familyrobot.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.familyrobot.core.FaceRegion
import org.familyrobot.core.FaceStroke
import org.familyrobot.core.FaceTouch

/** 首触只给可撤销视觉反馈；等待双击判定后才唤醒。所有手势共用一个识别器。 */
suspend fun PointerInputScope.faceGestures(
    preview:(Offset?)->Unit,touch:(FaceTouch)->Unit,stop:()->Unit,manage:()->Unit
) = coroutineScope {
    var single:Job?=null
    var lastUp=Long.MIN_VALUE
    var lastPoint=Offset.Zero
    try {
        awaitEachGesture {
            val down=awaitFirstDown()
            val width=size.width.toFloat();val height=size.height.toFloat()
            val p=down.position
            val management=FaceRegion.management(p.x,p.y,width,height)
            val stroke=FaceStroke(p.x,p.y,width,height,viewConfiguration.touchSlop,48*density)
            val double=single?.isActive==true && down.uptimeMillis-lastUp in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                (p-lastPoint).getDistance()<100*density
            if(double) { single?.cancel();single=null }
            var cancelled=false
            var handled=false
            if(!management && FaceRegion.contains(p.x,p.y,width,height))preview(p)
            down.consume()
            val hold=launch {
                delay(2000)
                if(!cancelled && !stroke.moved) {
                    handled=true;single?.cancel();single=null;preview(null)
                    if(management)manage()
                }
            }
            var upAt=down.uptimeMillis
            try {
                while(true) {
                    val event=awaitPointerEvent()
                    val change=event.changes.firstOrNull { it.id==down.id }
                    if(event.changes.size!=1 || change==null || change.isConsumed) { cancelled=true;break }
                    stroke.move(change.position.x,change.position.y)
                    if(stroke.moved)hold.cancel()
                    if(!management && !handled)preview(change.position.takeIf { FaceRegion.contains(it.x,it.y,width,height) })
                    upAt=change.uptimeMillis
                    change.consume()
                    if(!change.pressed)break
                }
            } finally { hold.cancel();preview(null) }
            if(cancelled || handled)return@awaitEachGesture
            // 管理角只保留长按和全屏双击兜底，不把滑动变成逗趣。
            val result=if(management)null else stroke.result()
            if(double && !stroke.moved) { stop();lastUp=Long.MIN_VALUE }
            else if(result==FaceTouch.PET || result==FaceTouch.TICKLE) {
                single?.cancel();single=null;touch(result)
            } else if(!stroke.moved) {
                single?.cancel()
                lastUp=upAt;lastPoint=p
                single=launch {
                    delay(viewConfiguration.doubleTapTimeoutMillis)
                    if(result==FaceTouch.TAP)touch(FaceTouch.TAP)
                }
            }
        }
    } finally { single?.cancel();preview(null) }
}
