package de.velospot.feature.wrapped.presentation

import de.velospot.core.stats.computeRideStatistics
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.feature.wrapped.domain.WrappedComparison
import de.velospot.feature.wrapped.domain.WrappedHighlightType
import de.velospot.feature.wrapped.domain.WrappedPeriod
import de.velospot.feature.wrapped.domain.WrappedReport
import de.velospot.feature.wrapped.engine.WrappedEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit coverage for the pure Story page mapper [buildWrappedStoryPages]: slide
 * ordering, which pages appear for a record-setting vs. a plain report, and the
 * empty-highlight edge case. No Android resources are touched here.
 */
class WrappedStoryPagesTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    private fun ride(
        id: String,
        startedAt: Long,
        distanceMeters: Double = 1_000.0,
        maxSpeedMps: Double = 8.0
    ) = RecordedRideSummary(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 600_000,
        distanceMeters = distanceMeters,
        elapsedSeconds = 600,
        movingSeconds = 600,
        avgSpeedMps = distanceMeters / 600.0,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = 50.0,
        elevationLossMeters = 40.0,
        isMock = false
    )

    private val week = WrappedPeriod.week(at(2024, 6, 12))
    private val now = at(2024, 6, 20)

    @Test
    fun `first page is intro, last page is outro`() {
        val report = WrappedEngine.build(listOf(ride("a", at(2024, 6, 12))), week, now)!!
        val pages = buildWrappedStoryPages(report)
        assertEquals(WrappedStoryPageKind.INTRO, pages.first().kind)
        assertEquals(WrappedStoryPageKind.OUTRO, pages.last().kind)
    }

    @Test
    fun `intro carries the total distance`() {
        val report = WrappedEngine.build(
            listOf(ride("a", at(2024, 6, 12), distanceMeters = 7_000.0)),
            week, now
        )!!
        val pages = buildWrappedStoryPages(report)
        assertEquals(7_000.0, pages.first().valueNumber, 0.0)
    }

    @Test
    fun `highlight pages preserve the engine ordering one-to-one`() {
        val report = WrappedEngine.build(listOf(ride("a", at(2024, 6, 12))), week, now)!!
        val pages = buildWrappedStoryPages(report)
        val highlightPages = pages.filter { it.kind == WrappedStoryPageKind.HIGHLIGHT }
        assertEquals(report.highlights.size, highlightPages.size)
        assertEquals(
            report.highlights.map { it.type },
            highlightPages.map { it.highlightType }
        )
    }

    @Test
    fun `record report surfaces a celebratory record page first`() {
        val report = WrappedEngine.build(
            listOf(
                // Prior best 3 km; a period ride that beats it → NEW_DISTANCE_RECORD.
                ride("old", at(2024, 1, 1), distanceMeters = 3_000.0),
                ride("new", at(2024, 6, 12), distanceMeters = 8_000.0)
            ),
            week, now
        )!!
        val pages = buildWrappedStoryPages(report)
        val firstHighlight = pages.first { it.kind == WrappedStoryPageKind.HIGHLIGHT }
        assertEquals(WrappedHighlightType.NEW_DISTANCE_RECORD, firstHighlight.highlightType)
        assertTrue(firstHighlight.isRecord)
    }

    @Test
    fun `plain report has no record pages`() {
        val report = WrappedEngine.build(
            listOf(ride("only", at(2024, 6, 12), distanceMeters = 4_000.0)),
            week, now
        )!!
        val pages = buildWrappedStoryPages(report)
        assertFalse(pages.any { it.isRecord })
    }

    @Test
    fun `empty highlight list still yields intro and outro only`() {
        val report = WrappedReport(
            period = WrappedPeriod.custom(0L, 1L),
            generatedAt = 0L,
            stats = computeRideStatistics(emptyList(), now),
            comparison = WrappedComparison(0.0, null, 0, 0),
            highlights = emptyList()
        )
        val pages = buildWrappedStoryPages(report)
        assertEquals(2, pages.size)
        assertEquals(WrappedStoryPageKind.INTRO, pages[0].kind)
        assertEquals(WrappedStoryPageKind.OUTRO, pages[1].kind)
    }
}

