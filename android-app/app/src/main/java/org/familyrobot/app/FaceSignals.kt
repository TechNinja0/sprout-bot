package org.familyrobot.app

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import org.familyrobot.core.FaceSignal
import kotlin.math.*

internal fun faceAccent(signal:FaceSignal)=when(signal) {
    FaceSignal.SLEEP -> Color(0xFF8BA9BC)
    FaceSignal.WAKE -> Color(0xFFFFD889)
    FaceSignal.LISTEN,FaceSignal.HEAR -> Color(0xFF83E8F1)
    FaceSignal.RECOGNIZE,FaceSignal.THINK,FaceSignal.LOOK -> Color(0xFFC0B5FF)
    FaceSignal.SPEAK,FaceSignal.PLAYFUL -> Color(0xFFA3F1D2)
    FaceSignal.WAIT,FaceSignal.PAUSE -> Color(0xFFFFCF87)
    FaceSignal.ERROR -> Color(0xFFFFAE96)
    FaceSignal.MUTED,FaceSignal.LOCK -> Color(0xFFADBAC9)
}

/** 大轮廓提示在远处也可识别；动画仅表达阶段，不伪造语音输入音量。 */
internal fun DrawScope.drawAttention(signal:FaceSignal,center:Offset,u:Float,time:Float,reduced:Boolean,mouth:Float,color:Color) {
    val cx=center.x;val cy=center.y
    when(signal) {
        FaceSignal.SLEEP -> {
            val rise=if(reduced)0f else sin(time*.9f)*u*.012f
            for(i in 0..2) {
                val x=cx+u*(.25f+i*.055f);val y=cy-u*(.15f+i*.07f)+rise
                val w=u*(.032f+i*.007f)
                drawPath(Path().apply { moveTo(x,y);lineTo(x+w,y);lineTo(x,y+w);lineTo(x+w,y+w) },color.copy(alpha=.45f+i*.18f),style=Stroke(u*.007f,cap=StrokeCap.Round,join=StrokeJoin.Round))
            }
        }
        FaceSignal.LISTEN,FaceSignal.HEAR -> {
            val active=signal==FaceSignal.HEAR
            for(side in listOf(-1,1))for(i in 0..2) {
                val r=u*(.055f+i*.031f)
                val pulse=if(reduced).8f else (.55f+.25f*sin(time*(if(active)6f else 2f)-i))
                drawArc(color.copy(alpha=pulse),if(side==1)-55f else 125f,110f,false,
                    Offset(cx+side*u*.31f-r,cy-r),Size(r*2,r*2),style=Stroke(u*(if(active).012f else .008f),cap=StrokeCap.Round))
            }
        }
        FaceSignal.THINK,FaceSignal.RECOGNIZE,FaceSignal.LOOK,FaceSignal.WAIT -> {
            val y=cy-u*.29f
            for(i in 0..2) {
                val bounce=if(reduced)0f else max(0f,sin(time*3f-i*1.1f))*u*.025f
                drawCircle(color.copy(alpha=if(reduced).85f else .45f+.5f*max(0f,sin(time*3f-i))),u*.013f,Offset(cx+(i-1)*u*.065f,y-bounce))
            }
            if(signal==FaceSignal.LOOK) {
                val x=cx-u*.34f;val y0=cy-u*.18f;val w=u*.68f;val h=u*.37f;val l=u*.055f
                for(sx in listOf(0,1))for(sy in listOf(0,1)) {
                    val px=x+sx*w;val py=y0+sy*h
                    drawPath(Path().apply { moveTo(px+(if(sx==0)l else -l),py);lineTo(px,py);lineTo(px,py+(if(sy==0)l else -l)) },color.copy(alpha=.6f),style=Stroke(u*.007f,cap=StrokeCap.Round))
                }
            }
        }
        FaceSignal.WAKE -> {
            for(i in 0..4) {
                val angle=PI.toFloat()*(1.15f+i*.175f)
                val a=Offset(cx+cos(angle)*u*.30f,cy+sin(angle)*u*.31f)
                val b=Offset(cx+cos(angle)*u*.35f,cy+sin(angle)*u*.37f)
                drawLine(color,a,b,u*.01f,StrokeCap.Round)
            }
        }
        FaceSignal.ERROR -> {
            val y=cy-u*.29f
            drawLine(color,Offset(cx,y-u*.038f),Offset(cx,y+u*.01f),u*.016f,StrokeCap.Round)
            drawCircle(color,u*.009f,Offset(cx,y+u*.039f))
        }
        FaceSignal.SPEAK -> {
            val strength=mouth.coerceIn(0f,1f)
            for(side in listOf(-1,1))for(i in 0..2) {
                val x=cx+side*u*(.34f+i*.035f)
                val h=u*(.016f+strength*(.08f-i*.018f))
                drawLine(color.copy(alpha=.75f),Offset(x,cy-h),Offset(x,cy+h),u*.01f,StrokeCap.Round)
            }
        }
        else -> Unit
    }
}

/** 独立的状态符号；静态模式下轮廓仍各不相同。 */
internal fun DrawScope.drawFaceSignal(signal:FaceSignal,color:Color,time:Float,reduced:Boolean,mouth:Float) {
    val u=size.height;val c=Offset(size.width/2,size.height/2);val r=u*.38f
    val stroke=Stroke(u*.065f,cap=StrokeCap.Round,join=StrokeJoin.Round)
    fun line(a:Offset,b:Offset)=drawLine(color,a,b,u*.065f,StrokeCap.Round)
    when(signal) {
        FaceSignal.SLEEP -> {
            drawCircle(color,r,c)
            drawCircle(Color(0xFF07141C),r*.87f,c+Offset(r*.52f,-r*.3f))
        }
        FaceSignal.WAKE -> {
            drawCircle(color,r*.5f,c,style=stroke)
            for(i in 0..7) {
                val a=i*PI.toFloat()/4
                line(c+Offset(cos(a),sin(a))*r*.85f,c+Offset(cos(a),sin(a))*r*1.2f)
            }
        }
        FaceSignal.LISTEN,FaceSignal.MUTED -> {
            drawRoundRect(color,c-Offset(r*.32f,r*.85f),Size(r*.64f,r*1.1f),CornerRadius(r*.3f),style=stroke)
            drawArc(color,0f,180f,false,c-Offset(r*.65f,r*.42f),Size(r*1.3f,r*1.3f),style=stroke)
            line(c+Offset(0f,r*.88f),c+Offset(0f,r*1.12f))
            if(signal==FaceSignal.MUTED)line(c-Offset(r,r),c+Offset(r,r))
        }
        FaceSignal.HEAR,FaceSignal.SPEAK -> {
            for(i in -3..3) {
                val envelope=if(signal==FaceSignal.SPEAK)mouth.coerceIn(0f,1f) else if(reduced).65f else .45f+.2f*sin(time*5+i)
                val h=u*(.07f+envelope*(.38f-abs(i)*.06f))
                line(c+Offset(i*u*.22f,-h),c+Offset(i*u*.22f,h))
            }
        }
        FaceSignal.RECOGNIZE -> {
            for(i in 0..2) {
                val y=c.y+(i-1)*r*.7f
                line(Offset(c.x-r,y),Offset(c.x+r*(if(i==2).4f else 1f),y))
            }
        }
        FaceSignal.THINK -> {
            drawCircle(color.copy(alpha=.28f),r,c,style=stroke)
            drawCircle(color,r*.17f,c)
            for(i in 0..2) {
                val a=(if(reduced)-.8f else time*2)+i*PI.toFloat()*2/3
                drawCircle(color,r*.23f,c+Offset(cos(a)*r,sin(a)*r))
            }
        }
        FaceSignal.LOOK -> {
            drawRoundRect(color,c-Offset(r*1.2f,r*.9f),Size(r*2.4f,r*1.8f),CornerRadius(r*.2f),style=stroke)
            val y=if(reduced)c.y else c.y+sin(time*2)*r*.5f
            line(Offset(c.x-r*.7f,y),Offset(c.x+r*.7f,y))
        }
        FaceSignal.WAIT -> {
            drawPath(Path().apply {
                moveTo(c.x-r*.7f,c.y-r);lineTo(c.x+r*.7f,c.y-r)
                lineTo(c.x+r*.7f,c.y-r*.55f);lineTo(c.x-r*.7f,c.y+r*.55f)
                lineTo(c.x-r*.7f,c.y+r);lineTo(c.x+r*.7f,c.y+r)
                lineTo(c.x+r*.7f,c.y+r*.55f);lineTo(c.x-r*.7f,c.y-r*.55f);close()
            },color,style=stroke)
            drawCircle(color,u*.045f,c+Offset(0f,if(reduced)r*.35f else (time%1f)*r*.6f))
        }
        FaceSignal.PAUSE -> for(side in listOf(-1,1))drawRoundRect(color,c+Offset(side*r*.5f-u*.045f,-r),Size(u*.09f,r*2),CornerRadius(u*.03f))
        FaceSignal.ERROR -> {
            drawPath(Path().apply { moveTo(c.x,c.y-r);lineTo(c.x+r*1.15f,c.y+r);lineTo(c.x-r*1.15f,c.y+r);close() },color,style=stroke)
            line(c+Offset(0f,-r*.3f),c+Offset(0f,r*.25f))
            drawCircle(color,u*.035f,c+Offset(0f,r*.62f))
        }
        FaceSignal.LOCK -> {
            drawRoundRect(color,c+Offset(-r*.75f,-r*.15f),Size(r*1.5f,r*1.2f),CornerRadius(u*.07f),style=stroke)
            drawArc(color,180f,180f,false,c+Offset(-r*.45f,-r),Size(r*.9f,r*1.25f),style=stroke)
        }
        FaceSignal.PLAYFUL -> {
            drawPath(Path().apply {
                moveTo(c.x,c.y+r)
                cubicTo(c.x-r*2,c.y-r*.25f,c.x-r*.5f,c.y-r*1.8f,c.x,c.y-r*.5f)
                cubicTo(c.x+r*.5f,c.y-r*1.8f,c.x+r*2,c.y-r*.25f,c.x,c.y+r)
            },color,style=stroke)
        }
    }
}
