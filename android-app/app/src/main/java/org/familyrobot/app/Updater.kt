package org.familyrobot.app

import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AppUpdateInfo(val versionName:String,val versionCode:Int,val sizeBytes:Long,val notes:String)

/** 家长端应用升级：只经家庭服务(8766)鉴权通道检查与下载，伙伴端不调用、不提示。 */
object AppUpdater {
    /** 返回 null 表示已是最新；网络等异常直接抛出，由调用方决定是否静默。 */
    suspend fun check(api:Api):AppUpdateInfo? {
        val json=withContext(Dispatchers.IO) { api.json("/v1/app/latest",timeoutMs=8000) }
        val remote=json.getInt("versionCode")
        if(remote<=BuildConfig.VERSION_CODE)return null
        return AppUpdateInfo(
            json.getString("versionName"),remote,
            json.optLong("sizeBytes",0L),json.optString("notes","").take(300)
        )
    }

    suspend fun download(activity:ComponentActivity,api:Api,update:AppUpdateInfo,onProgress:(Long,Long)->Unit):File {
        val target=File(File(activity.cacheDir,"updates"),"familyrobot-${update.versionCode}.apk")
        withContext(Dispatchers.IO) { api.download("/v1/app/download",target,onProgress) }
        return target
    }

    /** 返回 false 表示需要先在系统设置里允许“未知来源/安装未知应用”后重试。 */
    fun install(activity:ComponentActivity,file:File):Boolean {
        if(!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity,"请允许本应用安装未知来源应用后，再点击安装",Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.packageName)))
            }.onFailure { Toast.makeText(activity,"请在系统设置中找到本应用的安装权限",Toast.LENGTH_LONG).show() }
            return false
        }
        return try {
            val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.capture",file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        } catch(_:ActivityNotFoundException) {
            Toast.makeText(activity,"没有找到可用的应用安装器",Toast.LENGTH_LONG).show()
            false
        }
    }
}
