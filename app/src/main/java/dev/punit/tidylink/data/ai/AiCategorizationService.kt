package dev.punit.tidylink.data.ai

import dev.punit.tidylink.data.scraper.ScrapedData
import dev.punit.tidylink.data.settings.LlmProvider
import dev.punit.tidylink.data.settings.LlmProviderStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the LLM must return - parsed with kotlinx-serialization. */
@Serializable
data class AiClassification(
    val category: String,
    val aiSummary: String,
)

// --- OpenAI-compatible chat completions wire format ----------------------
// Works with Gemini's OpenAI-compat endpoint, xAI, and most other providers.

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
data class ChatChoice(val message: ChatMessage? = null)

/** One element of the JSON array the LLM returns for a batch request. */
@Serializable
data class BatchClassificationItem(
    val index: Int = -1,
    val category: String = "",
    val aiSummary: String = "",
)

// -------------------------------------------------------------------------

/**
 * A non-2xx reply. Carries the status code, because HTTP 429 is retried on
 * the same provider while every other code rotates to the next one.
 *
 * Extends IOException so an unhandled one still reads as a network failure
 * rather than escaping as something unexpected.
 */
internal class HttpStatusException(val code: Int) : IOException("HTTP $code")

/**
 * The chat-completions endpoint for a provider base URL, or null when the
 * URL is unusable.
 *
 * [LlmProviderStore.sanitized] already guarantees a trailing slash on every
 * stored provider, and the "test this provider" path normalizes before
 * calling too - but this trims anyway, because a doubled slash is a 404 on
 * some providers and silently fine on others, which is the worst kind of
 * difference to debug.
 *
 * Top-level and pure so the join is unit-testable without a provider store.
 */
internal fun chatEndpoint(baseUrl: String): String? =
    "${baseUrl.trim().trimEnd('/')}/chat/completions".toHttpUrlOrNull()?.toString()

/**
 * Why [baseUrl] can never work, or null if it's fine.
 *
 * Android blocks cleartext traffic by default for apps targeting API 28+,
 * and TidyLink targets 36, so no `http://` endpoint is reachable - including a LAN LLM
 * server (Ollama, LM Studio). Supporting those would need a network security
 * config permitting cleartext to private ranges; that's a feature, not a fix,
 * and it opens a new egress path worth disclosing. Until then, fail loudly at
 * paste time rather than opaquely on the next sweep.
 *
 * Top-level (not a method) so it's testable without constructing the service,
 * which would otherwise drag in a Context-dependent provider store.
 */
internal fun cleartextReason(baseUrl: String): String? =
    if (baseUrl.trim().startsWith("http://", ignoreCase = true)) {
        "Use https:// - Android blocks plain http:// traffic"
    } else {
        null
    }

/**
 * Turns a thrown exception into a short, user-facing reason string.
 *
 * Every failure used to be swallowed by `catch (e: Exception) { null }`, which
 * meant a revoked key, an unknown model and an R8-stripped serializer all
 * surfaced identically as "Failing". Mapping the cause is what makes the
 * provider sheet diagnosable.
 */
internal object ProviderFailure {

    fun describe(e: Throwable): String = when (e) {
        // Must stay ahead of the IOException branch - HttpStatusException is one.
        is HttpStatusException -> httpReason(e.code)
        is java.net.UnknownHostException -> "No connection - host unreachable"
        is java.net.SocketTimeoutException -> "Timed out - provider too slow"
        is kotlinx.serialization.SerializationException ->
            // In a release build this almost always means R8 stripped the
            // generated serializers (missing keep rules), not a bad payload.
            "Unreadable response - check R8 keep rules"
        is java.io.IOException -> "Network error: ${e.message ?: "unknown"}"
        else -> e.message?.take(120) ?: e.javaClass.simpleName
    }

    private fun httpReason(code: Int): String = when (code) {
        400 -> "HTTP 400 - bad request, check the model name"
        401 -> "HTTP 401 - invalid or revoked API key"
        403 -> "HTTP 403 - key lacks access to this model"
        404 -> "HTTP 404 - model or endpoint not found"
        429 -> "HTTP 429 - rate limited or quota exhausted"
        in 500..599 -> "HTTP $code - provider outage"
        else -> "HTTP $code"
    }
}

/**
 * Classifies a scraped link into category / summary using the LLM
 * providers the user configured in-app (see [LlmProviderStore]).
 *
 * Providers are tried in order: when one is rate-limited (HTTP 429) or
 * rejects the key/model, the next provider takes over - so several
 * keys (Gemini, xAI Grok, or any custom endpoint) stack their quotas.
 *
 * [classify] returns null on ANY failure (no providers, network, rate
 * limit, malformed JSON) - the repository falls back to defaults so the
 * link is never lost.
 */
class AiCategorizationService(
    private val providerStore: LlmProviderStore,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // free models can be slow
        .build()

    /**
     * Whether any LLM provider is configured at all. Callers use this to
     * distinguish "AI failed, worth retrying" from "the user never added a
     * key, so retrying can only ever fail".
     */
    fun isConfigured(): Boolean = hasProviders()

    private fun hasProviders() = providerStore.providers.value.isNotEmpty()

    /**
     * [existingCategories] are passed to the model with instructions to
     * reuse one of them whenever possible, so the taxonomy doesn't sprout a
     * new near-duplicate category ("Tech News" / "Technology News") per link.
     */
    suspend fun classify(
        data: ScrapedData,
        existingCategories: List<String> = emptyList(),
    ): AiClassification? {
        if (!hasProviders()) return null
        return try {
            val rawText = completeChat(
                SYSTEM_PROMPT,
                buildUserPrompt(data) + existingCategoriesBlock(existingCategories),
            ) ?: return null
            json.decodeFromString<AiClassification>(LlmTextParsing.extractJson(rawText))
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Asks the model to merge a sprawling category list into at most
     * [maxCategories] broad ones. Returns a mapping of EVERY input category
     * to its canonical replacement (identity mapping where unchanged), or
     * null on any failure.
     */
    suspend fun consolidateCategories(
        categories: List<Pair<String, Int>>,
        maxCategories: Int,
    ): Map<String, String>? {
        if (!hasProviders() || categories.isEmpty()) return null
        return try {
            val userPrompt = buildString {
                appendLine("Merge these bookmark categories down to at most $maxCategories.")
                appendLine("Categories (with link counts):")
                categories.forEach { (name, count) -> appendLine("- $name ($count)") }
            }
            val rawText = completeChat(CONSOLIDATE_SYSTEM_PROMPT, userPrompt) ?: return null
            val mapping =
                json.decodeFromString<Map<String, String>>(LlmTextParsing.extractJson(rawText))
            mapping.filterValues { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Sends one chat completion, walking the user's provider list in order.
     * Per provider: one retry after a short wait on burst rate limits
     * (HTTP 429); any other failure (bad key, unknown model, network)
     * rotates straight to the next provider. Returns null only when every
     * configured provider failed.
     */
    private suspend fun completeChat(systemPrompt: String, userPrompt: String): String? {
        for (provider in providerStore.providers.value) {
            val endpoint = chatEndpoint(provider.baseUrl)
            if (endpoint == null) {
                providerStore.recordFailure(provider.id, "Invalid base URL")
                continue
            }
            var attempt = 0
            var reason = "Unknown error"
            while (attempt <= MAX_RATE_LIMIT_RETRIES) {
                try {
                    val text = requestChat(endpoint, provider, systemPrompt, userPrompt)
                    if (text != null) {
                        providerStore.recordSuccess(provider.id)
                        return text
                    }
                    // 200 with empty choices (some providers wrap errors this
                    // way) - treat like a rate limit: retry, then rotate.
                    reason = "Empty response from provider"
                } catch (e: CancellationException) {
                    throw e // never swallow coroutine cancellation
                } catch (e: HttpStatusException) {
                    reason = ProviderFailure.describe(e)
                    if (e.code != 429) break // bad key/model → next provider
                } catch (e: Exception) {
                    reason = ProviderFailure.describe(e)
                    break // network/serialization → next provider
                }
                attempt++
                if (attempt <= MAX_RATE_LIMIT_RETRIES) delay(RATE_LIMIT_RETRY_DELAY_MS * attempt)
            }
            // Reaching here means this provider never produced a usable
            // answer (rate-limited out, bad key, network) - record WHY so the
            // provider sheet can surface it instead of a bare "Failing".
            providerStore.recordFailure(provider.id, reason)
        }
        return null
    }

    /**
     * One POST to an OpenAI-compatible chat-completions endpoint.
     *
     * Raw OkHttp rather than Retrofit: this is the only endpoint the app
     * calls, OkHttp and kotlinx-serialization are already direct
     * dependencies, and one fewer reflective layer is one fewer thing for R8
     * to strip in a release build that CI never produces.
     *
     * @throws HttpStatusException on any non-2xx reply.
     */
    private suspend fun requestChat(
        endpoint: String,
        provider: LlmProvider,
        systemPrompt: String,
        userPrompt: String,
    ): String? {
        val payload = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = provider.model,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userPrompt),
                ),
            ),
        )
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        // execute() blocks; Retrofit's suspend adapter used to move this off
        // the calling dispatcher, so it still has to be moved by hand.
        val body = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                response.body?.string()
            }
        } ?: return null
        return json.decodeFromString(ChatResponse.serializer(), body)
            .choices.firstOrNull()?.message?.content
    }

    /**
     * Sends one tiny live request to verify a provider's URL/model/key before
     * the user commits to it. Returns null on success, or a short reason.
     *
     * This runs against an unsaved [provider], so the user finds out a key is
     * wrong at the moment they paste it - not silently, hours later, on the
     * next background classification sweep.
     */
    suspend fun testProvider(provider: LlmProvider): String? {
        // Cleartext is blocked because targetSdk >= 28 makes usesCleartextTraffic
        // default to false, and that default applies on every API 28+ device -
        // i.e. all of them, at minSdk 29. So an http:// endpoint can never work;
        // it dies deep in OkHttp as an opaque "Network error" that reads like a
        // connectivity problem. Say so here, at paste time, instead.
        cleartextReason(provider.baseUrl)?.let { return it }
        val endpoint = chatEndpoint(provider.baseUrl) ?: return "Invalid base URL"
        return try {
            val text = requestChat(endpoint, provider, TEST_SYSTEM_PROMPT, TEST_USER_PROMPT)
            if (text.isNullOrBlank()) "Empty response from provider" else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProviderFailure.describe(e)
        }
    }

    /**
     * Classifies several links with a single LLM request (far fewer calls
     * than one-per-link, which matters on free-tier rate limits).
     *
     * Always returns a list the same size as [items]; entries are null where
     * classification failed, so callers can fall back per-link.
     */
    suspend fun classifyBatch(
        items: List<ScrapedData>,
        existingCategories: List<String> = emptyList(),
    ): List<AiClassification?> {
        if (items.isEmpty()) return emptyList()
        if (!hasProviders()) return List(items.size) { null }
        if (items.size == 1) return listOf(classify(items.first(), existingCategories))
        return try {
            val rawText = completeChat(
                BATCH_SYSTEM_PROMPT,
                buildBatchUserPrompt(items) + existingCategoriesBlock(existingCategories),
            ) ?: return List(items.size) { null }
            val parsed = json.decodeFromString<List<BatchClassificationItem>>(
                LlmTextParsing.extractJsonArray(rawText)
            )
            val byIndex = parsed
                .filter { it.index in items.indices && it.category.isNotBlank() }
                .associateBy { it.index }
            List(items.size) { i ->
                byIndex[i]?.let { AiClassification(it.category, it.aiSummary) }
            }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            List(items.size) { null }
        }
    }

    private fun buildUserPrompt(data: ScrapedData) = """
        Page metadata:
        URL: ${data.url}
        Title: ${data.title}
        Description: ${data.description.ifBlank { "(none)" }}
    """.trimIndent()

    private fun buildBatchUserPrompt(items: List<ScrapedData>) = buildString {
        appendLine("Classify these ${items.size} pages:")
        items.forEachIndexed { index, data ->
            appendLine()
            appendLine("--- Page $index ---")
            appendLine("URL: ${data.url}")
            appendLine("Title: ${data.title}")
            appendLine("Description: ${data.description.ifBlank { "(none)" }}")
        }
    }

    /** Appended to user prompts so the model reuses the current taxonomy. */
    private fun existingCategoriesBlock(existing: List<String>): String {
        if (existing.isEmpty()) return ""
        return "\n\nEXISTING CATEGORIES (you MUST reuse one of these, verbatim, " +
            "whenever the page reasonably fits; only invent a new category when " +
            "none of them fit at all):\n" +
            existing.joinToString(", ")
    }

    companion object {
        /**
         * One short retry per provider recovers burst limits; hard daily
         * quotas rotate to the next configured provider instead.
         */
        private const val MAX_RATE_LIMIT_RETRIES = 1
        private const val RATE_LIMIT_RETRY_DELAY_MS = 3_000L

        /** Same content type the Retrofit converter used to set. */
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Deliberately tiny - a connection check, not a real classification. */
        private const val TEST_SYSTEM_PROMPT = "Reply with the single word: ok"
        private const val TEST_USER_PROMPT = "ping"

        private val SYSTEM_PROMPT = """
            You are a smart bookmark categorization engine.
            Given web page metadata, respond with ONLY a JSON object
            (no markdown, no explanation) using exactly this shape:
            {"category": "<1-2 word category>", "aiSummary": "<one clean sentence summarizing the page>"}

            Rules:
            - "category" must be 1-2 words, Title Case (e.g. "Tech News", "Recipes", "Dev Tools").
            - "aiSummary" must be exactly one sentence.
            - Base everything on the SUBSTANTIVE content of the page. Social media
              captions often start with promotional calls-to-action ("Comment X to
              get...", "Follow for more", giveaway/webinar plugs) - skip those and
              categorize/summarize the actual topic being discussed instead.
            - Never use "Promotion", "Webinar" or similar marketing-mechanics words
              as the category unless the page has no other substance.
        """.trimIndent()

        private val BATCH_SYSTEM_PROMPT = """
            You are a smart bookmark categorization engine.
            You will receive metadata for SEVERAL web pages, each labelled with an index.
            Respond with ONLY a JSON array (no markdown, no explanation) containing one
            object per page, using exactly this shape:
            [{"index": 0, "category": "<1-2 word category>", "aiSummary": "<one clean sentence summarizing the page>"}, ...]

            Rules:
            - Include every page exactly once, with its original "index".
            - "category" must be 1-2 words, Title Case (e.g. "Tech News", "Recipes", "Dev Tools").
            - Reuse the same category string for pages about the same topic.
            - "aiSummary" must be exactly one sentence.
            - Base everything on the SUBSTANTIVE content of the page. Social media
              captions often start with promotional calls-to-action ("Comment X to
              get...", "Follow for more", giveaway/webinar plugs) - skip those and
              categorize/summarize the actual topic being discussed instead.
            - Never use "Promotion", "Webinar" or similar marketing-mechanics words
              as the category unless the page has no other substance.
        """.trimIndent()

        private val CONSOLIDATE_SYSTEM_PROMPT = """
            You reorganize bookmark categories that have grown messy.
            You will receive a list of category names with link counts.
            Respond with ONLY a JSON object (no markdown, no explanation) that maps
            EVERY input category name to a canonical category name, e.g.:
            {"Ai Animation": "AI & Tech", "AI-animation": "AI & Tech", "Recipes": "Food & Cooking"}

            Rules:
            - Every input category must appear exactly once as a key, spelled verbatim.
            - The set of DISTINCT values must not exceed the limit given by the user.
            - Values must be 1-3 words, Title Case, broad but meaningful.
            - Merge near-duplicates (case/punctuation/wording variants) and overly
              specific categories into the same canonical value.
            - Prefer keeping the wording of large existing categories.
            - Map "Uncategorized" to "Uncategorized" (it does not count towards the limit).
        """.trimIndent()
    }
}
