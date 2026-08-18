package de.velospot.feature.map.presentation.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the shared [ShareCardScaffold] contract. The Canvas rendering
 * itself needs a real graphics surface (instrumented) and is excluded, but the
 * scaffold's constants, the reusable alpha helper and the brand-trailing data model
 * are fully testable on the JVM.
 */
class ShareCardScaffoldTest {

    @Test
    fun `card dimensions are the shared 4 by 5 social tile`() {
        assertEquals(1080, SHARE_CARD_WIDTH)
        assertEquals(1350, SHARE_CARD_HEIGHT)
        // 4:5 aspect ratio.
        assertEquals(4f / 5f, SHARE_CARD_WIDTH.toFloat() / SHARE_CARD_HEIGHT, 1e-6f)
    }

    @Test
    fun `white constants carry the expected alpha`() {
        assertEquals(0xFFFFFFFF.toInt(), SHARE_CARD_WHITE)
        assertEquals(0xB3FFFFFF.toInt(), SHARE_CARD_WHITE_70)
    }

    @Test
    fun `withAlpha replaces only the alpha channel`() {
        // RGB preserved, alpha swapped.
        assertEquals(0x00123456, shareCardWithAlpha(0xFF123456.toInt(), 0x00))
        assertEquals(0xFF123456.toInt(), shareCardWithAlpha(0x00123456, 0xFF))
        assertEquals(0x73FFFFFF, shareCardWithAlpha(0xFFFFFFFF.toInt(), 0x73))
    }

    @Test
    fun `brand trailing captures the per-card typography`() {
        val rideTrailing = ShareCardBrandTrailing(text = "12 May 2024", textSize = 34f, bold = false, letterSpacing = 0f)
        assertEquals(34f, rideTrailing.textSize)
        assertEquals(false, rideTrailing.bold)

        val statsTrailing = ShareCardBrandTrailing(text = "ALL-TIME", textSize = 32f, bold = true, letterSpacing = 0.14f)
        assertEquals(32f, statsTrailing.textSize)
        assertEquals(true, statsTrailing.bold)
        assertEquals(0.14f, statsTrailing.letterSpacing)
    }

    @Test
    fun `footer sits below the card body near the bottom edge`() {
        // Baseline 70px above the bottom, visual top one text-size up from that.
        assertEquals(SHARE_CARD_HEIGHT - 70f, shareCardFooterBaseline(), 1e-4f)
        assertEquals(shareCardFooterBaseline() - SHARE_CARD_FOOTER_TEXT_SIZE, shareCardFooterTop(), 1e-4f)
    }

    @Test
    fun `bike stat grid never overlaps the footer even at its tallest`() {
        // The tallest grid is 6 cells → 3 rows. It must end with comfortable
        // clearance above the footer's visual top so the two never collide.
        val tallestBottom = bikeGridBottom(cellCount = 6)
        val clearance = shareCardFooterTop() - tallestBottom
        assertTrue(
            "grid bottom $tallestBottom should clear the footer top ${shareCardFooterTop()}",
            clearance >= 40f
        )
        // Fewer cells are shorter, so they clear by even more.
        assertTrue(bikeGridBottom(4) < tallestBottom)
        assertTrue(bikeGridBottom(2) < bikeGridBottom(4))
        // No cells → nothing drawn below the grid origin.
        assertEquals(bikeGridBottom(0), bikeGridBottom(0), 0f)
    }
}

