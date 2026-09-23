package org.familyrobot.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/** Only short-lived invitation data may leave the robot; never copy device credentials. */
fun parentPairingMaterial(connection:JSONObject, invitation:JSONObject):String = JSONObject()
    .put("protocolVersion",connection.optInt("protocolVersion",1))
    .put("address",connection.getString("address"))
    .put("serviceId",connection.getString("serviceId"))
    .put("certificateSha256",connection.getString("certificateSha256"))
    .put("invite",invitation.getString("invite"))
    .put("purpose","pair")
    .put("expiresIn",invitation.getInt("expiresIn"))
    .toString()

fun pairingQrBitmap(material:String):Bitmap {
    val matrix=QRCodeWriter().encode(material,BarcodeFormat.QR_CODE,768,768,mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 4,
    ))
    val pixels=IntArray(matrix.width*matrix.height) { index ->
        if(matrix[index%matrix.width,index/matrix.width])Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels,matrix.width,matrix.height,Bitmap.Config.ARGB_8888)
}

fun decodePairingQr(bitmap:Bitmap):String {
    val pixels=IntArray(bitmap.width*bitmap.height)
    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
        RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
    )),mapOf(DecodeHintType.TRY_HARDER to true,DecodeHintType.CHARACTER_SET to "UTF-8")).text
}

fun parseConnectionMaterial(material:String):JSONObject {
    require(material.isNotBlank()) { "请先扫描连接二维码或导入连接文件" }
    val c=try { JSONObject(material) } catch(_:org.json.JSONException) {
        throw IllegalArgumentException("连接材料不是有效的 JSON，请扫描配对二维码或导入完整文件")
    }
    require(listOf("address","serviceId","certificateSha256","invite").all {
        !c.isNull(it) && c.opt(it) is String && c.getString(it).isNotBlank()
    }) { "这不是完整的连接材料，请扫描机器人显示的配对二维码" }
    return c
}
