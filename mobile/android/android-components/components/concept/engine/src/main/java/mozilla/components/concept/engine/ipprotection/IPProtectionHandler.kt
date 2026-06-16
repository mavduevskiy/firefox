/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.concept.engine.ipprotection

import mozilla.components.ExperimentalAndroidComponentsApi

/**
 * App-to-engine handle for controlling the IP protection proxy. Returned by
 * [mozilla.components.concept.engine.Engine.registerIPProtectionDelegate].
 */
@ExperimentalAndroidComponentsApi
interface IPProtectionHandler {

    /**
     * Activates the IP protection.
     *
     * @param onResult Invoked once the activation request resolves. Receives `null` on success or
     *  the [Throwable] that caused the failure.
     */
    fun activate(onResult: (Throwable?) -> Unit = {})

    /**
     * Deactivates the IP protection proxy.
     *
     * @param onResult Invoked once the deactivation request resolves. Receives `null` on success or
     *  the [Throwable] that caused the failure.
     */
    fun deactivate(onResult: (Throwable?) -> Unit = {})

    /**
     * Triggers enrollment via the active auth provider. The [onResult] callback is invoked once
     * the enrollment attempt has completed, with the final outcome.
     *
     * @param onResult Called with the [EnrollResult] describing whether the user is now enrolled
     *  and entitled, and the error string if not.
     */
    fun enroll(onResult: (EnrollResult) -> Unit)

    /**
     * Request for the current [ServiceState].
     */
    fun getState(onResult: (ServiceState) -> Unit)

    /**
     * Initializes the proxy state machine.
     */
    fun init()

    /**
     * Uninitializes the proxy state machine.
     */
    fun uninit()

    /**
     * Sets the [AuthProvider] used to supply authentication tokens to the IP protection service.
     * Pass null to sign out.
     *
     * @param provider The [AuthProvider], or null to deauthenticate.
     */
    // FIXME(IPP) move this to the IPProtectionDelegate.
    fun setAuthProvider(
        provider: AuthProvider?,
    )

    /**
     * Selects the egress [Location] to route through. The selection is persisted by the engine
     * and applied immediately when the proxy is active, otherwise on the next [activate]. The
     * resulting selection is reported back through [IPProtectionDelegate.onLocationsChanged].
     *
     * @param code An ISO 3166-1 alpha-2 country code (see [Location.code]), or null to use the
     *  recommended (automatically selected) location.
     */
    fun setLocation(code: String?)

    /**
     * Result of an enrollment attempt.
     *
     * @property isEnrolledAndEntitled Whether the user is now enrolled and entitled to use the
     *  proxy.
     * @property error Error string describing why enrollment failed, or null on success.
     */
    data class EnrollResult(
        val isEnrolledAndEntitled: Boolean,
        val error: String? = null,
    )

    /**
     * Notify account state changed.
     */
    fun notifyAccountStatus(signedIn: Boolean)

    /**
     * Provides a fresh authentication token on demand. Invoked each time the engine needs to
     * authenticate with the Guardian API.
     */
    interface AuthProvider {
        /**
         * Fetches a fresh authentication token and delivers it via [onComplete].
         * Pass null to [onComplete] if the token could not be obtained.
         */
        fun getToken(onComplete: (String?) -> Unit)
    }

    /**
     * Holds the current IP protection service and proxy state along with usage information.
     */
    // refactor to enum in https://bugzilla.mozilla.org/show_bug.cgi?id=2030410
    data class StateInfo(
        val serviceState: ServiceState = ServiceState.Uninitialized,
        val proxyState: Int = PROXY_STATE_NOT_READY,
        val lastError: String? = null,
        val remaining: Long = -1L,
        val max: Long = -1L,
        val resetTime: String? = null,
    ) {
        val isEnrollmentNeeded: Boolean
            get() = serviceState == ServiceState.Unauthenticated

        companion object {
            const val PROXY_STATE_NOT_READY = 0
            const val PROXY_STATE_READY = 1
            const val PROXY_STATE_ACTIVATING = 2
            const val PROXY_STATE_ACTIVE = 3
            const val PROXY_STATE_ERROR = 4
            const val PROXY_STATE_PAUSED = 5
        }

        override fun toString(): String {
            val proxy = when (proxyState) {
                PROXY_STATE_NOT_READY -> "NOT_READY"
                PROXY_STATE_READY -> "READY"
                PROXY_STATE_ACTIVATING -> "ACTIVATING"
                PROXY_STATE_ACTIVE -> "ACTIVE"
                PROXY_STATE_ERROR -> "ERROR"
                PROXY_STATE_PAUSED -> "PAUSED"
                else -> "UNKNOWN($proxyState)"
            }
            return "StateInfo(serviceState=$serviceState, proxyState=$proxy," +
                " remaining=$remaining, max=$max, resetTime=$resetTime," +
                " lastError=$lastError)"
        }
    }
}

/**
 * A VPN egress location the user can select.
 *
 * @property code ISO 3166-1 alpha-2 country code.
 * @property available Whether this location currently has at least one available server.
 */
@ExperimentalAndroidComponentsApi
data class Location(
    val code: String,
    val available: Boolean,
)

/** The possible states of the IP protection service. */
@ExperimentalAndroidComponentsApi
enum class ServiceState {

    /** The service has not been initialized yet. */
    Uninitialized,

    /** The user is not eligible or still not signed in. */
    Unavailable,

    /** The user is signed out but eligible. */
    Unauthenticated,

    /** The user has opted out from using VPN. */
    OptedOut,

    /** The service is ready to be activated. */
    Ready,
}
