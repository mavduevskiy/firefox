/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

/**
 * Holds all VPN-related state surfaced by [IPProtectionController] and kept in [AppStore]
 * as the single source of truth for the whole app.
 *
 * @property vpnStatus Current connection state of the VPN proxy.
 * @property dataRemainingBytes Remaining monthly data allowance in bytes, or -1 if unavailable.
 * @property dataMaxBytes Maximum monthly data allowance in bytes, or -1 if unavailable.
 * @property resetDate ISO 8601 string for when the monthly allowance resets, or null if unavailable.
 */
data class VpnState(
    val vpnStatus: VpnStatus = VpnStatus.NotAvailable,
    val dataRemainingBytes: Long = -1L,
    val dataMaxBytes: Long = -1L,
    val resetDate: String? = null,
)
