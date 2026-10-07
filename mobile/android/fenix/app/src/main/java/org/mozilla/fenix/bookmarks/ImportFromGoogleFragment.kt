/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.compose.base.button.IconButton
import mozilla.components.concept.bookmarks.file.BookmarksImporterError
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.content.res.readJSONObject
import mozilla.components.support.ktx.android.org.json.toList
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.mozilla.fenix.bookmarks.importer.FenixBookmarkImporterError
import org.mozilla.fenix.bookmarks.importer.FenixImporterEvent
import org.mozilla.fenix.bookmarks.importer.fenixHtmlBookmarksImporter
import org.mozilla.fenix.bookmarks.importer.toFenixError
import org.mozilla.fenix.bookmarks.takeout.ZipExtractionFeature
import org.mozilla.fenix.bookmarks.takeout.TakeoutZipExtractor
import org.mozilla.fenix.components.components
import org.mozilla.fenix.downloads.DownloadService
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.nimbus.FxNimbus
import org.mozilla.fenix.settings.downloads.DownloadLocationManager
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.webflow.WebFlowRepository
import org.mozilla.fenix.webflow.WebFlowContent
import org.mozilla.fenix.webflow.WebFlowFeature
import org.json.JSONObject
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.bookmarks.BookmarksTestTag.BOOKMARK_TOOLBAR
import java.io.File

class ImportFromGoogleFragment : Fragment() {
    private var webFlowFeature by mutableStateOf<WebFlowFeature?>(null)
    private var downloadHosts: List<String>? = null

    private val logger = Logger("ImportFromGoogleFragment")
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val zipExtractionFeature = ViewBoundFeatureWrapper<ZipExtractionFeature>()
    private val extractDirectory by lazy { File(requireContext().cacheDir, EXTRACT_DIRECTORY) }

    private val webFlowRepository by lazy {
        WebFlowRepository(
            client = requireComponents.remoteSettingsService.value.remoteSettingsService.makeClient(
                WebFlowRepository.COLLECTION_NAME,
            ),
            readDump = { requireContext().assets.readJSONObject(WebFlowRepository.DUMP_ASSET) },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        val feature = webFlowFeature
        if (feature != null) {
            val tabId = feature.tabId
            FirefoxTheme {
                Scaffold(
                    modifier = Modifier,
                    topBar = {
                        TopAppBar(
                        modifier =
                            Modifier.semantics {
                                testTagsAsResourceId = true
                                testTag = BOOKMARK_TOOLBAR
                            },
                        title = {
                            Text(
                                style = FirefoxTheme.typography.headline5,
                                text = "Import bookmarks",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    findNavController().popBackStack()
                                },
                                contentDescription = null,
                            ) {
                                Icon(
                                    painter = painterResource(iconsR.drawable.mozac_ic_cross_24),
                                    contentDescription = null,
                                )
                            }
                        },
                        windowInsets =
                            WindowInsets(
                                top = 0.dp,
                                bottom = 0.dp,
                            ),
                    )
                    },
                ) { paddingValues ->
                    WebFlowContent(tabId = tabId, modifier = Modifier.padding(paddingValues))
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            val bookmarksFlow = withContext(Dispatchers.IO) {
                webFlowRepository.getFlowJSON(WebFlowRepository.GOOGLE_TAKEOUT_BOOKMARKS)
            }
            val flowLaunchUrl = bookmarksFlow?.optString(LAUNCH_URL)
            downloadHosts = bookmarksFlow?.optJSONArray(DOWNLOAD_HOSTS)?.toList()

            if (bookmarksFlow == null || flowLaunchUrl.isNullOrEmpty() || downloadHosts.isNullOrEmpty()) {
                logger.error("Invalid ${WebFlowRepository.GOOGLE_TAKEOUT_BOOKMARKS} flow")
                finish(FenixImporterEvent.Failure(FenixBookmarkImporterError.UNKNOWN_ERROR))
                // we probably want to close the fragment in that case and apologize to the user.
                return@launch
            }

            with(createWebFlowFeature(flowLaunchUrl, bookmarksFlow)) {
                // this smells - we need a tab before the feature starts, should creating the tab be a responsibility
                // of the fragment, not the feature?
                createTab()
                start()
                downloadHosts?.let {
                    setUpDownloadFeatures(view, this, it)
                }
                webFlowFeature = this
            }
        }
    }

    private fun createWebFlowFeature(launchUrl: String, journey: JSONObject): WebFlowFeature {
        return WebFlowFeature(
            engine = requireComponents.core.engine,
            store = requireComponents.core.store,
            launchUrl = launchUrl,
            config = { journey },
            onConfigured = { FxNimbus.features.googleTakeoutImport.recordExposure() },
        )
    }

    /**
     * The browser's DownloadsFeature only serves the tab it renders, so the
     * hidden tab needs its own to start the archive download; a second
     * feature extracts the archive once the download completes.
     */
    private fun setUpDownloadFeatures(
        view: View,
        webFlowFeature: WebFlowFeature,
        downloadHosts: List<String>,
    ) {
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
                tabId = webFlowFeature.tabId,
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

        zipExtractionFeature.set(
            feature = ZipExtractionFeature(
                context = context,
                store = components.core.store,
                hosts = downloadHosts,
                startedAt = webFlowFeature.startedAt,
                extractDirectory = extractDirectory,
                onExtracted = ::importBookmarks,
                onFailed = { throwable ->
                    logger.error("Takeout extraction failed", throwable)
                    finish(FenixImporterEvent.Failure(FenixBookmarkImporterError.FILE_READ_ERROR))
                },
            ),
            owner = viewLifecycleOwner,
            view = view,
        )
    }

    private fun importBookmarks(files: List<File>) {
        val bookmarksFile = files.firstOrNull { it.name == TakeoutZipExtractor.BOOKMARKS_HTML }
        if (bookmarksFile == null) {
            logger.error("No ${TakeoutZipExtractor.BOOKMARKS_HTML} in the export: $files")
            finish(FenixImporterEvent.Failure(FenixBookmarkImporterError.FILE_READ_ERROR))
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
            finish(event)
        }
    }

    private fun finish(event: FenixImporterEvent) {
        if (!isAdded) return

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
        webFlowFeature?.release()
        webFlowFeature = null
    }

    companion object {
        const val REQUEST_KEY = "import_from_google_request"
        private const val REQUEST_CODE_DOWNLOAD_PERMISSIONS = 1
        private const val EXTRACT_DIRECTORY = "takeout-import"
        private const val LAUNCH_URL = "launch-url"
        private const val DOWNLOAD_HOSTS = "download-hosts"
    }
}
