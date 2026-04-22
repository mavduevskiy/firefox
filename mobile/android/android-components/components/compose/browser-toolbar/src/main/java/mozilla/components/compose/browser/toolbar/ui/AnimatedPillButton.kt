/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.compose.browser.toolbar.ui

import android.graphics.drawable.Drawable
import android.view.SoundEffectConstants
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.compose.base.badge.BadgedIcon
import mozilla.components.compose.base.theme.AcornTheme
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.ui.icons.R as iconsR

const val FADE_OUT_DURATION = 600
const val ANIMATION_DELAY = 400L

/**
 * A transient pill-shaped button that displays an [icon] alongside a [text] label, then
 * animates away automatically: after [ANIMATION_DELAY] the label and pill fade out while
 * the pill shrinks to a circle, causing the parent to reflow its children.
 *
 * @param icon The base icon to display inside the pill (stays visible after the animation).
 * @param overlayIcon Optional smaller icon drawn at the bottom-end of [icon].
 * @param text The label text shown initially beside the icon.
 * @param contentDescription Accessibility content description for the button.
 * @param highlighted Whether a highlight badge should be drawn on top of [icon].
 * @param onClick Interaction dispatched when the button is tapped.
 * @param onInteraction Callback for dispatching [BrowserToolbarEvent]s to the store.
 */
@Composable
internal fun AnimatedPillButton(
    icon: Drawable,
    overlayIcon: Drawable? = null,
    text: String,
    contentDescription: String,
    highlighted: Boolean = false,
    onClick: BrowserToolbarInteraction,
    onInteraction: (BrowserToolbarEvent) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    var fullWidthPx by remember { mutableIntStateOf(0) }
    val widthFraction = remember { Animatable(1f) }
    val textAlpha = remember { Animatable(1f) }
    val containerAlpha = remember { Animatable(1f) }

    LaunchedEffect(fullWidthPx) {
        if (fullWidthPx == 0) return@LaunchedEffect
        delay(ANIMATION_DELAY)
        launch { textAlpha.animateTo(0f, tween(durationMillis = FADE_OUT_DURATION)) }
        launch { containerAlpha.animateTo(0f, tween(durationMillis = FADE_OUT_DURATION)) }
        widthFraction.animateTo(0f, tween(durationMillis = FADE_OUT_DURATION))
    }

    val collapsedWidthDp = 40.dp
    val animatedWidthDp = if (fullWidthPx > 0) {
        val collapsedPx = with(density) { collapsedWidthDp.toPx() }
        with(density) { (collapsedPx + (fullWidthPx - collapsedPx) * widthFraction.value).toDp() }
    } else {
        Dp.Unspecified
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .height(40.dp)
            .then(
                if (animatedWidthDp != Dp.Unspecified) Modifier.width(animatedWidthDp) else Modifier,
            )
            .onSizeChanged { size ->
                if (fullWidthPx == 0 && size.width > 0) fullWidthPx = size.width
            }
            .clip(RoundedCornerShape(90.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = containerAlpha.value))
            .clickable {
                view.playSoundEffect(SoundEffectConstants.CLICK)
                if (onClick is BrowserToolbarEvent) {
                    onInteraction(onClick)
                }
            }
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LayeredIcon(
                painter = rememberDrawablePainter(icon),
                overlayPainter = overlayIcon?.let { rememberDrawablePainter(it) },
                tint = MaterialTheme.colorScheme.onSurface,
                overlayTint = MaterialTheme.colorScheme.tertiary,
                overlayBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
                isHighlighted = highlighted,
            )

            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                modifier = Modifier.alpha(textAlpha.value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Renders an icon with an optional smaller [overlayPainter] layered at the bottom-end
 * corner, delegating the base icon + optional highlight badge to [BadgedIcon].
 *
 * The overlay is drawn on top of an opaque circular [overlayBackground], so that the
 * base icon underneath is visually occluded where the overlay sits.
 *
 * @param painter The base icon.
 * @param overlayPainter The optional overlay icon layered at the bottom-end corner.
 * @param tint Tint applied to [painter].
 * @param overlayTint Tint applied to [overlayPainter]. Ignored when [overlayPainter] is null.
 * @param overlayBackground Solid fill drawn behind [overlayPainter] as a circle, used to
 * occlude the base icon. Ignored when [overlayPainter] is null.
 * @param isHighlighted Whether to render a highlight badge on top of [painter].
 */
@Composable
private fun LayeredIcon(
    painter: Painter,
    overlayPainter: Painter?,
    tint: Color,
    overlayTint: Color = tint,
    overlayBackground: Color = Color.Unspecified,
    isHighlighted: Boolean = false,
) {
    Box {
        BadgedIcon(
            painter = painter,
            isHighlighted = isHighlighted,
            tint = tint,
        )
        if (overlayPainter != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = OVERLAY_OFFSET, y = OVERLAY_OFFSET)
                    .size(OVERLAY_SIZE)
                    .clip(CircleShape)
                    .background(overlayBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = overlayPainter,
                    contentDescription = null,
                    modifier = Modifier.size(OVERLAY_SIZE),
                    tint = overlayTint,
                )
            }
        }
    }
}

private val OVERLAY_SIZE = 10.dp
private val OVERLAY_OFFSET = 0.dp

@PreviewLightDark
@Composable
private fun AnimatedPillButtonPreview() {
    AcornTheme {
        AnimatedPillButton(
            icon = AppCompatResources.getDrawable(
                LocalContext.current,
                iconsR.drawable.mozac_ic_shield_checkmark_24,
            )!!,
            overlayIcon = AppCompatResources.getDrawable(
                LocalContext.current,
                iconsR.drawable.mozac_ic_globe_24,
            )!!,
            text = "VPN On",
            contentDescription = "VPN On",
            onClick = object : BrowserToolbarEvent {},
            onInteraction = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun AnimatedPillButtonHighlightedPreview() {
    AcornTheme {
        AnimatedPillButton(
            icon = AppCompatResources.getDrawable(
                LocalContext.current,
                iconsR.drawable.mozac_ic_shield_checkmark_24,
            )!!,
            overlayIcon = AppCompatResources.getDrawable(
                LocalContext.current,
                iconsR.drawable.mozac_ic_globe_24,
            )!!,
            text = "VPN On",
            contentDescription = "VPN On",
            highlighted = true,
            onClick = object : BrowserToolbarEvent {},
            onInteraction = {},
        )
    }
}
