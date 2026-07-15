package dev.punit.tidylink.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.punit.tidylink.R
import dev.punit.tidylink.ui.LinkViewModel
import dev.punit.tidylink.ui.dashboard.AiProviderSheet
import kotlinx.coroutines.launch

/** One intro page. [isAiSetup] marks the page that offers the key form. */
private data class IntroPage(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val isAiSetup: Boolean = false,
)

private val PAGES = listOf(
    IntroPage(
        icon = Icons.Filled.Home,
        titleRes = R.string.intro_welcome_title,
        bodyRes = R.string.intro_welcome_body,
    ),
    IntroPage(
        icon = Icons.Filled.Share,
        titleRes = R.string.intro_share_title,
        bodyRes = R.string.intro_share_body,
    ),
    IntroPage(
        icon = Icons.Filled.Search,
        titleRes = R.string.intro_organize_title,
        bodyRes = R.string.intro_organize_body,
    ),
    IntroPage(
        icon = Icons.Filled.Star,
        titleRes = R.string.intro_ai_title,
        bodyRes = R.string.intro_ai_body,
        isAiSetup = true,
    ),
)

/**
 * First-run intro: what TidyLink is, how links get in, how to find them
 * again, and an optional prompt to add an AI key.
 *
 * The AI step is deliberately skippable - categorization is an optional
 * feature that needs the user's own key, and a first-run wall demanding one
 * would misrepresent the app. Whoever skips still meets [AddProviderBanner]
 * on the dashboard, which shows while no provider is configured.
 *
 * Reached from [dev.punit.tidylink.MainActivity] while
 * [LinkViewModel.hasSeenIntro] is false. Sharing a URL into the app does NOT
 * come through here - see ShareReceiverActivity.
 */
@Composable
fun OnboardingScreen(
    viewModel: LinkViewModel,
    modifier: Modifier = Modifier,
) {
    val providers by viewModel.llmProviders.collectAsStateWithLifecycle()
    val health by viewModel.providerHealth.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()
    var showAiProviders by rememberSaveable { mutableStateOf(false) }

    val isLastPage = pagerState.currentPage == PAGES.lastIndex

    // Back steps through the intro rather than dropping straight to the
    // dashboard: leaving via Back would otherwise skip it permanently
    // without the user ever choosing to.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            // innerPadding already carries the system-bar insets (Scaffold's
            // default contentWindowInsets); adding statusBarsPadding() here
            // too would inset twice under enableEdgeToEdge.
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Skip is always available; the intro is informational, never a gate.
            // Fixed height: Skip fades out on the last page, and without a
            // reserved row the pages below would jump up as it goes.
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
            ) {
                AnimatedVisibility(visible = !isLastPage, enter = fadeIn(), exit = fadeOut()) {
                    TextButton(onClick = viewModel::markIntroSeen) {
                        Text(stringResource(R.string.intro_action_skip))
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                IntroPageContent(
                    page = PAGES[page],
                    hasProvider = providers.isNotEmpty(),
                    onAddKey = { showAiProviders = true },
                )
            }

            PageIndicator(
                pageCount = PAGES.size,
                currentPage = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )

            Button(
                onClick = {
                    if (isLastPage) {
                        viewModel.markIntroSeen()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .height(52.dp),
            ) {
                Text(
                    stringResource(
                        if (isLastPage) R.string.intro_action_done else R.string.intro_action_next
                    )
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAiProviders) {
        AiProviderSheet(
            providers = providers,
            health = health,
            onAdd = viewModel::addLlmProvider,
            onRemove = viewModel::removeLlmProvider,
            onMove = viewModel::moveLlmProvider,
            onTest = viewModel::testLlmProvider,
            onDismiss = { showAiProviders = false },
        )
    }
}

/** Icon, title, body - plus the key CTA on the AI page. */
@Composable
private fun IntroPageContent(
    page: IntroPage,
    hasProvider: Boolean,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (page.isAiSetup) {
            Spacer(Modifier.height(28.dp))
            if (hasProvider) {
                // Confirms the key landed - the sheet dismisses back to this
                // page, which would otherwise look like nothing happened.
                Text(
                    text = stringResource(R.string.intro_ai_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                OutlinedButton(onClick = onAddKey) {
                    Text(stringResource(R.string.intro_ai_add_key))
                }
            }
        }
    }
}

/** Dots showing position in the pager. */
@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .height(8.dp)
                    // The active dot stretches instead of just recolouring, so
                    // position stays readable without relying on colour alone.
                    .width(if (selected) 24.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
            )
        }
    }
}
