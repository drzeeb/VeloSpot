package de.velospot.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start benchmark used to *quantify* the Baseline Profile's effect.
 *
 * Run both variants and compare `timeToInitialDisplay`:
 *   ./gradlew :baselineprofile:pixel6Api34BenchmarkAndroidTest
 *
 *  - [startupNoCompilation]        — worst case, JIT only (no profile).
 *  - [startupBaselineProfile]      — partial AOT using the generated profile.
 *
 * The delta between the two is the cold-start improvement the profile buys.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() =
        startup(CompilationMode.Partial(baselineProfileMode = androidx.benchmark.macro.BaselineProfileMode.Require))

    private fun startup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
    ) {
        pressHome()
        startActivityAndWait()
    }
}

