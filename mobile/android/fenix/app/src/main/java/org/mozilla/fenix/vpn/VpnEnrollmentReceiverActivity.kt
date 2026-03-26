/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.ext.components

/**
 * Entry point for the Guardian OAuth enrollment flow. Mirrors [AuthIntentReceiverActivity]:
 * processes the custom tab intent (creates a BrowserStore session and embeds the session ID),
 * then routes to [VpnEnrollmentActivity] which shows the custom tab and observes for the
 * Guardian success/error redirect URL.
 */
class VpnEnrollmentReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MainScope().launch {
            val intent = intent?.let { Intent(intent) } ?: Intent()

            components.intentProcessors.customTabIntentProcessor.process(intent)

            intent.setClassName(applicationContext, VpnEnrollmentActivity::class.java.name)
            intent.putExtra(HomeActivity.OPEN_TO_BROWSER, true)

            startActivity(intent)
            finish()
        }
    }
}
