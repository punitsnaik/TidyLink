package dev.punit.tidylink.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class UrlhausResponse(
    val query_status: String = "",
    val url_status: String? = null,
    val threat: String? = null,
    val tags: List<String>? = null,
    val urlhaus_reference: String? = null,
)

data class UrlSafetyResult(
    val url: String,
    val isMalicious: Boolean,
    val threat: String?,
    val tags: List<String>,
    val referenceUrl: String?,
)

class UrlSafetyService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Checks if [url] is flagged in abuse.ch URLhaus malware database.
     * Returns null on network error.
     */
    suspend fun checkUrlSafety(url: String): UrlSafetyResult? = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("url", url)
                .build()

            val request = Request.Builder()
                .url("https://urlhaus-api.abuse.ch/v1/url/")
                .header("User-Agent", "TidyLink-Android")
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString<UrlhausResponse>(body)

                if (parsed.query_status.equals("ok", ignoreCase = true)) {
                    UrlSafetyResult(
                        url = url,
                        isMalicious = true,
                        threat = parsed.threat ?: "malware",
                        tags = parsed.tags ?: emptyList(),
                        referenceUrl = parsed.urlhaus_reference,
                    )
                } else {
                    UrlSafetyResult(
                        url = url,
                        isMalicious = false,
                        threat = null,
                        tags = emptyList(),
                        referenceUrl = null,
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
