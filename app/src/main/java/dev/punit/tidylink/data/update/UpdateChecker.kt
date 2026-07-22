package dev.punit.tidylink.data.update

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A newer release found on GitHub, ready to offer to the user. */
data class UpdateInfo(
    /** Human version from the tag, without the leading v (e.g. "1.1"). */
    val version: String,
    /** Direct download URL of the release APK asset. */
    val downloadUrl: String,
    /** Asset size in bytes, 0 when GitHub doesn't report one. */
    val sizeBytes: Long,
)

/**
 * Checks GitHub Releases for a newer version and downloads the APK.
 *
 * Deliberately parsed with platform [org.json] rather than
 * kotlinx-serialization: R8 has silently broken the serialization path in
 * this app before, and a framework parser can't be stripped. One endpoint,
 * three fields - a DTO earns nothing here.
 *
 * Privacy: this is a new thing that talks to the network
 * (api.github.com + the release asset host). It runs at most weekly, or when
 * the user explicitly taps "Check for updates". Nothing identifying is sent -
 * it's an unauthenticated GET. Documented in README -> Privacy.
 */
class UpdateChecker(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** True when the last automatic check is more than a week old. */
    fun shouldAutoCheck(): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    /**
     * Latest release if it's newer than the installed version, null when
     * already up to date. Throws [IOException] on network/API failure so
     * callers can tell "up to date" from "couldn't check".
     */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Empty response")
        }
        prefs.edit { putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()) }

        val json = JSONObject(body)
        val remote = json.optString("tag_name").removePrefix("v")
        if (remote.isBlank() || !isRemoteNewer(remote, installedVersion())) {
            return@withContext null
        }

        val assets = json.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) {
                return@withContext UpdateInfo(
                    version = remote,
                    downloadUrl = asset.optString("browser_download_url"),
                    sizeBytes = asset.optLong("size"),
                )
            }
        }
        null // Release without an APK asset - nothing installable.
    }

    /**
     * Downloads the APK into this app's private cache and returns the file.
     * [onProgress] gets 0-100 (a single 0 when the size is unknown).
     * Throws [IOException] on failure; partial files are deleted.
     */
    suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, APK_DIR).apply { mkdirs() }
        val file = File(dir, APK_NAME)
        val request = Request.Builder().url(info.downloadUrl).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val respBody = response.body ?: throw IOException("Empty download")
                val total = if (respBody.contentLength() > 0) {
                    respBody.contentLength()
                } else {
                    info.sizeBytes
                }
                respBody.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val percent = if (total > 0) {
                                ((copied * 100) / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        file
    }

    private fun installedVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    companion object {
        private const val PREFS_NAME = "updates"
        private const val KEY_LAST_CHECK_MS = "last_check_ms"
        private const val CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // weekly
        private const val RELEASES_URL =
            "https://api.github.com/repos/punitsnaik/TidyLink/releases/latest"
        private const val APK_DIR = "updates"
        const val APK_NAME = "TidyLink-update.apk"

        /**
         * Dotted numeric comparison: "1.10" > "1.9", "1.1" > "1.0.5".
         * Non-numeric segments count as 0, so a malformed remote tag can
         * never look newer than a well-formed installed version.
         */
        internal fun isRemoteNewer(remote: String, current: String): Boolean {
            val r = remote.split('.').map { it.trim().toIntOrNull() ?: 0 }
            val c = current.split('.').map { it.trim().toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, c.size)) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            return false
        }
    }
}
