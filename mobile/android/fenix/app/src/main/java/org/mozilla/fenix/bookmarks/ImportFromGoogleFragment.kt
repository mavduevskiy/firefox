/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.helper.Target
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.compose.engine.WebContent
import mozilla.components.concept.bookmarks.file.BookmarksImporterError
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.org.json.toList
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.mozilla.fenix.bookmarks.importer.FenixBookmarkImporterError
import org.mozilla.fenix.bookmarks.importer.FenixImporterEvent
import org.mozilla.fenix.bookmarks.importer.fenixHtmlBookmarksImporter
import org.mozilla.fenix.bookmarks.importer.toFenixError
import org.mozilla.fenix.bookmarks.takeout.TakeoutDownloadFeature
import org.mozilla.fenix.bookmarks.takeout.TakeoutZipExtractor
import org.mozilla.fenix.components.components
import org.mozilla.fenix.downloads.DownloadService
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.nimbus.FxNimbus
import org.mozilla.fenix.settings.downloads.DownloadLocationManager
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.webflow.WebFlowJourneyRepository
import org.mozilla.fenix.webflow.WebFlowRunner
import org.json.JSONObject
import java.io.File

private const val GOOGLE_AUTH_URL = "https://accounts.google.com/"

/** The screen to guide the user through importing data from Google. */
class ImportFromGoogleFragment : Fragment() {
    private var runner: WebFlowRunner? = null

    // Null until the journey is loaded and the runner's tab is created.
    private var tabId by mutableStateOf<String?>(null)
    private val logger = Logger("ImportFromGoogleFragment")
    private var importStarted = false
    private val extractDirectory by lazy { File(requireContext().cacheDir, EXTRACT_DIRECTORY) }
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val takeoutDownloadFeature = ViewBoundFeatureWrapper<TakeoutDownloadFeature>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        val tabId = tabId ?: return@content
        val authRequired =
            components.core.store
                .observeAsComposableState {
                    it.findCustomTab(tabId)?.content?.url?.contains(GOOGLE_AUTH_URL)
                }
                .value

//        if (authRequired == true) {
            FirefoxTheme {
                WebContent(
                    components.core.engine,
                    components.core.store,
                    Target.CustomTab(tabId),
                )
            }
//        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val journeys = requireComponents.webFlowJourneys
        viewLifecycleOwner.lifecycleScope.launch {
            val journey = withContext(Dispatchers.IO) {
                journeys.journey(WebFlowJourneyRepository.GOOGLE_TAKEOUT_BOOKMARKS)
            }
            val launchUrl = journey?.optString(LAUNCH_URL).orEmpty()
            if (journey == null || launchUrl.isEmpty()) {
                logger.error("No usable ${WebFlowJourneyRepository.GOOGLE_TAKEOUT_BOOKMARKS} journey")
                finishWith(FenixImporterEvent.Failure(FenixBookmarkImporterError.UNKNOWN_ERROR))
                return@launch
            }
            val runner = runner ?: createRunner(launchUrl, journey)
            val downloadHosts = journey.optJSONArray(DOWNLOAD_HOSTS)?.toList<String>().orEmpty().toSet()
            setUpDownloads(view, runner, downloadHosts)
            runner.start()
        }
    }

    private fun createRunner(launchUrl: String, journey: JSONObject): WebFlowRunner {
        val feature = FxNimbus.features.googleTakeoutImport
        return WebFlowRunner(
            engine = requireComponents.core.engine,
            store = requireComponents.core.store,
            launchUrl = launchUrl,
            config = { journey },
            onConfigured = { feature.recordExposure() },
        ).also {
            it.createTab()
            runner = it
            tabId = it.tabId
        }
    }

    /**
     * The browser's DownloadsFeature only serves the tab it renders, so the
     * hidden tab needs its own to start the archive download; a second
     * feature extracts the archive once the download completes.
     */
    private fun setUpDownloads(view: View, runner: WebFlowRunner, downloadHosts: Set<String>) {
        val components = requireComponents
        val context = requireContext().applicationContext
        val downloadFileUtils = DefaultDownloadFileUtils(
            context = context,
            downloadLocation = {
                DownloadLocationManager(components.settings, context.contentResolver).defaultLocation
            },
        )
        downloadsFeature.set(
            feature = DownloadsFeature(
                applicationContext = context,
                store = components.core.store,
                useCases = components.useCases.downloadUseCases,
                downloadFileUtils = downloadFileUtils,
                tabId = runner.tabId,
                downloadManager = FetchDownloadManager(
                    context,
                    components.core.store,
                    DownloadService::class,
                    notificationsDelegate = components.notificationsDelegate,
                ),
                onDownloadStartedListener = { downloadId -> logger.info("Download started: $downloadId") },
                onNeedToRequestPermissions = { permissions ->
                    @Suppress("DEPRECATION")
                    requestPermissions(permissions, REQUEST_CODE_DOWNLOAD_PERMISSIONS)
                },
            ),
            owner = viewLifecycleOwner,
            view = view,
        )
        takeoutDownloadFeature.set(
            feature = TakeoutDownloadFeature(
                context = context,
                store = components.core.store,
                hosts = downloadHosts,
                startedAt = runner.startedAt,
                extractDirectory = extractDirectory,
                onExtracted = ::importBookmarks,
                onFailed = { throwable ->
                    logger.error("Takeout extraction failed", throwable)
                    finishWith(FenixImporterEvent.Failure(FenixBookmarkImporterError.FILE_READ_ERROR))
                },
            ),
            owner = viewLifecycleOwner,
            view = view,
        )
    }

    /**
     * Imports the extracted bookmarks file with the same importer as the
     * import from file, then reports the result to the bookmarks screen.
     */
    private fun importBookmarks(files: List<File>) {
        // A second archive in the same attempt must not import the same
        // bookmarks again.
        if (importStarted) {
            return
        }
        importStarted = true

        val bookmarksFile = files.firstOrNull { it.name == TakeoutZipExtractor.BOOKMARKS_HTML }
        if (bookmarksFile == null) {
            logger.error("No ${TakeoutZipExtractor.BOOKMARKS_HTML} in the export: $files")
            finishWith(FenixImporterEvent.Failure(FenixBookmarkImporterError.FILE_READ_ERROR))
            return
        }
        val importer = fenixHtmlBookmarksImporter(requireContext(), requireComponents.core.bookmarksStorage)
        viewLifecycleOwner.lifecycleScope.launch {
            val event = importer.importBookmarksFromUri(Uri.fromFile(bookmarksFile)).fold(
                onSuccess = { FenixImporterEvent.Success(importCount = it.count) },
                onFailure = { throwable ->
                    logger.error("Bookmarks import failed", throwable)
                    FenixImporterEvent.Failure(
                        (throwable as? BookmarksImporterError).toFenixError()
                            ?: FenixBookmarkImporterError.UNKNOWN_ERROR,
                    )
                },
            )
            logger.info("Bookmarks import finished: $event")
            finishWith(event)
        }
    }

    private fun finishWith(event: FenixImporterEvent) {
        if (!isAdded) {
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // The extracted files hold the user's bookmarks; don't keep them.
            withContext(Dispatchers.IO) { extractDirectory.deleteRecursively() }
            parentFragmentManager.setFragmentResult(REQUEST_KEY, ImportBookmarksDialogFragment.encodeResult(event))
            findNavController().popBackStack()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (requestCode == REQUEST_CODE_DOWNLOAD_PERMISSIONS) {
            downloadsFeature.get()?.onPermissionsResult(permissions, grantResults)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runner?.release()
    }

    companion object {
        /** Fragment result key for the import result, encoded by [ImportBookmarksDialogFragment.encodeResult]. */
        const val REQUEST_KEY = "import_from_google_request"
        private const val REQUEST_CODE_DOWNLOAD_PERMISSIONS = 1
        private const val EXTRACT_DIRECTORY = "takeout-import"
        private const val LAUNCH_URL = "launch-url"
        private const val DOWNLOAD_HOSTS = "download-hosts"
    }
}
