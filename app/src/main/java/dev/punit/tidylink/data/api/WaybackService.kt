package dev.punit.tidylink.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class WaybackSnapshot(
    val status: String? = null,
    val available: Boolean = false,
    val url: String = "",
    val timestamp: String = "",
)

@Serializable
data class WaybackClosest(
    val closest: WaybackSnapshot? = null,
)

@Serializable
data class WaybackResponse(
    val url: String = "",
    val archived_snapshots: WaybackClosest = WaybackClosest(),
)

data class WaybackResult(
    val originalUrl: String,
    val isArchived: Boolean,
    val snapshotUrl: String?,
    val timestamp: String?,
    val savePageUrl: String,
)

class WaybackService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Queries Internet Archive Wayback Machine Availability API.
     * Returns null on network failure.
     */
    suspend fun checkAvailability(url: String): WaybackResult? = withContext(Dispatchers.IO) {
        val saveUrl = "https://web.archive.org/save/$url"
        try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url("https://archive.org/wayback/available?url=$encoded")
                .header("User-Agent", "TidyLink-Android")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString<WaybackResponse>(body)
                val closest = parsed.archived_snapshots.closest

                if (closest != null && closest.available && closest.url.isNotBlank()) {
                    WaybackResult(
                        originalUrl = url,
                        isArchived = true,
                        snapshotUrl = closest.url.replace("http://", "https://"),
                        timestamp = closest.timestamp,
                        savePageUrl = saveUrl,
                    )
                } else {
                    WaybackResult(
                        originalUrl = url,
                        isArchived = false,
                        snapshotUrl = null,
                        timestamp = null,
                        savePageUrl = saveUrl,
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
