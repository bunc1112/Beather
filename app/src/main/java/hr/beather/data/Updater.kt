package hr.beather.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import hr.beather.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater: checks the latest GitHub release, downloads Beather.apk and
 * hands it to Android's installer. Release tags end in the CI build number
 * (v2.3.12 → 12), which is also the app's versionCode.
 */
object Updater {
    private const val API = "https://api.github.com/repos/bunc1112/Beather/releases/latest"

    data class Release(val tag: String, val build: Int, val apkUrl: String, val notes: String)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(Http.get(API))
            val tag = o.getString("tag_name")
            val build = tag.substringAfterLast('.').filter { it.isDigit() }.toInt()
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@runCatching null
            Release(tag, build, apk.getString("browser_download_url"), o.optString("body"))
        }.getOrNull()
    }

    fun isNewer(r: Release) = r.build > BuildConfig.VERSION_CODE

    private fun apkFile(ctx: Context) = File(ctx.cacheDir, "updates/Beather.apk")

    suspend fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val file = apkFile(ctx)
        file.parentFile?.mkdirs()
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "Beather/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) withContext(Dispatchers.Main) { onProgress((done * 100 / total).toInt()) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        file
    }

    fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for Beather. */
    fun openInstallPermission(activity: Activity) {
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        )
    }

    fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
