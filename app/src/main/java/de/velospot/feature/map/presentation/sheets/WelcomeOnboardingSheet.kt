package de.velospot.feature.map.presentation.sheets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.feature.map.presentation.headingSemantics
import kotlinx.coroutines.launch

/** A single onboarding page's content. */
internal data class OnboardingPage(
    val icon: ImageVector,
    val titleRes: Int,
    val descRes: Int,
    /** When `true`, the page shows the "set up offline routing" call-to-action. */
    val showOfflineCta: Boolean = false
)

/**
 * First-launch **welcome onboarding** — a compact 3-card bottom sheet that
 * introduces VeloSpot without the fragility of a coordinate-based coach-mark tour.
 *
 * The three pages mirror the app's own building blocks:
 *  1. **Welcome** — what VeloSpot is.
 *  2. **Offline maps & routing** — works without a connection; offers a direct CTA
 *     that opens the offline-regions manager ([OfflineRegionsSheet]).
 *  3. **Get started** — a nudge towards search / the map / the menus.
 *
 * Shown only when [de.velospot.domain.repository.MapSettingsRepository.onboardingCompleted]
 * is `false`; swiping it away or finishing marks it completed via [onFinish]. It can
 * be re-opened later from the About sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeOnboardingSheet(
    onFinish: () -> Unit,
    onActivateOfflineRouting: () -> Unit
) {
    val pages = listOf(
        OnboardingPage(
            icon = Icons.Default.PedalBike,
            titleRes = R.string.onboarding_welcome_title,
            descRes = R.string.onboarding_welcome_desc
        ),
        OnboardingPage(
            icon = Icons.Default.Map,
            titleRes = R.string.onboarding_offline_title,
            descRes = R.string.onboarding_offline_desc,
            showOfflineCta = true
        ),
        OnboardingPage(
            icon = Icons.AutoMirrored.Filled.DirectionsBike,
            titleRes = R.string.onboarding_ready_title,
            descRes = R.string.onboarding_ready_desc
        )
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    // Dismissing (swipe down / back / scrim tap) counts as completing so the sheet
    // does not reappear on the next launch.
    ModalBottomSheet(onDismissRequest = onFinish, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp)
        ) {
            // Skip control (top-right) — hidden on the last page where the primary
            // button already reads "Get started".
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (pagerState.currentPage < pages.lastIndex) {
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                } else {
                    Spacer(Modifier.height(48.dp))
                }
            }

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 8.dp),
                pageSpacing = 16.dp
            ) { pageIndex ->
                OnboardingPageContent(
                    page = pages[pageIndex],
                    onActivateOfflineRouting = onActivateOfflineRouting
                )
            }

            Spacer(Modifier.height(20.dp))

            // Page indicator dots.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { index ->
                    val selected = pagerState.currentPage == index
                    val color by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        label = "dotColor"
                    )
                    val dotWidth by animateDpAsState(
                        if (selected) 22.dp else 8.dp, label = "dotWidth"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            val isLastPage = pagerState.currentPage == pages.lastIndex
            Button(
                onClick = {
                    if (isLastPage) {
                        onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = stringResource(
                        if (isLastPage) R.string.onboarding_finish else R.string.onboarding_next
                    )
                )
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    onActivateOfflineRouting: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.headingSemantics()
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(page.descRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (page.showOfflineCta) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onActivateOfflineRouting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.onboarding_offline_cta))
            }
        }
    }
}

