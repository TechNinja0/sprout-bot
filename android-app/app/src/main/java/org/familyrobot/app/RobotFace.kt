package org.familyrobot.app

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.familyrobot.core.FaceTouch
import org.familyrobot.core.FaceFeedback
import org.familyrobot.core.FaceSignal
import org.familyrobot.core.faceFeedback
import kotlinx.coroutines.delay
import kotlin.math.sin

/** 工作状态用姿态、符号和文字共同表达；表演不阻止暂停、时间限制和资源释放。 */
@Composable fun RobotFace(mode:String,mouth:Float,reducedMotion:Boolean,stop:()->Unit,manage:()->Unit,touch:()->Unit,interact:(FaceTouch)->Unit={ touch() },expressionIntensity:Float=.6f,feedback:FaceFeedback=faceFeedback(mode)) {
    val emotionStrength=expressionIntensity.coerceIn(.4f,1f)
    var time by remember { mutableFloatStateOf(0f) }
    var contact by remember { mutableStateOf<Offset?>(null) }
    val onTouch by rememberUpdatedState(interact)
    val onStop by rememberUpdatedState(stop)
    val onManage by rememberUpdatedState(manage)
    val sleeping=mode in setOf("standby","rest")
    val locked=feedback.signal==FaceSignal.LOCK
    val shy=mode=="fault"
    val restMode=sleeping || locked || mode=="muted"
    val processing=mode in setOf("thinking","recognizing","vision","preparing","loading")
    val color=faceAccent(feedback.signal)
    val attentive=mode in setOf("waking","listening","hearing")
    val eyeScale by animateFloatAsState(when {
        restMode -> .01f
        shy -> .16f
        mode=="paused" -> .09f
        processing -> .13f
        mode=="waking" -> .31f
        attentive -> .26f
        else -> .22f
    },tween(if(reducedMotion)0 else 220),label="eye-opening")
    val tilt by animateFloatAsState(if(mode=="listening" && !reducedMotion)2f else 0f,tween(220),label="listening-tilt")
    LaunchedEffect(mode,reducedMotion) {
        time=0f
        if(reducedMotion)return@LaunchedEffect
        val interval=if(restMode)80L else 40L
        while(true) { delay(interval);time+=interval/1000f }
    }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { stateDescription=feedback.title+"，"+feedback.detail }.pointerInput(Unit) {
            faceGestures({ contact=it },{ onTouch(it) },{ onStop() },{ onManage() })
        }) {
        val compact=maxHeight<420.dp
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF07141C))
            val cx=size.width/2;val cy=size.height*0.46f;val u=minOf(size.width,size.height)
            val rest=restMode;val thinking=processing;val happy=mode in setOf("happy","laugh","song","pet","tickle")
            val sad=mode in setOf("hurt","cry");val closed=rest || shy || mode in setOf("laugh","cry") || (mode=="pet" && time<.6f) || (mode=="tickle" && time<.85f) || (!reducedMotion && time%5.8f>5.65f)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha=if(rest).025f else .09f),Color.Transparent),Offset(cx,cy),u*.65f),u*.65f,Offset(cx,cy))
            drawAttention(feedback.signal,Offset(cx,cy),u,time,reducedMotion,mouth,color)
            if(locked)drawLockReason(mode,Offset(cx,cy),u,color)
            val sway=when {
                reducedMotion -> 0f
                mode=="tickle" && time<1.1f -> sin(time*10f)*2.5f
                mode=="pet" && time<1.1f -> sin(time*3f)*2f
                mode=="song" -> sin(time*2.4f)*3*mouth
                shy -> 2f+sin(time*1.3f)*.7f
                else -> tilt
            }
            val breath=if(reducedMotion)0f else sin(time*1.5f)
            translate(top=if(sleeping)breath*u*.009f else 0f) {
            rotate(sway,Offset(cx,cy)) {
                for(sign in listOf(-1,1)) {
                    val x=cx+sign*u*0.22f
                    val eyeW=u*(if(thinking).21f else .185f)
                    val eyeH=u*eyeScale
                    if(closed) {
                        val curve=if(shy)-u*.045f else if(mode in setOf("laugh","pet","tickle"))-u*.055f else if(mode=="cry")u*.035f else if(sleeping || locked)u*.036f else 0f
                        drawPath(Path().apply { moveTo(x-eyeW/2,cy);quadraticTo(x,cy+curve,x+eyeW/2,cy) },color,style=Stroke(u*.018f,cap=StrokeCap.Round))
                    } else {
                        drawRoundRect(Brush.verticalGradient(listOf(color,color.copy(alpha=.68f)),cy-eyeH/2,cy+eyeH/2),Offset(x-eyeW/2,cy-eyeH/2),Size(eyeW,eyeH),CornerRadius(eyeW*(if(thinking || mode=="paused").18f else .48f)))
                        val gazeX=contact?.let { ((it.x-cx)/u).coerceIn(-.04f,.04f)*u } ?: if(thinking)u*.02f else if(!reducedMotion && mode=="standby")sin(time*.55f)*u*.008f else 0f
                        val pupilH=minOf(u*.14f,eyeH*.68f)
                        val gazeY=if(thinking)-eyeH*.08f else if(sad)eyeH*.08f else 0f
                        drawOval(Color(0xFF10242E),Offset(x-u*.041f+gazeX,cy-pupilH/2+gazeY),Size(u*.082f,pupilH))
                        drawCircle(Color(0xFFE2FFFF),minOf(u*.019f,pupilH*.24f),Offset(x-u*.018f+gazeX,cy-pupilH*.26f+gazeY))
                    }
                    val browY=cy-eyeH/2-u*(if(mode=="waking" || mode=="hearing").075f else .05f)
                    if(!rest && !shy) {
                        val tilt=when { sad -> sign*u*.038f*emotionStrength;thinking && sign==1 -> u*.032f;happy -> -u*.02f;else -> 0f }
                        drawPath(Path().apply { moveTo(x-eyeW*.35f,browY-tilt);quadraticTo(x,browY-u*.018f,x+eyeW*.35f,browY+tilt) },color.copy(alpha=.7f),style=Stroke(u*.012f,cap=StrokeCap.Round))
                    }
                    if(happy || mode=="listening")drawOval(Color(0xFF7DC3B9).copy(alpha=.28f),Offset(x-eyeW*.45f,cy+u*.16f),Size(eyeW*.9f,u*.026f))
                    if(shy) {
                        val blush=Color(0xFFFF94B3)
                        val cheek=Offset(x,cy+u*.09f)
                        drawOval(blush.copy(alpha=.4f),cheek-Offset(u*.075f,u*.028f),Size(u*.15f,u*.056f))
                        for(i in -1..1)drawLine(blush.copy(alpha=.85f),cheek+Offset(u*(i*.032f-.007f),-u*.012f),cheek+Offset(u*(i*.032f+.007f),u*.012f),u*.006f,StrokeCap.Round)
                    }
                    if(mode=="cry") {
                        val drop=if(reducedMotion).05f else ((time*0.35f)%0.16f)
                        drawOval(Color(0xFF7ABEEB).copy(alpha=emotionStrength),Offset(x+sign*u*.07f-u*.013f,cy+u*.05f+u*drop*emotionStrength),Size(u*.026f,u*.058f*emotionStrength))
                    }
                }
                // 横屏给下方状态符号留出明确间距，最大张嘴时也不与提示重叠。
                val y=cy+u*(if(compact).18f else .235f)
                when {
                    sleeping && mouth<=.04f -> {
                        val h=u*(.043f+breath*.008f)
                        drawOval(color,Offset(cx-u*.037f,y-u*.006f),Size(u*.074f,h),style=Stroke(u*.009f))
                        drawSleepDrool(Offset(cx+u*.03f,y+h*.55f),u,time,reducedMotion)
                    }
                    shy -> drawPath(Path().apply {
                        moveTo(cx-u*.043f,y);quadraticTo(cx,y+u*.035f,cx+u*.043f,y)
                    },color,style=Stroke(u*.011f,cap=StrokeCap.Round))
                    mode in setOf("speaking","story","song","waking","pet","tickle","rest","thinking","vision") && mouth>.04f -> drawOval(color,Offset(cx-u*.075f,y),Size(u*.15f,u*(.02f+mouth*(if(compact).09f else .13f))))
                    mode=="laugh" -> { drawOval(color,Offset(cx-u*.09f,y-u*.01f),Size(u*.18f,u*.13f));drawOval(Color(0xFF10242E),Offset(cx-u*.063f,y+u*.01f),Size(u*.126f,u*.072f)) }
                    mode=="cry" -> drawOval(color,Offset(cx-u*.06f,y-u*.015f),Size(u*.12f,u*.1f*emotionStrength))
                    mode=="vision" -> drawOval(color,Offset(cx-u*.025f,y),Size(u*.05f,u*.06f),style=Stroke(u*.012f))
                    else -> {
                        val bend=when { locked -> u*.045f;rest || mode=="paused" -> 0f;thinking -> .005f*u;sad -> -u*.045f*emotionStrength;happy -> u*.1f;mode=="listening" -> u*.02f;else -> u*.05f }
                        drawPath(Path().apply { moveTo(cx-u*.09f,y);quadraticTo(cx,y+bend,cx+u*.09f,y) },color,style=Stroke(u*.013f,cap=StrokeCap.Round))
                    }
                }
            }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
            .padding(horizontal=24.dp).padding(bottom=if(compact)12.dp else 28.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(if(compact)3.dp else 7.dp)) {
            Canvas(Modifier.size(if(compact)40.dp else 52.dp,if(compact)22.dp else 30.dp)) {
                drawFaceSignal(feedback.signal,color,time,reducedMotion,mouth)
            }
            Text(feedback.title,color=color,fontSize=if(compact)20.sp else 24.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
            if(feedback.detail.isNotEmpty())Text(feedback.detail,color=Color(0xFFA5BAC4),fontSize=if(compact)12.sp else 14.sp,textAlign=TextAlign.Center,lineHeight=18.sp)
        }
    }
}
