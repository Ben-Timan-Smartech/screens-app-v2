package com.smartech.screens.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartech.screens.data.LocationTaxonomy

// Shared palette tokens (kept in sync with StaffScreens / AdminScreens).
internal val LpInk      = Color(0xFF141414)
internal val LpBoneSoft = Color(0xFFEFEDE6)
internal val LpBoneLine = Color(0xFFE2DED3)
internal val LpMuted    = Color(0xFF6E6B62)

/**
 * Cascading location form — region → city → store → concept → floor → table → screen code.
 * Used by both the on-tablet first-run onboarding and the super-admin Device admin.
 *
 * Required fields (region, city, store, concept, screen code) are marked with a red dot.
 * Floor and Table are optional. Each value persists immediately via the supplied callbacks.
 */
/**
 * Cascading location form, simplified for a four-store fleet.
 *
 *   • Store dropdown is the primary control — picking a store auto-fills
 *     region + city, which display as read-only rows for clarity. The
 *     region/city callbacks are still invoked so the data is persisted
 *     through the same path; only the UI control is locked.
 *   • Concept dropdown only renders when the city has multiple in-store
 *     concepts (NYC, LDN). For BER and ROM the store IS the concept, so
 *     showing it would be noise.
 *   • Floor / Table / Screen Code unchanged.
 */
@Composable
fun LocationPicker(
    region: String?, city: String?, storeId: String?,
    concept: String?, floor: String?, table: String?, screenCode: String?,
    onRegion: (String?) -> Unit, onCity: (String?) -> Unit, onStore: (String?) -> Unit,
    onConcept: (String?) -> Unit, onFloor: (String?) -> Unit, onTable: (String?) -> Unit,
    onScreenCode: (String) -> Unit,
    showRequiredMarkers: Boolean = false,
) {
    val store = remember(storeId) { LocationTaxonomy.storeById(storeId) }
    val showConcept = city != null && city in LocationTaxonomy.MULTI_CONCEPT_CITIES
    // Floor only applies to NYC right now. LDN and the EU stores are
    // single-floor so the question would just be noise. Table is always
    // hidden for now (TBD when we have real table layouts).
    val showFloor = city == "NYC"

    // Store is the single authority for region + city. The call site (onboarding
    // / admin) is expected to atomically write all three keys via
    // DeviceStore.setLocStoreCascade so we don't trip the cascade-clear in the
    // individual setters. This callback just hands the chosen storeId up.
    LpDropdownRow(
        "Store",
        storeId,
        LocationTaxonomy.stores.map { it.id to it.name },
        onStore,
        currentLabel = store?.name,
        required = showRequiredMarkers,
    )
    LpDivider()
    LpReadOnlyRow("City",   city ?: "—",   placeholder = "Set by Store")
    LpDivider()
    LpReadOnlyRow("Region", region ?: "—", placeholder = "Set by Store")
    LpDivider()
    if (showConcept) {
        LpDropdownRow(
            "Concept",
            concept,
            LocationTaxonomy.concepts.map { it to it },
            onConcept,
            required = showRequiredMarkers,
        )
        LpDivider()
    }
    if (showFloor) {
        LpDropdownRow("Floor", floor, LocationTaxonomy.floors.map { it to it }, onFloor)
        LpDivider()
    }
    LpEditableRow("Screen Code", screenCode.orEmpty(), placeholder = "e.g. GF.A.1 or A1",
        onSave = onScreenCode,
        required = showRequiredMarkers)
}

/** Read-only display row — looks like a disabled dropdown but never opens. */
@Composable
internal fun LpReadOnlyRow(label: String, value: String, placeholder: String) {
    val empty = value.isBlank() || value == "—"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LpLabel(label, missing = false)
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(LpBoneSoft.copy(alpha = 0.5f))
                .border(1.dp, LpBoneLine, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                if (empty) placeholder else value,
                color = if (empty) Color(0xFFB5B0A2) else LpInk,
                fontSize = 13.sp,
                fontFamily = if (empty) FontFamily.Default else FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun LpDropdownRow(
    label: String,
    current: String?,
    options: List<Pair<String, String>>,
    onChange: (String?) -> Unit,
    currentLabel: String? = null,
    disabled: Boolean = false,
    disabledHint: String? = null,
    required: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = currentLabel ?: options.firstOrNull { it.first == current }?.second ?: current

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LpLabel(label, required && current.isNullOrBlank())
        Box(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (disabled) LpBoneSoft.copy(alpha = 0.5f) else LpBoneSoft)
                    .border(1.dp, LpBoneLine, RoundedCornerShape(6.dp))
                    .clickable(enabled = !disabled) { expanded = true }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            disabled && disabledHint != null -> disabledHint
                            display == null -> "—"
                            else -> display
                        },
                        color = when {
                            disabled -> Color(0xFFB5B0A2)
                            display == null -> LpMuted
                            else -> LpInk
                        },
                        fontSize = 13.sp,
                        fontFamily = if (display != null && !disabled) FontFamily.Monospace else FontFamily.Default,
                        modifier = Modifier.weight(1f),
                    )
                    Text("▾", color = LpMuted, fontSize = 12.sp)
                }
            }
            if (expanded) {
                androidx.compose.material3.DropdownMenu(
                    expanded = true,
                    onDismissRequest = { expanded = false },
                ) {
                    if (current != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("— Clear", color = LpMuted, fontSize = 13.sp) },
                            onClick = { onChange(null); expanded = false },
                        )
                    }
                    options.forEach { (value, label2) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label2, fontSize = 14.sp) },
                            onClick = { onChange(value); expanded = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LpEditableRow(
    label: String,
    value: String,
    placeholder: String,
    onSave: (String) -> Unit,
    required: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(TextFieldValue(value)) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LpLabel(label, required && value.isBlank())
        if (editing) {
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(LpBoneSoft)
                    .border(1.dp, LpBoneLine, RoundedCornerShape(6.dp))
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
                    textStyle = TextStyle(color = LpInk, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("Save", color = LpInk, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onSave(draft.text); editing = false }
                    .padding(8.dp))
            Text("Cancel", color = LpMuted, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { editing = false; draft = TextFieldValue(value) }
                    .padding(8.dp))
        } else {
            Text(
                value.ifBlank { "—" },
                color = if (value.isBlank()) LpMuted else LpInk,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text("Edit", color = LpInk, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { editing = true }
                    .padding(8.dp))
        }
    }
}

@Composable
private fun LpLabel(label: String, missing: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(0.4f),
    ) {
        Text(label, color = LpMuted, fontSize = 13.sp)
        if (missing) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .height(6.dp)
                    .width(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFA63824))
            )
        }
    }
}

@Composable
internal fun LpDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LpBoneLine.copy(alpha = 0.5f)))
}
