package de.velospot.screenshots

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import de.velospot.MainActivity
import de.velospot.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the marketing / store / page screenshots from the **real running app**
 * (real MapLibre map, real DI graph) and writes them as PNGs via [ScreenshotSaver].
 *
 * Privacy: location permission is intentionally **denied** (the test dismisses the
 * system dialog), so the map never centres on the real device location — no personal
 * location ends up in the published screenshots.
 *
 * Run it on an emulator/device (the map needs a real GPU + network for tiles):
 *   ./gradlew :app:connectedGooglePlayDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.velospot.screenshots.PageScreenshotTest
 *
 * Output is pulled to the host automatically by AGP (via TestStorage) into:
 *   app/build/outputs/connected_android_test_additional_output/googlePlayDebug/connected/<device>/screenshots/
 *
 * Each step is best-effort (wrapped in runCatching) so a single missing element
 * never aborts the whole run — you still get every screenshot that succeeded.
 */
@RunWith(AndroidJUnit4::class)
class PageScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val uiDevice: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val activity get() = composeRule.activity
    private fun str(id: Int, vararg args: Any): String = activity.getString(id, *args)

    /** Let recompositions settle and give MapLibre time to fetch & draw tiles. */
    private fun settle(mapTilesMs: Long = MAP_SETTLE_MS) {
        composeRule.waitForIdle()
        Thread.sleep(mapTilesMs)
        composeRule.waitForIdle()
    }

    /**
     * Denies the runtime location permission dialog (system UI, outside Compose) so it
     * is not in the shot and the map stays off the user's real position. Handles the
     * one- or two-step Android dialog and both EN/DE button labels.
     */
    private fun denyLocationPermission() {
        val denyResIds = listOf(
            "com.android.permissioncontroller:id/permission_deny_button",
            "com.android.packageinstaller:id/permission_deny_button"
        )
        val denyTexts = listOf("Don't allow", "Deny", "Nicht zulassen", "Ablehnen")
        repeat(2) {
            var clicked = false
            for (id in denyResIds) {
                val obj = uiDevice.wait(Until.findObject(By.res(id)), 2_000)
                if (obj != null) { obj.click(); clicked = true; break }
            }
            if (!clicked) {
                for (text in denyTexts) {
                    val obj = uiDevice.findObject(By.text(text))
                    if (obj != null) { obj.click(); clicked = true; break }
                }
            }
            if (!clicked) return
            uiDevice.waitForIdle()
        }
    }

    private fun openMenu() {
        composeRule.onNodeWithContentDescription(str(R.string.menu_open)).performClick()
        composeRule.waitForIdle()
    }

    /** Opens the overflow menu and clicks the item whose label contains [fragment]. */
    private fun clickMenuItemContaining(fragment: String) {
        openMenu()
        composeRule.onAllNodes(hasText(fragment, substring = true), useUnmergedTree = true)
            .onFirst()
            .performClick()
    }

    private fun dismissSheet() {
        runCatching { Espresso.pressBack() }
        composeRule.waitForIdle()
    }

    @Test
    fun generatePageScreenshots() {
        // Deny location so the map does not reveal the real device position.
        denyLocationPermission()

        // Give the app time to initialise (Hilt graph, ViewModel, parking data) and
        // MapLibre time to fetch & render the first batch of tiles before capturing.
        composeRule.waitForIdle()
        Thread.sleep(APP_INIT_MS)

        // 1) Map overview — the hero shot.
        settle()
        ScreenshotSaver.captureAndSave("map-overview")

        // 2) Address search with a typed query + result dropdown.
        runCatching {
            composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Berlin")
            settle()
            ScreenshotSaver.captureAndSave("searchbar")
            // Clear the query again so it doesn't bleed into later shots.
            composeRule.onNodeWithContentDescription(str(R.string.search_clear)).performClick()
            composeRule.waitForIdle()
        }

        // 3) Layers sheet.
        runCatching {
            clickMenuItemContaining(str(R.string.menu_layers))
            settle(SHEET_SETTLE_MS)
            ScreenshotSaver.captureAndSave("layers")
            dismissSheet()
        }

        // 4) Favorites sheet. Label is "Favorites (N)" -> match the leading word.
        runCatching {
            clickMenuItemContaining(str(R.string.menu_favorites_count, 0).substringBefore("(").trim())
            settle(SHEET_SETTLE_MS)
            ScreenshotSaver.captureAndSave("favorites-sheet")
            dismissSheet()
        }

        // 5) Language picker. Label is "Language: <flag>" -> match the leading word.
        runCatching {
            clickMenuItemContaining(str(R.string.menu_language_with_flag, "").substringBefore(":").trim())
            settle(SHEET_SETTLE_MS)
            ScreenshotSaver.captureAndSave("language-picker")
            dismissSheet()
        }

        // 6) Dark mode — toggle last as it changes the theme for everything after.
        runCatching {
            clickMenuItemContaining(str(R.string.menu_enable_dark_mode))
            settle()
            ScreenshotSaver.captureAndSave("dark-mode")
        }

        // TODO (needs map data / interaction, script once on a device):
        //  - "found-location":  type an address, tap a result, capture the dropped pin.
        //  - "parking-details": tap a parking marker on the map, capture the detail sheet.
    }

    companion object {
        /** One-time wait after launch for app init + first map tiles. */
        private const val APP_INIT_MS = 6_000L

        /** Wait for MapLibre to fetch & render tiles before capturing. */
        private const val MAP_SETTLE_MS = 6_000L

        /** Shorter wait for bottom sheets / dialogs (no tile loading). */
        private const val SHEET_SETTLE_MS = 1_500L
    }
}

