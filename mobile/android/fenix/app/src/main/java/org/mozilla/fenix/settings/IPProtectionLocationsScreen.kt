/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import mozilla.components.ExperimentalAndroidComponentsApi
import mozilla.components.compose.base.annotation.FlexibleWindowPreview
import mozilla.components.compose.base.button.IconButton
import mozilla.components.concept.engine.ipprotection.IPProtectionHandler
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.TextListItem
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.PreviewThemeProvider
import org.mozilla.fenix.theme.Theme
import java.text.Collator
import mozilla.components.ui.icons.R as iconsR

/**
 * Screen that lets the user choose the VPN egress location.
 *
 * @param countries Countries available in the proxy serverlist.
 * @param selectedCountryCode ISO 3166-1 alpha-2 code of the selected location, or null for the
 * recommended location.
 * @param onLocationSelected Called with the chosen country code, or null for the recommended
 * location.
 * @param onNavigateBack Called when the back navigation icon is tapped.
 */
@OptIn(ExperimentalAndroidComponentsApi::class)
@Composable
fun IPProtectionLocationsScreen(
    countries: List<IPProtectionHandler.Country>,
    selectedCountryCode: String?,
    onLocationSelected: (String?) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val sortedCountries = remember(countries) {
        val collator = Collator.getInstance()
        countries.sortedWith { first, second ->
            collator.compare(localizedCountryName(first.code), localizedCountryName(second.code))
        }
    }

    Scaffold(
        topBar = { IPProtectionLocationsAppBar(onNavigateBack = onNavigateBack) },
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                IconListItem(
                    label = stringResource(R.string.ip_protection_location_recommended_label),
                    description = stringResource(R.string.ip_protection_location_recommended_description),
                    maxDescriptionLines = Int.MAX_VALUE,
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_globe_24),
                    afterIconPainter = if (selectedCountryCode == null) {
                        painterResource(iconsR.drawable.mozac_ic_checkmark_24)
                    } else {
                        null
                    },
                    onClick = { onLocationSelected(null) },
                )

                Text(
                    text = stringResource(R.string.ip_protection_locations_header),
                    style = FirefoxTheme.typography.headline8,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        horizontal = FirefoxTheme.layout.space.dynamic200,
                        vertical = FirefoxTheme.layout.space.static150,
                    ),
                )

                sortedCountries.forEach { country ->
                    TextListItem(
                        label = localizedCountryName(country.code),
                        enabled = country.available,
                        onClick = { onLocationSelected(country.code) },
                        iconPainter = if (country.code == selectedCountryCode) {
                            painterResource(iconsR.drawable.mozac_ic_checkmark_24)
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IPProtectionLocationsAppBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.ip_protection_locations_title),
                style = FirefoxTheme.typography.headline5,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onNavigateBack,
                contentDescription = stringResource(
                    R.string.ip_protection_navigate_back_button_content_description,
                ),
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_back_24),
                    contentDescription = null,
                )
            }
        },
        windowInsets = WindowInsets(top = 0.dp, bottom = 0.dp),
    )
}

@OptIn(ExperimentalAndroidComponentsApi::class)
@FlexibleWindowPreview
@Composable
private fun IPProtectionLocationsScreenPreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        IPProtectionLocationsScreen(
            countries = listOf(
                IPProtectionHandler.Country(code = "DK", available = true),
                IPProtectionHandler.Country(code = "FR", available = true),
                IPProtectionHandler.Country(code = "GB", available = true),
                IPProtectionHandler.Country(code = "US", available = false),
            ),
            selectedCountryCode = "FR",
            onLocationSelected = {},
            onNavigateBack = {},
        )
    }
}
