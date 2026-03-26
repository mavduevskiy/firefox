/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.flow

/**
 * Handles the Guardian OAuth enrollment flow for users who are signed in to Firefox Account
 * but have not yet enrolled their device with the Guardian VPN proxy service.
 *
 * [beginEnrollment] opens the Guardian OAuth URL as a background (non-selected) Fenix tab.
 * The FxA session is shared across all GeckoView sessions in the same runtime, so the OAuth
 * dance completes silently — no tab flashes, no custom tab UI, no user interaction needed.
 *
 * URL changes are observed via [BrowserStore] flow. On success redirect, [retriggerEnrollment]
 * is called then we wait for [VpnStatus.Ready] before activating VPN.
 *
 * @param appStore Used to observe vpn status
 * @param browserStore Used to observe tab URL changes.
 * @param tabsUseCases Used to open and remove the background tab.
 * @param ipProtectionIntegration Called on success to retrigger enrollment and activate VPN.
 */
class VpnEnrollmentFeature(
    private val appStore: AppStore,
    private val browserStore: BrowserStore,
    private val tabsUseCases: TabsUseCases,
    private val ipProtectionIntegration: IpProtectionIntegration,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var observationJob: Job? = null
    private var enrollmentTabId: String? = null

    /**
     * Opens the Guardian enrollment URL as a non-selected background tab and observes
     * it for the success/error redirect.
     */
    fun beginEnrollment() {
        cancelEnrollment()

        val tabId = tabsUseCases.addTab(
            url = GUARDIAN_ENROLLMENT_URL,
            selectTab = false,
        )
        enrollmentTabId = tabId
        observeTab(tabId)
    }

    private fun cancelEnrollment() {
        val tabId = enrollmentTabId ?: return
        observationJob?.cancel()
        observationJob = null
        tabsUseCases.removeTab(tabId)
        enrollmentTabId = null
    }

    private fun observeTab(tabId: String) {
        observationJob = scope.launch {
            browserStore.flow()
                .mapNotNull { state -> state.findTab(tabId)?.content?.url }
                .distinctUntilChanged()
                .collect { url ->
                    when {
                        url.startsWith(GUARDIAN_SUCCESS_URL) -> onSuccess(tabId)
                        url.startsWith(GUARDIAN_ERROR_URL) -> onError(tabId)
                    }
                }
        }
    }

    private fun onSuccess(tabId: String) {
        observationJob?.cancel()
        observationJob = null
        enrollmentTabId = null
        tabsUseCases.removeTab(tabId)

        ipProtectionIntegration.retriggerEnrollment()

        scope.launch {
            val currentStatus = appStore.state.vpnState.vpnStatus
            if (currentStatus == VpnStatus.Ready) {
                ipProtectionIntegration.activate()
                return@launch
            }

            // Waiting here for the vpn state machine to move the gears, to enable vpn once
            // the authorization finishes.
            appStore.flow()
                .map { it.vpnState.vpnStatus }
                .filter { it == VpnStatus.Ready }
                .first()
            ipProtectionIntegration.activate()
        }
    }

    private fun onError(tabId: String) {
        observationJob?.cancel()
        observationJob = null
        enrollmentTabId = null
        tabsUseCases.removeTab(tabId)
    }

    companion object {
        /** Guardian OAuth enrollment entry point — same URL the desktop hidden browser loads. */
        const val GUARDIAN_ENROLLMENT_URL = "https://vpn.mozilla.org/api/v1/fpn/auth?experiment=alpha"

        /** Guardian redirects here on successful enrollment. */
        const val GUARDIAN_SUCCESS_URL = "https://vpn.mozilla.org/oauth/success"

        /** Guardian redirects here if enrollment fails or is rejected. */
        const val GUARDIAN_ERROR_URL = "https://vpn.mozilla.org/api/v1/fpn/error"
    }
}
