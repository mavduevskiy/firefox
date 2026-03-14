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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.sync.OAuthAccount
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.IPProtectionController

/** Fragment hosting the VPN / IP Protection settings screen. */
class IpProtectionFragment : Fragment() {
    private var controller: IPProtectionController? = null
    private var uiState by mutableStateOf(IpProtectionState())

    private val delegate = object : IPProtectionController.Delegate {
        override fun onStateChanged(info: IPProtectionController.StateInfo) {
            updateUI(info)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        initIPProtection()

        val account = requireContext().components.backgroundServices.accountManager.authenticatedAccount()
        if (account != null) {
            authenticateIPProtection(account)
        } else {
            // If the user has logged off after using the VPN service, clear the token to move
            // the state machine into the unauthenticated state.
            updateTokenProvider(null)
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    IpProtectionScreen(
                        state = uiState,
                        onVpnToggle = { isChecked ->
                            if (isChecked) controller?.activate() else controller?.deactivate()
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

    override fun onDestroyView() {
        controller?.setDelegate(null)
        controller = null
        super.onDestroyView()
    }

    private fun initIPProtection() {
        controller = requireContext().components.core.geckoRuntime.getIPProtectionController().also {
            it.delegate = delegate
            it.state.accept { info -> info?.let { updateUI(it) } }
        }
    }

    private fun authenticateIPProtection(account: OAuthAccount) {
        viewLifecycleOwner.lifecycleScope.launch {
            val tokenProvider = IPProtectionController.TokenProvider {
                val result = GeckoResult<String>()
                lifecycleScope.launch {
                    val tokenInfo = withContext(Dispatchers.IO) {
                        runCatching { account.getAccessToken("https://identity.mozilla.com/apps/vpn") }.getOrNull()
                    }
                    result.complete(tokenInfo?.token)
                }
                result
            }

            updateTokenProvider(tokenProvider)
        }
    }

    private fun updateTokenProvider(tokenProvider: IPProtectionController.TokenProvider?) {
        controller?.let {
            it.setTokenProvider(tokenProvider)
            it.state.accept { info ->
                if (info != null) {
                    updateUI(info)
                }
            }
        }
    }

    private fun updateUI(info: IPProtectionController.StateInfo) {
        uiState = IpProtectionState(
            vpnStatus = proxyStateToVpnStatus(info.proxyState),
            dataRemainingBytes = info.remaining,
            dataMaxBytes = info.max,
            resetDate = info.resetTime,
        )
    }

    private fun proxyStateToVpnStatus(proxyState: Int): VpnStatus = when (proxyState) {
        IPProtectionController.PROXY_STATE_ACTIVE -> VpnStatus.Active
        IPProtectionController.PROXY_STATE_ACTIVATING -> VpnStatus.Activating
        IPProtectionController.PROXY_STATE_READY -> VpnStatus.Ready
        IPProtectionController.PROXY_STATE_PAUSED -> VpnStatus.Paused
        IPProtectionController.PROXY_STATE_ERROR -> VpnStatus.Error
        else -> VpnStatus.NotAvailable
    }
}
