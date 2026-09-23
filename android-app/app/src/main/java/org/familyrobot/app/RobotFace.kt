package org.familyrobot.app

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import org.familyrobot.core.FaceTouch
import kotlinx.coroutines.delay
import kotlin.math.sin

/** 十二种原创表演，独立于业务状态；表演永不阻止暂停、时间限制和资源释放。 */
@Composable fun RobotFace(mode:String,mouth:Float,reducedMotion:Boolean,stop:()->Unit,manage:()->Unit,touch:()->Unit,interact:(FaceTouch)->Unit={ touch() },expressionIntensity:Float=.6f) {
    val emotionStrength=expressionIntensity.coerceIn(.4f,1f)
    var time by remember { mutableFloatStateOf(0f) }
    var contact by remember { mutableStateOf<Offset?>(null) }
    val onTouch by rememberUpdatedState(interact)
    val onStop by rememberUpdatedState(stop)
    val onManage by rememberUpdatedState(manage)
    val restMode=mode in setOf("rest","muted","blocked")
    val attentive=mode in setOf("waking","listening","hearing")
    val eyeScale by animateFloatAsState(when {
        restMode -> .01f
        mode=="standby" && contact==null -> .12f
        attentive -> .26f
        else -> .22f
    },tween(if(reducedMotion)0 else 220),label="eye-opening")
    val tilt by animateFloatAsState(if(mode=="listening" && !reducedMotion)2f else 0f,tween(220),label="listening-tilt")
    LaunchedEffect(mode,reducedMotion) {
        time=0f
        val interval=if(reducedMotion)1000L else if(mode in setOf("standby","rest","muted","blocked"))80L else 40L
        while(true) { delay(interval);time+=if(reducedMotion)0f else interval/1000f }
    }
    run {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            faceGestures({ contact=it },{ onTouch(it) },{ onStop() },{ onManage() })
        }) {
            drawRect(Color(0xFF07141C))
            val cx=size.width/2;val cy=size.height*0.46f;val u=minOf(size.width,size.height)
            val rest=mode in setOf("rest","muted","blocked");val thinking=mode=="thinking";val happy=mode in setOf("happy","laugh","song","pet","tickle")
            val sad=mode in setOf("hurt","cry");val closed=rest || mode in setOf("laugh","cry") || (mode=="pet" && time<.6f) || (mode=="tickle" && time<.85f) || (!reducedMotion && time%5.8f>5.65f)
            val color=when { mode=="fault" -> Color(0xFFE0AD68);mode=="blocked" -> Color(0xFF918DBA);mode=="muted" -> Color(0xFF586970);rest -> Color(0xFF66888D);thinking -> Color(0xFFBCC6FF);happy -> Color(0xFFA3F1D2);else -> Color(0xFF8BDCE6) }
            val sway=when {
                reducedMotion -> 0f
                mode=="tickle" && time<1.1f -> sin(time*10f)*2.5f
                mode=="pet" && time<1.1f -> sin(time*3f)*2f
                mode=="song" -> sin(time*2.4f)*3*mouth
                else -> tilt
            }
            rotate(sway,Offset(cx,cy)) {
                for(sign in listOf(-1,1)) {
                    val x=cx+sign*u*0.22f
                    val eyeW=u*0.185f
                    val eyeH=u*eyeScale
                    if(closed) {
                        val curve=if(mode in setOf("laugh","pet","tickle"))-u*.055f else if(mode=="cry")u*.035f else if(mode=="blocked")u*.025f else 0f
                        drawPath(Path().apply { moveTo(x-eyeW/2,cy);quadraticTo(x,cy+curve,x+eyeW/2,cy) },color,style=Stroke(u*.018f,cap=StrokeCap.Round))
                    } else {
                        drawRoundRect(Brush.verticalGradient(listOf(color,Color(0xFF498990)),cy-eyeH/2,cy+eyeH/2),Offset(x-eyeW/2,cy-eyeH/2),Size(eyeW,eyeH),CornerRadius(eyeW*.48f))
                        val gazeX=contact?.let { ((it.x-cx)/u).coerceIn(-.04f,.04f)*u } ?: if(thinking)u*.02f else if(!reducedMotion && mode=="standby")sin(time*.55f)*u*.008f else 0f
                        val gazeY=if(thinking)-u*.026f else if(sad)u*.024f else 0f
                        drawOval(Color(0xFF10242E),Offset(x-u*.041f+gazeX,cy-u*.07f+gazeY),Size(u*.082f,u*.14f))
                        drawCircle(Color(0xFFE2FFFF),u*.022f,Offset(x-u*.018f+gazeX,cy-u*.046f+gazeY))
                        drawCircle(Color(0xFF81C6D2),u*.009f,Offset(x+u*.019f+gazeX,cy+u*.036f+gazeY))
                    }
                    val browY=cy-eyeH/2-u*(if(mode=="waking" || mode=="hearing").075f else .05f)
                    if(!rest && mode!="standby") {
                        val tilt=when { sad -> sign*u*.038f*emotionStrength;thinking && sign==1 -> u*.032f;happy -> -u*.02f;else -> 0f }
                        drawPath(Path().apply { moveTo(x-eyeW*.35f,browY-tilt);quadraticTo(x,browY-u*.018f,x+eyeW*.35f,browY+tilt) },color.copy(alpha=.7f),style=Stroke(u*.012f,cap=StrokeCap.Round))
                    }
                    if(happy || mode=="listening")drawOval(Color(0xFF7DC3B9).copy(alpha=.28f),Offset(x-eyeW*.45f,cy+u*.16f),Size(eyeW*.9f,u*.026f))
                    if(mode=="cry") {
                        val drop=if(reducedMotion).05f else ((time*0.35f)%0.16f)
                        drawOval(Color(0xFF7ABEEB).copy(alpha=emotionStrength),Offset(x+sign*u*.07f-u*.013f,cy+u*.05f+u*drop*emotionStrength),Size(u*.026f,u*.058f*emotionStrength))
                    }
                }
                val y=cy+u*.235f
                when {
                    mode in setOf("speaking","story","song","waking","pet","tickle","rest","thinking","vision") && mouth>.04f -> drawOval(color,Offset(cx-u*.075f,y),Size(u*.15f,u*(.02f+mouth*.13f)))
                    mode=="laugh" -> { drawOval(color,Offset(cx-u*.09f,y-u*.01f),Size(u*.18f,u*.13f));drawOval(Color(0xFF10242E),Offset(cx-u*.063f,y+u*.01f),Size(u*.126f,u*.072f)) }
                    mode=="cry" -> drawOval(color,Offset(cx-u*.06f,y-u*.015f),Size(u*.12f,u*.1f*emotionStrength))
                    mode=="vision" -> drawOval(color,Offset(cx-u*.025f,y),Size(u*.05f,u*.06f),style=Stroke(u*.012f))
                    else -> {
                        val bend=when { rest -> 0f;sad -> -u*.045f*emotionStrength;happy -> u*.1f;mode=="listening" -> u*.02f;else -> u*.05f }
                        drawPath(Path().apply { moveTo(cx-u*.09f,y);quadraticTo(cx,y+bend,cx+u*.09f,y) },color,style=Stroke(u*.013f,cap=StrokeCap.Round))
                    }
                }
            }
        }
    }
}
