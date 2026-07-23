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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** v0.2.8: on a touch device showing a multi-product video, how long each
 *  product stays up before the card rotates to the next — but only while
 *  collapsed, so it never pulls a description out from under someone reading
 *  it. Signage devices use the 6s compact/expanded step instead. */
private const val PRODUCT_DWELL_MS = 9_000L

/** v0.2.8: the compact/expanded step on signage. Each product gets one compact
 *  beat then one expanded beat before the card advances to the next. */
private const val SIGNAGE_STEP_MS = 6_000L

/**
 * Shopper-facing product-info card.
 *
 * Sits over the video (NOT full-screen-opaque) in the bottom-start corner,
 * showing a product's name, region price, image, and description.
 * Only mounts when the screen's [productCard][com.smartech.screens.data.PlayerRepository.productCardFlow]
 * flag is on — the caller passes that through [enabled].
 *
 * v0.2.8 — cycling. Most videos map to one product and the card shows it
 * exactly as before. But a tm:rw family- or brand-scope video represents
 * SEVERAL products (variants of one thing, or a brand's range), and the server
 * now attaches them as [VideoItem.products]. When there are two or more, the
 * card rotates through them so each gets its price + description on screen —
 * a single widget covering every product that shares the clip.
 *
 * Two states per product:
 *  - compact  — image + product name + price (+ a "tap for details" hint on
 *               touch devices).
 *  - expanded — image + name + price + the full description.
 *
 * On touch devices the shopper taps to toggle compact/expanded; an expanded
 * card auto-collapses after [EXPANDED_AUTO_COLLAPSE_MS], and a multi-product
 * card rotates to the next product every [PRODUCT_DWELL_MS] while collapsed.
 * On TV-class / no-touch devices taps are ignored: each product shows compact
 * then expanded on a fixed ticker, then the card advances. A small row of dots
 * marks position when there's more than one.
 *
 * Everything is null-safe — a missing field just drops that row.
 */
@Composable
fun ProductInfoCardOverlay(
    enabled: Boolean,
    item: VideoItem?,
    city: String?,
) {
    if (!enabled) return
    if (item == null) return

    // Build the product views once per playing item. ≥2 → cycle; otherwise a
    // single view from the item's own fields (the original behaviour).
    val views = remember(item.id) { buildCardViews(item) }
    val multi = views.size > 1
    // Nothing worth showing for a lone product (the splash sentinel, or a plain
    // brand clip with no card data) → render nothing. Done here, before any
    // stateful hooks, so the hook set is identical every recomposition. A
    // multi-product card always draws; the server already dropped products with
    // nothing to say.
    if (!multi) {
        val only = views[0]
        if (resolveRegionPrice(only.prices, city) == null && only.description.isNullOrBlank()) return
    }
    val touch = InputMode.hasTouch(LocalContext.current)

    // Which product, and whether it's expanded. Both reset when the item
    // changes (item.id-keyed remembers).
    var index by remember(item.id) { mutableIntStateOf(0) }
    var expanded by remember(item.id) { mutableStateOf(false) }

    if (!touch) {
        // Signage: compact beat → expanded beat → advance to next product.
        // A single product just toggles compact/expanded, as before.
        LaunchedEffect(item.id) {
            while (true) {
                delay(SIGNAGE_STEP_MS)
                if (expanded) {
                    expanded = false
                    if (multi) index = (index + 1) % views.size
                } else {
                    expanded = true
                }
            }
        }
    } else {
        // Touch: auto-collapse an expanded card so a walked-away shopper's tap
        // doesn't leave the description covering the video forever.
        LaunchedEffect(item.id, expanded) {
            if (expanded) {
                delay(EXPANDED_AUTO_COLLAPSE_MS)
                expanded = false
            }
        }
        // Touch + multi: rotate products while collapsed. Keyed on index so it
        // re-arms after each advance; keyed on expanded so it pauses (and
        // resumes) around a shopper reading the details.
        if (multi) {
            LaunchedEffect(item.id, index, expanded) {
                if (!expanded) {
                    delay(PRODUCT_DWELL_MS)
                    index = (index + 1) % views.size
                }
            }
        }
    }

    val view = views[index.coerceIn(0, views.size - 1)]
    val priceLabel = resolveRegionPrice(view.prices, city)
    val description = view.description
    val productName = view.name
    val hasImage = view.packshotUrl != null || view.brandLogoUrl != null

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
                        packshotUrl = view.packshotUrl,
                        brandLogoUrl = view.brandLogoUrl,
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

            // v0.2.8: position dots — only when the card is cycling products.
            if (multi) {
                Spacer(Modifier.height(if (expanded) 14.dp else 10.dp))
                PositionDots(count = views.size, active = index)
            }
        }
    }
}

/** v0.2.8: a small row of dots showing which product of how many is up. The
 *  active one is the amber accent; the rest are dim. Keeps a cycling card from
 *  looking like it's flickering between unrelated items. */
@Composable
private fun PositionDots(count: Int, active: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            if (i > 0) Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (i == active) Color(0xFFE8A33D) else Color(0x55FFFFFF))
            )
        }
    }
}

/** Below this the packshot leaves the name and price too little room to be
 *  worth showing at all, so the card drops it and keeps the words. */
private const val IMAGE_NEEDS_DP = 260

/** One product's slot on the card's cycle. Flattens either a [VideoItem]'s own
 *  fields (single-product video) or one of its [VideoItem.products] entries
 *  (multi-product video) into the same shape the layout renders. */
private data class CardView(
    val name: String,
    val prices: Prices?,
    /** Long description preferred, short as fallback — what the expanded panel shows. */
    val description: String?,
    val packshotUrl: String?,
    val brandLogoUrl: String?,
)

/**
 * The products this card will cycle through. When the server attached two or
 * more [VideoItem.products] (a family/brand video), each becomes a slot;
 * otherwise the card shows the item's own single product exactly as before.
 * Brand logo falls back to the item's for every slot, since it's a property of
 * the video's brand, not the individual product.
 */
private fun buildCardViews(item: VideoItem): List<CardView> {
    val products = item.products
    if (products != null && products.size >= 2) {
        val views = products.mapNotNull { p ->
            val name = p.product?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CardView(
                name = name,
                prices = p.prices,
                description = p.descriptionLong ?: p.description,
                packshotUrl = p.packshotUrl,
                brandLogoUrl = item.brandLogoUrl,
            )
        }
        if (views.size >= 2) return views
    }
    return listOf(
        CardView(
            name = item.product ?: item.title,
            prices = item.prices,
            description = item.descriptionLong ?: item.description,
            packshotUrl = item.packshotUrl,
            brandLogoUrl = item.brandLogoUrl,
        )
    )
}

/**
 * Packshot → brand-logo fallback chain. The outer image loads the packshot; if
 * it's null or fails, the error slot loads the brand logo; if that's also null
 * or fails, nothing renders. A null [packshotUrl] makes Coil go straight to the
 * error slot, so a brand-logo-only slot still shows its logo.
 */
@Composable
private fun ProductImage(
    packshotUrl: String?,
    brandLogoUrl: String?,
    contentDescription: String?,
    size: Dp,
) {
    val imageModifier = Modifier
        .size(size)
        .clip(RoundedCornerShape(8.dp))
    SubcomposeAsyncImage(
        model = packshotUrl,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = imageModifier,
        loading = {},
        error = {
            SubcomposeAsyncImage(
                model = brandLogoUrl,
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
