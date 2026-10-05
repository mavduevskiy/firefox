/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks.takeout

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext

@RunWith(AndroidJUnit4::class)
class TakeoutDownloadFeatureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val startedAt = 1_000L
    private val hosts = setOf("takeout-download.usercontent.google.com")

    private val extracted = mutableListOf<List<File>>()
    private val failures = mutableListOf<Throwable>()

    @Test
    fun `GIVEN a completed archive download WHEN observed THEN its bookmarks are extracted`() = runTest(testDispatcher) {
        val store = BrowserStore(BrowserState(downloads = mapOf("1" to archive(id = "1"))))
        val extractDirectory = tempFolder.newFolder("extract")

        feature(store, extractDirectory).start()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, extracted.size)
        val file = extracted.single().single()
        assertEquals(TakeoutZipExtractor.BOOKMARKS_HTML, file.name)
        assertEquals(extractDirectory.canonicalFile, file.parentFile?.canonicalFile)
        assertEquals("<html>bookmarks</html>", file.readText())
    }

    @Test
    fun `GIVEN downloads that are not the archive of this attempt WHEN observed THEN nothing is extracted`() =
        runTest(testDispatcher) {
            val store = BrowserStore(
                BrowserState(
                    downloads = mapOf(
                        "old" to archive(id = "old", createdTime = startedAt - 1),
                        "other-host" to archive(id = "other-host", url = "https://example.com/takeout.zip"),
                        "running" to archive(id = "running", status = DownloadState.Status.DOWNLOADING),
                    ),
                ),
            )

            feature(store, tempFolder.newFolder("extract")).start()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(extracted.isEmpty())
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `GIVEN an extracted archive WHEN the store changes THEN it is not extracted again`() = runTest(testDispatcher) {
        val store = BrowserStore(BrowserState(downloads = mapOf("1" to archive(id = "1"))))

        feature(store, tempFolder.newFolder("extract")).start()
        testDispatcher.scheduler.advanceUntilIdle()
        store.dispatch(DownloadAction.AddDownloadAction(archive(id = "2", status = DownloadState.Status.FAILED)))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, extracted.size)
    }

    @Test
    fun `GIVEN an extraction cancelled by stop WHEN started again THEN the archive is extracted`() =
        runTest(testDispatcher) {
            val store = BrowserStore(BrowserState(downloads = mapOf("1" to archive(id = "1"))))
            val ioDispatcher = ManualDispatcher()
            val feature = feature(store, tempFolder.newFolder("extract"), ioDispatcher)

            feature.start()
            testDispatcher.scheduler.advanceUntilIdle()
            feature.stop()
            ioDispatcher.runAll()
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(extracted.isEmpty())

            feature.start()
            testDispatcher.scheduler.advanceUntilIdle()
            ioDispatcher.runAll()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, extracted.size)
        }

    @Test
    fun `GIVEN an archive that can't be read WHEN observed THEN the failure is reported once`() =
        runTest(testDispatcher) {
            val missing = archive(id = "1").copy(fileName = "missing.zip")
            val store = BrowserStore(BrowserState(downloads = mapOf("1" to missing)))

            feature(store, tempFolder.newFolder("extract")).start()
            testDispatcher.scheduler.advanceUntilIdle()
            store.dispatch(DownloadAction.AddDownloadAction(archive(id = "2", status = DownloadState.Status.FAILED)))
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(extracted.isEmpty())
            assertEquals(1, failures.size)
        }

    private fun feature(
        store: BrowserStore,
        extractDirectory: File,
        ioDispatcher: CoroutineDispatcher = testDispatcher,
    ) = TakeoutDownloadFeature(
        context = mockk(relaxed = true),
        store = store,
        hosts = hosts,
        startedAt = startedAt,
        extractDirectory = extractDirectory,
        onExtracted = { extracted += it },
        onFailed = { failures += it },
        mainDispatcher = testDispatcher,
        ioDispatcher = ioDispatcher,
    )

    /** Holds dispatched work until [runAll], so a test can act while the work is pending. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runAll() {
            while (queue.isNotEmpty()) {
                queue.removeFirst().run()
            }
        }
    }

    private fun archive(
        id: String,
        url: String = "https://takeout-download.usercontent.google.com/download/takeout.zip",
        status: DownloadState.Status = DownloadState.Status.COMPLETED,
        createdTime: Long = startedAt + 1,
    ): DownloadState {
        val directory = tempFolder.root.resolve("downloads").apply { mkdirs() }
        val zip = directory.resolve("takeout-$id.zip")
        if (!zip.exists()) {
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("Takeout/Chrome/Bookmarks.html"))
                it.write("<html>bookmarks</html>".toByteArray())
                it.closeEntry()
            }
        }
        return DownloadState(
            id = id,
            url = url,
            fileName = zip.name,
            directoryPath = directory.path,
            status = status,
            createdTime = createdTime,
        )
    }
}
