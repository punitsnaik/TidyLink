package dev.punit.tidylink.data.api

import androidx.core.net.toUri
import dev.punit.tidylink.data.UrlCanonicalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class GitHubLicense(
    val key: String? = null,
    val name: String? = null,
    val spdx_id: String? = null,
)

@Serializable
data class GitHubRepoResponse(
    val name: String = "",
    val full_name: String = "",
    val description: String? = null,
    val stargazers_count: Int = 0,
    val language: String? = null,
    val forks_count: Int = 0,
    val open_issues_count: Int = 0,
    val license: GitHubLicense? = null,
    val html_url: String = "",
)

data class GitHubRepoDetails(
    val fullName: String,
    val description: String?,
    val stars: Int,
    val language: String?,
    val license: String?,
    val forks: Int,
)

class GitHubRepoService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Checks if [url] points to a GitHub repository and extracts owner and repo name.
     */
    fun extractRepoPath(url: String): Pair<String, String>? {
        val host = UrlCanonicalizer.hostOf(url)
        if (host != "github.com" && host != "www.github.com") return null

        val path = runCatching {
            java.net.URI(UrlCanonicalizer.cleanUrl(url)).rawPath.orEmpty()
        }.getOrNull() ?: return null

        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null

        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")

        // Exclude reserved GitHub paths that are not repositories
        val reserved = setOf(
            "features", "explore", "trending", "topics", "collections",
            "events", "sponsors", "settings", "notifications", "search",
            "orgs", "users", "pulls", "issues", "login", "join", "about",
        )
        if (owner.lowercase() in reserved || repo.isBlank()) return null

        return owner to repo
    }

    /**
     * Fetches repo stats from GitHub public API.
     * Respects unauthenticated rate limits. Returns null on error.
     */
    suspend fun fetchRepoDetails(owner: String, repo: String): GitHubRepoDetails? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo")
                .header("User-Agent", "TidyLink-Android")
                .header("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString<GitHubRepoResponse>(body)

                val licenseName = parsed.license?.spdx_id?.takeIf { it != "NOASSERTION" }
                    ?: parsed.license?.name

                GitHubRepoDetails(
                    fullName = parsed.full_name,
                    description = parsed.description,
                    stars = parsed.stargazers_count,
                    language = parsed.language,
                    license = licenseName,
                    forks = parsed.forks_count,
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
