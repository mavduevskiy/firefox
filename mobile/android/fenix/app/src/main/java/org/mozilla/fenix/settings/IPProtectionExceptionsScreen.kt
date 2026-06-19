/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.annotation.FlexibleWindowPreview
import mozilla.components.compose.base.button.FilledButton
import mozilla.components.compose.base.button.FloatingActionButton
import mozilla.components.compose.base.button.IconButton
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.FaviconListItem
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.PreviewThemeProvider
import org.mozilla.fenix.theme.Theme
import mozilla.components.ui.icons.R as iconsR

/**
 * Screen that lets the user manage the websites for which the built-in VPN is disabled.
 *
 * @param exceptions Origins for which the proxy is disabled.
 * @param onAddClick Called when the add-website action is tapped.
 * @param onRemoveException Called with the origin to remove from the exceptions.
 * @param onClearAll Called when the user chooses to re-enable the VPN for all sites.
 * @param onNavigateBack Called when the back navigation icon is tapped.
 */
@Composable
fun IPProtectionExceptionsScreen(
    exceptions: List<String>,
    onAddClick: () -> Unit,
    onRemoveException: (String) -> Unit,
    onClearAll: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            IPProtectionExceptionsAppBar(
                onNavigateBack = onNavigateBack,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                icon = painterResource(iconsR.drawable.mozac_ic_plus_24),
                contentDescription = stringResource(
                    R.string.ip_protection_exceptions_add_button_content_description,
                ),
                onClick = onAddClick,
            )
        },
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
                Text(
                    text = stringResource(R.string.ip_protection_exceptions_header),
                    style = FirefoxTheme.typography.headline8,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        horizontal = FirefoxTheme.layout.space.dynamic200,
                        vertical = FirefoxTheme.layout.space.static150,
                    ),
                )

                exceptions.forEach { origin ->
                    FaviconListItem(
                        label = origin,
                        url = origin,
                        iconPainter = painterResource(iconsR.drawable.mozac_ic_cross_24),
                        iconDescription = stringResource(
                            R.string.ip_protection_exceptions_remove_button_content_description,
                        ),
                        onIconClick = { onRemoveException(origin) },
                    )
                }

                if (exceptions.isNotEmpty()) {
                    FilledButton(
                        text = stringResource(R.string.ip_protection_exceptions_clear_all_button),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = FirefoxTheme.layout.space.dynamic200,
                                vertical = FirefoxTheme.layout.space.static150,
                            ),
                        onClick = onClearAll,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IPProtectionExceptionsAppBar(
    onNavigateBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.ip_protection_exceptions_title),
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

@FlexibleWindowPreview
@Composable
private fun IPProtectionExceptionsScreenPreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        IPProtectionExceptionsScreen(
            exceptions = listOf(
                "https://www.example.com",
                "https://mozilla.org",
            ),
            onAddClick = {},
            onRemoveException = {},
            onClearAll = {},
            onNavigateBack = {},
        )
    }
}

@FlexibleWindowPreview
@Composable
private fun IPProtectionExceptionsScreenEmptyPreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        IPProtectionExceptionsScreen(
            exceptions = emptyList(),
            onAddClick = {},
            onRemoveException = {},
            onClearAll = {},
            onNavigateBack = {},
        )
    }
}
