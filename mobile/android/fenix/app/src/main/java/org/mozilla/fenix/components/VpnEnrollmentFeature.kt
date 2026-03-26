/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import android.content.Context
import android.util.Log
import org.mozilla.fenix.settings.SupportUtils
import org.mozilla.fenix.vpn.VpnEnrollmentReceiverActivity

private const val TAG = "VPN_ENROLL"

/**
 * Handles launching the Guardian OAuth enrollment flow for users who are signed in to Firefox
 * Account but have not yet enrolled their device with the Guardian VPN proxy service.
 *
 * [beginEnrollment] opens the Guardian OAuth URL as an internal Fenix custom tab (same GeckoView
 * instance so the user's FxA session is shared, and the OAuth dance completes silently).
 * [VpnEnrollmentActivity] manages URL observation and closes itself on success/error.
 */
class VpnEnrollmentFeature {
    /**
     * Opens the Guardian enrollment URL as an internal Fenix custom tab.
     * [VpnEnrollmentActivity] takes ownership from here — it observes the URL, closes itself,
     * and triggers enrollment completion + VPN activation.
     *
     * Requires an Activity context because [SupportUtils.createCustomTabIntent] calls
     * [Context.getColorFromAttr] which needs a themed Activity context, not Application context.
     */
    fun beginEnrollment(activityContext: Context) {
        Log.d(TAG, "beginEnrollment: launching VpnEnrollmentActivity → $GUARDIAN_ENROLLMENT_URL")
        // Point to VpnEnrollmentReceiverActivity which runs CustomTabIntentProcessor first
        // (creating the BrowserStore session), then routes to VpnEnrollmentActivity.
        val intent = SupportUtils.createCustomTabIntent(activityContext, GUARDIAN_ENROLLMENT_URL)
            .setClassName(activityContext, VpnEnrollmentReceiverActivity::class.java.name)
        activityContext.startActivity(intent)
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
