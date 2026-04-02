/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.concept.engine.ipprotection

/**
 * Engine-to-app callbacks for IP protection state changes. Passed to
 * [mozilla.components.concept.engine.Engine.registerIPProtectionDelegate].
 */
interface IPProtectionDelegate {
    fun onStateChanged(info: IPProtectionHandler.StateInfo) {}
}
