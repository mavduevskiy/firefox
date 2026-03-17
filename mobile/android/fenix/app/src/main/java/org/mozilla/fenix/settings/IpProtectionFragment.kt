/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import mozilla.components.lib.state.ext.flow
import org.mozilla.fenix.R
import org.mozilla.fenix.components.VpnState
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.theme.FirefoxTheme

/** Fragment hosting the VPN / IP Protection settings screen. */
class IpProtectionFragment : Fragment() {
    private var uiState by mutableStateOf(IpProtectionState())

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.ip_protection_toggle_label))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewLifecycleOwner.lifecycleScope.launch {
            requireContext().components.appStore.flow()
                .distinctUntilChangedBy { it.vpnState }
                .collect { state -> uiState = state.vpnState.toIpProtectionState() }
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    IpProtectionScreen(
                        state = uiState,
                        onVpnToggle = { isChecked ->
                            with(requireContext().components.core.ipProtectionController) {
                                if (isChecked) activate() else deactivate()
                            }
                        },
                        onLearnMoreClick = {},
                        onAutoLocationToggle = {},
                        onLocationClick = {},
                        onManageWebsiteSettingsClick = {},
                    )
                }
            }
        }
    }
}

private fun VpnState.toIpProtectionState() = IpProtectionState(
    vpnStatus = vpnStatus,
    dataRemainingBytes = dataRemainingBytes,
    dataMaxBytes = dataMaxBytes,
    resetDate = resetDate,
)
