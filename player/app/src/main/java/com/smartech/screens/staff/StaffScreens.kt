package com.smartech.screens.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(horizontal = 48.dp, vertical = 56.dp)
    ) {
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
            Spacer(Modifier.weight(1f))
            Text(
                "Swap what's playing".uppercase(),
                color = Color(0x73FFFFFF),
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(title, color = Bone, fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text(sub, color = Color(0x99FFFFFF), fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))
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
}

@Composable
private fun Layout(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(420.dp).fillMaxSize()) { left() }
        Box(Modifier.fillMaxSize().background(Bone)) { right() }
    }
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

    Layout(
        left = { Rail("Tap in your PIN", "Last 4 of your staff ID. Session ends automatically.", 0) },
        right = {
            Column(
                Modifier.fillMaxSize(),
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
private fun Numpad(onKey: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(
            listOf(1, 2, 3),
            listOf(4, 5, 6),
            listOf(7, 8, 9),
            listOf(0, 0, -1),
        ).forEachIndexed { rowIdx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEachIndexed { colIdx, n ->
                    if (rowIdx == 3 && colIdx == 0) {
                        Spacer(Modifier.size(140.dp, 90.dp))
                    } else {
                        NumpadKey(n, onClick = { onKey(n) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NumpadKey(n: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(140.dp, 90.dp)
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
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium,
            color = Ink,
        )
    }
}

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
    val brandsFromServer = library?.brands?.map { it.name } ?: emptyList()
    val brands = if (brandsFromServer.isNotEmpty()) brandsFromServer
                 else listOf("Anker", "Bang & Olufsen", "DVX", "Ember", "Foreo", "Motorola", "SONOS")
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val filtered = brands.filter { it.contains(query.text, ignoreCase = true) }

    Layout(
        left = { Rail("Which brand?", "Search or tap a brand. Tap back to re-enter PIN.", 1) },
        right = {
            Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 56.dp)) {
                SearchBar(query, onQueryChange = { query = it }, placeholder = "Search brands")
                Spacer(Modifier.height(28.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(filtered) { brand ->
                        BrandCard(brand) { onPickBrand(brand) }
                    }
                }
                Spacer(Modifier.weight(1f))
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
private fun BrandCard(brand: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Bone)
            .border(1.dp, BoneLine, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(22.dp)
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BoneSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(brand.first().toString(), fontSize = 32.sp, fontWeight = FontWeight.Medium, color = Ink)
        }
        Spacer(Modifier.height(18.dp))
        Text(brand, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink)
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
@Composable
fun VideoPickerScreen(
    brand: String,
    videos: List<VideoItem>,
    onBack: () -> Unit,
    onPick: (List<VideoItem>) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    // Track selected ids (not the full VideoItem) so the set is
    // stable across re-filter / re-fetch — same video keeps its tick
    // even if the underlying list reshuffles.
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val filtered = videos.filter { it.title.contains(query.text, ignoreCase = true) }
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
            Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 56.dp)) {
                SearchBar(query, onQueryChange = { query = it }, placeholder = "Search $brand videos")
                Spacer(Modifier.height(28.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(filtered) { v ->
                        VideoCard(
                            video = v,
                            selected = v.id in selectedIds,
                            onClick = {
                                selectedIds = if (v.id in selectedIds) selectedIds - v.id
                                              else selectedIds + v.id
                            },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavPill("← Back", onBack)
                    Spacer(Modifier.weight(1f))
                    // Count badge so the staff member can see what
                    // they've queued without scrolling back through
                    // the grid. Hidden when none selected.
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
                            // Preserve the order the staff picked
                            // them in (selectedIds is a LinkedHashSet
                            // by construction since Kotlin's `+` on a
                            // Set keeps insertion order).
                            val picked = selectedIds.mapNotNull { id ->
                                videos.firstOrNull { it.id == id }
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

@Composable
private fun VideoCard(video: VideoItem, selected: Boolean, onClick: () -> Unit) {
    // Selected = green-tinted border + check overlay so it reads on a
    // TV across the room. Unselected stays subtle so the grid still
    // scans cleanly when nothing's been picked yet.
    val borderColor = if (selected) Ok else BoneLine
    val borderWidth = if (selected) 3.dp else 1.dp
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Bone)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .fillMaxWidth()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Color(0xFF27272A)),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                video.title.uppercase().take(30),
                color = Bone,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(16.dp),
            )
            if (selected) {
                // Selection checkmark in the top-right corner.
                Box(
                    Modifier
                        .padding(12.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Ok)
                        .align(Alignment.TopEnd),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Bone, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Column(Modifier.padding(16.dp)) {
            Text(video.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                (video.product ?: "Brand loop") + " · " + (video.durationSec?.let { "${it}s" } ?: "—"),
                fontSize = 13.sp,
                color = Muted,
            )
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
