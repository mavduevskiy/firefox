/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import mozilla.components.ExperimentalAndroidComponentsApi
import mozilla.components.compose.base.Switch
import mozilla.components.compose.base.annotation.FlexibleWindowPreview
import mozilla.components.compose.base.button.IconButton
import mozilla.components.concept.engine.ipprotection.Location
import org.mozilla.fenix.R
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.PreviewThemeProvider
import org.mozilla.fenix.theme.Theme
import java.util.Locale
import mozilla.components.ui.icons.R as iconsR

private const val DISABLED_ALPHA = 0.38f
private val FLAG_WIDTH = 28.dp
private val FLAG_HEIGHT = 20.dp

/**
 * Full-screen picker for choosing the VPN egress location.
 *
 * @param locations The selectable countries reported by the engine.
 * @param selectedLocation The currently selected ISO 3166-1 alpha-2 country code, or null for the
 * recommended (automatically selected) location.
 * @param onLocationSelected Called with the chosen country code, or null when the recommended
 * location is selected.
 * @param onNavigateBack Called when the back navigation icon is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAndroidComponentsApi::class)
@Composable
fun IPProtectionLocationScreen(
    locations: List<Location>,
    selectedLocation: String?,
    onLocationSelected: (String?) -> Unit,
    onNavigateBack: () -> Unit,
) {
    // Whether the recommended location is in use. Local UI state: turning the switch off enables
    // the country list without changing the selection until the user taps a country.
    var recommendedMode by rememberSaveable { mutableStateOf(selectedLocation == null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.ip_protection_location_picker_title),
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
                windowInsets = WindowInsets(
                    top = 0.dp,
                    bottom = 0.dp,
                ),
            )
        },
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.surface,
        ) {
            LazyColumn {
                item {
                    RecommendedRow(
                        checked = recommendedMode,
                        onCheckedChange = { checked ->
                            recommendedMode = checked
                            if (checked) {
                                onLocationSelected(null)
                            }
                        },
                    )

                    HorizontalDivider()

                    Text(
                        text = stringResource(R.string.ip_protection_location_list_header),
                        style = FirefoxTheme.typography.headline8,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            horizontal = FirefoxTheme.layout.space.dynamic200,
                            vertical = FirefoxTheme.layout.space.static150,
                        ),
                    )
                }

                items(locations) { location ->
                    LocationRow(
                        location = location,
                        selected = !recommendedMode && location.code == selectedLocation,
                        enabled = !recommendedMode,
                        onClick = {
                            recommendedMode = false
                            onLocationSelected(location.code)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendedRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(
                horizontal = FirefoxTheme.layout.space.dynamic200,
                vertical = FirefoxTheme.layout.space.static150,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FirefoxTheme.layout.space.static200),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ip_protection_location_recommended_label),
                style = FirefoxTheme.typography.subtitle1,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.ip_protection_location_recommended_description),
                style = FirefoxTheme.typography.body2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@OptIn(ExperimentalAndroidComponentsApi::class)
@Composable
private fun LocationRow(
    location: Location,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val rowEnabled = enabled && location.available
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(
                enabled = rowEnabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .alpha(if (rowEnabled) 1f else DISABLED_ALPHA)
            .padding(
                horizontal = FirefoxTheme.layout.space.dynamic200,
                vertical = FirefoxTheme.layout.space.static150,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FirefoxTheme.layout.space.static200),
    ) {
        CountryFlag(
            code = location.code,
            modifier = Modifier.size(width = FLAG_WIDTH, height = FLAG_HEIGHT),
        )
        Text(
            text = countryDisplayName(location.code),
            modifier = Modifier.weight(1f),
            style = FirefoxTheme.typography.subtitle1,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_checkmark_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Renders the flag for [code], falling back to an empty placeholder when no matching drawable
 * exists.
 */
@Composable
internal fun CountryFlag(
    code: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resId = remember(code) { flagResourceFor(context, code) }
    val shape = MaterialTheme.shapes.extraSmall

    if (resId == 0) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        return
    }

    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = modifier.clip(shape),
    )
}

/**
 * Resolves the `flag_<iso2>` drawable for [code], or 0 when none is bundled.
 */
@SuppressLint("DiscouragedApi")
internal fun flagResourceFor(context: Context, code: String): Int =
    context.resources.getIdentifier(
        "flag_${code.lowercase(Locale.US)}",
        "drawable",
        context.packageName,
    )

/**
 * Localized display name for an ISO 3166-1 alpha-2 country [code], falling back to the raw code.
 */
internal fun countryDisplayName(code: String): String =
    runCatching {
        Locale.Builder().setRegion(code).build().displayCountry
    }.getOrNull()?.ifEmpty { code } ?: code

@OptIn(ExperimentalAndroidComponentsApi::class)
@FlexibleWindowPreview
@Composable
private fun IPProtectionLocationScreenPreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        IPProtectionLocationScreen(
            locations = listOf(
                Location(code = "US", available = true),
                Location(code = "GB", available = true),
                Location(code = "FR", available = false),
                Location(code = "DK", available = true),
            ),
            selectedLocation = "GB",
            onLocationSelected = {},
            onNavigateBack = {},
        )
    }
}
