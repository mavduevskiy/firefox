/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks.takeout

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.log.logger.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Waits for the Takeout archive download of the current attempt to complete
 * and extracts the files the import needs from it.
 *
 * @param context Used to read the download through MediaStore when needed.
 * @param store Observed for download state changes.
 * @param hosts Hosts the archive is downloaded from.
 * @param startedAt Downloads created before this time belong to earlier
 * attempts (they are restored with the browser state) and are ignored.
 * @param extractDirectory Directory the files are extracted into. It is
 * emptied before each extraction; deleting it afterwards is up to the caller.
 * @param onExtracted Invoked on the main thread with the extracted files.
 * @param onFailed Invoked on the main thread when extraction fails.
 */
class TakeoutDownloadFeature(
    private val context: Context,
    private val store: BrowserStore,
    private val hosts: Set<String>,
    private val startedAt: Long,
    private val extractDirectory: File,
    private val onExtracted: (List<File>) -> Unit,
    private val onFailed: (Throwable) -> Unit,
    private val extractor: TakeoutZipExtractor = TakeoutZipExtractor(),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LifecycleAwareFeature {
    private val logger = Logger("TakeoutDownloadFeature")

    // Downloads whose extraction finished, successfully or not. A download
    // whose extraction was cancelled by stop() is not in here and is picked
    // up again on the next start().
    private val handledDownloads = mutableSetOf<String>()
    private val extractingDownloads = mutableSetOf<String>()
    private var scope: CoroutineScope? = null

    override fun start() {
        scope = store.flowScoped(dispatcher = mainDispatcher) { flow ->
            flow.collect { state ->
                state.downloads.values
                    .filter {
                        it.isCompletedArchive() &&
                            it.id !in handledDownloads &&
                            extractingDownloads.add(it.id)
                    }
                    .forEach { extract(it) }
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
    }

    private fun DownloadState.isCompletedArchive(): Boolean =
        status == DownloadState.Status.COMPLETED &&
            createdTime >= startedAt &&
            url.toUri().host?.lowercase() in hosts

    private fun extract(download: DownloadState) {
        val scope = scope ?: return
        logger.info("Extracting ${download.fileName}")
        scope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    runCatching {
                        extractDirectory.deleteRecursively()
                        openDownload(download).use { extractor.extract(it, extractDirectory) }
                    }.onFailure { if (it is CancellationException) throw it }
                }
                handledDownloads.add(download.id)
                result
                    .onSuccess { files ->
                        logger.info("Extracted ${files.map { it.absolutePath }}")
                        onExtracted(files)
                    }
                    .onFailure { throwable ->
                        logger.error("Failed to extract ${download.fileName}", throwable)
                        onFailed(throwable)
                    }
            } finally {
                extractingDownloads.remove(download.id)
            }
        }
    }

    /**
     * Files in the public Downloads directory are written through MediaStore
     * on API 29+ and can't be opened by path there.
     */
    private fun openDownload(download: DownloadState): InputStream {
        runCatching { File(download.filePath).inputStream() }.getOrNull()?.let { return it }

        val fileName = download.fileName
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || fileName == null) {
            throw FileNotFoundException(download.filePath)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val id = context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(fileName),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            ?: throw FileNotFoundException("$fileName not found in MediaStore")

        return context.contentResolver.openInputStream(ContentUris.withAppendedId(collection, id))
            ?: throw FileNotFoundException("$fileName could not be opened")
    }
}
