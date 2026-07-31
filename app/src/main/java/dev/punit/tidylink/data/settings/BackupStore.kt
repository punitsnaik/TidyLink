package dev.punit.tidylink.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Settings row needs to render, in one object so it updates as a
 * unit rather than as four independent flows.
 *
 * [lastFailed] is deliberately separate from "never ran": a backup that
 * broke because the folder was deleted or permission was revoked must not
 * look identical to one that simply hasn't run yet. Silent backup failure
 * is the failure mode that matters here, because the whole point is that
 * the phone stops being the only copy.
 */
data class BackupState(
    val enabled: Boolean = false,
    val folderUri: String? = null,
    val lastSuccessAt: Long = 0L,
    val lastFailed: Boolean = false,
)

/**
 * Persists the scheduled-backup settings.
 *
 * SharedPreferences, following [ThemeStore] and [OnboardingStore] - the
 * same reasoning applies for a different reason: BackupWorker runs in a
 * background process with no ViewModel, and reading prefs synchronously is
 * simpler there than plumbing DataStore's suspend API through a Worker.
 */
class BackupStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private fun read() = BackupState(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        folderUri = prefs.getString(KEY_FOLDER_URI, null),
        lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0L),
        lastFailed = prefs.getBoolean(KEY_LAST_FAILED, false),
    )

    /** Turning it on and choosing a folder happen together - one write. */
    fun enable(folderUri: String) {
        prefs.edit {
            putBoolean(KEY_ENABLED, true)
            putString(KEY_FOLDER_URI, folderUri)
            putBoolean(KEY_LAST_FAILED, false)
        }
        _state.value = read()
    }

    /** Keeps the folder URI: re-enabling shouldn't re-ask for the folder. */
    fun disable() {
        prefs.edit { putBoolean(KEY_ENABLED, false) }
        _state.value = read()
    }

    fun recordSuccess(at: Long) {
        prefs.edit {
            putLong(KEY_LAST_SUCCESS, at)
            putBoolean(KEY_LAST_FAILED, false)
        }
        _state.value = read()
    }

    fun recordFailure() {
        prefs.edit { putBoolean(KEY_LAST_FAILED, true) }
        _state.value = read()
    }

    /**
     * Read directly rather than through the flow: [BackupStore] may be
     * constructed fresh inside a Worker, where nothing has collected yet.
     */
    fun currentFolderUri(): String? = prefs.getString(KEY_FOLDER_URI, null)

    private companion object {
        const val PREFS_NAME = "backup"
        const val KEY_ENABLED = "enabled"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_LAST_SUCCESS = "last_success_at"
        const val KEY_LAST_FAILED = "last_failed"
    }
}
