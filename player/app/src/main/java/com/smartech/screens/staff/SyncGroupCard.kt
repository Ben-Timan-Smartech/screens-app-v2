package com.smartech.screens.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartech.screens.data.PlayerRepository

/**
 * v0.1.36: shared Sync group card. Renders one of two states:
 *
 *   • IN A GROUP — header with group id + Calibrate + Leave buttons,
 *     then a row per member with online dot + name + screen code.
 *     Self carries a "this screen" tag.
 *   • NOT IN A GROUP — header "Sync group", subtext "Not in a group",
 *     then a row per available group across the fleet, each with a
 *     [Join] button. Picking one POSTs sync-group on the tablet's own
 *     deviceId. Creating new groups stays a CMS task — the picker only
 *     lists groups that already exist.
 *
 * Used by both the Device admin page (AdminScreens) and the content
 * page (PlaylistView). On a TV remote a focusable button per group
 * beats a text field, so we deliberately omit a free-form input.
 */
@Composable
fun SyncGroupCard(
    currentGroupId: String?,
    members: List<PlayerRepository.LiveGroupMember>,
    availableGroups: List<PlayerRepository.AvailableSyncGroup>,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
    onCalibrate: () -> Unit,
) {
    val inGroup = !currentGroupId.isNullOrBlank()
    var confirmLeave by remember { mutableStateOf(false) }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave sync group?") },
            text = {
                Text(
                    "This screen will run independently. You can mix splash again, " +
                        "push a different playlist, etc. Rejoin from here any time."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onLeave()
                }) { Text("Leave group") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
            },
        )
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sync group",
                color = SyncInk, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (inGroup) {
                // Calibrate runs the 60 s synchronised clock overlay on
                // every group member — quickest "are we on the same
                // tick?" sanity check.
                PillButton(
                    label = "Calibrate",
                    onClick = onCalibrate,
                )
                Spacer(Modifier.size(8.dp))
                PillButton(
                    label = "Leave",
                    onClick = { confirmLeave = true },
                    tone = PillTone.Danger,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (inGroup) {
            MemberList(currentGroupId!!, members)
        } else {
            JoinPicker(availableGroups, onJoin)
        }
    }
}

@Composable
private fun MemberList(
    groupId: String,
    members: List<PlayerRepository.LiveGroupMember>,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, SyncBoneLine, RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Group", color = SyncMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                groupId,
                color = SyncInk, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            )
        }
        SyncDivider()
        if (members.isEmpty()) {
            Text(
                "Waiting for member list… (next poll)",
                color = SyncMuted, fontSize = 13.sp,
                modifier = Modifier.padding(18.dp),
            )
        } else {
            members.forEachIndexed { i, m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (m.online) Color(0xFF3D8C4B) else Color(0xFFB5B0A2)
                            )
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.name ?: m.deviceId,
                            color = SyncInk, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                        val sub = listOfNotNull(m.storeId, m.screenCode).joinToString(" · ")
                        if (sub.isNotEmpty()) {
                            Text(sub, color = SyncMuted, fontSize = 12.sp)
                        }
                    }
                    if (m.isSelf) {
                        Text(
                            "this screen",
                            color = Color(0xFF3A3832),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, SyncBoneLine, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (i < members.size - 1) SyncDivider()
            }
        }
    }
}

@Composable
private fun JoinPicker(
    available: List<PlayerRepository.AvailableSyncGroup>,
    onJoin: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, SyncBoneLine, RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Not in a sync group",
                    color = SyncInk, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    "Playing independently. Joining a group locks this screen to the " +
                        "group's playlist and clock so videos start in step.",
                    color = SyncMuted, fontSize = 12.sp,
                )
            }
        }
        SyncDivider()
        if (available.isEmpty()) {
            Text(
                "No sync groups exist on the fleet yet. Create one from the CMS " +
                    "(Screens → pick a screen → Sync group).",
                color = SyncMuted, fontSize = 13.sp,
                modifier = Modifier.padding(18.dp),
            )
        } else {
            available.forEachIndexed { i, g ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            g.id,
                            color = SyncInk, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "${g.memberCount} screen${if (g.memberCount == 1) "" else "s"}" +
                                " · ${g.onlineCount} online",
                            color = SyncMuted, fontSize = 12.sp,
                        )
                    }
                    PillButton(
                        label = "Join",
                        onClick = { onJoin(g.id) },
                        tone = PillTone.Primary,
                    )
                }
                if (i < available.size - 1) SyncDivider()
            }
        }
    }
}

private enum class PillTone { Neutral, Primary, Danger }

@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    tone: PillTone = PillTone.Neutral,
) {
    val bg = when (tone) {
        PillTone.Neutral -> SyncBoneSoft
        PillTone.Primary -> SyncInk
        PillTone.Danger  -> Color(0xFFF6E2DC)
    }
    val fg = when (tone) {
        PillTone.Neutral -> SyncInk
        PillTone.Primary -> SyncBone
        PillTone.Danger  -> Color(0xFFA63824)
    }
    val border = when (tone) {
        PillTone.Neutral -> SyncBoneLine
        PillTone.Primary -> SyncInk
        PillTone.Danger  -> Color(0xFFE5BFB4)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SyncDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SyncBoneLine)
    )
}

private val SyncInk      = Color(0xFF141414)
private val SyncBone     = Color(0xFFF7F6F2)
private val SyncBoneSoft = Color(0xFFE6E2D6)
private val SyncBoneLine = Color(0xFFB8B1A0)
private val SyncMuted    = Color(0xFF3A3832)
