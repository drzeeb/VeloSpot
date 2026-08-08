package de.velospot.feature.wrapped.presentation

import de.velospot.feature.wrapped.domain.WrappedHighlightType
import de.velospot.feature.wrapped.domain.WrappedReport

/**
 * The kind of "slide" a [WrappedStoryPage] represents in the auto-advancing Story.
 *
 * - [INTRO]  — the opening slide (period label + headline distance).
 * - [HIGHLIGHT] — one metric / record slide (see [WrappedStoryPage.highlightType]).
 * - [OUTRO]  — the closing share card.
 */
internal enum class WrappedStoryPageKind { INTRO, HIGHLIGHT, OUTRO }

/**
 * A neutral, **display-agnostic** model for one Story slide.
 *
 * All string resolution and number formatting is left to the Composable so this
 * mapper — and its ordering logic — stays fully JVM-unit-testable (no Android
 * resources). Only the pre-computed numeric value(s), the optional delta and the
 * optional source ride id travel with each page.
 *
 * @property kind which kind of slide this is.
 * @property highlightType the metric on a [WrappedStoryPageKind.HIGHLIGHT] slide
 *  (always `null` on the intro / outro).
 * @property valueNumber the primary numeric value (meters, seconds, m/s, count …),
 *  interpretation defined by [highlightType] (or the total distance on the intro).
 * @property deltaPercent optional percentage change vs. the previous window.
 * @property rideId set when a single ride is the source of the highlight.
 * @property isRecord `true` for the celebratory NEW_* record slides.
 */
internal data class WrappedStoryPage(
    val kind: WrappedStoryPageKind,
    val highlightType: WrappedHighlightType? = null,
    val valueNumber: Double = 0.0,
    val deltaPercent: Double? = null,
    val rideId: String? = null,
    val isRecord: Boolean = false
)

/** The NEW_* highlight types that get a celebratory "record" treatment. */
private val RECORD_HIGHLIGHT_TYPES = setOf(
    WrappedHighlightType.NEW_DISTANCE_RECORD,
    WrappedHighlightType.NEW_TOP_SPEED_RECORD,
    WrappedHighlightType.NEW_CLIMB_RECORD
)

/**
 * Turns a [WrappedReport] into the ordered list of Story slides:
 *
 * 1. a single [WrappedStoryPageKind.INTRO] slide carrying the total distance,
 * 2. one [WrappedStoryPageKind.HIGHLIGHT] slide per report highlight, preserving
 *    the engine's ordering (NEW_* records first, then core totals, then streak /
 *    comparisons),
 * 3. a closing [WrappedStoryPageKind.OUTRO] share slide.
 *
 * Pure and deterministic — every input needed to reproduce the output is in
 * [report]. An empty highlight list simply yields `[INTRO, OUTRO]`.
 */
internal fun buildWrappedStoryPages(report: WrappedReport): List<WrappedStoryPage> =
    buildList {
        add(
            WrappedStoryPage(
                kind = WrappedStoryPageKind.INTRO,
                valueNumber = report.stats.totalDistanceMeters
            )
        )
        report.highlights.forEach { highlight ->
            add(
                WrappedStoryPage(
                    kind = WrappedStoryPageKind.HIGHLIGHT,
                    highlightType = highlight.type,
                    valueNumber = highlight.valueNumber,
                    deltaPercent = highlight.deltaPercent,
                    rideId = highlight.rideId,
                    isRecord = highlight.type in RECORD_HIGHLIGHT_TYPES
                )
            )
        }
        add(WrappedStoryPage(kind = WrappedStoryPageKind.OUTRO))
    }

