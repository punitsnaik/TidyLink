package dev.punit.tidylink.desktop

import dev.punit.tidylink.shared.db.Link
import dev.punit.tidylink.shared.db.Peer
import dev.punit.tidylink.shared.db.TidyLinkDb
import dev.punit.tidylink.shared.db.TrashedLink
import dev.punit.tidylink.shared.identity.DeviceIdentity
import dev.punit.tidylink.shared.sync.SyncServer
import dev.punit.tidylink.shared.sync.SyncStatus
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The single state holder for the desktop app: owns the database, identity,
 * sync server and the app coroutine scope. Composables stay stateless and
 * call through here.
 */
class AppState {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dataDir: Path = run {
        val home = System.getProperty("user.home")
        val dir =
            if (System.getProperty("os.name").orEmpty().contains("Mac")) {
                Paths.get(home, "Library", "Application Support", "TidyLink")
            } else {
                Paths.get(home, ".tidylink")
            }
        Files.createDirectories(dir)
        dir
    }

    val db: TidyLinkDb = TidyLinkDb.open(dataDir.resolve("tidylink.db").toString())
    val identity: DeviceIdentity = DeviceIdentity.loadOrCreate(dataDir)
    val server = SyncServer(db, identity, scope)

    init {
        // start() also wires mDNS advertise + watch internally (SyncServer
        // owns discovery).
        server.start()
        // 90-day trash purge, once per launch - tombstones age out locally.
        scope.launch {
            db.syncDao().purgeTrashBefore(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
        }
    }

    val searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val links: Flow<List<Link>> = searchQuery.flatMapLatest { db.linkDao().observeAll(it) }

    val peers: Flow<List<Peer>> = db.syncDao().observePeers()

    val status: StateFlow<SyncStatus> = server.status

    /** Create or update a link. Blank category falls back to the entity default. */
    suspend fun saveLink(existing: Link?, url: String, title: String, category: String, note: String) {
        val now = System.currentTimeMillis()
        val trimmedUrl = url.trim()
        val link = existing?.copy(
            title = title,
            category = category.ifBlank { "Unsorted" },
            note = note,
            modifiedAt = now,
        ) ?: Link(
            id = UUID.randomUUID().toString(),
            url = trimmedUrl,
            title = title,
            category = category.ifBlank { "Unsorted" },
            timestamp = now,
            // ponytail: simplified dedupeKey; Android's UrlCanonicalizer becomes
            // the shared one at KMP-unification time.
            dedupeKey = trimmedUrl.lowercase()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .trimEnd('/'),
            note = note,
            modifiedAt = now,
        )
        db.linkDao().upsert(link)
    }

    /** Tombstone + delete as one atomic pair (shared @Transaction DAO helper). */
    suspend fun trashLink(link: Link) {
        val now = System.currentTimeMillis()
        db.syncDao().trashAndDeleteLink(
            TrashedLink(link.id, Json.encodeToString(Link.serializer(), link), now),
        )
    }

    suspend fun togglePin(link: Link) =
        db.linkDao().upsert(link.copy(pinned = !link.pinned, modifiedAt = System.currentTimeMillis()))

    suspend fun removePeer(deviceId: String) = db.syncDao().deletePeer(deviceId)

    fun syncNow(): Job = scope.launch { server.syncNow() }

    fun openInBrowser(url: String) {
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (_: Exception) {
            // Headless, unsupported desktop, or a malformed URL - nothing to do.
        }
    }

    /** Window-close teardown: stop the server (and discovery), cancel work, close db. */
    fun shutdown() {
        server.stop()
        // Join before closing the db: a still-running coroutine mid-DAO-call
        // against a closed database is a crash on the way out.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        runCatching { db.close() }
    }
}
