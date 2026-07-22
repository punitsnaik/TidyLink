package dev.punit.tidylink.data.ai

import dev.punit.tidylink.data.scraper.ScrapedData
import dev.punit.tidylink.data.settings.LlmProvider
import dev.punit.tidylink.data.settings.LlmProviderStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/** What the LLM must return - parsed with kotlinx-serialization. */
@Serializable
data class AiClassification(
    val category: String,
    val tags: List<String>,
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
    val tags: List<String> = emptyList(),
    val aiSummary: String = "",
)

// -------------------------------------------------------------------------

interface ChatCompletionsApi {
    @POST("chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest,
    ): ChatResponse
}

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
        is retrofit2.HttpException -> httpReason(e.code())
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
 * Classifies a scraped link into category / tags / summary using the LLM
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

    /** One Retrofit stub per distinct base URL, built lazily. */
    private val apiCache = mutableMapOf<String, ChatCompletionsApi>()

    @Synchronized
    private fun apiFor(baseUrl: String): ChatCompletionsApi? = try {
        apiCache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ChatCompletionsApi::class.java)
        }
    } catch (e: Exception) {
        null // malformed base URL - skip this provider
    }

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
            val api = apiFor(provider.baseUrl)
            if (api == null) {
                providerStore.recordFailure(provider.id, "Invalid base URL")
                continue
            }
            var attempt = 0
            var reason = "Unknown error"
            while (attempt <= MAX_RATE_LIMIT_RETRIES) {
                try {
                    val text = requestChat(api, provider, systemPrompt, userPrompt)
                    if (text != null) {
                        providerStore.recordSuccess(provider.id)
                        return text
                    }
                    // 200 with empty choices (some providers wrap errors this
                    // way) - treat like a rate limit: retry, then rotate.
                    reason = "Empty response from provider"
                } catch (e: CancellationException) {
                    throw e // never swallow coroutine cancellation
                } catch (e: retrofit2.HttpException) {
                    reason = ProviderFailure.describe(e)
                    if (e.code() != 429) break // bad key/model → next provider
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

    private suspend fun requestChat(
        api: ChatCompletionsApi,
        provider: LlmProvider,
        systemPrompt: String,
        userPrompt: String,
    ): String? = api.createChatCompletion(
        authorization = "Bearer ${provider.apiKey}",
        request = ChatRequest(
            model = provider.model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
        ),
    ).choices.firstOrNull()?.message?.content

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
        val api = apiFor(provider.baseUrl) ?: return "Invalid base URL"
        return try {
            val text = requestChat(api, provider, TEST_SYSTEM_PROMPT, TEST_USER_PROMPT)
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
                byIndex[i]?.let { AiClassification(it.category, it.tags, it.aiSummary) }
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

        /** Deliberately tiny - a connection check, not a real classification. */
        private const val TEST_SYSTEM_PROMPT = "Reply with the single word: ok"
        private const val TEST_USER_PROMPT = "ping"

        private val SYSTEM_PROMPT = """
            You are a smart bookmark categorization engine.
            Given web page metadata, respond with ONLY a JSON object
            (no markdown, no explanation) using exactly this shape:
            {"category": "<1-2 word category>", "tags": ["<tag1>", "<tag2>", "<tag3>"], "aiSummary": "<one clean sentence summarizing the page>"}

            Rules:
            - "category" must be 1-2 words, Title Case (e.g. "Tech News", "Recipes", "Dev Tools").
            - "tags" must be an array of 3 to 5 short lowercase strings.
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
            [{"index": 0, "category": "<1-2 word category>", "tags": ["<tag1>", "<tag2>", "<tag3>"], "aiSummary": "<one clean sentence summarizing the page>"}, ...]

            Rules:
            - Include every page exactly once, with its original "index".
            - "category" must be 1-2 words, Title Case (e.g. "Tech News", "Recipes", "Dev Tools").
            - Reuse the same category string for pages about the same topic.
            - "tags" must be an array of 3 to 5 short lowercase strings.
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
