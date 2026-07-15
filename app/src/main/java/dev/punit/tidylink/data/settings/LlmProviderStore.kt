package dev.punit.tidylink.data.settings

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * One OpenAI-compatible LLM endpoint the user has configured in-app.
 * Providers are tried in list order; on rate limits the app falls back to
 * the next one, so adding several free-tier keys multiplies daily quota.
 */
@Serializable
data class LlmProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    /** Safe-to-display form of the key, e.g. "sk-o…" */
    val maskedKey: String
        get() = if (apiKey.length <= 8) "••••••" else apiKey.take(4) + "…"
}

/**
 * Health snapshot for one provider: when it last answered successfully, when
 * it last failed, and WHY, so quota/key problems are visible in Settings.
 *
 * [lastError] matters more than it looks: without a reason surfaced in the UI,
 * a stripped-serializer or revoked-key failure are indistinguishable — both
 * just read "Failing". See [ProviderFailure].
 */
@Serializable
data class ProviderHealth(
    val lastOkAt: Long = 0L,
    val lastFailAt: Long = 0L,
    val lastError: String? = null,
) {
    /** null = never used yet; otherwise whether the LAST call succeeded. */
    val isHealthy: Boolean?
        get() = when {
            lastOkAt == 0L && lastFailAt == 0L -> null
            else -> lastOkAt >= lastFailAt
        }
}

/**
 * On-device storage for the user's LLM providers. The provider list
 * (containing API keys) is encrypted with an Android Keystore key before it
 * touches SharedPreferences — see [KeyStoreCrypto] — and the prefs file is
 * additionally excluded from cloud backup and device transfer. Keys are
 * entered in-app (Settings → AI providers) and never leave the device.
 *
 * Entries written by pre-encryption versions of the app are migrated to the
 * encrypted format on first load. If decryption ever fails (e.g. the
 * Keystore key was invalidated), the list resets to empty and the user must
 * re-enter their keys — the data is unrecoverable by design.
 */
class LlmProviderStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _providers = MutableStateFlow(load())
    val providers: StateFlow<List<LlmProvider>> = _providers.asStateFlow()

    private val _health = MutableStateFlow(loadHealth())
    /** Provider id -> latest health snapshot. */
    val health: StateFlow<Map<String, ProviderHealth>> = _health.asStateFlow()

    fun add(provider: LlmProvider) {
        persist(_providers.value + provider.sanitized().withUniqueName(_providers.value))
    }

    /**
     * Stacking several keys for the SAME provider is a supported way to
     * multiply free-tier quota, so name collisions are expected. Suffix them
     * ("Gemini 2") rather than leaving two indistinguishable rows.
     */
    private fun LlmProvider.withUniqueName(existing: List<LlmProvider>): LlmProvider {
        val taken = existing.map { it.name }.toSet()
        if (name !in taken) return this
        var n = 2
        while ("$name $n" in taken) n++
        return copy(name = "$name $n")
    }

    fun remove(id: String) {
        persist(_providers.value.filterNot { it.id == id })
        persistHealth(_health.value - id)
    }

    /**
     * Moves a provider one slot up or down. List order IS the fallback order,
     * so this is the only way to control which key gets used first.
     */
    fun move(id: String, up: Boolean) {
        val list = _providers.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = if (up) from - 1 else from + 1
        if (to !in list.indices) return
        list.add(to, list.removeAt(from))
        persist(list)
    }

    /** Applies the same URL/whitespace fixes [add] does, without saving. */
    fun normalize(provider: LlmProvider): LlmProvider = provider.sanitized()

    fun recordSuccess(id: String) {
        val current = _health.value[id] ?: ProviderHealth()
        persistHealth(
            _health.value + (
                id to current.copy(
                    lastOkAt = System.currentTimeMillis(),
                    lastError = null,
                )
                )
        )
    }

    /** [reason] is shown verbatim in the provider sheet — keep it short. */
    fun recordFailure(id: String, reason: String) {
        val current = _health.value[id] ?: ProviderHealth()
        persistHealth(
            _health.value + (
                id to current.copy(
                    lastFailAt = System.currentTimeMillis(),
                    lastError = reason,
                )
                )
        )
    }

    private fun persist(list: List<LlmProvider>) {
        _providers.value = list
        writeEncrypted(list)
    }

    /** Prefs write only — safe to call before [_providers] exists (migration). */
    private fun writeEncrypted(list: List<LlmProvider>) {
        val encrypted = try {
            Base64.encodeToString(
                KeyStoreCrypto.encrypt(json.encodeToString(list).toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP,
            )
        } catch (e: Exception) {
            return // Keystore unavailable: keep in memory only, never plaintext.
        }
        prefs.edit()
            .putString(KEY_PROVIDERS_ENCRYPTED, encrypted)
            .remove(KEY_PROVIDERS_LEGACY)
            .apply()
    }

    private fun load(): List<LlmProvider> {
        // Preferred: the encrypted blob.
        prefs.getString(KEY_PROVIDERS_ENCRYPTED, null)?.let { blob ->
            return try {
                val plain = KeyStoreCrypto.decrypt(Base64.decode(blob, Base64.NO_WRAP))
                json.decodeFromString<List<LlmProvider>>(String(plain, Charsets.UTF_8))
            } catch (e: Exception) {
                emptyList() // Keystore key invalidated — keys must be re-entered.
            }
        }
        // Migration: plaintext entries from pre-encryption versions.
        val legacy = try {
            prefs.getString(KEY_PROVIDERS_LEGACY, null)
                ?.let { json.decodeFromString<List<LlmProvider>>(it) }
                .orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        if (legacy.isNotEmpty()) writeEncrypted(legacy)
        return legacy
    }

    private fun persistHealth(map: Map<String, ProviderHealth>) {
        _health.value = map
        prefs.edit().putString(KEY_HEALTH, json.encodeToString(map)).apply()
    }

    private fun loadHealth(): Map<String, ProviderHealth> = try {
        prefs.getString(KEY_HEALTH, null)
            ?.let { json.decodeFromString<Map<String, ProviderHealth>>(it) }
            .orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }

    /** Fixes the common URL mistakes so Retrofit doesn't reject the entry. */
    private fun LlmProvider.sanitized(): LlmProvider {
        var url = baseUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        if (!url.endsWith("/")) url += "/"
        return copy(
            name = name.trim().ifBlank { hostLabel(url) },
            baseUrl = url,
            model = model.trim(),
            apiKey = apiKey.trim(),
        )
    }

    private fun hostLabel(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .removePrefix("www.")

    private companion object {
        const val PREFS_NAME = "llm_providers"
        const val KEY_PROVIDERS_LEGACY = "providers"
        const val KEY_PROVIDERS_ENCRYPTED = "providers_enc"
        const val KEY_HEALTH = "provider_health"
    }
}
