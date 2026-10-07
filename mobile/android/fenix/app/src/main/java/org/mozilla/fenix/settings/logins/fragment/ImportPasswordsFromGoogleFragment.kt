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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.passwords.file.PasswordsFileImporter
import mozilla.components.feature.password.importer.PasswordsImporterResult
import mozilla.components.lib.passwords.file.csvImporter
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.content.res.readJSONObject
import org.mozilla.fenix.SecureFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.nimbus.FxNimbus
import org.mozilla.fenix.settings.logins.ImportPasswordsDialogFragment
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.webflow.PrivateDownloadFeature
import org.mozilla.fenix.webflow.WebFlowContent
import org.mozilla.fenix.webflow.WebFlowRepository
import org.mozilla.fenix.webflow.WebFlowFeature
import org.json.JSONObject
import java.io.File

/**
 * The screen that imports passwords from the user's Google account by
 * running the Google Password Manager export in a hidden tab.
 */
class ImportPasswordsFromGoogleFragment : SecureFragment() {
    private var runner: WebFlowFeature? = null

    // Null until the journey is loaded and the runner's tab is created.
    private var tabId by mutableStateOf<String?>(null)
    private val logger = Logger("ImportPasswordsFromGoogleFragment")
    private var importStarted = false
    private val exportDirectory by lazy { File(requireContext().noBackupFilesDir, EXPORT_DIRECTORY) }
    private val exportFile by lazy { File(exportDirectory, EXPORT_FILE) }
    private val downloadFeature = ViewBoundFeatureWrapper<PrivateDownloadFeature>()

    private val webFlowJourneys by lazy {
        WebFlowRepository(
            client = requireComponents.remoteSettingsService.value.remoteSettingsService.makeClient(
                WebFlowRepository.COLLECTION_NAME,
            ),
            readDump = { requireContext().assets.readJSONObject(WebFlowRepository.DUMP_ASSET) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // An export left behind by an earlier attempt, for example when the
        // process was killed before it was deleted, must not outlive it.
        deleteExport(exportDirectory)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        val tabId = tabId ?: return@content
        FirefoxTheme {
            WebFlowContent(tabId = tabId)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val journeys = webFlowJourneys
        viewLifecycleOwner.lifecycleScope.launch {
            val journey = withContext(Dispatchers.IO) {
                journeys.getFlowJSON(WebFlowRepository.GOOGLE_PASSWORDS_IMPORT)
            }
            val launchUrl = journey?.optString(LAUNCH_URL).orEmpty()
            // The pattern comes from Remote Settings; a broken value must fail
            // the import, not crash the app.
            val fileNamePattern = journey?.optString(DOWNLOAD_FILE_PATTERN)
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { Regex(it) }.getOrNull() }
            if (journey == null || launchUrl.isEmpty() || fileNamePattern == null) {
                logger.error("No usable ${WebFlowRepository.GOOGLE_PASSWORDS_IMPORT} journey")
                finishWith(PasswordsImporterResult.Failure)
                return@launch
            }
            val runner = runner ?: createRunner(launchUrl, journey)
            setUpDownload(view, runner, fileNamePattern)
            runner.start()
        }
    }

    private fun createRunner(launchUrl: String, journey: JSONObject): WebFlowFeature {
        val feature = FxNimbus.features.googlePasswordsImport
        return WebFlowFeature(
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

    private fun setUpDownload(view: View, runner: WebFlowFeature, fileNamePattern: Regex) {
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
        runner?.release()
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
        private const val LAUNCH_URL = "launch-url"
        private const val DOWNLOAD_FILE_PATTERN = "download-file-pattern"
    }
}
