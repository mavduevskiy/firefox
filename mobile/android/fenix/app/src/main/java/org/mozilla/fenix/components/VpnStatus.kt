/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

/** Represents the possible states of the VPN proxy. */
enum class VpnStatus {
    /** The proxy is active and routing traffic. */
    Active,

    /** The proxy is in the process of connecting. */
    Activating,

    /** The proxy is authenticated and ready to be activated. */
    Ready,

    /** The proxy is paused, typically because the monthly data quota has been exhausted. */
    Paused,

    /** The proxy encountered an error and could not activate or stay active. */
    Error,

    /** The proxy is not available, e.g. the user is not signed in or not eligible. */
    NotAvailable,
}
