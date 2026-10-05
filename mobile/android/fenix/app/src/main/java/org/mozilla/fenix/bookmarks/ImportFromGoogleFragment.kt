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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import mozilla.components.browser.state.helper.Target
import mozilla.components.browser.state.selector.findTab
import mozilla.components.compose.engine.WebContent
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.lib.state.ext.flow
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject
import org.mozilla.fenix.components.components
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.nimbus.FxNimbus
import org.mozilla.fenix.theme.FirefoxTheme
import java.util.UUID

private const val GOOGLE_AUTH_URL = "https://accounts.google.com/"
private const val EXTENSION_ID: String = "takeout-importer@mozac.org"
private const val EXTENSION_URL: String = "resource://android/assets/extensions/google_takeout/"
private const val DEFAULT_PORT: String = "mozacTakeoutImporter"

/** The screen to guide the user through importing data from Google. */
class ImportFromGoogleFragment : Fragment() {
    private lateinit var tabId: String
    private val attemptId = UUID.randomUUID().toString()
    private val logger = Logger("ImportFromGoogleFragment")
    private var engineSession: EngineSession? = null
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

        // The journey the extension runs comes from Nimbus. The background
        // script connects at startup and receives it with this attempt's id.
        extensionController.registerBackgroundMessageHandler(
            object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    val feature = FxNimbus.features.googleTakeoutImport
                    feature.recordExposure()
                    port.postMessage(
                        JSONObject()
                            .put("type", "configure")
                            .put("attemptId", attemptId)
                            .put("config", feature.value().toJSONObject()),
                    )
                }
            },
        )

        extensionController.install(
            runtime = requireComponents.core.engine,
            onSuccess = {
                println("$this, install onSuccess")
            },
            onError = { error ->
                println("$this, install onError, error = $error")
            },
        )

        // Here we are looking for the created "hidden" tab to send it a "start" message.
        // Without this mechanism, the runner would execute on any takeout pages opened by the user as well.
        lifecycleScope.launch {
            requireComponents.core.store.flow()
                .mapNotNull { it.findTab(tabId)?.engineState?.engineSession }
                .first()
                .also { session ->
                    engineSession = session
                    extensionController.registerContentMessageHandler(
                        session,
                        object : MessageHandler {
                            override fun onPortConnected(port: Port) {
                                port.postMessage(
                                    JSONObject()
                                        .put("type", "start")
                                        .put("attemptId", attemptId),
                                )
                            }

                            override fun onPortMessage(message: Any, port: Port) {
                                logger.info("runner: $message")
                            }
                        },
                    )
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        extensionController.disconnectPort(engineSession)
        extensionController.uninstall(requireComponents.core.engine)
        requireComponents.useCases.tabsUseCases.removeTab(tabId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tabId =
            requireComponents.useCases.tabsUseCases.addTab.invoke(
                url = FxNimbus.features.googleTakeoutImport.value().launchUrl,
                selectTab = false,
            )
    }
}
