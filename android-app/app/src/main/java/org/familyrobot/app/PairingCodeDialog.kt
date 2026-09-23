package org.familyrobot.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun PairingCodeDialog(bitmap:Bitmap,deadline:Long,busy:Boolean,message:String,onRegenerate:()->Unit,onDismiss:()->Unit) {
    var remaining by remember(deadline) { mutableLongStateOf(((deadline-SystemClock.elapsedRealtime()+999)/1000).coerceAtLeast(0)) }
    LaunchedEffect(deadline) {
        while(remaining>0) {
            delay(250)
            remaining=((deadline-SystemClock.elapsedRealtime()+999)/1000).coerceAtLeast(0)
        }
    }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("家长手机扫码配对") },text={
        Column(Modifier.verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally) {
            Text("家长手机打开小伙伴 App，选择“家长身份”，点击“扫描二维码”。")
            Spacer(Modifier.height(12.dp))
            if(remaining>0) {
                Image(bitmap.asImageBitmap(),"家长配对二维码",Modifier.fillMaxWidth().aspectRatio(1f))
                Text("有效期剩余 ${remaining} 秒",Modifier.padding(top=8.dp))
            } else Text("二维码已过期，请重新生成",color=MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text("扫码后，家长手机点击“验证并连接”；再回到这里确认配对请求。")
            if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={ TextButton(onClick=onDismiss) { Text("返回管理页确认配对") } },
        dismissButton={ TextButton(onClick=onRegenerate,enabled=!busy) { Text(if(busy)"生成中…" else "重新生成") } })
}
