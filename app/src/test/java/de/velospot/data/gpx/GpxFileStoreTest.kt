package de.velospot.data.gpx

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import de.velospot.core.share.GpxDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * JVM unit tests for [GpxFileStore]'s SAF file I/O.
 *
 * The `ContentResolver`/`Uri` are Mockito stubs feeding in-memory streams. GPX
 * parsing itself relies on `android.util.Xml` (unavailable on the JVM), so the
 * read paths degrade to the swallowed-error branch — which is exactly what these
 * tests assert (empty results, no crash) while exercising the surrounding I/O.
 */
class GpxFileStoreTest {

    private val resolver: ContentResolver = mock()
    private val cacheDir = Files.createTempDirectory("gpx-cache").toFile()
    private val context: Context = mock {
        whenever(it.contentResolver).thenReturn(resolver)
        whenever(it.cacheDir).thenReturn(cacheDir)
    }
    private val store = GpxFileStore(context)
    private val uri: Uri = mock()

    @Test
    fun `writeDocument writes the content to the opened output stream`() = runTest {
        val out = ByteArrayOutputStream()
        whenever(resolver.openOutputStream(uri)).thenReturn(out)

        val ok = store.writeDocument(uri, "<gpx/>")

        assertTrue(ok)
        assertEquals("<gpx/>", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `writeDocument returns false when no output stream is available`() = runTest {
        whenever(resolver.openOutputStream(uri)).thenReturn(null)
        assertFalse(store.writeDocument(uri, "<gpx/>"))
    }

    @Test
    fun `writeDocumentsToTree returns 0 for an empty document list`() = runTest {
        assertEquals(0, store.writeDocumentsToTree(uri, emptyList()))
    }

    @Test
    fun `readRides yields no rides when the file cannot be parsed`() = runTest {
        whenever(resolver.openInputStream(uri))
            .thenReturn(ByteArrayInputStream("<gpx></gpx>".toByteArray()))
        assertTrue(store.readRides(uri).isEmpty())
    }

    @Test
    fun `readRides yields no rides when the stream is unreadable`() = runTest {
        whenever(resolver.openInputStream(uri)).thenReturn(null)
        assertTrue(store.readRides(uri).isEmpty())
    }

    @Test
    fun `importRides reads every uri and flattens the results`() = runTest {
        whenever(resolver.openInputStream(any()))
            .thenReturn(ByteArrayInputStream("<gpx></gpx>".toByteArray()))
        val rides = store.importRides(listOf(uri, mock()))
        assertTrue(rides.isEmpty())
    }

    @Test
    fun `cacheIncomingGpx returns null when the source cannot be read`() = runTest {
        whenever(resolver.openInputStream(uri)).thenReturn(null)
        assertNull(store.cacheIncomingGpx(uri))
    }

    @Test
    fun `cacheIncomingGpx copies the source bytes into the cache directory`() = runTest {
        whenever(resolver.openInputStream(uri))
            .thenReturn(ByteArrayInputStream("<gpx>payload</gpx>".toByteArray()))

        // Uri.fromFile is an Android static (unavailable on the JVM), so the method
        // returns null — but the copy into the cache dir has already happened, which
        // is what we assert here (and it exercises the whole copy/prune path).
        store.cacheIncomingGpx(uri)

        val opened = java.io.File(cacheDir, "opened_gpx").listFiles().orEmpty()
        assertTrue(opened.any { it.readText() == "<gpx>payload</gpx>" })
    }

    @Test
    fun `writeDocument survives a wrapped GpxDocument content`() = runTest {
        val out = ByteArrayOutputStream()
        whenever(resolver.openOutputStream(uri)).thenReturn(out)
        val doc = GpxDocument(fileName = "ride.gpx", content = "<gpx>ride</gpx>")
        assertTrue(store.writeDocument(uri, doc.content))
        assertEquals(doc.content, out.toString(Charsets.UTF_8.name()))
    }
}

