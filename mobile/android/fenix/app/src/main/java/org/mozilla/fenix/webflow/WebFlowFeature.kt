/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webflow

import mozilla.components.browser.state.action.CustomTabListAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.createCustomTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject
import java.util.UUID

class WebFlowFeature(
    private val engine: Engine,
    private val store: BrowserStore,
    private val launchUrl: String,
    private val config: () -> JSONObject,
    private val onConfigured: () -> Unit = {},
) {
    private val logger = Logger("WebFlowFeature")
    private val extensionController = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, PORT_NAME)
    private var engineSession: EngineSession? = null
    private var configured = false

    val attemptId: String = UUID.randomUUID().toString()

    val startedAt: Long = System.currentTimeMillis()

    lateinit var tabId: String
        private set

    fun createTab() {
        val session = engine.createSession()
        engineSession = session
        val tab = createCustomTab(url = launchUrl, source = SessionState.Source.Internal.CustomTab)
        tabId = tab.id
        store.dispatch(CustomTabListAction.AddCustomTabAction(tab))
        // Linking registers the store's observer on the session, so its URL
        // changes and download requests reach the store.
        store.dispatch(EngineAction.LinkEngineSessionAction(tabId, session, skipLoading = true))
    }

    fun start() {
        val session = checkNotNull(engineSession)
        extensionController.registerContentMessageHandler(
            session,
            object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    port.postMessage(
                        JSONObject()
                            .put("type", "start")
                            .put("attemptId", attemptId)
                            .put("config", config()),
                    )
                    if (!configured) {
                        configured = true
                        onConfigured()
                    }
                }

                override fun onPortMessage(message: Any, port: Port) {
                    logger.info("runner: $message")
                }
            },
        )
        extensionController.install(
            runtime = engine,
            // Loading earlier could finish before the extension can inject
            // its content script into the page.
            onSuccess = { session.loadUrl(launchUrl) },
            onError = { error -> logger.error("Failed to install the web flow runner", error) },
        )
    }

    fun release() {
        extensionController.disconnectPort(engineSession)
        extensionController.uninstall(engine)
        store.dispatch(CustomTabListAction.RemoveCustomTabAction(tabId))
    }

    private companion object {
        const val EXTENSION_ID = "web-flow-runner@mozilla.org"
        const val EXTENSION_URL = "resource://android/assets/extensions/web_flow_runner/"
        const val PORT_NAME = "mozacWebFlowRunner"
    }
}
