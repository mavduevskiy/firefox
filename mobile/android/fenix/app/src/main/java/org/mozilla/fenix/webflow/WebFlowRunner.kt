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

/**
 * Runs a web flow journey in a hidden session with the built-in web flow
 * runner extension. One instance is one attempt.
 *
 * The session is registered in the store as a custom tab, like add-on
 * popups: it stays out of the tabs tray and session restore, while
 * store-based features (downloads, prompts) still work for it.
 *
 * @param engine Creates the session and runs the extension.
 * @param store Holds the session as a custom tab.
 * @param launchUrl The URL the session starts on.
 * @param config Provides the journey config sent to the extension.
 * @param onConfigured Invoked when the config was sent, for exposure recording.
 */
class WebFlowRunner(
    private val engine: Engine,
    private val store: BrowserStore,
    private val launchUrl: String,
    private val config: () -> JSONObject,
    private val onConfigured: () -> Unit = {},
) {
    private val logger = Logger("WebFlowRunner")
    private val extensionController = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, PORT_NAME)
    private var engineSession: EngineSession? = null

    /** Identifies this attempt in every message exchanged with the extension. */
    val attemptId: String = UUID.randomUUID().toString()

    /** Downloads created before this time belong to earlier attempts. */
    val startedAt: Long = System.currentTimeMillis()

    /** The id of the session's custom tab, available after [createTab]. */
    lateinit var tabId: String
        private set

    /**
     * Creates the hidden session the journey runs in. It loads the launch URL
     * only in [start], once the extension is installed.
     */
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

    /**
     * Installs the extension, sends it the journey and starts the runner in
     * the hidden session only, so takeout pages the user opens elsewhere are
     * left alone.
     */
    fun start() {
        val session = checkNotNull(engineSession)
        extensionController.registerBackgroundMessageHandler(
            object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    port.postMessage(
                        JSONObject()
                            .put("type", "configure")
                            .put("attemptId", attemptId)
                            .put("config", config()),
                    )
                    onConfigured()
                }

                // The runner reports every result through the background script.
                override fun onPortMessage(message: Any, port: Port) {
                    logger.info("runner: $message")
                }
            },
        )
        extensionController.registerContentMessageHandler(
            session,
            object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    port.postMessage(JSONObject().put("type", "start").put("attemptId", attemptId))
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

    /** Uninstalls the extension and removes the hidden session. */
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
