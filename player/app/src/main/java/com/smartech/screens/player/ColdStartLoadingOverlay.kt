package com.smartech.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Cold-start loading overlay.
 *
 * Renders on top of the splash when the server has pushed content
 * but the local cache hasn't caught up. Shows a real progress bar
 * (sum of downloaded bytes / sum of total bytes across every
 * in-flight download) plus a "ready X / N items" line so the
 * operator can see why the splash is still on the screen.
 *
 * Renders nothing when:
 *  - playback is healthy (`isPlaying == true`), OR
 *  - there's no intended content (`intendedItemCount == 0`), OR
 *  - intended items exist but no download is in flight (likely all
 *    items already cached and `publish()` is about to flip state)
 *
 * The overlay is meant to be reassuring, not blocking — it sits in
 * the lower-third so the splash still dominates and we can't be
 * accused of replacing branded content with a dialog.
 */
@Composable
fun ColdStartLoadingOverlay(
    isPlaying: Boolean,
    intendedItemCount: Int,
    downloads: Map<String, PlayerRepository.DownloadProgress>,
) {
    if (isPlaying) return
    if (intendedItemCount <= 0) return
    if (downloads.isEmpty()) return

    val totalBytes = downloads.values.sumOf { it.totalBytes ?: 0L }
    val downloadedBytes = downloads.values.sumOf { it.bytes }
    val fraction: Float = when {
        totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    val readyCount = intendedItemCount - downloads.size
    val totalMb = totalBytes / 1_000_000.0
    val downloadedMb = downloadedBytes / 1_000_000.0
    val combinedMbPerSec = downloads.values.sumOf { it.bytesPerSec } / 1_000_000.0

    Box(
        Modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xCC101010))
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "LOADING CONTENT",
                    color = Color(0xFFE8A33D),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.4.sp,
                )
                Spacer(Modifier.height(10.dp))

                Text(
                    "${readyCount.coerceAtLeast(0)} of $intendedItemCount items ready",
                    color = Color(0xFFF7F6F2),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(Modifier.height(14.dp))

                // Progress bar — 420dp wide, 6dp tall. Indeterminate-
                // looking fill (the bar grows continuously as bytes
                // arrive) when totalBytes is known; falls back to a
                // muted background-only when the server didn't send
                // Content-Length.
                Box(
                    Modifier
                        .width(420.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x33FFFFFF)),
                ) {
                    if (totalBytes > 0) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(Color(0xFFE8A33D))
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (totalBytes > 0) "${(fraction * 100).toInt()}%" else "Downloading…",
                        color = Color(0xFFE8A33D),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (totalMb > 0) "%.1f / %.1f MB".format(downloadedMb, totalMb)
                        else "%.1f MB downloaded".format(downloadedMb),
                        color = Color(0xCCFFFFFF),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (combinedMbPerSec > 0.05) {
                        Text(
                            "%.1f MB/s".format(combinedMbPerSec),
                            color = Color(0x99FFFFFF),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
