package com.smartech.screens.player

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.smartech.screens.data.Prices
import com.smartech.screens.data.VideoItem
import com.smartech.screens.util.InputMode
import com.smartech.screens.util.rememberScreenMetrics
import kotlinx.coroutines.delay

/** v0.1.90: how long an expanded card stays open on a touch device before
 *  auto-collapsing back to compact. Long enough to read a short product
 *  description, short enough that the card resets for the next shopper if
 *  someone taps and walks away. Non-touch devices ignore this — they run
 *  the fixed 6s/6s auto-cycle instead. */
private const val EXPANDED_AUTO_COLLAPSE_MS = 15_000L

/**
 * Shopper-facing product-info card.
 *
 * Sits over the video (NOT full-screen-opaque) in the bottom-start
 * corner, showing the CURRENTLY PLAYING item's name, region price, and
 * image. Only mounts when the screen's [productCard][com.smartech.screens.data.PlayerRepository.productCardFlow]
 * flag is on — the caller passes that through [enabled].
 *
 * Two states:
 *  - compact  — image + product name + price (+ a "tap for details"
 *               hint on touch devices).
 *  - expanded — image + name + price + the full description, plus a
 *               close affordance on touch devices.
 *
 * On touch devices the shopper taps to toggle compact/expanded; an
 * expanded card auto-collapses back to compact after
 * [EXPANDED_AUTO_COLLAPSE_MS] so it never sits open indefinitely. On
 * TV-class / no-touch devices taps are ignored and the card auto-cycles
 * between the two states on a fixed ticker so the description still gets
 * airtime unattended.
 *
 * Styling mirrors [ColdStartLoadingOverlay]: dark rounded card, white
 * copy, tm:rw amber accent. Everything is null-safe — a missing field
 * just drops that row rather than crashing.
 */
@Composable
fun ProductInfoCardOverlay(
    enabled: Boolean,
    item: VideoItem?,
    city: String?,
) {
    if (!enabled) return
    if (item == null) return

    val priceLabel = resolveRegionPrice(item.prices, city)
    val description = item.descriptionLong ?: item.description
    // Nothing worth showing → render nothing (the splash sentinel item,
    // or a plain brand clip with no card data, lands here).
    if (priceLabel == null && description.isNullOrBlank()) return

    val productName = item.product ?: item.title
    val hasImage = item.packshotUrl != null || item.brandLogoUrl != null
    val touch = InputMode.hasTouch(LocalContext.current)

    // Reset to compact whenever the playing item changes. Keying the
    // remember on item.id also restarts the auto-cycle ticker below.
    var expanded by remember(item.id) { mutableStateOf(false) }

    // Non-touch (signage / TV) devices can't tap, so cycle the two
    // states on a timer — compact 6s, expanded 6s. Mirrors the
    // CalibrationOverlay ticker pattern.
    if (!touch) {
        LaunchedEffect(item.id) {
            while (true) {
                delay(6_000L)
                expanded = !expanded
            }
        }
    } else {
        // v0.1.90: touch devices — auto-collapse an expanded card after a
        // timeout so a shopper who taps for details and walks away doesn't
        // leave the description covering the video indefinitely. The effect
        // re-arms every time the card (re-)expands; a manual second tap
        // collapses it earlier by flipping `expanded`, which restarts this
        // effect with expanded=false (a no-op). Item change resets to
        // compact via the item.id-keyed remember above.
        LaunchedEffect(item.id, expanded) {
            if (expanded) {
                delay(EXPANDED_AUTO_COLLAPSE_MS)
                expanded = false
            }
        }
    }

    // v0.2.0: the width fractions are tuned for a wide screen. At 40% of a
    // 360dp phone the card is 144dp, and once the 20dp padding and a 72dp image
    // are taken out the name and price are left ~16dp — a character per line.
    // Compact takes most of the width instead, and drops the image when even
    // that isn't enough to leave the text a readable column.
    val metrics = rememberScreenMetrics()
    val compact = metrics.isNarrow
    val widthFraction = when {
        compact -> if (expanded) 0.92f else 0.78f
        expanded -> 0.55f
        else -> 0.40f
    }
    val showImage = hasImage && (!compact || metrics.widthDp * widthFraction >= IMAGE_NEEDS_DP)
    Box(
        Modifier
            .fillMaxSize()
            .padding(if (compact || metrics.isShort) 12.dp else 32.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            Modifier
                .fillMaxWidth(widthFraction)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCC101010))
                .then(if (touch) Modifier.clickable { expanded = !expanded } else Modifier)
                .animateContentSize()
                .padding(if (compact) 14.dp else 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showImage) {
                    ProductImage(
                        item = item,
                        contentDescription = productName,
                        size = if (expanded) (if (compact) 64.dp else 96.dp)
                               else (if (compact) 48.dp else 72.dp),
                    )
                    Spacer(Modifier.width(if (compact) 10.dp else 16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        productName,
                        color = Color(0xFFF7F6F2),
                        fontSize = if (expanded) 22.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (priceLabel != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            priceLabel,
                            color = Color(0xFFE8A33D),
                            fontSize = if (expanded) 24.sp else 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            if (expanded) {
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    // v0.2.0: maxLines. The card is bottom-anchored, so an
                    // unbounded description grows it UPWARD — on a short screen
                    // a long one pushed its own opening lines off the top of the
                    // display, clipping from the wrong end entirely.
                    Text(
                        description,
                        color = Color(0xCCFFFFFF),
                        fontSize = if (compact) 13.sp else 15.sp,
                        lineHeight = if (compact) 18.sp else 21.sp,
                        maxLines = if (metrics.isShort) 4 else 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (touch) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Tap to close",
                        color = Color(0x99E8A33D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.5.sp,
                    )
                }
            } else if (touch && !description.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "TAP FOR DETAILS",
                    color = Color(0x99E8A33D),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.8.sp,
                )
            }
        }
    }
}

/** Below this the packshot leaves the name and price too little room to be
 *  worth showing at all, so the card drops it and keeps the words. */
private const val IMAGE_NEEDS_DP = 260

/**
 * Packshot → brand-logo fallback chain. The outer image loads the
 * packshot; if it's null or fails, the error slot loads the brand logo;
 * if that's also null or fails, nothing renders. A null [VideoItem.packshotUrl]
 * makes Coil go straight to the error slot, so a brand-logo-only item
 * still shows its logo.
 */
@Composable
private fun ProductImage(
    item: VideoItem,
    contentDescription: String?,
    size: Dp,
) {
    val imageModifier = Modifier
        .size(size)
        .clip(RoundedCornerShape(8.dp))
    SubcomposeAsyncImage(
        model = item.packshotUrl,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = imageModifier,
        loading = {},
        error = {
            SubcomposeAsyncImage(
                model = item.brandLogoUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
                loading = {},
                error = {},
            )
        },
    )
}

/** Format a price with no decimals when whole (£449), else 2dp (£449.99). */
private fun formatPrice(amount: Double, symbol: String): String =
    if (amount % 1.0 == 0.0) "$symbol${amount.toLong()}"
    else "$symbol%.2f".format(amount)

/**
 * Pick the price for this screen's city, falling back through
 * eur → gbp → usd as a last resort (also used when the city is unknown
 * or its preferred field is absent). Returns null when no price fits.
 */
private fun resolveRegionPrice(prices: Prices?, city: String?): String? {
    if (prices == null) return null
    val preferred: Pair<Double?, String> = when (city?.uppercase()) {
        "LDN" -> prices.gbp to "£"
        "NYC" -> prices.usd to "$"
        "BER" -> prices.berlinEur to "€"
        "ROM" -> prices.romeEur to "€"
        else -> null to ""
    }
    preferred.first?.let { return formatPrice(it, preferred.second) }
    prices.eur?.let { return formatPrice(it, "€") }
    prices.gbp?.let { return formatPrice(it, "£") }
    prices.usd?.let { return formatPrice(it, "$") }
    return null
}
