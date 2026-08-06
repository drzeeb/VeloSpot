package de.velospot.core.format

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class RideFormatTest {

    private lateinit var originalLocale: Locale

    // Pin a dot-decimal locale so the string assertions are deterministic; the
    // formatter itself follows the JVM/device default locale like the other
    // RideFormat helpers.
    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `co2 below one kilogram is shown in whole grams`() {
        assertEquals("0 g", formatCo2Saved(0.0))
        assertEquals("850 g", formatCo2Saved(850.0))
        assertEquals("999 g", formatCo2Saved(999.4))
    }

    @Test
    fun `co2 at or above one kilogram is shown in kilograms with one decimal`() {
        assertEquals("1.0 kg", formatCo2Saved(1_000.0))
        assertEquals("3.2 kg", formatCo2Saved(3_200.0))
        assertEquals("12.3 kg", formatCo2Saved(12_345.0))
    }
}

