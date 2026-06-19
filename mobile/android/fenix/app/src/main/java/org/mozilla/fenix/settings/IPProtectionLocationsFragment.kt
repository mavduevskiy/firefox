/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.navigation.fragment.findNavController
import mozilla.components.ExperimentalAndroidComponentsApi
import mozilla.components.concept.engine.ipprotection.IPProtectionHandler
import mozilla.components.feature.ipprotection.store.IPProtectionAction
import mozilla.components.feature.ipprotection.store.state.Authorized
import mozilla.components.feature.ipprotection.store.state.IPProtectionState
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.components.components
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.hideToolbar
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme

/** Fragment hosting the IP Protection location chooser screen. */
class IPProtectionLocationsFragment : Fragment(), SystemInsetsPaddedFragment {

    @OptIn(ExperimentalAndroidComponentsApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        val state = components.ipProtection.store.observeAsComposableState { it }.value

        var countries by remember { mutableStateOf(emptyList<IPProtectionHandler.Country>()) }
        LaunchedEffect(Unit) {
            requireComponents.ipProtection.feature.getServerList { countries = it }
        }

        FirefoxTheme {
            IPProtectionLocationsScreen(
                countries = countries,
                selectedCountryCode = state.selectedCountryCode,
                onLocationSelected = { code -> selectLocation(state, code) },
                onNavigateBack = { findNavController().popBackStack() },
            )
        }
    }

    @OptIn(ExperimentalAndroidComponentsApi::class)
    private fun selectLocation(state: IPProtectionState, code: String?) {
        requireComponents.ipProtection.store.dispatch(IPProtectionAction.LocationSelected(code))
        // When already active, selecting a location switches the connection live.
        if (state.proxyStatus is Authorized.Active) {
            requireComponents.ipProtection.feature.switchTo(
                code?.let { IPProtectionHandler.Country(code = it, available = true) },
            )
        }
        findNavController().popBackStack()
    }

    override fun onResume() {
        super.onResume()
        hideToolbar()
    }
}
