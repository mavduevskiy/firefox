/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.annotation.FlexibleWindowPreview
import mozilla.components.compose.base.button.FilledButton
import mozilla.components.compose.base.button.IconButton
import mozilla.components.compose.base.textfield.TextField
import org.mozilla.fenix.R
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.PreviewThemeProvider
import org.mozilla.fenix.theme.Theme
import mozilla.components.ui.icons.R as iconsR

/**
 * Screen that lets the user manually add a website to the built-in VPN exceptions.
 *
 * @param onAddException Called with the user-entered website when the add action is triggered.
 * @param onNavigateBack Called when the back navigation icon is tapped.
 */
@Composable
fun IPProtectionAddExceptionScreen(
    onAddException: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = { IPProtectionAddExceptionAppBar(onNavigateBack = onNavigateBack) },
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(FirefoxTheme.layout.space.dynamic200),
                verticalArrangement = Arrangement.spacedBy(FirefoxTheme.layout.space.static150),
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = stringResource(R.string.ip_protection_add_exception_hint),
                    errorText = "",
                    label = stringResource(R.string.ip_protection_add_exception_label),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (text.isNotBlank()) onAddException(text.trim()) },
                    ),
                )

                FilledButton(
                    text = stringResource(R.string.ip_protection_add_exception_button),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = text.isNotBlank(),
                    onClick = { onAddException(text.trim()) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IPProtectionAddExceptionAppBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.ip_protection_add_exception_title),
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
private fun IPProtectionAddExceptionScreenPreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        IPProtectionAddExceptionScreen(
            onAddException = {},
            onNavigateBack = {},
        )
    }
}
