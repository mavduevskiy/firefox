/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.sync.OAuthAccount
import org.mozilla.fenix.R
import org.mozilla.fenix.databinding.SettingsIpProtectionBinding
import org.mozilla.fenix.ext.components
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.IPProtectionController

class IpProtectionFragment : Fragment() {
    private var binding: SettingsIpProtectionBinding? = null
    private var controller: IPProtectionController? = null

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
        val binding = SettingsIpProtectionBinding.inflate(inflater)

        initRowLabels(binding)
        initIPProtection()

        val account = requireContext().components.backgroundServices.accountManager.authenticatedAccount()
        binding.ipProtectionSwitch.isEnabled = account != null
        if (account != null) {
            authenticateIPProtection(account)
        } else {
            // if the user has logged off after using vpn service, the controller will still keep
            // the old token, we have to clear that to move the state machine into non auth state.
            updateTokenProvider(null)
        }

        this.binding = binding
        return binding.root
    }

    override fun onDestroyView() {
        controller?.setDelegate(null)
        controller = null
        binding = null
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
                    try {
                        val tokenInfo = withContext(Dispatchers.IO) {
                            account.getAccessToken("https://identity.mozilla.com/apps/vpn")
                        }
                        result.complete(tokenInfo?.token)
                    } catch (e: Exception) {
                        result.completeExceptionally(e)
                    }
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
    private fun initRowLabels(b: SettingsIpProtectionBinding) {
        b.rowServiceState.root.findViewById<TextView>(R.id.row_label).text = "Service State"
        b.rowProxyState.root.findViewById<TextView>(R.id.row_label).text = "Proxy State"
        b.rowLastError.root.findViewById<TextView>(R.id.row_label).text = "Last Error"
        b.rowRemaining.root.findViewById<TextView>(R.id.row_label).text = "Remaining"
        b.rowMax.root.findViewById<TextView>(R.id.row_label).text = "Max"
        b.rowResetTime.root.findViewById<TextView>(R.id.row_label).text = "Reset Time"
    }

    private fun setRowValue(row: View, value: String) {
        row.findViewById<TextView>(R.id.row_value).text = value
    }

    private fun updateUI(info: IPProtectionController.StateInfo) {
        val b = binding ?: return
        val proxyState = info.proxyState
        val isActive = proxyState == IPProtectionController.PROXY_STATE_ACTIVE ||
            proxyState == IPProtectionController.PROXY_STATE_ACTIVATING

        b.ipProtectionSwitch.setOnCheckedChangeListener(null)
        b.ipProtectionSwitch.isChecked = isActive

        b.ipProtectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                controller?.activate()
            } else {
                controller?.deactivate()
            }
        }

        setRowValue(b.rowServiceState.root, serviceStateToString(info.serviceState))
        setRowValue(b.rowProxyState.root, proxyStateToString(proxyState))
        setRowValue(b.rowLastError.root, info.lastError ?: "-")
        setRowValue(
            b.rowRemaining.root,
            if (info.remaining >= 0) info.remaining.toString() else "-",
        )
        setRowValue(b.rowMax.root, if (info.max >= 0) info.max.toString() else "-")
        setRowValue(b.rowResetTime.root, info.resetTime ?: "-")
    }

    private fun serviceStateToString(state: Int): String = when (state) {
        IPProtectionController.SERVICE_STATE_UNINITIALIZED -> "uninitialized"
        IPProtectionController.SERVICE_STATE_UNAVAILABLE -> "unavailable"
        IPProtectionController.SERVICE_STATE_UNAUTHENTICATED -> "unauthenticated"
        IPProtectionController.SERVICE_STATE_READY -> "ready"
        else -> "unknown"
    }

    private fun proxyStateToString(state: Int): String = when (state) {
        IPProtectionController.PROXY_STATE_NOT_READY -> "not ready"
        IPProtectionController.PROXY_STATE_READY -> "ready"
        IPProtectionController.PROXY_STATE_ACTIVATING -> "activating"
        IPProtectionController.PROXY_STATE_ACTIVE -> "active"
        IPProtectionController.PROXY_STATE_ERROR -> "error"
        IPProtectionController.PROXY_STATE_PAUSED -> "paused"
        else -> "unknown"
    }
}
