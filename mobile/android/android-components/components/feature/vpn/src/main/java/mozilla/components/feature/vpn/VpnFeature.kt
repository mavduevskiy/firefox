/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.vpn

/**
 * Public interface for the VPN feature.
 */
interface VpnFeature {
    /** Activates the VPN proxy */
    fun activate()

    /** Deactivates the VPN proxy */
    fun deactivate()

    /** Starts the enrollment for eligible users  */
    fun beginEnrollment()

    /**
     * Re-fires the token provider to trigger a fresh Guardian entitlement check.
     * Called after the enrollment tab completes successfully.
     *
     * Might need to remove it; might have added it as a workaround for not
     * very straightforward bridge to the toolkit code.
     */
    fun retriggerEnrollment()
}
