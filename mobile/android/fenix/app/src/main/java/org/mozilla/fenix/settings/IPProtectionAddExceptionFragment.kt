/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.navigation.fragment.findNavController
import mozilla.components.ExperimentalAndroidComponentsApi
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.hideToolbar
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme

/** Fragment hosting the screen for manually adding an IP Protection site exception. */
class IPProtectionAddExceptionFragment : Fragment(), SystemInsetsPaddedFragment {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        FirefoxTheme {
            IPProtectionAddExceptionScreen(
                onAddException = { input -> addException(input) },
                onNavigateBack = { findNavController().popBackStack() },
            )
        }
    }

    @OptIn(ExperimentalAndroidComponentsApi::class)
    private fun addException(input: String) {
        // Minimal normalization: the bridge resolves this via Services.io.newURI, which needs a
        // scheme. This entry point is for testing, so we keep validation light.
        val url = if (input.contains("://")) input else "https://$input"
        requireComponents.ipProtection.feature.addException(url)
        findNavController().popBackStack()
    }

    override fun onResume() {
        super.onResume()
        hideToolbar()
    }
}
