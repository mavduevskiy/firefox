/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webflow

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.log.logger.Logger
import java.io.File
import java.io.IOException

/**
 * Handles the download requests of a hidden web flow tab without the
 * download manager: the response body of a matching request is written
 * straight into [destination], so the file never appears in the public
 * Downloads directory, the downloads list or a notification. Requests that
 * don't match are cancelled.
 *
 * Meant for small sensitive files, such as a passwords export, that are read
 * once and deleted.
 *
 * @param store Observed for the tab's download requests.
 * @param downloadUseCases Used to claim or cancel the requests.
 * @param tabId The hidden tab.
 * @param fileNamePattern Matched against the name of the downloaded file.
 * @param destination The file the matching download is written to.
 * @param onDownloaded Invoked on the main thread once [destination] is written.
 * @param onFailed Invoked on the main thread when the download can't be written.
 */
class PrivateDownloadFeature(
    private val store: BrowserStore,
    private val downloadUseCases: DownloadsUseCases,
    private val tabId: String,
    private val fileNamePattern: Regex,
    private val destination: File,
    private val onDownloaded: (File) -> Unit,
    private val onFailed: (Throwable) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LifecycleAwareFeature {
    private val logger = Logger("PrivateDownloadFeature")
    private var observerScope: CoroutineScope? = null

    // The request is claimed before its body is read, so a copy that has
    // started must not be cancelled by stop(): nothing could retry it.
    // Copies run here and their result waits for the next start().
    private val copyScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var pendingResult: Result<File>? = null

    override fun start() {
        observerScope = store.flowScoped(dispatcher = mainDispatcher) { flow ->
            flow.mapNotNull { it.findTab(tabId)?.content?.download }
                .distinctUntilChangedBy { it.id }
                .collect { handle(it) }
        }
        pendingResult?.let {
            pendingResult = null
            deliver(it)
        }
    }

    override fun stop() {
        observerScope?.cancel()
        observerScope = null
    }

    private fun handle(download: DownloadState) {
        val fileName = download.fileName
        if (fileName == null || !fileNamePattern.matches(fileName)) {
            logger.info("Ignoring download $fileName")
            downloadUseCases.cancelDownloadRequest(tabId, download.id)
            return
        }
        val response = download.response
        // Claim the request so nothing else handles it. Consuming does not
        // close the response, which is read below.
        downloadUseCases.consumeDownload(tabId, download.id)
        copyScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    if (response == null) {
                        throw IOException("The download of $fileName has no response")
                    }
                    response.use {
                        destination.parentFile?.mkdirs()
                        it.body.useStream { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    destination
                }.onFailure { destination.delete() }
            }
            if (observerScope != null) {
                deliver(result)
            } else {
                pendingResult = result
            }
        }
    }

    private fun deliver(result: Result<File>) {
        result
            .onSuccess {
                logger.info("Downloaded ${it.name}")
                onDownloaded(it)
            }
            .onFailure { throwable ->
                logger.error("Failed to write the download", throwable)
                onFailed(throwable)
            }
    }
}
