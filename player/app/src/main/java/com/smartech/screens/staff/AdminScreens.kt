package com.smartech.screens.staff

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartech.screens.BuildConfig
import com.smartech.screens.ScreensApp
import com.smartech.screens.data.LocationTaxonomy
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.data.UserDirectory
import com.smartech.screens.update.Updater
import com.smartech.screens.util.DeviceInfo
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF141414)
private val Bone = Color(0xFFF7F6F2)
private val BoneSoft = Color(0xFFE6E2D6)
// v0.1.19: bumped from #E2DED3 -> #B8B1A0 so borders read on
// low-contrast TVs (Sumvision Cyclone, generic HDMI sticks). Old
// value was almost invisible against the Bone background.
private val BoneLine = Color(0xFFB8B1A0)
// v0.1.19: secondary text was #6E6B62 (mid-gray) — passed WCAG AA
// at 5:1 against Bone but felt washed out on a TV viewed from the
// other side of a room. Dropping to #3A3832 takes contrast to
// ~11:1, comfortably AAA, and matches the weight of the Ink primary
// text without losing the secondary/primary visual hierarchy.
private val Muted = Color(0xFF3A3832)

// ─────────────────────────────────────────────────────────────
// Super-admin home — branches into "Swap content" or "Device admin"
// ─────────────────────────────────────────────────────────────
@Composable
fun SuperAdminHome(
    user: UserDirectory.User,
    onSwapContent: () -> Unit,
    onDeviceAdmin: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        // Left rail
        Box(
            Modifier
                .width(420.dp)
                .fillMaxHeight()
                .background(Ink)
                .padding(horizontal = 48.dp, vertical = 56.dp)
        ) {
            Column {
                Text("Super admin".uppercase(), color = Color(0x73FFFFFF), fontSize = 11.sp, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(14.dp))
                Text("Welcome, ${user.name}", color = Bone, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pick what to do. Tap cancel to dismiss the overlay.",
                    color = Color(0x99FFFFFF), fontSize = 14.sp,
                )
            }
        }

        // Right pane — two big actions
        Column(
            Modifier
                .fillMaxSize()
                .background(Bone)
                .padding(horizontal = 64.dp, vertical = 56.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            HomeAction(
                title = "Swap content",
                sub = "Pick a brand and a video to play on this screen.",
                onClick = onSwapContent,
            )
            Spacer(Modifier.height(20.dp))
            HomeAction(
                title = "Device admin",
                sub = "See logs, change orientation, location, and cache settings.",
                onClick = onDeviceAdmin,
            )
            Spacer(Modifier.height(40.dp))
            Text(
                "Cancel",
                color = Muted,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onCancel() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun HomeAction(title: String, sub: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(BoneSoft)
            .border(1.dp, BoneLine, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(28.dp),
    ) {
        Column {
            Text(title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Muted, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Device admin — info, log feed, editable config
// ─────────────────────────────────────────────────────────────
@Composable
fun DeviceAdminScreen(
    repository: PlayerRepository,
    user: UserDirectory.User,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = repository.store

    val deviceId by store.deviceId.collectAsState(initial = null)
    val orientation by store.orientationOverride.collectAsState(initial = null)
    val syncGroup by repository.syncGroupFlow.collectAsState()
    val syncGroupMembers by repository.syncGroupMembersFlow.collectAsState()
    val availableSyncGroups by repository.availableSyncGroupsFlow.collectAsState()
    val cacheCap by store.cacheCapBytes.collectAsState(initial = 8L * 1024 * 1024 * 1024)
    val info = remember { DeviceInfo.snapshot(ctx) }

    // Structured location fields
    val locRegion     by store.locRegion.collectAsState(initial = null)
    val locCity       by store.locCity.collectAsState(initial = null)
    val locStoreId    by store.locStoreId.collectAsState(initial = null)
    val locConcept    by store.locConcept.collectAsState(initial = null)
    val locFloor      by store.locFloor.collectAsState(initial = null)
    val locTable      by store.locTable.collectAsState(initial = null)
    val locScreenCode by store.locScreenCode.collectAsState(initial = null)
    val liveServerUrl by store.liveServerUrl.collectAsState(initial = null)

    // v0.1.22: Recent activity is a single focus target by default —
    // tapping / Enter opens the full viewer overlay. This stops D-pad
    // operators from having to scroll past dozens of log entries to
    // reach the Reboot / Reinitialise actions below.
    var logViewerOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        // Left rail
        Box(
            Modifier
                .width(420.dp)
                .fillMaxHeight()
                .background(Ink)
                .padding(horizontal = 48.dp, vertical = 56.dp),
        ) {
            Column {
                Text("Device admin".uppercase(), color = Color(0x73FFFFFF), fontSize = 11.sp, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(14.dp))
                Text("This tablet", color = Bone, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Acting as ${user.name} (${UserDirectory.roleLabel(user.role)}). " +
                        "Changes save instantly.",
                    color = Color(0x99FFFFFF),
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(40.dp))

                Text("Server", color = Color(0x66FFFFFF), fontSize = 11.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                ServerStatusLine(repository, fallbackUrl = BuildConfig.API_BASE)
                Spacer(Modifier.height(20.dp))
                Text("Build", color = Color(0x66FFFFFF), fontSize = 11.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Bone, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // Right pane — scrollable
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Bone)
                .padding(horizontal = 56.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Row {
                    Text("Back", color = Muted, fontSize = 16.sp,
                        modifier = Modifier.clickable { onBack() }.padding(8.dp))
                    Spacer(Modifier.weight(1f))
                    // Version always visible at the top of the right pane —
                    // doesn't disappear behind scroll, doesn't depend on the
                    // Build line in the rail being above the fold.
                    Text(
                        "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                        color = Muted, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    // "Return to splash" — outlined button so it reads as a
                    // deliberate action rather than a destructive flag. Wraps
                    // the same onCancel callback (closes the staff overlay
                    // back to the player loop, which renders the splash
                    // until the next playlist tick).
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, Muted, RoundedCornerShape(6.dp))
                            .clickable { onCancel() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "Return to splash",
                            color = Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // Recent activity — hoisted to the top on v0.1.15 so it's
            // visible on first render on Android TV / TX3-class boxes.
            // v0.1.22: collapsed into a single focusable preview card
            // by default. Tapping (or pressing Enter on TV) opens a
            // full-screen viewer with filter chips — operators no
            // longer have to scroll through 50+ entries to reach the
            // Reboot / Reinitialise actions below.
            item { LogPanel(onExpand = { logViewerOpen = true }) }

            // Info card
            item {
                CardSection("Device info") {
                    InfoRow("Device ID",   deviceId ?: "—")
                    // "Screen ID" historically came from the legacy /device/register
                    // flow which the live-server path never hits, so it always read
                    // "demo (no backend)" even on a fully-registered tablet. Show
                    // the human-readable screen code set during onboarding instead;
                    // the device ID above is the real server-side identifier.
                    InfoRow("Screen code", locScreenCode ?: "(not set)")
                    // Sync group is read-only here; admins set it from the CMS
                    // screen detail page. Showing it lets staff confirm whether
                    // this tablet is locked to another screen's playback or
                    // running independently.
                    InfoRow("Sync group",  syncGroup ?: "(independent)")
                    InfoRow("Display",     "${info.widthPx} × ${info.heightPx} · ${info.orientation}")
                    InfoRow("RAM",        "${info.ramMb} MB")
                    InfoRow("Cached",     "${repository.cache.cachedIds().size} videos · ${formatBytes(repository.cache.totalBytes())}")
                    InfoRow("Free disk",  formatBytes(ctx.filesDir.usableSpace))
                }
            }

            // v0.1.35: Sync group card — lists every member of this
            // screen's group with online state + a Calibrate button
            // so the operator can verify clock sync without leaving
            // the device admin.
            // v0.1.36: now always visible. When the screen isn't in a
            // group, the card pivots to a Join picker listing every
            // existing sync group on the fleet, so staff can attach
            // this tablet without going back to the CMS. Leaving the
            // group is also one tap from here.
            item {
                SyncGroupCard(
                    currentGroupId = syncGroup,
                    members = syncGroupMembers,
                    availableGroups = availableSyncGroups,
                    onJoin = { gid ->
                        scope.launch {
                            runCatching { repository.setSyncGroupOnServer(gid) }
                                .onFailure { LogBuffer.w("Admin", "Join group failed: ${it.message}") }
                            repository.refreshNow()
                        }
                    },
                    onLeave = {
                        scope.launch {
                            runCatching { repository.setSyncGroupOnServer(null) }
                                .onFailure { LogBuffer.w("Admin", "Leave group failed: ${it.message}") }
                            repository.refreshNow()
                        }
                    },
                    onCalibrate = {
                        scope.launch {
                            runCatching { repository.triggerLocalCalibration(60) }
                                .onFailure { LogBuffer.w("Admin", "Calibrate failed: ${it.message}") }
                        }
                    },
                )
            }

            // Location — cascading dropdowns
            item {
                CardSection("Location") {
                    LocationPicker(
                        region = locRegion,
                        city = locCity,
                        storeId = locStoreId,
                        concept = locConcept,
                        floor = locFloor,
                        table = locTable,
                        screenCode = locScreenCode,
                        // Region + city are read-only; store-pick atomically writes all three
                        // so cascade-clear in setLocRegion/setLocCity can't wipe siblings.
                        onRegion = { /* set via onStore */ },
                        onCity = { /* set via onStore */ },
                        onStore = { v ->
                            val picked = LocationTaxonomy.storeById(v)
                            val cityCode = picked?.cityCode
                            val regionName = LocationTaxonomy.regionOfCity(cityCode)?.name
                            val clearConcept = cityCode != null &&
                                cityCode !in LocationTaxonomy.MULTI_CONCEPT_CITIES
                            val clearFloorTable = cityCode != "NYC"
                            scope.launch {
                                store.setLocStoreCascade(v, cityCode, regionName, clearConcept, clearFloorTable)
                                LogBuffer.i("Admin", "Store → ${picked?.name ?: "—"} by ${user.name}")
                            }
                        },
                        onConcept = { v -> scope.launch { store.setLocConcept(v); LogBuffer.i("Admin", "Concept → ${v ?: "—"} by ${user.name}") } },
                        onFloor = { v -> scope.launch { store.setLocFloor(v); LogBuffer.i("Admin", "Floor → ${v ?: "—"} by ${user.name}") } },
                        onTable = { v -> scope.launch { store.setLocTable(v); LogBuffer.i("Admin", "Table → ${v ?: "—"} by ${user.name}") } },
                        onScreenCode = { v -> scope.launch { store.setLocScreenCode(v); LogBuffer.i("Admin", "Screen code → ${v.ifBlank { "—" }} by ${user.name}") } },
                    )
                }
            }

            // Live demo server (LAN). When set, the player polls this URL's
            // /api/state every few seconds and replays whatever the CMS pushed.
            item {
                CardSection("Live demo server") {
                    LpEditableRow(
                        label = "Server URL",
                        // Default to the build's API_BASE (Cloud Run by default,
                        // overridable at build time via -PapiBase=…) so admins
                        // see the canonical URL without retyping. Strip the
                        // /api convention suffix — this field expects bare URL.
                        value = liveServerUrl ?: BuildConfig.API_BASE.removeSuffix("/api"),
                        placeholder = "https://screens.smartechworld.com",
                        onSave = { v ->
                            scope.launch {
                                store.setLiveServerUrl(v.takeIf { it.isNotBlank() })
                                LogBuffer.i("Admin", "Live server set to '${v.ifBlank { "—" }}' by ${user.name}")
                                if (v.isNotBlank()) {
                                    runCatching { repository.refreshPlaylist() }
                                }
                            }
                        }
                    )
                }
            }

            // Editable config
            item {
                CardSection("Configuration") {
                    OrientationRow(
                        current = orientation,
                        onChange = { value ->
                            scope.launch {
                                store.setOrientationOverride(value)
                                LogBuffer.i("Admin", "Orientation override → ${value ?: "AUTO"} by ${user.name}")
                            }
                        }
                    )
                    Divider()
                    EditableRow(
                        label = "Cache cap (GB)",
                        value = (cacheCap / (1024L * 1024 * 1024)).toString(),
                        placeholder = "8",
                        onSave = { v ->
                            v.toLongOrNull()?.takeIf { it in 1..64 }?.let { gb ->
                                scope.launch {
                                    store.setCacheCapBytes(gb * 1024L * 1024 * 1024)
                                    LogBuffer.i("Admin", "Cache cap → ${gb} GB by ${user.name}")
                                }
                            }
                        }
                    )
                    // "Poll interval" setting removed in v0.1.6.1 — it
                    // was a leftover from the legacy /device/settings
                    // flow and the live-server path uses hardcoded 3 s
                    // (or 60 s when Low data mode is on) instead. The
                    // input was misleading: editing it did nothing.
                }
            }

            // Actions
            item {
                // Pull the singleton updater off the Application so the
                // action below can poke it directly. The check is
                // already gated to non-destructive — surfaceFailures is
                // true so the user sees "Up to date" feedback via the
                // overlay if there's nothing new to install.
                val updater = (ctx.applicationContext as ScreensApp).updater
                val updateState by updater.state.collectAsState()
                val checking = updateState is Updater.State.Checking
                CardSection("Actions") {
                    ActionRow(
                        title = "Run network test",
                        sub = "Latency, packet loss, download / upload, link details.",
                        onClick = onOpenDiagnostics,
                    )
                    Divider()
                    ActionRow(
                        title = if (checking) "Checking for updates…" else "Check for updates",
                        sub = "Asks the server whether a newer player APK is published. Triggers the installer overlay if one is.",
                        onClick = {
                            if (!checking) {
                                LogBuffer.i("Admin", "Update check triggered by ${user.name}")
                                scope.launch { updater.checkAndUpdate(surfaceFailures = true) }
                            }
                        },
                    )
                    Divider()
                    ActionRow(
                        title = "Refresh playlist now",
                        sub = "Re-fetch from the server (or demo source).",
                        onClick = { scope.launch { repository.refreshPlaylist() } },
                    )
                    Divider()
                    // v0.1.81: relaunch the player from the staff overlay —
                    // quickest recovery for a screen stuck on the splash or
                    // wrong content, short of a full device reboot. Cache +
                    // registration survive (scheduleSelfRestart relaunches the
                    // activity via the launcher intent).
                    ActionRow(
                        title = "Restart app",
                        sub = "Relaunch the player now. Cached videos + registration are kept.",
                        onClick = {
                            LogBuffer.w("Admin", "Restart app triggered by ${user.name}")
                            repository.scheduleSelfRestart()
                        },
                    )
                    Divider()
                    // v0.1.66: manual content-library re-pull. The library
                    // (brands + videos) also refreshes automatically on
                    // every launch, but this lets staff force it after a
                    // CMS-side change without restarting the app.
                    ActionRow(
                        title = "Refresh content library now",
                        sub = "Re-pull the brand + video list from the server. Also runs automatically on every launch.",
                        onClick = { repository.refreshLibraryNow() },
                    )
                    Divider()
                    ActionRow(
                        title = "Re-register device",
                        sub = "Wipes the device token. Next launch registers fresh.",
                        onClick = {
                            scope.launch {
                                store.clearRegistration()
                                LogBuffer.w("Admin", "Registration cleared by ${user.name}")
                            }
                        },
                        destructive = true,
                    )
                    Divider()
                    ActionRow(
                        title = "Reinitialise screen",
                        sub = "Clear all location fields and run first-time setup again.",
                        onClick = {
                            scope.launch {
                                store.setLocRegion(null)
                                store.setLocCity(null)
                                store.setLocStoreId(null)
                                store.setLocConcept(null)
                                store.setLocFloor(null)
                                store.setLocTable(null)
                                store.setLocScreenCode(null)
                                store.clearRegistration()
                                LogBuffer.w("Admin", "Screen reinitialised by ${user.name} — onboarding will start")
                            }
                        },
                        destructive = true,
                    )
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
    // v0.1.22: full-screen viewer overlay, rendered as a sibling of the
    // Row so it sits above the entire Device admin pane (including the
    // dark left rail). Opens when LogPanel's card is clicked; Back or
    // the Close button dismisses.
    if (logViewerOpen) {
        LogViewerOverlay(onClose = { logViewerOpen = false })
    }
    } // end outer Box
}

/** v0.1.22: passes the open-the-viewer callback from DeviceAdminScreen
 *  down to LogPanel. Kept as a typealias so the LazyColumn item slot
 *  stays a one-liner. */
private typealias LogPanelExpandCallback = () -> Unit

@Composable
private fun CardSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, BoneLine, RoundedCornerShape(12.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EditableRow(
    label: String,
    value: String,
    placeholder: String,
    onSave: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(TextFieldValue(value)) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(0.6f))
        if (editing) {
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(BoneSoft)
                    .border(1.dp, BoneLine, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.text.isEmpty()) {
                    Text(placeholder, color = Color(0xFF9A968A), fontSize = 13.sp)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text("Save", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        onSave(draft.text)
                        editing = false
                    }
                    .padding(8.dp))
            Text("Cancel", color = Muted, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { editing = false; draft = TextFieldValue(value) }
                    .padding(8.dp))
        } else {
            Text(
                value.ifBlank { "—" },
                color = if (value.isBlank()) Muted else Ink,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text("Edit", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { editing = true }
                    .padding(8.dp))
        }
    }
}

@Composable
private fun OrientationRow(current: String?, onChange: (String?) -> Unit) {
    val options = listOf(null to "Auto", "LANDSCAPE" to "Landscape", "PORTRAIT" to "Portrait")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Orientation", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(0.6f))
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, label) ->
                val selected = current == value
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) Ink else BoneSoft)
                        .border(1.dp, if (selected) Ink else BoneLine, RoundedCornerShape(4.dp))
                        .clickable { onChange(value) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(label, color = if (selected) Bone else Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, sub: String, onClick: () -> Unit, destructive: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (destructive) Color(0xFFA63824) else Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        Text("›", color = Muted, fontSize = 18.sp)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(BoneLine.copy(alpha = 0.5f)))
}

// ─────────────────────────────────────────────────────────────
// Log panel — newest first, last 100 lines
//
// v0.1.22: collapsed by default to a single focusable preview card.
// Tap / Enter opens [LogViewerOverlay], which has filter chips for
// Errors / Warnings / Info. Operators no longer have to scroll
// through every entry to reach the Reboot / Reinitialise rows.
// ─────────────────────────────────────────────────────────────
@Composable
private fun LogPanel(onExpand: LogPanelExpandCallback) {
    val entries by LogBuffer.entries.collectAsState()

    val errorCount = entries.count { it.level == LogBuffer.Level.E }
    val warnCount  = entries.count { it.level == LogBuffer.Level.W }
    val infoCount  = entries.count { it.level == LogBuffer.Level.I }
    val mostRecent = entries.firstOrNull()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.UK) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recent activity", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                "Clear",
                color = Muted, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { LogBuffer.clear() }
                    .padding(8.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        // Single focusable preview card. The whole thing is one click
        // / focus target, so D-pad DOWN past it lands directly on the
        // Device info card below — no entry-by-entry tab traversal.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, BoneLine, RoundedCornerShape(12.dp))
                .clickable { onExpand() }
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Column {
                if (entries.isEmpty()) {
                    Text(
                        "No activity yet — uploads, pushes, and errors appear here.",
                        color = Muted, fontSize = 13.sp,
                    )
                } else {
                    // Count summary as colour-coded chips, mirroring
                    // [LogViewerOverlay]'s filter palette so the
                    // visual mapping carries across the two views.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LogCountChip(label = "$errorCount errors",  color = Color(0xFFA63824))
                        Spacer(Modifier.width(8.dp))
                        LogCountChip(label = "$warnCount warnings", color = Color(0xFFE8A33D))
                        Spacer(Modifier.width(8.dp))
                        LogCountChip(label = "$infoCount info",     color = Color(0xFF3D8C4B))
                    }
                    if (mostRecent != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(levelColor(mostRecent.level))
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                fmt.format(Date(mostRecent.time)),
                                color = Muted, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                mostRecent.message + (mostRecent.cause?.let { " · $it" } ?: ""),
                                color = Ink, fontSize = 13.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tap to browse all ${entries.size} event${if (entries.size == 1) "" else "s"}",
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** Small coloured pill — count + label — used both in the LogPanel
 *  preview and as inactive-state filter chips inside the viewer. */
@Composable
private fun LogCountChip(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.size(6.dp))
        Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * v0.1.22: full-screen viewer for the in-memory log. Opens when the
 * preview card on the Device admin page is tapped. Has filter chips
 * for All / Errors / Warnings / Info, a scrollable LazyColumn of
 * entries, and a Close button. Back key (TV remote / hardware) also
 * dismisses.
 *
 * Filter palette matches the [levelColor] dots elsewhere — red for
 * errors, amber for warnings, green for info — so the visual mapping
 * carries across the preview, the dots, and the chips themselves.
 */
@Composable
private fun LogViewerOverlay(onClose: () -> Unit) {
    val entries by LogBuffer.entries.collectAsState()
    var filter by remember { mutableStateOf<LogBuffer.Level?>(null) }  // null = All
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.UK) }

    val filtered = remember(entries, filter) {
        if (filter == null) entries
        else entries.filter { it.level == filter }
    }
    val errorCount = entries.count { it.level == LogBuffer.Level.E }
    val warnCount  = entries.count { it.level == LogBuffer.Level.W }
    val infoCount  = entries.count { it.level == LogBuffer.Level.I }

    // Back key dismisses. Declared at this composable level so it
    // unregisters automatically when the overlay leaves composition.
    androidx.activity.compose.BackHandler(enabled = true, onBack = onClose)

    // Scrim — clicking outside the card closes. The card body itself
    // swallows clicks via its own clickable {} no-op (no onClick),
    // otherwise tapping anywhere on the card would also dismiss.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onClose),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp))
                .background(Bone)
                .clickable(onClick = {})       // swallow scrim clicks
                .padding(24.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header — title + close
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recent activity",
                        color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, BoneLine, RoundedCornerShape(6.dp))
                            .clickable(onClick = onClose)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("Close", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(14.dp))

                // Filter chips — All / Errors / Warnings / Info.
                // Tappable + focusable; selected state highlights with
                // the matching level colour so the visual mapping
                // carries from the LogPanel preview.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        label = "All (${entries.size})",
                        selected = filter == null,
                        accent = Ink,
                        onClick = { filter = null },
                    )
                    FilterChip(
                        label = "Errors ($errorCount)",
                        selected = filter == LogBuffer.Level.E,
                        accent = Color(0xFFA63824),
                        onClick = { filter = LogBuffer.Level.E },
                    )
                    FilterChip(
                        label = "Warnings ($warnCount)",
                        selected = filter == LogBuffer.Level.W,
                        accent = Color(0xFFE8A33D),
                        onClick = { filter = LogBuffer.Level.W },
                    )
                    FilterChip(
                        label = "Info ($infoCount)",
                        selected = filter == LogBuffer.Level.I,
                        accent = Color(0xFF3D8C4B),
                        onClick = { filter = LogBuffer.Level.I },
                    )
                }
                Spacer(Modifier.height(12.dp))

                // Entries list. Empty-state copy adapts to the filter:
                // "no errors" reads better than "no activity yet"
                // when the user has explicitly chosen Errors and the
                // log is mostly green.
                if (filtered.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BoneSoft)
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (filter) {
                                LogBuffer.Level.E -> "No errors recorded."
                                LogBuffer.Level.W -> "No warnings recorded."
                                LogBuffer.Level.I -> "No info events recorded."
                                else -> "No activity yet."
                            },
                            color = Muted, fontSize = 14.sp,
                        )
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, BoneLine, RoundedCornerShape(12.dp)),
                    ) {
                        items(filtered.size) { i ->
                            val e = filtered[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    // No-op clickable so D-pad can land
                                    // on each row + scroll the inner
                                    // LazyColumn on TV. Same trick as
                                    // v0.1.15's original visibility fix.
                                    .clickable(onClick = {})
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(levelColor(e.level))
                                )
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    fmt.format(Date(e.time)),
                                    color = Muted, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(76.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(e.tag, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        e.message + (e.cause?.let { " · $it" } ?: ""),
                                        color = Ink, fontSize = 13.sp,
                                    )
                                }
                            }
                            if (i < filtered.size - 1) Divider()
                        }
                    }
                }
            }
        }
    }
}

/** Filter chip used inside [LogViewerOverlay]. Selected state pulls
 *  the accent colour up as background + ink as text; unselected is a
 *  bordered ghost button. TvFocusIndication adds the amber focus
 *  ring on D-pad navigation. */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) accent else Color.Transparent)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) accent else BoneLine,
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun levelColor(level: LogBuffer.Level): Color = when (level) {
    LogBuffer.Level.D -> Color(0xFF9A968A)
    LogBuffer.Level.I -> Color(0xFF3D8C4B)
    LogBuffer.Level.W -> Color(0xFFE8A33D)
    LogBuffer.Level.E -> Color(0xFFA63824)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024        -> "%.0f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L               -> "%.0f KB".format(bytes / 1024.0)
    else                         -> "$bytes B"
}
