/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.state.selector.findTab
import mozilla.components.concept.bookmark.parser.BookmarksFileParser
import mozilla.components.concept.bookmarks.file.BookmarksFileImporter
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.takeout.importer.TakeoutImporterFeature
import mozilla.components.lib.bookmark.parser.jsoup.jsoupParser
import mozilla.components.lib.bookmarks.file.htmlImporter
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.mozilla.fenix.R
import org.mozilla.fenix.databinding.FragmentTakeoutImportBinding
import org.mozilla.fenix.downloads.DownloadService
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.settings.downloads.DownloadLocationManager
import java.io.File

/**
 * Proof-of-concept screen that drives the Google Takeout export flow via the
 * built-in WebExtension and emits the extracted Bookmarks.html path back to
 * the caller as a fragment result.
 */
class TakeoutImportFragment : Fragment(R.layout.fragment_takeout_import) {

    private var _binding: FragmentTakeoutImportBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val takeoutFeature = ViewBoundFeatureWrapper<TakeoutImporterFeature>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private var importTabId: String? = null
    private val logger = Logger("TakeoutImportFragment")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTakeoutImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val components = requireComponents
        binding.takeoutProgress.visibility = View.VISIBLE

        // Stale takeout tabs from previous sessions re-run the content script on
        // restore and their buffered failures would abort this attempt.
        components.core.store.state.tabs
            .filter { it.content.url.startsWith("https://takeout.google.com") }
            .forEach {
                logger.info("removing stale takeout tab: ${it.id} ${it.content.url}")
                components.useCases.tabsUseCases.removeTab(it.id)
            }

        logger.info("launching takeout flow, opening $TAKEOUT_URL")
        importTabId = components.useCases.tabsUseCases.addTab(
            url = TAKEOUT_URL,
            selectTab = false,
        )
        logger.info("takeout tab created: $importTabId")

        takeoutFeature.set(
            feature = TakeoutImporterFeature(
                context = requireContext(),
                engine = components.core.engine,
                store = components.core.store,
                onStep = { step -> logger.info("takeout step: $step") },
                onCompleted = ::onImportFinished,
            ),
            owner = viewLifecycleOwner,
            view = view,
        )

        // Downloads for the hidden takeout tab are not handled anywhere else:
        // BaseBrowserFragment's DownloadsFeature only serves the tab it renders.
        val downloadFileUtils = DefaultDownloadFileUtils(
            context = requireContext(),
            downloadLocation = {
                DownloadLocationManager(
                    components.settings,
                    requireContext().contentResolver,
                ).defaultLocation
            },
        )
        downloadsFeature.set(
            feature = DownloadsFeature(
                applicationContext = requireContext().applicationContext,
                store = components.core.store,
                useCases = components.useCases.downloadUseCases,
                downloadFileUtils = downloadFileUtils,
                tabId = importTabId,
                downloadManager = FetchDownloadManager(
                    requireContext().applicationContext,
                    components.core.store,
                    DownloadService::class,
                    notificationsDelegate = components.notificationsDelegate,
                ),
                onDownloadStartedListener = { downloadId ->
                    logger.info("download started: $downloadId")
                },
                onNeedToRequestPermissions = { permissions ->
                    logger.warn("download blocked on permissions: ${permissions.toList()}")
                    @Suppress("DEPRECATION")
                    requestPermissions(permissions, REQUEST_CODE_DOWNLOAD_PERMISSIONS)
                },
            ),
            owner = viewLifecycleOwner,
            view = view,
        )

        observeImportSession()
    }

    private fun observeImportSession() {
        val store = requireComponents.core.store
        val tabId = importTabId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            store.flow(viewLifecycleOwner)
                .mapNotNull { state -> state.findTab(tabId)?.engineState?.engineSession }
                .distinctUntilChanged()
                .collect { session ->
                    binding.takeoutEngineView.render(session)
                    binding.takeoutProgress.visibility = View.GONE
                }
        }
    }

    private fun onImportFinished(result: TakeoutImporterFeature.Result) {
        when (result) {
            is TakeoutImporterFeature.Result.Success -> {
                logger.info("takeout extraction finished: ${result.extractedFiles.map { it.absolutePath }}")
                val bookmarksFile = result.extractedFiles.firstOrNull {
                    it.name.endsWith(".html", ignoreCase = true)
                }
                if (bookmarksFile != null) {
                    importBookmarks(bookmarksFile)
                } else {
                    logger.error("no bookmarks html in extracted files")
                    finish("Takeout import: no Bookmarks.html found")
                }
            }
            is TakeoutImporterFeature.Result.Failure -> {
                logger.error("takeout flow failed: ${result.reason}")
                finish("Takeout import failed: ${result.reason}")
            }
        }
    }

    private fun importBookmarks(file: File) {
        logger.info("importing bookmarks from ${file.absolutePath}")
        val importer = BookmarksFileImporter.htmlImporter(
            context = requireContext(),
            parentGuid = BookmarkRoot.Mobile.id,
            parser = BookmarksFileParser.jsoupParser(
                rootFolderName = requireContext().getString(R.string.bookmark_import_destination_default_name),
            ),
            inserter = requireComponents.core.bookmarksStorage,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val result = importer.importBookmarksFromUri(Uri.fromFile(file))
            result.onSuccess { imported ->
                logger.info("bookmarks imported: count=${imported.count} guid=${imported.guid}")
                setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply { putString(KEY_FILE_PATH, file.absolutePath) },
                )
                finish("Imported ${imported.count} bookmarks")
            }.onFailure { throwable ->
                logger.error("bookmarks import failed", throwable)
                finish("Bookmarks import failed: ${throwable.message}")
            }
        }
    }

    private fun finish(message: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        importTabId?.let { requireComponents.useCases.tabsUseCases.removeTab(it) }
        importTabId = null
        _binding = null
        super.onDestroyView()
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

    companion object {
        const val REQUEST_KEY = "takeout_import_request"
        const val KEY_FILE_PATH = "file_path"
        private const val TAKEOUT_URL = "https://takeout.google.com/"
        private const val REQUEST_CODE_DOWNLOAD_PERMISSIONS = 1
    }
}
