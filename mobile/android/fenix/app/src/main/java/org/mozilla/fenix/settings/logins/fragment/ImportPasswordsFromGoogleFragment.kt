/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.logins.fragment

import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.helper.Target
import mozilla.components.compose.engine.WebContent
import mozilla.components.concept.passwords.file.PasswordsFileImporter
import mozilla.components.feature.password.importer.PasswordsImporterResult
import mozilla.components.lib.passwords.file.csvImporter
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.SecureFragment
import org.mozilla.fenix.components.components
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.nimbus.FxNimbus
import org.mozilla.fenix.settings.logins.ImportPasswordsDialogFragment
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.webflow.PrivateDownloadFeature
import org.mozilla.fenix.webflow.WebFlowRunner
import java.io.File

/**
 * The screen that imports passwords from the user's Google account by
 * running the Google Password Manager export in a hidden tab.
 */
class ImportPasswordsFromGoogleFragment : SecureFragment() {
    private lateinit var runner: WebFlowRunner
    private val logger = Logger("ImportPasswordsFromGoogleFragment")
    private var importStarted = false
    private val exportDirectory by lazy { File(requireContext().noBackupFilesDir, EXPORT_DIRECTORY) }
    private val exportFile by lazy { File(exportDirectory, EXPORT_FILE) }
    private val downloadFeature = ViewBoundFeatureWrapper<PrivateDownloadFeature>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // An export left behind by an earlier attempt, for example when the
        // process was killed before it was deleted, must not outlive it.
        deleteExport(exportDirectory)

        // The journey the extension runs comes from Nimbus.
        val feature = FxNimbus.features.googlePasswordsImport
        runner = WebFlowRunner(
            engine = requireComponents.core.engine,
            store = requireComponents.core.store,
            tabsUseCases = requireComponents.useCases.tabsUseCases,
            launchUrl = feature.value().launchUrl,
            config = { feature.value().toJSONObject() },
            onConfigured = { feature.recordExposure() },
        )
        runner.createTab()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        FirefoxTheme {
            WebContent(
                components.core.engine,
                components.core.store,
                Target.Tab(runner.tabId),
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The pattern comes from Nimbus; a broken value must fail the import,
        // not crash the app.
        val fileNamePattern = runCatching {
            Regex(FxNimbus.features.googlePasswordsImport.value().downloadFilePattern)
        }.getOrElse { throwable ->
            logger.error("Invalid download file pattern", throwable)
            finishWith(PasswordsImporterResult.Failure)
            return
        }
        downloadFeature.set(
            feature = PrivateDownloadFeature(
                store = requireComponents.core.store,
                downloadUseCases = requireComponents.useCases.downloadUseCases,
                tabId = runner.tabId,
                fileNamePattern = fileNamePattern,
                destination = exportFile,
                onDownloaded = ::importPasswords,
                onFailed = { finishWith(PasswordsImporterResult.Failure) },
            ),
            owner = viewLifecycleOwner,
            view = view,
        )
        runner.start(lifecycleScope)
    }

    /**
     * Imports the exported CSV with the same importer as the import from
     * file, then reports the result to the saved logins screen.
     */
    private fun importPasswords(file: File) {
        // A second export in the same attempt must not import again.
        if (importStarted) {
            return
        }
        importStarted = true

        val importer = PasswordsFileImporter.csvImporter(
            context = requireContext(),
            loginsStorage = requireComponents.core.passwordsStorage,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val result = importer.importPasswordsFromUri(Uri.fromFile(file)).fold(
                onSuccess = { PasswordsImporterResult.Success(importCount = it.count) },
                onFailure = { throwable ->
                    logger.error("Passwords import failed", throwable)
                    PasswordsImporterResult.Failure
                },
            )
            logger.info("Passwords import finished: $result")
            finishWith(result)
        }
    }

    private fun finishWith(result: PasswordsImporterResult) {
        if (!isAdded) {
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // The export holds the user's passwords in plain text.
            withContext(Dispatchers.IO) { exportDirectory.deleteRecursively() }
            parentFragmentManager.setFragmentResult(REQUEST_KEY, ImportPasswordsDialogFragment.encodeResult(result))
            findNavController().popBackStack()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runner.release()
        // Also covers leaving the screen before the import finished.
        deleteExport(exportDirectory)
    }

    private fun deleteExport(directory: File) {
        requireComponents.strictMode.allowViolation(StrictMode::allowThreadDiskWrites) {
            directory.deleteRecursively()
        }
    }

    companion object {
        /** Fragment result key for the import result, encoded by [ImportPasswordsDialogFragment.encodeResult]. */
        const val REQUEST_KEY = "import_passwords_from_google_request"
        private const val EXPORT_DIRECTORY = "google-passwords-import"
        private const val EXPORT_FILE = "export.csv"
    }
}
