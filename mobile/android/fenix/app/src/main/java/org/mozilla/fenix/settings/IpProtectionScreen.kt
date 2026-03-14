/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.annotation.FlexibleWindowPreview
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.compose.list.TextListItem
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.PreviewThemeProvider
import org.mozilla.fenix.theme.Theme
import mozilla.components.ui.icons.R as iconsR

private const val BYTES_PER_GIB = 1_073_741_824f

private val PROMO_CARD_CORNER_RADIUS = 28.dp
private val PROMO_ILLUSTRATION_SIZE = 60.dp

private fun VpnStatus.isSwitchChecked() = this == VpnStatus.Active || this == VpnStatus.Activating

private fun VpnStatus.isToggleEnabled() = this == VpnStatus.Active || this == VpnStatus.Activating ||
    this == VpnStatus.Ready || this == VpnStatus.Error

private fun VpnStatus.useColorfulIllustration() = this == VpnStatus.Active || this == VpnStatus.Activating

/**
 * The main VPN / IP Protection settings screen.
 *
 * @param state Current [IpProtectionState] to render.
 * @param onVpnToggle Called when the VPN switch is toggled.
 * @param onLearnMoreClick Called when any "Learn more" link is tapped.
 * @param onAutoLocationToggle Called when the automatic location switch is toggled.
 * @param onLocationClick Called when the selected location row is tapped.
 * @param onManageWebsiteSettingsClick Called when "Manage website settings" is tapped.
 */
@Composable
fun IpProtectionScreen(
    state: IpProtectionState,
    onVpnToggle: (Boolean) -> Unit,
    onLearnMoreClick: () -> Unit,
    onAutoLocationToggle: (Boolean) -> Unit,
    onLocationClick: () -> Unit,
    onManageWebsiteSettingsClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(FirefoxTheme.layout.space.static100))

            VpnPromoCard(
                isActive = state.vpnStatus.useColorfulIllustration(),
                onLearnMoreClick = onLearnMoreClick,
                modifier = Modifier.padding(horizontal = FirefoxTheme.layout.space.dynamic200),
            )

            Spacer(modifier = Modifier.height(FirefoxTheme.layout.space.static100))

            VpnToggleRow(
                checked = state.vpnStatus.isSwitchChecked(),
                enabled = state.vpnStatus.isToggleEnabled(),
                onToggle = onVpnToggle,
            )

            HorizontalDivider()

            VpnDataSection(state = state, onLearnMoreClick = onLearnMoreClick)

            HorizontalDivider()

            VpnLocationSection(
                state = state,
                onAutoLocationToggle = onAutoLocationToggle,
                onLocationClick = onLocationClick,
            )

            HorizontalDivider()

            TextListItem(
                label = stringResource(R.string.ip_protection_manage_website_settings),
                onClick = onManageWebsiteSettingsClick,
            )

            Spacer(modifier = Modifier.height(FirefoxTheme.layout.space.static200))
        }
    }
}

@Composable
private fun VpnDataSection(state: IpProtectionState, onLearnMoreClick: () -> Unit) {
    TextListItem(
        label = stringResource(R.string.ip_protection_data_limit_label),
        description = dataLimitDescription(state, stringResource(R.string.ip_protection_data_limit_value)),
    )

    LinearProgressIndicator(
        progress = { dataProgress(state) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FirefoxTheme.layout.space.dynamic200)
            .clip(RoundedCornerShape(percent = 50)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
    )

    Spacer(modifier = Modifier.height(FirefoxTheme.layout.space.static100))

    val linkColor = MaterialTheme.colorScheme.tertiary
    Text(
        text = buildAnnotatedString {
            append(stringResource(R.string.ip_protection_data_reset_info))
            append(" ")
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                append(stringResource(R.string.ip_protection_learn_more))
            }
        },
        style = FirefoxTheme.typography.body2,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLearnMoreClick() }
            .padding(
                horizontal = FirefoxTheme.layout.space.dynamic200,
                vertical = FirefoxTheme.layout.space.static150,
            ),
    )
}

@Composable
private fun VpnLocationSection(
    state: IpProtectionState,
    onAutoLocationToggle: (Boolean) -> Unit,
    onLocationClick: () -> Unit,
) {
    Text(
        text = stringResource(R.string.ip_protection_location_section),
        style = FirefoxTheme.typography.headline8,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(
            horizontal = FirefoxTheme.layout.space.dynamic200,
            vertical = FirefoxTheme.layout.space.static150,
        ),
    )

    SwitchListItem(
        label = stringResource(R.string.ip_protection_location_automatic_label),
        description = stringResource(R.string.ip_protection_location_automatic_description),
        maxDescriptionLines = 3,
        checked = state.isAutoLocation,
        showSwitchAfter = true,
        onClick = onAutoLocationToggle,
    )

    // Flag emoji placeholder — replace with a proper drawable when location selection is implemented
    TextListItem(
        label = "\uD83C\uDDFA\uD83C\uDDF8  ${state.selectedLocation}",
        onClick = onLocationClick,
    )
}

@Composable
private fun VpnToggleRow(
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .padding(
                horizontal = FirefoxTheme.layout.space.dynamic200,
                vertical = FirefoxTheme.layout.space.static150,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FirefoxTheme.layout.space.static200),
    ) {
        val contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
        Icon(
            painter = painterResource(iconsR.drawable.mozac_ic_globe_24),
            contentDescription = null,
            tint = contentColor,
        )
        Text(
            text = stringResource(R.string.ip_protection_toggle_label),
            modifier = Modifier.weight(1f),
            style = FirefoxTheme.typography.subtitle1,
            color = contentColor,
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
private fun VpnPromoCard(
    isActive: Boolean,
    onLearnMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(PROMO_CARD_CORNER_RADIUS),
    ) {
        Row(
            modifier = Modifier.padding(FirefoxTheme.layout.space.static200),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ip_protection_promo_headline),
                    style = FirefoxTheme.typography.headline7,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(FirefoxTheme.layout.space.static50))

                val linkColor = MaterialTheme.colorScheme.tertiary
                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.ip_protection_promo_body, stringResource(R.string.app_name)))
                        append(" ")
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(stringResource(R.string.ip_protection_learn_more))
                        }
                    },
                    style = FirefoxTheme.typography.body2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onLearnMoreClick() },
                )
            }

            Spacer(modifier = Modifier.width(FirefoxTheme.layout.space.static200))

            Icon(
                painter = painterResource(
                    if (isActive) {
                        R.drawable.ic_kit_shield_on_state
                    } else {
                        R.drawable.ic_kit_shield_off_state
                    },
                ),
                contentDescription = null,
                modifier = Modifier.size(PROMO_ILLUSTRATION_SIZE),
                tint = Color.Unspecified,
            )
        }
    }
}

private fun dataLimitDescription(state: IpProtectionState, format: String): String? {
    val max = state.dataMaxBytes
    val remaining = state.dataRemainingBytes
    if (max <= 0L || remaining < 0L) return null
    val remainingGib = remaining / BYTES_PER_GIB
    val maxGib = max / BYTES_PER_GIB
    return format.format(remainingGib, maxGib)
}

private fun dataProgress(state: IpProtectionState): Float {
    val max = state.dataMaxBytes
    val remaining = state.dataRemainingBytes
    if (max <= 0L || remaining < 0L) return 0f
    return (remaining.toFloat() / max.toFloat()).coerceIn(0f, 1f)
}

@FlexibleWindowPreview
@Composable
private fun IpProtectionScreenActivePreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        IpProtectionScreen(
            state = IpProtectionState(
                vpnStatus = VpnStatus.Active,
                dataRemainingBytes = 47L * BYTES_PER_GIB.toLong(),
                dataMaxBytes = 50L * BYTES_PER_GIB.toLong(),
                selectedLocation = "United States",
            ),
            onVpnToggle = {},
            onLearnMoreClick = {},
            onAutoLocationToggle = {},
            onLocationClick = {},
            onManageWebsiteSettingsClick = {},
        )
    }
}

@FlexibleWindowPreview
@Composable
private fun IpProtectionScreenNotAvailablePreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        IpProtectionScreen(
            state = IpProtectionState(vpnStatus = VpnStatus.NotAvailable),
            onVpnToggle = {},
            onLearnMoreClick = {},
            onAutoLocationToggle = {},
            onLocationClick = {},
            onManageWebsiteSettingsClick = {},
        )
    }
}

@FlexibleWindowPreview
@Composable
private fun IpProtectionScreenPausedPreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        IpProtectionScreen(
            state = IpProtectionState(
                vpnStatus = VpnStatus.Paused,
                dataRemainingBytes = 0L,
                dataMaxBytes = 50L * BYTES_PER_GIB.toLong(),
                selectedLocation = "United States",
            ),
            onVpnToggle = {},
            onLearnMoreClick = {},
            onAutoLocationToggle = {},
            onLocationClick = {},
            onManageWebsiteSettingsClick = {},
        )
    }
}
