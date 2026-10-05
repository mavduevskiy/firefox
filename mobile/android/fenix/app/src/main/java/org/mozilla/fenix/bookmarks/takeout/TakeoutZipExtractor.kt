/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks.takeout

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Streams a Google Takeout ZIP and writes selected entries out to a destination
 * directory.
 *
 * @param targetEntryNames Entry file names (without directory portion) that
 * should be extracted. Defaults to the bookmarks export filename Chrome ships
 * inside Takeout archives.
 */
class TakeoutZipExtractor(
    private val targetEntryNames: Set<String> = DEFAULT_TARGET_ENTRIES,
) {

    /**
     * Reads the ZIP from [input], writes any matching entries into [destinationDir]
     * (creating it if necessary), and returns the resulting files. The input
     * stream is consumed but not closed by this method.
     */
    fun extract(input: InputStream, destinationDir: File): List<File> {
        if (!destinationDir.exists()) {
            destinationDir.mkdirs()
        }
        val canonicalDestination = destinationDir.canonicalFile
        val extracted = mutableListOf<File>()

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                writeEntryIfTargeted(zip, entry, canonicalDestination)?.let { extracted += it }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return extracted
    }

    private fun writeEntryIfTargeted(
        zip: ZipInputStream,
        entry: java.util.zip.ZipEntry,
        canonicalDestination: File,
    ): File? {
        if (entry.isDirectory) return null
        val entryName = entry.name.substringAfterLast('/')
        if (entryName !in targetEntryNames) return null

        val output = File(canonicalDestination, entryName)
        // Guard against path-traversal entries (e.g. "../../etc/passwd").
        if (output.canonicalFile.parentFile != canonicalDestination) return null

        output.outputStream().use { out -> zip.copyTo(out) }
        return output
    }

    companion object {
        /**
         * The default file inside a Takeout export that holds the bookmark
         * tree as Netscape Bookmark Format HTML.
         */
        const val BOOKMARKS_HTML = "Bookmarks.html"

        val DEFAULT_TARGET_ENTRIES: Set<String> = setOf(BOOKMARKS_HTML)
    }
}
