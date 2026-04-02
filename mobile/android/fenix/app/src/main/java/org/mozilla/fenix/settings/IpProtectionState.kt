/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import mozilla.components.feature.vpn.VpnStatus

/**
 * UI state for the VPN / IP Protection settings screen.
 *
 * @property vpnStatus Current [VpnStatus] of the VPN proxy.
 * @property dataRemainingBytes Remaining monthly data allowance in bytes, or -1 if unavailable.
 * @property dataMaxBytes Maximum monthly data allowance in bytes, or -1 if unavailable.
 * @property resetDate ISO 8601 string for when the monthly allowance resets, or null if unavailable.
 * @property isAutoLocation Whether automatic server location selection is enabled.
 * @property selectedLocation Display name of the currently selected VPN server location.
 * @property isEnrollmentNeeded True when the user is signed in but Guardian has not yet enrolled
 */
data class IpProtectionState(
    val vpnStatus: VpnStatus = VpnStatus.NotAvailable,
    val dataRemainingBytes: Long = -1L,
    val dataMaxBytes: Long = -1L,
    val resetDate: String? = null,
    val isAutoLocation: Boolean = true,
    val selectedLocation: String = "United States",
    val isEnrollmentNeeded: Boolean = false,
)
