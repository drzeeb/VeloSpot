package de.velospot.metadata

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards the Google Play Store limit for release notes: every fastlane
 * changelog file (`fastlane/metadata/android/<locale>/changelogs/<code>.txt`)
 * must be **at most 500 characters**.
 *
 * The Play Developer API rejects an upload otherwise, e.g.:
 * `The release created has notes in language en-US with length 526, which is
 * too long (max: 500).`
 *
 * Play counts Unicode **code points** with **normalized (LF) line endings**,
 * including any trailing newline — a file stored with CRLF is counted as if the
 * `\r` were absent. We count exactly the same way here so this test mirrors what
 * the Play API would reject (the 526 in the error above equals this count).
 */
class FastlaneChangelogLengthTest {

    private companion object {
        const val MAX_CHARS = 500
    }

    @Test
    fun everyChangelogIsWithinPlayLimit() {
        val changelogsRoot = locateAndroidMetadataDir()

        val changelogFiles = changelogsRoot.walkTopDown()
            .filter { it.isFile && it.extension == "txt" && it.parentFile?.name == "changelogs" }
            .sortedBy { it.path }
            .toList()

        assertTrue(
            "No fastlane changelog files found under ${changelogsRoot.absolutePath} — " +
                "the test could not locate the metadata, so it would silently pass.",
            changelogFiles.isNotEmpty()
        )

        val offenders = changelogFiles.mapNotNull { file ->
            val count = playCharCount(file)
            if (count > MAX_CHARS) "${relativeName(changelogsRoot, file)} = $count characters" else null
        }

        if (offenders.isNotEmpty()) {
            fail(
                "These fastlane changelog files exceed the Google Play " +
                    "$MAX_CHARS-character limit (Play would reject the release):\n" +
                    offenders.joinToString("\n") { "  • $it" }
            )
        }
    }

    /** Code-point length with CRLF normalized to LF — exactly how Play counts. */
    private fun playCharCount(file: File): Int =
        file.readText(Charsets.UTF_8).replace("\r\n", "\n").codePointCount()

    private fun String.codePointCount(): Int = codePointCount(0, length)

    private fun relativeName(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    /**
     * Walks up from the test's working directory until it finds
     * `fastlane/metadata/android`, so the test works regardless of whether it is
     * run from the module or the repository root.
     */
    private fun locateAndroidMetadataDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "fastlane/metadata/android")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate 'fastlane/metadata/android' starting from " +
                File("").absolutePath
        )
    }
}

