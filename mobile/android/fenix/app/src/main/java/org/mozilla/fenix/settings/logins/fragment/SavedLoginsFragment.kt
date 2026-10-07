/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.logins.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.getSystemService
import androidx.fragment.compose.content
import androidx.navigation.NavHostController
import androidx.navigation.fragment.findNavController
import mozilla.components.concept.engine.EngineSession
import mozilla.components.feature.password.importer.PasswordsImporterResult
import mozilla.components.lib.state.helpers.StoreProvider.Companion.fragmentStore
import org.mozilla.fenix.Config
import org.mozilla.fenix.R
import org.mozilla.fenix.SecureFragment
import org.mozilla.fenix.components.LogMiddleware
import org.mozilla.fenix.compose.snackbar.Snackbar
import org.mozilla.fenix.compose.snackbar.SnackbarState
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.hideToolbar
import org.mozilla.fenix.ext.openToBrowser
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.nimbus.FxNimbus
import org.mozilla.fenix.settings.logins.ImportPasswordsDialogFragment
import org.mozilla.fenix.settings.logins.ui.DefaultSavedLoginsStorage
import org.mozilla.fenix.settings.logins.ui.LoginsListAppeared
import org.mozilla.fenix.settings.logins.ui.LoginsMiddleware
import org.mozilla.fenix.settings.logins.ui.LoginsSortOrder
import org.mozilla.fenix.settings.logins.ui.LoginsState
import org.mozilla.fenix.settings.logins.ui.LoginsStore
import org.mozilla.fenix.settings.logins.ui.LoginsTelemetryMiddleware
import org.mozilla.fenix.settings.logins.ui.SavedLoginsScreen
import org.mozilla.fenix.theme.FirefoxTheme

/** Defines the fragment containing the saved logins. */
class SavedLoginsFragment : SecureFragment(), SystemInsetsPaddedFragment {

    private var loginsStore: LoginsStore? = null

    override fun onResume() {
        super.onResume()
        hideToolbar()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.setFragmentResultListener(
            ImportPasswordsDialogFragment.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            if (ImportPasswordsDialogFragment.decodeResult(bundle) is PasswordsImporterResult.Success) {
                loginsStore?.dispatch(LoginsListAppeared)
            }
        }
        // The Google import is a sibling destination, so its result arrives
        // through the parent fragment manager.
        parentFragmentManager.setFragmentResultListener(
            ImportPasswordsFromGoogleFragment.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val result = ImportPasswordsDialogFragment.decodeResult(bundle)
            if (result is PasswordsImporterResult.Success) {
                loginsStore?.dispatch(LoginsListAppeared)
            }
            showImportFromGoogleResult(result)
        }
    }

    private fun showImportFromGoogleResult(result: PasswordsImporterResult?) {
        val message = when (result) {
            is PasswordsImporterResult.Success -> resources.getQuantityString(
                R.plurals.passwords_import_from_google_success,
                result.importCount,
                result.importCount,
            )
            PasswordsImporterResult.Failure -> getString(R.string.passwords_import_from_google_failure)
            PasswordsImporterResult.Canceled, null -> return
        }
        Snackbar.make(
            snackBarParentView = requireView(),
            snackbarState = SnackbarState(message = message),
        ).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        val buildStore = { composeNavController: NavHostController ->
            val navController = findNavController()

            val store by
                fragmentStore(
                    LoginsState.default.copy(
                        showPasswordsImport = requireComponents.settings.importPasswordsFeatureFlagEnabled,
                        showPasswordsImportFromGoogle =
                            requireComponents.settings.importPasswordsFeatureFlagEnabled &&
                                FxNimbus.features.googlePasswordsImport.value().enabled,
                        sortOrder =
                            LoginsSortOrder.fromString(
                                value = requireComponents.settings.loginsListSortOrder,
                                default = LoginsSortOrder.Alphabetical,
                            ),
                    )
                ) {
                    LoginsStore(
                        initialState = it,
                        middleware =
                            listOf(
                                LogMiddleware(
                                    tag = "LoginsStore",
                                    shouldIncludeDetailedData = { Config.channel.isDebug },
                                ),
                                LoginsTelemetryMiddleware(),
                                LoginsMiddleware(
                                    loginsStorage = requireContext().components.core.passwordsStorage,
                                    getNavController = { composeNavController },
                                    exitLogins = { navController.popBackStack() },
                                    persistLoginsSortOrder = { sortOrder ->
                                        DefaultSavedLoginsStorage(requireComponents.settings).savedLoginsSortOrder =
                                            sortOrder
                                    },
                                    navigateToImportDialog = {
                                        ImportPasswordsDialogFragment()
                                            .show(
                                                childFragmentManager,
                                                ImportPasswordsDialogFragment.TAG,
                                            )
                                    },
                                    navigateToImportFromGoogleScreen = {
                                        navController.navigate(
                                            R.id.action_savedLoginsFragment_to_importPasswordsFromGoogleFragment,
                                        )
                                    },
                                    openTab = { url, openInNewTab ->
                                        findNavController().openToBrowser()
                                        requireComponents.useCases.fenixBrowserUseCases.loadUrlOrSearch(
                                            searchTermOrURL = url,
                                            newTab = openInNewTab,
                                            flags =
                                                EngineSession.LoadUrlFlags.select(
                                                    EngineSession.LoadUrlFlags.ALLOW_JAVASCRIPT_URL
                                                ),
                                        )
                                    },
                                    clipboardManager = requireContext().getSystemService(),
                                ),
                            ),
                    )
                }
            loginsStore = store
            store
        }

        FirefoxTheme {
            SavedLoginsScreen(
                buildStore = buildStore,
                exitLogins = {
                    findNavController().popBackStack()
                },
            )
        }
    }
}
