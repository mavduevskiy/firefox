/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.engine.gecko.ipprotection

import mozilla.components.concept.engine.ipprotection.IPProtectionHandler
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.IPProtectionController as GeckoViewIPProtectionController

/**
 * Gecko-based implementation of [IPProtectionHandler], wrapping the geckoview
 * [GeckoViewIPProtectionController] and adapting its GeckoResult-based API to callbacks.
 */
internal class GeckoIPProtectionHandler(
    private val geckoController: GeckoViewIPProtectionController,
) : IPProtectionHandler {

    override fun activate() = geckoController.activate()

    override fun deactivate() = geckoController.deactivate()

    override fun setTokenProvider(
        provider: IPProtectionHandler.TokenProvider?,
        onInitialState: ((IPProtectionHandler.StateInfo) -> Unit)?,
    ) {
        val geckoProvider = provider?.let { conceptProvider ->
            GeckoViewIPProtectionController.TokenProvider {
                val result = GeckoResult<String>()
                conceptProvider.getToken { token -> result.complete(token) }
                result
            }
        }
        geckoController.setTokenProvider(geckoProvider).accept { geckoStateInfo ->
            geckoStateInfo?.let { onInitialState?.invoke(it.toConceptStateInfo()) }
        }
    }
}


