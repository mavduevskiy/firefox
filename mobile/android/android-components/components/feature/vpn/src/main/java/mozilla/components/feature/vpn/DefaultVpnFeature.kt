/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.flow
import mozilla.components.service.fxa.manager.FxaAccountManager
import mozilla.components.support.base.feature.LifecycleAwareFeature
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.IPProtectionController

private const val TAG = "VPN_ENROLL"
private const val VPN_TOKEN_SCOPE = "https://identity.mozilla.com/apps/vpn"

/**
 * AC feature that brings VPN proxy functionality to Android.
 *
 * @param controller [IPProtectionController] a bridge to [org.mozilla.geckoview.GeckoView].
 * @param accountManager [FxaAccountManager] used to supply FxA tokens to the proxy Guardian.
 * @param store [VpnStore] holds the feature state.
 * @param browserStore [BrowserStore] to observe enrollment tab URL changes.
 * @param tabsUseCases [TabsUseCases] to open/remove the enrollment tab.
 */
class DefaultVpnFeature(
    private val controller: IPProtectionController,
    private val accountManager: FxaAccountManager,
    private val store: VpnStore,
    private val browserStore: BrowserStore,
    private val tabsUseCases: TabsUseCases,
) : LifecycleAwareFeature, VpnFeature {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var enrollmentTabId: String? = null
    private var enrollmentObservationJob: Job? = null

    private val accountObserver = object : AccountObserver {
        override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
            // FxaAccountManager callbacks fire on a background thread; IPProtectionController
            // requires the UI thread, so dispatch via the main-thread scope.
            scope.launch { setTokenProvider(account) }
        }

        override fun onLoggedOut() {
            scope.launch { controller.setTokenProvider(null) }
        }

        override fun onAuthenticationProblems() {
            scope.launch { controller.setTokenProvider(null) }
        }
    }

    override fun start() {
        controller.delegate = object : IPProtectionController.Delegate {
            override fun onStateChanged(info: IPProtectionController.StateInfo) {
                val state = info.toVpnState()
                Log.d(TAG, "onStateChanged: proxyState=${info.proxyState} serviceState=${info.serviceState}" +
                    " remaining=${info.remaining} max=${info.max} resetTime=${info.resetTime}" +
                    " lastError=${info.lastError} → vpnStatus=${state.vpnStatus}" +
                    " isEnrollmentNeeded=${state.isEnrollmentNeeded}")
                store.dispatch(VpnAction.UpdateState(state))
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

    override fun stop() {
        accountManager.unregister(accountObserver)
        controller.delegate = null
        controller.setTokenProvider(null)
        cancelEnrollment()
    }

    /** Activates the VPN proxy. */
    override fun activate() { controller.activate() }

    /** Deactivates the VPN proxy. */
    override fun deactivate() { controller.deactivate() }

    /** Starts authorizing vpn service */
    override fun beginEnrollment() {
        cancelEnrollment()
        Log.d(TAG, "beginEnrollment: opening background tab → $GUARDIAN_ENROLLMENT_URL")
        val tabId = tabsUseCases.addTab(url = GUARDIAN_ENROLLMENT_URL, selectTab = false)
        enrollmentTabId = tabId
        observeEnrollmentTab(tabId)
    }


    private fun cancelEnrollment() {
        val tabId = enrollmentTabId ?: return
        enrollmentObservationJob?.cancel()
        enrollmentObservationJob = null
        tabsUseCases.removeTab(tabId)
        enrollmentTabId = null
    }

    private fun observeEnrollmentTab(tabId: String) {
        enrollmentObservationJob = scope.launch {
            browserStore.flow()
                .mapNotNull { state -> state.findTab(tabId)?.content?.url }
                .distinctUntilChanged()
                .collect { url ->
                    Log.d(TAG, "enrollment tab navigated → $url")
                    when {
                        url.startsWith(GUARDIAN_SUCCESS_URL) -> onEnrollmentSuccess(tabId)
                        url.startsWith(GUARDIAN_ERROR_URL) -> onEnrollmentError(tabId)
                    }
                }
        }
    }

    private fun onEnrollmentSuccess(tabId: String) {
        Log.d(TAG, "onEnrollmentSuccess: closing tab and triggering entitlement check")
        enrollmentObservationJob?.cancel()
        enrollmentObservationJob = null
        enrollmentTabId = null
        tabsUseCases.removeTab(tabId)

        // Re-fire the token provider to trigger a fresh updateEntitlement() in Gecko JS.
        retriggerEnrollment()

        scope.launch {
            val currentStatus = store.state.vpnStatus
            if (currentStatus == VpnStatus.Ready) {
                Log.d(TAG, "onEnrollmentSuccess: already Ready — activating")
                activate()
                return@launch
            }
            store.flow()
                .map { it.vpnStatus }
                .filter { it == VpnStatus.Ready }
                .first()
            Log.d(TAG, "onEnrollmentSuccess: proxy reached Ready — activating")
            activate()
        }
    }

    private fun onEnrollmentError(tabId: String) {
        Log.w(TAG, "onEnrollmentError: Guardian enrollment failed")
        enrollmentObservationJob?.cancel()
        enrollmentObservationJob = null
        enrollmentTabId = null
        tabsUseCases.removeTab(tabId)
    }


    /** Deactivates the VPN proxy. */
    override fun retriggerEnrollment() {
        val account = accountManager.authenticatedAccount() ?: return
        Log.d(TAG, "retriggerEnrollment: re-firing token provider")
        setTokenProvider(account)
    }

    private fun setTokenProvider(account: OAuthAccount) {
        controller.setTokenProvider {
            val result = GeckoResult<String>()
            scope.launch {
                val tokenInfo = withContext(Dispatchers.IO) {
                    runCatching { account.getAccessToken(VPN_TOKEN_SCOPE) }.getOrNull()
                }
                println("$this Spamming token!")
                result.complete(tokenInfo?.token)
            }
            result
        }.accept { info ->
            info?.let {
                val state = it.toVpnState()
                Log.d(TAG, "setTokenProvider result: proxyState=${it.proxyState} serviceState=${it.serviceState}" +
                    " remaining=${it.remaining} max=${it.max} → vpnStatus=${state.vpnStatus}" +
                    " isEnrollmentNeeded=${state.isEnrollmentNeeded}")
                store.dispatch(VpnAction.UpdateState(state))
            }
        }
    }

    private fun IPProtectionController.StateInfo.toVpnState() = VpnState(
        vpnStatus = proxyStateToVpnStatus(proxyState),
        dataRemainingBytes = remaining,
        dataMaxBytes = max,
        resetDate = resetTime,
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

    companion object {
        const val GUARDIAN_ENROLLMENT_URL = "https://vpn.mozilla.org/api/v1/fpn/auth?experiment=alpha"
        const val GUARDIAN_SUCCESS_URL = "https://vpn.mozilla.org/oauth/success"
        const val GUARDIAN_ERROR_URL = "https://vpn.mozilla.org/api/v1/fpn/error"
    }
}
