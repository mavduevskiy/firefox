/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import mozilla.components.browser.state.helper.Target
import mozilla.components.browser.state.selector.findTab
import mozilla.components.compose.engine.WebContent
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.mozilla.fenix.components.components
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme

private const val GOOGLE_AUTH_URL = "https://accounts.google.com/"
private const val GOOGLE_TAKEOUT_URL = "https://takeout.google.com/"
private const val EXTENSION_ID: String = "takeout-importer@mozac.org"
private const val EXTENSION_URL: String = "resource://android/assets/extensions/google_takeout/"
private const val DEFAULT_PORT: String = "mozacTakeoutImporter"

/** The screen to guide the user through importing data from Google. */
class ImportFromGoogleFragment : Fragment() {
    private lateinit var tabId: String
    private val extensionController = BuiltInWebExtensionController(
        EXTENSION_ID,
        EXTENSION_URL,
        DEFAULT_PORT,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        val authRequired =
            components.core.store
                .observeAsComposableState {
                    it.findTab(tabId)?.content?.url?.contains(GOOGLE_AUTH_URL)
                }
                .value

//        if (authRequired == true) {
            FirefoxTheme {
                WebContent(
                    components.core.engine,
                    components.core.store,
                    Target.Tab(tabId),
                )
            }
//        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        extensionController.install(
            runtime = requireComponents.core.engine,
            onSuccess = {
                println("$this, install onSuccess")
            },
            onError = { error ->
                println("$this, install onError, error = $error")
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tabId =
            requireComponents.useCases.tabsUseCases.addTab.invoke(
                url = GOOGLE_TAKEOUT_URL,
                selectTab = false,
            )
    }
}
