package de.velospot.feature.map.presentation.markers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MarkerRenderCache]'s diff-gate and, crucially, its [invalidate]
 * escape hatch.
 *
 * Regression guard for the "navigation route stays drawn after stopping" bug:
 * while navigating, `NavigationManager` owns and writes [SOURCE_ROUTE] behind this
 * cache's back. On navigation stop the cache would therefore still believe the
 * source holds whatever the marker renderer last drew (usually empty) and wrongly
 * skip the redraw — leaving a stale route polyline on the map. Calling
 * [MarkerRenderCache.invalidate] on stop must force the next [MarkerRenderCache.changed]
 * for that source to report a change so the renderer re-serialises it.
 */
class MarkerRenderCacheTest {

    @Test
    fun `changed returns true the first time and false for the same key`() {
        val cache = MarkerRenderCache()
        assertTrue("first observation must rebuild", cache.changed(SOURCE_ROUTE, "a;b;c"))
        assertFalse("identical key must skip the rebuild", cache.changed(SOURCE_ROUTE, "a;b;c"))
    }

    @Test
    fun `changed returns true when the key changes`() {
        val cache = MarkerRenderCache()
        cache.changed(SOURCE_ROUTE, "a;b;c")
        assertTrue(cache.changed(SOURCE_ROUTE, "a;b"))
    }

    @Test
    fun `invalidate forces a rebuild even when the key is unchanged`() {
        val cache = MarkerRenderCache()
        // Marker renderer last drew an empty route, so the cache key is "".
        assertTrue(cache.changed(SOURCE_ROUTE, ""))
        assertFalse(cache.changed(SOURCE_ROUTE, ""))

        // Simulate NavigationManager having drawn a real route into SOURCE_ROUTE
        // behind the cache's back, then navigation stopping: the screen invalidates
        // the entry so the renderer becomes authoritative again.
        cache.invalidate(SOURCE_ROUTE)

        // Even though the state route is still empty (same "" key), the source MUST
        // be rebuilt now — otherwise the stale navigation polyline would remain.
        assertTrue("invalidate must force one unconditional rebuild", cache.changed(SOURCE_ROUTE, ""))
    }

    @Test
    fun `invalidate only affects the named source`() {
        val cache = MarkerRenderCache()
        cache.changed(SOURCE_ROUTE, "route")
        cache.changed(SOURCE_PARKING, "parking")

        cache.invalidate(SOURCE_ROUTE)

        assertTrue("invalidated source rebuilds", cache.changed(SOURCE_ROUTE, "route"))
        assertFalse("untouched source still skips", cache.changed(SOURCE_PARKING, "parking"))
    }

    @Test
    fun `invalidate on an unknown source is a no-op`() {
        val cache = MarkerRenderCache()
        // Must not throw and must not affect other entries.
        cache.invalidate(SOURCE_ROUTE)
        assertTrue(cache.changed(SOURCE_ROUTE, "x"))
    }
}

