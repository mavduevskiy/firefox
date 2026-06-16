/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.concept.engine.ipprotection

import mozilla.components.ExperimentalAndroidComponentsApi
import mozilla.components.concept.engine.Engine

/**
 * Engine-to-app callbacks for IP protection state changes. Passed to [Engine.registerIPProtectionDelegate].
 */
@ExperimentalAndroidComponentsApi
interface IPProtectionDelegate {
    /**
     * Called when the IP protection proxy state changes.
     *
     * @param info The current [IPProtectionHandler.StateInfo].
     */
    fun onStateChanged(info: IPProtectionHandler.StateInfo)

    /**
     * Called when the set of selectable locations or the current selection changes. Also called
     * once after initialization with the initial value.
     *
     * @param locations The selectable egress locations.
     * @param selected The selected ISO 3166-1 alpha-2 country code, or null when the recommended
     *  (automatically selected) location is in use.
     */
    fun onLocationsChanged(locations: List<Location>, selected: String?) = Unit
}
