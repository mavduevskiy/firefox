/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.menu

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import mozilla.components.lib.state.helpers.AbstractBinding
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.components.appstate.AppState
import org.mozilla.fenix.components.menu.store.MenuAction
import org.mozilla.fenix.components.menu.store.MenuStore

/**
 * Wires up the VPN state in [AppStore] with [MenuStore], so that we have the
 * right state in the menu UI.
 *
 * @param appStore The [AppStore] to observe for [AppState.vpnStatus] changes.
 * @param menuStore The [MenuStore] to dispatch [MenuAction.UpdateVpnStatus] into.
 * @param mainDispatcher The [CoroutineDispatcher] to collect on.
 */
class VpnMenuBinding(
    appStore: AppStore,
    private val menuStore: MenuStore,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AbstractBinding<AppState>(appStore, mainDispatcher) {

    override suspend fun onState(flow: Flow<AppState>) {
        flow.distinctUntilChangedBy { it.vpnStatus }
            .collect { state ->
                menuStore.dispatch(MenuAction.UpdateVpnStatus(state.vpnStatus))
            }
    }
}
