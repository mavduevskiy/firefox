/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.service.fxa.manager.FxaAccountManager
import org.mozilla.fenix.components.appstate.AppAction
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.IPProtectionController

private const val VPN_TOKEN_SCOPE = "https://identity.mozilla.com/apps/vpn"

/**
 * App-lifetime integration that bridges [IPProtectionController] proxy state changes into
 * [AppStore] as the single source of truth for all VPN-related state in the app.
 *
 * Dispatches [AppAction.UpdateVpnState] on every state change from the controller, carrying
 * both the connection status and quota data. All UI (menu, address bar, settings fragment)
 * reads exclusively from [AppStore] rather than holding their own controller references.
 *
 * Call [start] once at app startup and [stop] when the integration is no longer needed.
 *
 * @param controller The [IPProtectionController] obtained from [GeckoRuntime].
 * @param accountManager The [FxaAccountManager] used to supply authentication tokens.
 * @param appStore The [AppStore] to dispatch [AppAction.UpdateVpnState] into.
 */
class IpProtectionIntegration(
    private val controller: IPProtectionController,
    private val accountManager: FxaAccountManager,
    private val appStore: AppStore,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val accountObserver = object : AccountObserver {
        override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
            // FxaAccountManager callbacks fire on a background thread; IPProtectionController
            // requires the UI thread (@UiThread), so dispatch via the main-thread scope.
            scope.launch { setTokenProvider(account) }
        }

        override fun onLoggedOut() {
            scope.launch { controller.setTokenProvider(null) }
        }

        override fun onAuthenticationProblems() {
            scope.launch { controller.setTokenProvider(null) }
        }
    }

    /** Wires the delegate, token provider, and account observer. Call once at app startup. */
    fun start() {
        controller.delegate = object : IPProtectionController.Delegate {
            override fun onStateChanged(info: IPProtectionController.StateInfo) {
                val state = info.toVpnState()
                android.util.Log.d("VPN_ENROLL", "IpProtectionIntegration.onStateChanged: proxyState=${info.proxyState} serviceState=${info.serviceState} → vpnStatus=${state.vpnStatus} isEnrollmentNeeded=${state.isEnrollmentNeeded}")
                appStore.dispatch(AppAction.UpdateVpnState(state))
            }
        }

        accountManager.register(accountObserver)

        val account = accountManager.authenticatedAccount()
        if (account != null) {
            setTokenProvider(account)
        } else {
            controller.setTokenProvider(null)
        }
    }

    /** Unregisters the account observer and clears the delegate and token provider. */
    fun stop() {
        accountManager.unregister(accountObserver)
        controller.delegate = null
        controller.setTokenProvider(null)
    }

    private fun setTokenProvider(account: OAuthAccount) {
        controller.setTokenProvider {
            val result = GeckoResult<String>()
            scope.launch {
                val tokenInfo = withContext(Dispatchers.IO) {
                    runCatching { account.getAccessToken(VPN_TOKEN_SCOPE) }.getOrNull()
                }
                result.complete(tokenInfo?.token)
            }
            result
        }.accept { info ->
            info?.let {
                val state = it.toVpnState()
                android.util.Log.d("VPN_ENROLL", "setTokenProvider result: proxyState=${it.proxyState} serviceState=${it.serviceState} → vpnStatus=${state.vpnStatus} isEnrollmentNeeded=${state.isEnrollmentNeeded}")
                appStore.dispatch(AppAction.UpdateVpnState(state))
            }
        }
    }

    fun retriggerEnrollment() {
        val account = accountManager.authenticatedAccount() ?: run {
            android.util.Log.w("VPN_ENROLL", "retriggerEnrollment: no authenticated account, skipping")
            return
        }
        android.util.Log.d("VPN_ENROLL", "retriggerEnrollment: re-firing token provider to recheck Guardian entitlement")
        setTokenProvider(account)
    }

    fun activate() {
        android.util.Log.d("VPN_ENROLL", "activate: calling controller.activate() post-enrollment")
        controller.activate()
    }

    private fun IPProtectionController.StateInfo.toVpnState() = VpnState(
        vpnStatus = proxyStateToVpnStatus(proxyState),
        dataRemainingBytes = remaining,
        dataMaxBytes = max,
        resetDate = resetTime,
        // isEnrollmentNeeded is true only when the user IS signed in (FxA account present)
        // but Guardian hasn't enrolled this device yet (service says UNAUTHENTICATED).
        // SERVICE_STATE_UNAUTHENTICATED (2) means "eligible but not signed in to Guardian".
        isEnrollmentNeeded = proxyState == IPProtectionController.PROXY_STATE_NOT_READY &&
            serviceState == IPProtectionController.SERVICE_STATE_UNAUTHENTICATED &&
            accountManager.authenticatedAccount() != null,
    )

    private fun proxyStateToVpnStatus(proxyState: Int): VpnStatus = when (proxyState) {
        IPProtectionController.PROXY_STATE_ACTIVE -> VpnStatus.Active
        IPProtectionController.PROXY_STATE_ACTIVATING -> VpnStatus.Activating
        IPProtectionController.PROXY_STATE_READY -> VpnStatus.Ready
        IPProtectionController.PROXY_STATE_PAUSED -> VpnStatus.Paused
        IPProtectionController.PROXY_STATE_ERROR -> VpnStatus.Error
        else -> VpnStatus.NotAvailable
    }
}
