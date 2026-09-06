package dev.punit.tidylink

import android.app.Application
import dev.punit.tidylink.data.ai.AiCategorizationService
import dev.punit.tidylink.data.local.AppDatabase
import dev.punit.tidylink.data.repository.LinkRepository
import dev.punit.tidylink.data.scraper.LinkScraperService
import dev.punit.tidylink.data.settings.BackupStore
import dev.punit.tidylink.data.settings.LlmProviderStore
import dev.punit.tidylink.data.settings.OnboardingStore
import dev.punit.tidylink.data.settings.ThemeStore
import dev.punit.tidylink.data.settings.UiPreferencesStore
import dev.punit.tidylink.data.api.GitHubRepoService
import dev.punit.tidylink.data.api.UrlSafetyService
import dev.punit.tidylink.data.api.WaybackService
import dev.punit.tidylink.data.reader.ReaderModeService
import dev.punit.tidylink.data.update.UpdateChecker
import dev.punit.tidylink.data.work.EnrichmentSweepWorker
import dev.punit.tidylink.sync.DeviceIdentity
import dev.punit.tidylink.sync.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container - deliberately simple (no Hilt) for an app of
 * this size. Everything is lazy so nothing is built until first use.
 */
class AppContainer(application: Application) {
    private val database by lazy { AppDatabase.getInstance(application) }

    private val app = application

    val llmProviderStore by lazy { LlmProviderStore(app.applicationContext) }

    val onboardingStore by lazy { OnboardingStore(app.applicationContext) }

    val themeStore by lazy { ThemeStore(app.applicationContext) }

    val uiPreferencesStore by lazy { UiPreferencesStore(app.applicationContext) }

    val backupStore by lazy { BackupStore(app.applicationContext) }

    val updateChecker by lazy { UpdateChecker(app.applicationContext) }

    /** Shared so the provider sheet can test a key before saving it. */
    val aiService by lazy { AiCategorizationService(llmProviderStore) }

    /** This device's stable sync identity (schema v9). See `sync/DeviceIdentity.kt`. */
    private val deviceIdentity by lazy { DeviceIdentity.loadOrCreate(app.applicationContext) }

    val syncClient by lazy { SyncClient(database, deviceIdentity) }

    val waybackService by lazy { WaybackService() }
    val urlSafetyService by lazy { UrlSafetyService() }
    val gitHubRepoService by lazy { GitHubRepoService() }
    val readerModeService by lazy { ReaderModeService() }

    val linkRepository by lazy {
        LinkRepository(
            linkDao = database.linkDao(),
            scraper = LinkScraperService(),
            aiService = aiService,
            appContext = app.applicationContext,
            waybackService = waybackService,
            urlSafetyService = urlSafetyService,
            gitHubRepoService = gitHubRepoService,
            readerModeService = readerModeService,
        )
    }
}

class TidyLinkApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** App-lifetime scope for work that must outlive any single activity (e.g. share captures). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch {
            // One-time upgrade: index legacy rows for fast duplicate checks.
            runCatching { container.linkRepository.backfillDedupeKeys() }
            // Drop trash past its 90 days. One DELETE at startup rather than
            // a worker - trash purged a few hours late harms nobody, and a
            // periodic job would be more machinery than this deserves.
            runCatching { container.linkRepository.purgeExpiredTrash() }
            // Resume any interrupted enrichment (app killed mid-import, or
            // rows upgraded from a schema without scrape bookkeeping).
            runCatching {
                if (container.linkRepository.hasPendingEnrichment()) {
                    EnrichmentSweepWorker.enqueue(this@TidyLinkApplication)
                }
            }
        }
    }
}
