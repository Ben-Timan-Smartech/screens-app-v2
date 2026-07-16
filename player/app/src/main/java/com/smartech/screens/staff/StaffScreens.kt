package com.smartech.screens.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import coil.compose.SubcomposeAsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartech.screens.util.rememberScreenMetrics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.smartech.screens.ScreensApp
import com.smartech.screens.data.LocationTaxonomy
import com.smartech.screens.data.UserDirectory
import com.smartech.screens.data.VideoItem
import kotlinx.coroutines.delay

private val Ink = Color(0xFF141414)
private val Bone = Color(0xFFF7F6F2)
private val BoneSoft = Color(0xFFEFEDE6)
private val BoneLine = Color(0xFFE2DED3)
private val Muted = Color(0xFF6E6B62)
private val Amber = Color(0xFFE8A33D)
private val Ok = Color(0xFF3D8C4B)

// v0.1.48: shared pill button used for Back / Cancel on the brand and
// video pickers. The previous "Muted text + padding" treatment was
// invisible on a TV across the room — staff couldn't see how to exit
// the Add-content flow without using the remote's Back key.
@Composable
private fun NavPill(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val bg = if (primary) Ink else BoneSoft
    val fg = if (primary) Bone else Ink
    val border = if (primary) Ink else BoneLine
    Box(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────
// Shared left rail (dark, contextual copy)
// ─────────────────────────────────────────────────────────────
@Composable
private fun Rail(title: String, sub: String, step: Int) {
    // Look up the store this device is registered to so the rail subtitle
    // names where it actually lives, rather than a copy-paste string. Falls
    // back to "Smartech Group" until onboarding has set a storeId.
    val app = LocalContext.current.applicationContext as? ScreensApp
    val storeId by (app?.repository?.store?.locStoreId
        ?: kotlinx.coroutines.flow.flowOf(null)).collectAsState(initial = null)
    val storeSubtitle = remember(storeId) {
        LocationTaxonomy.stores.firstOrNull { it.id == storeId }?.name ?: "Smartech Group"
    }

    // v0.2.0: the Ink background + outer padding used to live here. They're now
    // the scaffold's job (see [Layout] → TwoPaneScaffold), because the right
    // padding depends on whether this is a rail beside content or a header
    // stacked above it — and only the scaffold knows which.
    val metrics = rememberScreenMetrics()
    val compact = metrics.isNarrow

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Bone),
                contentAlignment = Alignment.Center,
            ) { Text("S", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Screens", color = Bone, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(storeSubtitle, color = Color(0x8CFFFFFF), fontSize = 13.sp)
            }
        }
        // In a tall rail this pushes the copy down to sit against the bottom
        // edge. Stacked or scrolling there's no spare height to push into and
        // it resolves to zero — which is the right result there anyway, so the
        // fixed spacer below re-supplies just enough separation.
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(if (compact) 16.dp else 0.dp))
        Text(
            "Swap what's playing".uppercase(),
            color = Color(0x73FFFFFF),
            fontSize = 11.sp,
            letterSpacing = 1.6.sp,
        )
        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        // 30sp is sized to be read from across a shop floor; on a phone held at
        // arm's length it just wraps the title onto three lines.
        Text(
            title,
            color = Bone,
            fontSize = if (compact) 20.sp else 30.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        Text(sub, color = Color(0x99FFFFFF), fontSize = if (compact) 12.sp else 14.sp)
        Spacer(Modifier.height(if (compact) 14.dp else 32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .height(4.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step) Bone else Color(0x2EFFFFFF))
                )
            }
        }
    }
}

@Composable
private fun Layout(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    // v0.2.0: was a hard-coded 420dp rail beside the content, which on a
    // portrait phone left the content pane exactly 0dp wide — the PIN pad,
    // brand picker and video picker were all laid out into nothing. The
    // scaffold shrinks the rail proportionally and stacks it below 600dp.
    TwoPaneScaffold(
        rail = left,
        pane = right,
        railColor = Ink,
        paneColor = Bone,
    )
}


// ─────────────────────────────────────────────────────────────
// PIN
// ─────────────────────────────────────────────────────────────
@Composable
fun PinScreen(
    onCorrect: (UserDirectory.User) -> Unit,
    onCancel: () -> Unit,
) {
    // Validates against [UserDirectory]. On a wrong PIN, the dots flash and
    // clear; rate-limited by the user only being able to type 4 digits at a time.
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    if (error) {
        LaunchedEffect(error) {
            delay(700)
            pin = ""
            error = false
        }
    }

    // v0.2.0: the keypad is sized for the screen. At the tablet's fixed 140x90
    // keys a row of three is 456dp wide and the stack 414dp tall — wider than a
    // portrait phone and taller than a landscape one, so the "3/6/9/⌫" column
    // fell off the side and Cancel off the bottom. The scroll is a backstop for
    // the shortest screens, where even the compact keypad plus the dots and
    // Cancel can't all fit at once.
    val metrics = rememberScreenMetrics()
    val compactPad = metrics.isNarrow || metrics.isShort

    Layout(
        left = { Rail("Tap in your PIN", "Last 4 of your staff ID. Session ends automatically.", 0) },
        right = {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (compactPad) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    repeat(4) { i ->
                        val filled = i < pin.length
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .border(
                                    2.dp,
                                    when {
                                        error -> Color(0xFFA63824)
                                        filled -> Ink
                                        else -> BoneLine
                                    },
                                    CircleShape,
                                )
                                .background(
                                    when {
                                        error -> Color(0xFFA63824)
                                        filled -> Ink
                                        else -> Color.Transparent
                                    }
                                )
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (error) "Wrong PIN" else " ",
                    color = Color(0xFFA63824),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                Numpad(
                    compact = compactPad,
                    onKey = { k ->
                        if (error) return@Numpad
                        pin = when {
                            k == -1 -> pin.dropLast(1)
                            pin.length >= 4 -> pin
                            else -> pin + k.toString()
                        }
                        if (pin.length == 4) {
                            val user = UserDirectory.authenticate(pin)
                            if (user != null) {
                                onCorrect(user)
                            } else {
                                error = true
                            }
                        }
                    },
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    "Cancel",
                    color = Muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onCancel() }
                        .padding(12.dp),
                )
            }
        },
    )
}

@Composable
private fun Numpad(compact: Boolean, onKey: (Int) -> Unit) {
    // Compact keys are still ~84x56dp — well above the 48dp minimum touch
    // target, so they stay tappable with a thumb; they just stop being sized
    // to be hit from a step back off a shop floor.
    val keyW = if (compact) COMPACT_KEY_W else KEY_W
    val keyH = if (compact) COMPACT_KEY_H else KEY_H
    val gap = if (compact) 10.dp else 18.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        listOf(
            listOf(1, 2, 3),
            listOf(4, 5, 6),
            listOf(7, 8, 9),
            listOf(0, 0, -1),
        ).forEachIndexed { rowIdx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { colIdx, n ->
                    if (rowIdx == 3 && colIdx == 0) {
                        Spacer(Modifier.size(keyW, keyH))
                    } else {
                        NumpadKey(n, keyW, keyH, compact, onClick = { onKey(n) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NumpadKey(n: Int, keyW: Dp, keyH: Dp, compact: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(keyW, keyH)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (n == -1) Modifier
                else Modifier
                    .background(Bone)
                    .border(1.dp, BoneLine, RoundedCornerShape(16.dp))
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (n == -1) "⌫" else n.toString(),
            fontSize = if (compact) 22.sp else 32.sp,
            fontWeight = FontWeight.Medium,
            color = Ink,
        )
    }
}

// Tablet: sized to be read and hit from across a shop floor. A row of three is
// 456dp wide and the four rows 414dp tall — fine on 1280x800, impossible on a
// phone in either orientation.
private val KEY_W = 140.dp
private val KEY_H = 90.dp
private val COMPACT_KEY_W = 84.dp
private val COMPACT_KEY_H = 56.dp

// ─────────────────────────────────────────────────────────────
// Brand picker
// ─────────────────────────────────────────────────────────────
@Composable
fun BrandPickerScreen(
    onBack: () -> Unit,
    onPickBrand: (String) -> Unit,
    onCancel: () -> Unit,
    remoteLibrary: com.smartech.screens.data.RemoteLibrary? = null,
) {
    // Pull from /api/library when the live server is reachable. Falls back to
    // a short hardcoded alphabetical set while the first response is in flight
    // (or when the player isn't pointed at a server). Server responses are
    // already alphabetical (sorted in scan-videos.py), so we display them
    // as-is rather than re-sorting on every recomposition.
    val library = remoteLibrary?.state?.collectAsState()?.value
    // v0.1.72: carry the tm:rw logoUrl alongside the name so the cards can
    // render real brand logos (with a letter fallback). Fallback set has
    // no logos → letters.
    val brandsFromServer = library?.brands?.map { it.name to it.logoUrl } ?: emptyList()
    val brands: List<Pair<String, String?>> = if (brandsFromServer.isNotEmpty()) brandsFromServer
                 else listOf("Anker", "Bang & Olufsen", "DVX", "Ember", "Foreo", "Motorola", "SONOS").map { it to null }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val filtered = brands.filter { it.first.contains(query.text, ignoreCase = true) }

    Layout(
        left = { Rail("Which brand?", "Search or tap a brand. Tap back to re-enter PIN.", 1) },
        right = {
            Column(Modifier.fillMaxSize().padding(paneInset())) {
                SearchBar(query, onQueryChange = { query = it }, placeholder = "Search brands")
                Spacer(Modifier.height(if (compactPane()) 14.dp else 28.dp))
                // v0.1.53: bound the grid so the bottom row stays
                // on-screen if the brand list grows past one screen.
                // v0.2.0: Adaptive, not Fixed(4). Four columns assumed a wide
                // pane; in a narrow one each cell fell to ~49dp — narrower than
                // a BrandCard's own padding, so the 72dp logo and the name were
                // both crushed. Adaptive fits as many 132dp columns as there's
                // room for and no fewer than one.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(filtered) { (name, logoUrl) ->
                        BrandCard(name, logoUrl) { onPickBrand(name) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavPill("← Back", onBack)
                    Spacer(Modifier.weight(1f))
                    NavPill("Cancel", onCancel)
                }
            }
        },
    )
}

@Composable
private fun BrandCard(brand: String, logoUrl: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Bone)
            .border(1.dp, BoneLine, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(22.dp)
    ) {
        BrandLogo(brand, logoUrl)
        Spacer(Modifier.height(18.dp))
        Text(brand, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink)
    }
}

// v0.1.72: 72dp brand mark. Renders the tm:rw logo when present, with a
// first-letter tile as the loading/error/empty fallback so a missing or
// slow logo never leaves a blank square on the picker.
@Composable
private fun BrandLogo(brand: String, logoUrl: String?) {
    val fallback: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(BoneSoft), contentAlignment = Alignment.Center) {
            Text(
                brand.firstOrNull()?.toString() ?: "?",
                fontSize = 32.sp, fontWeight = FontWeight.Medium, color = Ink,
            )
        }
    }
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(BoneSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (logoUrl.isNullOrBlank()) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = logoUrl,
                contentDescription = brand,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = { fallback() },
                error = { fallback() },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Video picker
// ─────────────────────────────────────────────────────────────
//
// v0.1.49: tap-to-toggle selection, batch commit on Add. Staff can
// queue up several videos in one PIN session instead of going through
// pick → success → back → pick → success → back for each. The
// `onPick` callback now receives the full set of selected videos in
// one call; the caller pushes them as a single append.
// v0.1.67: lightweight UI model for the picker. Wraps the pushable
// VideoItem with the tm:rw asset-manager tags so the picker can group
// by product line and flag orphan / pending videos without the
// playlist model (VideoItem) needing to know about any of it.
data class PickerVideo(
    val item: VideoItem,
    val productLine: String?,
    val active: Boolean,
    val assigned: Boolean,   // false → "orphan" (in Drive, unknown to tm:rw)
    val pending: Boolean,    // assigned but no streamable file yet → not pushable
    val scope: String?,      // "brand" → Brand global; else grouped by product
    // v0.1.71: extra columns for the picker's list view. width/height +
    // sizeMb come from the Drive scan; sku/orientation/resolution come
    // from tm:rw (the latter two as a fallback when there's no scan).
    val sizeMb: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sku: String? = null,
    val orientation: String? = null,
    val resolution: String? = null,
    // v0.1.77: product packshot (tm:rw main image) thumbnail URL, or null
    // for brand/orphan videos with no product image.
    val packshotUrl: String? = null,
)

private const val ORPHANS = "__orphans__"
private const val BRAND_GLOBAL = "__brand_global__"
// v0.1.71: single "Products" filter (replaces the per-product-line pills)
// listing every product/family-scope video for the brand.
private const val PRODUCTS = "__products__"

private fun PickerVideo.isBrandGlobal() = assigned && scope == "brand"
private fun PickerVideo.isProduct() = assigned && scope != "brand" && productLine != null

// v0.1.71: column formatters for the list view. Prefer the exact scanned
// dimensions; fall back to tm:rw's orientation/resolution strings (the
// only source for pending-sync videos not yet in the Drive folder).
private fun PickerVideo.productName(): String =
    productLine ?: item.product ?: item.title

private fun PickerVideo.lengthLabel(): String {
    val d = item.durationSec ?: return "—"
    return if (d < 60) "${d}s" else "${d / 60}:${(d % 60).toString().padStart(2, '0')}"
}

private fun PickerVideo.sizeLabel(): String {
    val s = sizeMb ?: return "—"
    return if (s == s.toLong().toDouble()) "${s.toLong()} MB" else String.format("%.1f MB", s)
}

private fun PickerVideo.orientationLabel(): String {
    val w = width; val h = height
    if (w != null && h != null && w > 0 && h > 0) {
        return when {
            w > h -> "Landscape"
            h > w -> "Portrait"
            else  -> "Square"
        }
    }
    val o = (orientation ?: "").lowercase()
    return when {
        o.startsWith("land") -> "Landscape"
        o.startsWith("port") -> "Portrait"
        o.startsWith("sq")   -> "Square"
        else -> orientation ?: "—"
    }
}

private fun PickerVideo.resolutionLabel(): String {
    val w = width; val h = height
    if (w != null && h != null && w > 0 && h > 0) {
        return when {
            w == 1920 && h == 1080 -> "1080p"
            w == 1080 && h == 1920 -> "1080p ↕"
            w == 1280 && h == 720  -> "720p"
            w == 3840 && h == 2160 -> "4K"
            else -> "${w}×${h}"
        }
    }
    return resolution ?: "—"
}

@Composable
private fun FilterPill(label: String, count: Int, selected: Boolean, tone: Color = Ink, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Ink else BoneSoft)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            "$label · $count",
            color = if (selected) Bone else tone,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun VideoPickerScreen(
    brand: String,
    videos: List<PickerVideo>,
    onBack: () -> Unit,
    onPick: (List<VideoItem>) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    // Track selected ids (not the full item) so the set is stable
    // across re-filter / re-fetch — same video keeps its tick even if
    // the underlying list reshuffles.
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // v0.1.67: product-line filter. null = all, a productLine, or ORPHANS.
    var filter by remember { mutableStateOf<String?>(null) }

    // Sections from the tm:rw asset-manager tags: brand-global (scope
    // "brand"), products (scope family/product), and orphans (in the
    // Drive folder, unknown to the asset manager). v0.1.71: products are
    // a single "Products" list, counted by distinct product.
    val brandGlobalCount = videos.count { it.isBrandGlobal() }
    val productCount = remember(videos) {
        videos.filter { it.isProduct() }.mapNotNull { it.productLine }.distinct().size
    }
    val orphanCount = videos.count { !it.assigned }

    val byFilter = when (filter) {
        null -> videos
        PRODUCTS -> videos.filter { it.isProduct() }
        BRAND_GLOBAL -> videos.filter { it.isBrandGlobal() }
        ORPHANS -> videos.filter { !it.assigned }
        else -> videos.filter { it.isProduct() && it.productLine == filter }
    }
    val filtered = byFilter.filter { it.item.title.contains(query.text, ignoreCase = true) }
    val selectedCount = selectedIds.size
    val canCommit = selectedCount > 0

    Layout(
        left = { Rail(
            "$brand videos",
            if (selectedCount == 0)
                "Tap videos to add them to the playlist. Pick as many as you want, then press Add."
            else
                "$selectedCount selected. Tap Add to push them all to the playlist, or tap more to keep building.",
            2,
        ) },
        right = {
            val compact = compactPane()
            Column(Modifier.fillMaxSize().padding(paneInset())) {
                SearchBar(query, onQueryChange = { query = it }, placeholder = "Search $brand videos")
                // v0.1.71: four filters — All · Products · Brand videos ·
                // Orphans (Unassigned). Only shown when tm:rw has section
                // data; otherwise the flat "All" list stands on its own.
                if (productCount > 0 || brandGlobalCount > 0 || orphanCount > 0) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterPill("All", videos.size, filter == null) { filter = null }
                        if (productCount > 0) {
                            FilterPill("Products", productCount, filter == PRODUCTS) { filter = PRODUCTS }
                        }
                        if (brandGlobalCount > 0) {
                            FilterPill("Brand videos", brandGlobalCount, filter == BRAND_GLOBAL) { filter = BRAND_GLOBAL }
                        }
                        if (orphanCount > 0) {
                            FilterPill("Orphans (Unassigned)", orphanCount, filter == ORPHANS, tone = Amber) { filter = ORPHANS }
                        }
                    }
                }
                Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
                // v0.1.71: list view with columns — Product Name · Size ·
                // Length · Orientation · Resolution · SKU Name. weight(1f)
                // keeps the Add row below on-screen with 30+ videos.
                // v0.2.0: six weighted columns need a wide pane to mean
                // anything. Narrow, they divide down to ~30dp each — every
                // heading truncated, the title left with a few dp beside its
                // packshot. So on a compact pane the header goes and each row
                // stacks its metadata instead (see VideoListRow).
                if (!compact) {
                    VideoListHeader()
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    lazyColumnItems(filtered, key = { it.item.id }) { v ->
                        VideoListRow(
                            video = v,
                            selected = v.item.id in selectedIds,
                            compact = compact,
                            onClick = {
                                // Pending videos can't be pushed (no
                                // streamable file yet) — ignore taps.
                                if (!v.pending) {
                                    selectedIds = if (v.item.id in selectedIds) selectedIds - v.item.id
                                                  else selectedIds + v.item.id
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavPill("← Back", onBack)
                    Spacer(Modifier.weight(1f))
                    if (selectedCount > 0) {
                        Text(
                            "$selectedCount selected",
                            color = Muted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                    NavPill("Cancel", onCancel)
                    Spacer(Modifier.width(12.dp))
                    NavPill(
                        label = if (selectedCount <= 1) "Add video" else "Add $selectedCount videos",
                        onClick = {
                            if (!canCommit) return@NavPill
                            // Preserve pick order (insertion-ordered set).
                            val picked = selectedIds.mapNotNull { id ->
                                videos.firstOrNull { it.item.id == id }?.item
                            }
                            if (picked.isNotEmpty()) onPick(picked)
                        },
                        primary = canCommit,
                    )
                }
            }
        },
    )
}

// v0.1.71: column header bar above the list rows. Weights match
// VideoListRow exactly so the columns line up.
@Composable
private fun VideoListHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PRODUCT NAME", Modifier.weight(3f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("SIZE", Modifier.weight(1f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("LENGTH", Modifier.weight(1f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("ORIENTATION", Modifier.weight(1.4f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("RESOLUTION", Modifier.weight(1.4f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("SKU NAME", Modifier.weight(2f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(40.dp))   // selection check column
    }
}

// v0.1.71: single list row. Selected = green border + check; pending
// videos are dimmed + badged (can't be pushed); orphans are badged but
// still pushable.
@Composable
private fun VideoListRow(
    video: PickerVideo,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val productName = video.productName()
    val subtitle = if (video.item.title != productName) video.item.title else null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BoneSoft else Bone)
            .border(if (selected) 2.dp else 1.dp, if (selected) Ok else BoneLine, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .alpha(if (video.pending) 0.55f else 1f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(if (compact) 1f else 3f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v0.1.77: product packshot (tm:rw main image). Coil loads the
            // small public Drive thumbnail; nothing renders while loading or
            // on error, so brand/orphan videos with no image just show text.
            video.packshotUrl?.let { url ->
                SubcomposeAsyncImage(
                    model = url,
                    contentDescription = productName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(width = 48.dp, height = 32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Bone),
                    loading = {},
                    error = {},
                )
                Spacer(Modifier.width(if (compact) 10.dp else 12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        productName,
                        color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val badge = when {
                        video.pending -> "PENDING"
                        !video.assigned -> "ORPHAN"
                        else -> null
                    }
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (video.pending) Color(0xFF2D5BFF) else Amber)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(badge, color = Bone, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (subtitle != null) {
                    Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // v0.2.0: compact has no columns to line up with, so the same
                // facts fold into one dot-separated line under the title. The
                // SKU is dropped rather than truncated — it's the least useful
                // of the six when you're picking a video by sight.
                if (compact) {
                    Text(
                        listOf(
                            video.sizeLabel(),
                            video.lengthLabel(),
                            video.orientationLabel(),
                            video.resolutionLabel(),
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Muted, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (!compact) {
            Text(video.sizeLabel(), Modifier.weight(1f), color = Muted, fontSize = 13.sp)
            Text(video.lengthLabel(), Modifier.weight(1f), color = Muted, fontSize = 13.sp)
            Text(video.orientationLabel(), Modifier.weight(1.4f), color = Muted, fontSize = 13.sp)
            Text(video.resolutionLabel(), Modifier.weight(1.4f), color = Muted, fontSize = 13.sp)
            Text(video.sku ?: "—", Modifier.weight(2f), color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) {
            if (selected) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(Ok),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Bone, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).border(2.dp, BoneLine, RoundedCornerShape(5.dp)))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Success
// ─────────────────────────────────────────────────────────────
@Composable
fun SuccessScreen(
    video: VideoItem,
    count: Int = 1,
    onBack: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val headline = if (count <= 1) "Added to playlist" else "Added $count videos"
    val sub = when {
        count <= 1 -> video.title
        count == 2 -> "${video.title} and 1 more"
        else       -> "${video.title} and ${count - 1} more"
    }
    Layout(
        left = { Rail("Added", "Returning to the playlist in 10s. Tap Back to pick another.", 3) },
        right = {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0x263D8C4B)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Ok, fontSize = 40.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(32.dp))
                Text(headline, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text(sub, fontSize = 18.sp, color = Muted, textAlign = TextAlign.Center)

                Spacer(Modifier.height(40.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(BoneSoft)
                            .border(1.dp, BoneLine, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .clickable { onBack() }
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                    ) { Text("← Back to videos", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                    Box(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(Ink)
                            .clickable { onDone() }
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                    ) { Text("View playlist", color = Bone, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                }
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────
// Shared search bar
// ─────────────────────────────────────────────────────────────
@Composable
private fun SearchBar(
    value: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    placeholder: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(BoneSoft)
            .border(1.dp, BoneLine, RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.text.isEmpty()) {
            Text(placeholder, color = Muted, fontSize = 18.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onQueryChange,
            textStyle = TextStyle(color = Ink, fontSize = 18.sp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
