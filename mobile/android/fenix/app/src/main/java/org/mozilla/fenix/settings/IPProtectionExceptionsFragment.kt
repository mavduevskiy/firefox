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
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.hideToolbar
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme

/** Fragment hosting the IP Protection site exceptions ("Manage website settings") screen. */
class IPProtectionExceptionsFragment : Fragment(), SystemInsetsPaddedFragment {

    @OptIn(ExperimentalAndroidComponentsApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        var exceptions by remember { mutableStateOf(emptyList<String>()) }

        fun reload() {
            requireComponents.ipProtection.feature.getExceptions { exceptions = it }
        }

        LaunchedEffect(Unit) { reload() }

        FirefoxTheme {
            IPProtectionExceptionsScreen(
                exceptions = exceptions,
                onAddClick = {
                    findNavController().navigate(
                        IPProtectionExceptionsFragmentDirections
                            .actionIpProtectionExceptionsFragmentToIpProtectionAddExceptionFragment(),
                    )
                },
                onRemoveException = { origin ->
                    requireComponents.ipProtection.feature.removeException(origin) { reload() }
                },
                onClearAll = {
                    requireComponents.ipProtection.feature.clearExceptions { reload() }
                },
                onNavigateBack = { findNavController().popBackStack() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hideToolbar()
    }
}
