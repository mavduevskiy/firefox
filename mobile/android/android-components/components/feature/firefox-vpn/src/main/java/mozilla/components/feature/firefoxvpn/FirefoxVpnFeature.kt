/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.firefoxvpn

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.support.base.feature.LifecycleAwareFeature

/**
 * A feature that routes browser traffic through a VPN service provider.
 *
 * @param onVpnStatusChange Callback invoked with the new connection state whenever the VPN
 * connection status changes. `true` means connected, `false` means disconnected.
 * @param mainDispatcher The coroutine dispatcher used for the VPN connection operations.
 */
class FirefoxVpnFeature(
    private val onVpnStatusChange: (Boolean) -> Unit,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : LifecycleAwareFeature {
    private var job: Job? = null
    private val scope = CoroutineScope(mainDispatcher)

    override fun start() {
        job = scope.launch {
            onVpnStatusChange(true)
        }
    }

    override fun stop() {
        job?.cancel()
        onVpnStatusChange(false)
    }
}
