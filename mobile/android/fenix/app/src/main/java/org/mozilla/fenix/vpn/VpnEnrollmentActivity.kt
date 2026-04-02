/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vpn

import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.feature.vpn.DefaultVpnFeature
import mozilla.components.feature.vpn.VpnStatus
import mozilla.components.lib.state.ext.flow
import org.mozilla.fenix.customtabs.ExternalAppBrowserActivity
import org.mozilla.fenix.ext.components

private const val TAG = "VPN_ENROLL"

/**
 * Internal Fenix custom tab activity that hosts the Guardian OAuth enrollment flow.
 */
class VpnEnrollmentActivity : ExternalAppBrowserActivity() {

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "VpnEnrollmentActivity.onResume: activity started, tabId=${getExternalTabId()}")
        observeEnrollmentTab()
    }

    private fun observeEnrollmentTab() {
        val tabId = getExternalTabId() ?: run {
            Log.w(TAG, "VpnEnrollmentActivity: no tab ID found, finishing")
            finish()
            return
        }

        lifecycleScope.launch {
            components.core.store.flow()
                .mapNotNull { state -> state.findCustomTab(tabId)?.content?.url }
                .distinctUntilChanged()
                .collect { url ->
                    Log.d(TAG, "tab navigated → $url")
                    when {
                        url.startsWith(DefaultVpnFeature.GUARDIAN_SUCCESS_URL) -> onEnrollmentSuccess()
                        url.startsWith(DefaultVpnFeature.GUARDIAN_ERROR_URL) -> onEnrollmentError(url)
                    }
                }
        }
    }

    private fun onEnrollmentSuccess() {
        Log.d(TAG, "onSuccess: Guardian enrollment complete — triggering entitlement check")
        components.vpnFeature.retriggerEnrollment()

        val currentStatus = components.vpnStore.state.vpnStatus
        Log.d(TAG, "onSuccess: current vpnStatus=$currentStatus")
        if (currentStatus == VpnStatus.Ready) {
            Log.d(TAG, "onSuccess: already Ready — activating VPN now")
            components.vpnFeature.activate()
            finish()
            return
        }

        lifecycleScope.launch {
            components.vpnStore.flow()
                .map { it.vpnStatus }
                .filter { it == VpnStatus.Ready }
                .first()

            Log.d(TAG, "onSuccess: proxy reached Ready — activating VPN")
            components.vpnFeature.activate()
            finish()
        }
    }

    private fun onEnrollmentError(url: String) {
        Log.w(TAG, "onError: Guardian enrollment failed → $url")
        finish()
    }
}
