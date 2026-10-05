/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Response
import mozilla.components.feature.downloads.DownloadsUseCases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.coroutines.CoroutineContext

@RunWith(AndroidJUnit4::class)
class PrivateDownloadFeatureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val tabId = "hidden"
    private val pattern = Regex("^Google Passwords.*[.]csv$")

    private val downloaded = mutableListOf<File>()
    private val failures = mutableListOf<Throwable>()

    @Test
    fun `GIVEN a matching request WHEN observed THEN its body is written to the destination and the request is claimed`() =
        runTest(testDispatcher) {
            val store = storeWith(download("Google Passwords.csv", body = "name,url,username,password"))
            val destination = tempFolder.root.resolve("private/export.csv")

            feature(store, destination).start()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(destination), downloaded)
            assertEquals("name,url,username,password", destination.readText())
            assertNull(store.state.findTab(tabId)?.content?.download)
        }

    @Test
    fun `GIVEN a request that does not match WHEN observed THEN it is cancelled and nothing is written`() =
        runTest(testDispatcher) {
            val store = storeWith(download("something-else.zip"))
            val destination = tempFolder.root.resolve("export.csv")

            feature(store, destination).start()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(downloaded.isEmpty())
            assertFalse(destination.exists())
            assertNull(store.state.findTab(tabId)?.content?.download)
        }

    @Test
    fun `GIVEN a matching request without a response WHEN observed THEN the failure is reported and no file is left`() =
        runTest(testDispatcher) {
            val store = storeWith(download("Google Passwords.csv", body = null))
            val destination = tempFolder.root.resolve("export.csv")

            feature(store, destination).start()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(downloaded.isEmpty())
            assertEquals(1, failures.size)
            assertFalse(destination.exists())
        }

    @Test
    fun `GIVEN a copy in progress WHEN the feature stops THEN the result is delivered on the next start`() =
        runTest(testDispatcher) {
            val store = storeWith(download("Google Passwords.csv", body = "data"))
            val destination = tempFolder.root.resolve("export.csv")
            val ioDispatcher = ManualDispatcher()
            val feature = feature(store, destination, ioDispatcher)

            feature.start()
            testDispatcher.scheduler.advanceUntilIdle()
            feature.stop()
            ioDispatcher.runAll()
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(downloaded.isEmpty())

            feature.start()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(destination), downloaded)
            assertEquals("data", destination.readText())
        }

    private fun feature(
        store: BrowserStore,
        destination: File,
        ioDispatcher: CoroutineDispatcher = testDispatcher,
    ) = PrivateDownloadFeature(
        store = store,
        downloadUseCases = DownloadsUseCases(store, mockk(relaxed = true)),
        tabId = tabId,
        fileNamePattern = pattern,
        destination = destination,
        onDownloaded = { downloaded += it },
        onFailed = { failures += it },
        mainDispatcher = testDispatcher,
        ioDispatcher = ioDispatcher,
    )

    private fun storeWith(download: DownloadState): BrowserStore {
        val tab = createTab("https://passwords.google.com/options", id = tabId)
        return BrowserStore(
            BrowserState(tabs = listOf(tab.copy(content = tab.content.copy(download = download)))),
        )
    }

    private fun download(fileName: String, body: String? = "data") = DownloadState(
        url = "blob:null/1234",
        fileName = fileName,
        directoryPath = tempFolder.root.path,
        sessionId = tabId,
        response = body?.let {
            Response(
                url = "blob:null/1234",
                status = 200,
                headers = MutableHeaders(),
                body = Response.Body(ByteArrayInputStream(it.toByteArray())),
            )
        },
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
}
