package de.velospot.feature.wrapped.presentation

import de.velospot.feature.wrapped.domain.WrappedHighlightType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure (non-Composable) Story presentation helpers: the icon
 * mapping, the title-resource selection and — chiefly — the value-formatting
 * **unit selection** per highlight type. These carry the branch logic Kover counts
 * for the `wrapped/presentation` package; the surrounding `@Composable`s are excluded.
 */
class WrappedStoryVisualsTest {

    @Test
    fun `every highlight type maps to an icon`() {
        WrappedHighlightType.entries.forEach { type ->
            assertNotNull("missing icon for $type", wrappedIconFor(type))
        }
    }

    @Test
    fun `every highlight type has a title resource`() {
        WrappedHighlightType.entries.forEach { type ->
            assertNotEquals("missing title for $type", 0, wrappedTitleResFor(type))
        }
    }

    @Test
    fun `distance metrics format in km`() {
        val v = formatWrappedValue(WrappedHighlightType.TOTAL_DISTANCE, 12_340.0)
        assertTrue(v.endsWith("km"))
    }

    @Test
    fun `moving time formats as a clock`() {
        val v = formatWrappedValue(WrappedHighlightType.MOVING_TIME, 3_661.0)
        assertTrue(v.contains(":"))
    }

    @Test
    fun `elevation metrics carry the up-arrow and metres`() {
        val v = formatWrappedValue(WrappedHighlightType.ELEVATION_GAIN, 1_234.0)
        assertTrue(v.startsWith("↑"))
        assertTrue(v.endsWith("m"))
    }

    @Test
    fun `speed metrics format in km per hour`() {
        val v = formatWrappedValue(WrappedHighlightType.TOP_SPEED, 10.0)
        assertTrue(v.endsWith("km/h"))
    }

    @Test
    fun `count metrics are whole numbers`() {
        assertEquals("7", formatWrappedValue(WrappedHighlightType.RIDE_COUNT, 7.0))
        assertEquals("3", formatWrappedValue(WrappedHighlightType.ACTIVE_DAYS, 3.0))
    }

    @Test
    fun `delta formatting signs the percentage`() {
        assertEquals("+42%", formatWrappedDelta(42.4))
        assertEquals("-13%", formatWrappedDelta(-13.0))
        assertEquals("0%", formatWrappedDelta(0.0))
        assertNull(formatWrappedDelta(null))
    }
}

