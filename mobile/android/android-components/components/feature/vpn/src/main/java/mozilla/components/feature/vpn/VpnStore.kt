/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.vpn

import mozilla.components.lib.state.Action
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Reducer
import mozilla.components.lib.state.Store

/** Actions that can be dispatched to [VpnStore]. */
sealed interface VpnAction : Action {
    /** Replaces the full VPN state with a new snapshot from the GeckoView proxy. */
    data class UpdateState(val state: VpnState) : VpnAction
}

internal fun vpnReducer(state: VpnState, action: VpnAction): VpnState = when (action) {
    is VpnAction.UpdateState -> state.copy(
        vpnStatus = action.state.vpnStatus,
        dataRemainingBytes = action.state.dataRemainingBytes,
        dataMaxBytes = action.state.dataMaxBytes,
        resetDate = action.state.resetDate,
        isEnrollmentNeeded = action.state.isEnrollmentNeeded,
    )
}

/**
 * Dedicated [Store] for VPN state. Instantiated in the app's component graph and passed
 * into [DefaultVpnFeature] and any UI consumers that need to observe VPN state.
 */
class VpnStore(
    initialState: VpnState = VpnState(),
    reducer: Reducer<VpnState, VpnAction> = ::vpnReducer,
    middleware: List<Middleware<VpnState, VpnAction>> = emptyList(),
) : Store<VpnState, VpnAction>(initialState, reducer, middleware)
