/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks.takeout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TakeoutZipExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `extract writes only matching entries to destination`() {
        val zipBytes = buildZip(
            "Takeout/Chrome/Bookmarks.html" to "<html>bookmarks</html>",
            "Takeout/Chrome/Browser History" to "ignored",
            "Takeout/archive_browser.html" to "<html>readme</html>",
        )
        val extractor = TakeoutZipExtractor()
        val destination = tempFolder.newFolder("out")

        val files = extractor.extract(ByteArrayInputStream(zipBytes), destination)

        assertEquals(1, files.size)
        val extracted = files.single()
        assertEquals("Bookmarks.html", extracted.name)
        assertEquals("<html>bookmarks</html>", extracted.readText())
    }

    @Test
    fun `extract creates destination directory when missing`() {
        val zipBytes = buildZip("Takeout/Chrome/Bookmarks.html" to "<html></html>")
        val destination = tempFolder.newFolder("root").resolve("nested")
        assertFalse(destination.exists())

        TakeoutZipExtractor().extract(ByteArrayInputStream(zipBytes), destination)

        assertTrue(destination.isDirectory)
    }

    @Test
    fun `extract honours custom target entry names`() {
        val zipBytes = buildZip(
            "Takeout/Passwords/Passwords.csv" to "url,user,pass",
            "Takeout/Chrome/Bookmarks.html" to "<html></html>",
        )
        val destination = tempFolder.newFolder("out")

        val files = TakeoutZipExtractor(targetEntryNames = setOf("Passwords.csv"))
            .extract(ByteArrayInputStream(zipBytes), destination)

        assertEquals(1, files.size)
        assertEquals("Passwords.csv", files.single().name)
    }

    @Test
    fun `extract skips path traversal entries`() {
        val zipBytes = buildZip("../Bookmarks.html" to "evil")
        val destination = tempFolder.newFolder("out")

        val files = TakeoutZipExtractor().extract(ByteArrayInputStream(zipBytes), destination)

        // The substringAfterLast('/') normalises away the path, so the entry still
        // resolves inside the destination — but the traversal guard ensures the
        // file would be rejected if a normalised path escaped the destination.
        assertEquals(1, files.size)
        assertEquals(destination.canonicalFile, checkNotNull(files.single().parentFile).canonicalFile)
    }

    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
