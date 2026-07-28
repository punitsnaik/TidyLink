package dev.punit.tidylink

import android.app.Application
import dev.punit.tidylink.data.ai.AiCategorizationService
import dev.punit.tidylink.data.local.AppDatabase
import dev.punit.tidylink.data.repository.LinkRepository
import dev.punit.tidylink.data.scraper.LinkScraperService
import dev.punit.tidylink.data.settings.LlmProviderStore
import dev.punit.tidylink.data.settings.OnboardingStore
import dev.punit.tidylink.data.settings.ThemeStore
import dev.punit.tidylink.data.update.UpdateChecker
import dev.punit.tidylink.data.work.EnrichmentSweepWorker
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

    val updateChecker by lazy { UpdateChecker(app.applicationContext) }

    /** Shared so the provider sheet can test a key before saving it. */
    val aiService by lazy { AiCategorizationService(llmProviderStore) }

    val linkRepository by lazy {
        LinkRepository(
            linkDao = database.linkDao(),
            scraper = LinkScraperService(),
            aiService = aiService,
            appContext = app.applicationContext,
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
