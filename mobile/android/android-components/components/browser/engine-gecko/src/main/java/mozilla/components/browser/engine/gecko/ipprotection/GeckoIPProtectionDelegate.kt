/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.engine.gecko.ipprotection

import mozilla.components.concept.engine.ipprotection.IPProtectionDelegate
import mozilla.components.concept.engine.ipprotection.IPProtectionHandler
import org.mozilla.geckoview.IPProtectionController as GeckoViewIPProtectionController

/**
 * Wraps a concept-engine [IPProtectionDelegate] to implement the geckoview
 * [GeckoViewIPProtectionController.Delegate] interface.
 */
internal class GeckoIPProtectionDelegate(
    private val delegate: IPProtectionDelegate,
) : GeckoViewIPProtectionController.Delegate {

    override fun onStateChanged(info: GeckoViewIPProtectionController.StateInfo) {
        delegate.onStateChanged(info.toConceptStateInfo())
    }
}

internal fun GeckoViewIPProtectionController.StateInfo.toConceptStateInfo() =
    IPProtectionHandler.StateInfo(
        serviceState = serviceState,
        proxyState = proxyState,
        lastError = lastError,
        remaining = remaining,
        max = max,
        resetTime = resetTime,
    )
