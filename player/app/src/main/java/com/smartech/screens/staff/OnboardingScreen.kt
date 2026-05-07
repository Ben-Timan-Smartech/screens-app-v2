package com.smartech.screens.staff

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartech.screens.BuildConfig
import com.smartech.screens.data.LocationTaxonomy
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.launch

private val Ink      = Color(0xFF141414)
private val Bone     = Color(0xFFF7F6F2)
private val BoneSoft = Color(0xFFEFEDE6)
private val BoneLine = Color(0xFFE2DED3)
private val Muted    = Color(0xFF6E6B62)

/**
 * First-run setup. Required before the player will register or play anything.
 * Captures the structured location, persists each field as it's chosen, and
 * triggers `ensureRegistered` once the required fields are all set.
 *
 * Shown by [com.smartech.screens.MainActivity] whenever
 * [com.smartech.screens.data.DeviceStore.isOnboarded] is false.
 */
@Composable
fun OnboardingScreen(
    repository: PlayerRepository,
    onDone: () -> Unit,
) {
    val store = repository.store
    val scope = rememberCoroutineScope()

    val region     by store.locRegion.collectAsState(initial = null)
    val city       by store.locCity.collectAsState(initial = null)
    val storeId    by store.locStoreId.collectAsState(initial = null)
    val concept    by store.locConcept.collectAsState(initial = null)
    val floor      by store.locFloor.collectAsState(initial = null)
    val table      by store.locTable.collectAsState(initial = null)
    val screenCode by store.locScreenCode.collectAsState(initial = null)
    val serverUrl  by store.liveServerUrl.collectAsState(initial = null)

    // Concept is only required for cities with multiple in-store concepts
    // (NYC, LDN). BER and ROM are single-concept stores, so the concept
    // dropdown isn't shown and we don't gate on it here.
    val conceptRequired = !city.isNullOrBlank() &&
        city in com.smartech.screens.data.LocationTaxonomy.MULTI_CONCEPT_CITIES
    val ready =
        !region.isNullOrBlank() && !city.isNullOrBlank() &&
            !storeId.isNullOrBlank() &&
            (!conceptRequired || !concept.isNullOrBlank()) &&
            !screenCode.isNullOrBlank()

    Row(Modifier.fillMaxSize().background(Bone)) {
        // Left rail — same dark slab the staff overlay uses.
        Box(
            Modifier
                .width(420.dp)
                .fillMaxHeight()
                .background(Ink)
                .padding(horizontal = 48.dp, vertical = 56.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .height(44.dp)
                            .width(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Bone),
                        contentAlignment = Alignment.Center,
                    ) { Text("S", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Screens", color = Bone, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Smartech Group", color = Color(0x8CFFFFFF), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(48.dp))
                Text(
                    "First-time setup".uppercase(),
                    color = Color(0x73FFFFFF), fontSize = 11.sp, letterSpacing = 1.6.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Tell us where this screen lives",
                    color = Bone, fontSize = 30.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "We need the location to register this tablet on the system. " +
                        "An admin can change any of these later from device admin.",
                    color = Color(0x99FFFFFF), fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                Text("Server", color = Color(0x66FFFFFF), fontSize = 11.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(6.dp))
                ServerStatusLine(repository, fallbackUrl = BuildConfig.API_BASE)
            }
        }

        // Right pane — scrollable form.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Bone)
                .padding(horizontal = 56.dp, vertical = 56.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Text("Connect to the demo server", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Optional. Enter the LAN URL of the laptop running the CMS (printed by serve.py on startup). Leave blank to keep the offline demo loop.",
                    color = Muted, fontSize = 14.sp,
                )
            }

            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, BoneLine, RoundedCornerShape(12.dp)),
                ) {
                    LpEditableRow(
                        label = "Server URL",
                        // Prefill from BuildConfig (which the build pins to the
                        // hosted demo deploy by default) so a fresh-install
                        // tablet talks to the cloud without the user having
                        // to type anything. The /api suffix from API_BASE is
                        // stripped because this field is the bare URL — the
                        // player appends /api/... to paths itself.
                        value = serverUrl ?: BuildConfig.API_BASE.removeSuffix("/api"),
                        placeholder = "https://screens-app-v2-962486680568.europe-west1.run.app",
                        onSave = { v ->
                            scope.launch { store.setLiveServerUrl(v.takeIf { it.isNotBlank() }) }
                        },
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Where is this screen?", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Region, city, store, concept, and screen code are required. Floor and table are optional.",
                    color = Muted, fontSize = 14.sp,
                )
            }

            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, BoneLine, RoundedCornerShape(12.dp)),
                ) {
                    LocationPicker(
                        region = region, city = city, storeId = storeId,
                        concept = concept, floor = floor, table = table, screenCode = screenCode,
                        // Region + city write happens inside setLocStoreCascade.
                        onRegion     = { /* read-only — set via onStore */ },
                        onCity       = { /* read-only — set via onStore */ },
                        onStore      = { v ->
                            val picked = com.smartech.screens.data.LocationTaxonomy.storeById(v)
                            val cityCode = picked?.cityCode
                            val regionName = com.smartech.screens.data.LocationTaxonomy.regionOfCity(cityCode)?.name
                            val clearConcept = cityCode != null &&
                                cityCode !in com.smartech.screens.data.LocationTaxonomy.MULTI_CONCEPT_CITIES
                            // Floor/Table only apply to NYC right now.
                            val clearFloorTable = cityCode != "NYC"
                            scope.launch {
                                store.setLocStoreCascade(v, cityCode, regionName, clearConcept, clearFloorTable)
                            }
                        },
                        onConcept    = { v -> scope.launch { store.setLocConcept(v) } },
                        onFloor      = { v -> scope.launch { store.setLocFloor(v) } },
                        onTable      = { v -> scope.launch { store.setLocTable(v) } },
                        onScreenCode = { v -> scope.launch { store.setLocScreenCode(v.takeIf { it.isNotBlank() }) } },
                        showRequiredMarkers = true,
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val storeName = LocationTaxonomy.storeById(storeId)?.name
                    Text(
                        if (ready)
                            "Ready: ${region}/${city}/${storeName ?: storeId}/${concept}" +
                                (floor?.let { "/${it}" } ?: "") +
                                (table?.let { "/${it}" } ?: "") +
                                "/${screenCode}"
                        else
                            "Pick all required fields above to continue",
                        color = if (ready) Color(0xFF2F6B3B) else Muted,
                        fontSize = 13.sp,
                        fontFamily = if (ready) FontFamily.Monospace else FontFamily.Default,
                        modifier = Modifier.weight(1f),
                    )

                    Box(
                        Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (ready) Ink else BoneSoft)
                            .clickable(enabled = ready) {
                                scope.launch {
                                    LogBuffer.i(
                                        "Onboarding",
                                        "Registering as ${region}/${city}/${storeId}/${concept}" +
                                            (floor?.let { "/$it" } ?: "") +
                                            (table?.let { "/$it" } ?: "") +
                                            "/${screenCode}",
                                    )
                                    runCatching {
                                        repository.ensureRegistered(BuildConfig.JOIN_CODE)
                                        repository.refreshSettings()
                                        repository.refreshPlaylist()
                                    }.onFailure {
                                        LogBuffer.w("Onboarding", "Initial sync failed (will retry)", it)
                                    }
                                    onDone()
                                }
                            }
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Register screen →",
                            color = if (ready) Bone else Color(0xFFB5B0A2),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/**
 * Server URL display + connection status dot. Reads the live server URL from
 * DataStore (so the moment the user types it in, the rail updates) and the
 * connection status flow from the repository. The dot:
 *   • Green pulseless when reachable
 *   • Red pulseless when last fetch failed
 *   • Amber pulsing while connecting / waiting for the first response
 *   • Muted grey when no URL is configured
 *
 * Designed for use on dark rails (onboarding + device admin), so text colours
 * sit on Ink rather than Bone.
 */
@Composable
fun ServerStatusLine(
    repository: PlayerRepository,
    fallbackUrl: String,
) {
    val liveUrl by repository.store.liveServerUrl.collectAsState(initial = null)
    val status by repository.connection.collectAsState()
    val displayUrl = (liveUrl?.takeIf { it.isNotBlank() } ?: fallbackUrl)

    val baseColor = when (status) {
        PlayerRepository.ConnectionStatus.ONLINE -> Color(0xFF3D8C4B)         // ok-dot green
        PlayerRepository.ConnectionStatus.OFFLINE -> Color(0xFFA63824)         // err-dot red
        PlayerRepository.ConnectionStatus.CONNECTING -> Color(0xFFE8A33D)      // amber
        PlayerRepository.ConnectionStatus.DISCONNECTED -> Color(0x66FFFFFF)    // muted
    }
    val alpha = if (status == PlayerRepository.ConnectionStatus.CONNECTING) {
        val t = rememberInfiniteTransition(label = "connecting")
        t.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "connectingPulse",
        ).value
    } else 1f
    val statusLabel = when (status) {
        PlayerRepository.ConnectionStatus.ONLINE -> "Online"
        PlayerRepository.ConnectionStatus.OFFLINE -> "Unreachable"
        PlayerRepository.ConnectionStatus.CONNECTING -> "Connecting…"
        PlayerRepository.ConnectionStatus.DISCONNECTED -> "Not configured"
    }

    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(baseColor.copy(alpha = alpha))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            statusLabel,
            color = baseColor.copy(alpha = if (status == PlayerRepository.ConnectionStatus.CONNECTING) 1f else 1f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        displayUrl,
        color = Bone, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
    )
}
