/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.menu.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.annotation.FlexibleWindowLightDarkPreview
import mozilla.components.compose.base.theme.surfaceDimVariant
import org.mozilla.fenix.R
import org.mozilla.fenix.components.VpnStatus
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.theme.PreviewThemeProvider
import org.mozilla.fenix.theme.Theme
import mozilla.components.ui.icons.R as iconsR

private val MENU_ITEM_MIN_HEIGHT = 52.dp
private val ROUNDED_CORNER = RoundedCornerShape(4.dp)

/**
 * A menu item showing the current VPN / IP Protection status.
 * Tapping the label or badge area toggles the VPN; tapping the chevron navigates to settings.
 *
 * @param vpnStatus The current [VpnStatus] to render.
 * @param isSignedIn Whether the user is currently signed in to a Firefox Account.
 *   When false the badge shows "Sign in" regardless of VPN status, since the user
 *   must authenticate before VPN can be activated.
 * @param isEnrollmentNeeded Whether the user is signed in but Guardian has not yet enrolled
 *   this device. When true the badge shows "Authorize" to signal that tapping will open
 *   the Guardian OAuth flow rather than toggling the VPN.
 * @param onToggle Called when the label/badge area is tapped to activate or deactivate VPN.
 * @param onNavigate Called when the chevron is tapped to open the IP Protection settings screen.
 */
@Composable
internal fun VpnMenuItem(
    vpnStatus: VpnStatus,
    isSignedIn: Boolean,
    isEnrollmentNeeded: Boolean,
    onToggle: () -> Unit,
    onNavigate: () -> Unit,
) {
    val badgeText = when {
        !isSignedIn        -> stringResource(R.string.vpn_menu_sign_in)
        isEnrollmentNeeded -> stringResource(R.string.vpn_menu_authorize)
        else               -> vpnStatusBadgeText(vpnStatus)
    }
    val menuItemState = vpnStatusMenuItemState(vpnStatus)

    Row(
        modifier = Modifier
            .wrapContentSize()
            .clip(ROUNDED_CORNER)
            .background(MaterialTheme.colorScheme.surfaceDimVariant)
            .height(IntrinsicSize.Min)
            .defaultMinSize(minHeight = MENU_ITEM_MIN_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = FirefoxTheme.layout.space.dynamic200),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FirefoxTheme.layout.space.static200),
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_globe_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = stringResource(R.string.ip_protection_toggle_label),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = FirefoxTheme.typography.subtitle1,
                maxLines = 1,
            )

            Badge(badgeText = badgeText, state = menuItemState)
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(role = Role.Button, onClick = onNavigate)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_chevron_right_24),
                contentDescription = stringResource(R.string.ip_protection_navigate_settings),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun vpnStatusBadgeText(status: VpnStatus): String = when (status) {
    VpnStatus.Active -> stringResource(R.string.preferences_ip_protection_on)
    VpnStatus.Activating -> stringResource(R.string.ip_protection_menu_connecting)
    VpnStatus.Paused -> stringResource(R.string.ip_protection_menu_paused)
    VpnStatus.Error -> stringResource(R.string.ip_protection_menu_error)
    // Ready means authenticated and VPN available but not yet active — show "Off".
    // NotAvailable falls through to "Off" too; the isSignedIn guard above handles
    // the "Sign in" case before this function is consulted.
    else -> stringResource(R.string.preferences_ip_protection_off)
}

private fun vpnStatusMenuItemState(status: VpnStatus): MenuItemState = when (status) {
    VpnStatus.Active -> MenuItemState.ACTIVE
    VpnStatus.Paused, VpnStatus.Error -> MenuItemState.WARNING
    else -> MenuItemState.ENABLED
}

@FlexibleWindowLightDarkPreview
@Composable
private fun VpnMenuItemOffPreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        MenuGroup {
            VpnMenuItem(vpnStatus = VpnStatus.NotAvailable, isSignedIn = false, isEnrollmentNeeded = false, onToggle = {}, onNavigate = {})
        }
    }
}

@FlexibleWindowLightDarkPreview
@Composable
private fun VpnMenuItemOnPreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        MenuGroup {
            VpnMenuItem(vpnStatus = VpnStatus.Active, isSignedIn = true, isEnrollmentNeeded = false, onToggle = {}, onNavigate = {})
        }
    }
}

@FlexibleWindowLightDarkPreview
@Composable
private fun VpnMenuItemConnectingPreview(
    @PreviewParameter(PreviewThemeProvider::class) theme: Theme,
) {
    FirefoxTheme(theme = theme) {
        MenuGroup {
            VpnMenuItem(vpnStatus = VpnStatus.Activating, isSignedIn = true, isEnrollmentNeeded = false, onToggle = {}, onNavigate = {})
        }
    }
}
