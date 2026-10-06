/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.webflow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject
import java.util.UUID

/**
 * Runs a web flow journey in a hidden tab with the built-in web flow runner
 * extension. One instance is one attempt.
 *
 * @param engine The engine the extension is installed in.
 * @param store Observed for the hidden tab's engine session.
 * @param tabsUseCases Used to create and remove the hidden tab.
 * @param launchUrl The URL the hidden tab starts on.
 * @param config Provides the journey config sent to the extension.
 * @param onConfigured Invoked when the config was sent, for exposure recording.
 */
class WebFlowRunner(
    private val engine: Engine,
    private val store: BrowserStore,
    private val tabsUseCases: TabsUseCases,
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

    /** The id of the hidden tab, available after [createTab]. */
    lateinit var tabId: String
        private set

    /** Creates the hidden tab the journey runs in. */
    fun createTab() {
        tabId = tabsUseCases.addTab(url = launchUrl, selectTab = false)
    }

    /**
     * Installs the extension, sends it the journey and starts the runner in
     * the hidden tab only, so takeout pages the user opens elsewhere are left
     * alone.
     */
    fun start(scope: CoroutineScope) {
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
        extensionController.install(
            runtime = engine,
            onError = { error -> logger.error("Failed to install the web flow runner", error) },
        )
        scope.launch {
            val session = store.flow()
                .mapNotNull { it.findTab(tabId)?.engineState?.engineSession }
                .first()
            engineSession = session
            extensionController.registerContentMessageHandler(
                session,
                object : MessageHandler {
                    override fun onPortConnected(port: Port) {
                        port.postMessage(JSONObject().put("type", "start").put("attemptId", attemptId))
                    }
                },
            )
        }
    }

    /** Uninstalls the extension and removes the hidden tab. */
    fun release() {
        extensionController.disconnectPort(engineSession)
        extensionController.uninstall(engine)
        tabsUseCases.removeTab(tabId)
    }

    private companion object {
        const val EXTENSION_ID = "web-flow-runner@mozilla.org"
        const val EXTENSION_URL = "resource://android/assets/extensions/web_flow_runner/"
        const val PORT_NAME = "mozacWebFlowRunner"
    }
}
