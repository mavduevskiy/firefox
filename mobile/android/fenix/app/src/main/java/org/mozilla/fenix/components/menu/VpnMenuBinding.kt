/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.menu

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import mozilla.components.feature.vpn.VpnState
import mozilla.components.feature.vpn.VpnStore
import mozilla.components.lib.state.helpers.AbstractBinding
import org.mozilla.fenix.components.menu.store.MenuAction
import org.mozilla.fenix.components.menu.store.MenuStore

/**
 * Bridges [VpnStore] with [MenuStore] so the menu badge reflects live VPN state.
 *
 * @param vpnStore The [VpnStore] to observe for [VpnState.vpnStatus] changes.
 * @param menuStore The [MenuStore] to dispatch [MenuAction.UpdateVpnStatus] into.
 * @param mainDispatcher The [CoroutineDispatcher] to collect on.
 */
class VpnMenuBinding(
    vpnStore: VpnStore,
    private val menuStore: MenuStore,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AbstractBinding<VpnState>(vpnStore, mainDispatcher) {

    override suspend fun onState(flow: Flow<VpnState>) {
        flow.distinctUntilChangedBy { it.vpnStatus }
            .collect { state ->
                menuStore.dispatch(MenuAction.UpdateVpnStatus(state.vpnStatus))
            }
    }
}
