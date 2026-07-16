package com.smartech.screens.player

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.smartech.screens.ScreensApp
import com.smartech.screens.util.InputMode
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.delay
import java.io.File

/**
 * Guided brand experience overlay (v0.1.92).
 *
 * When the screen has an [experienceUrl] set, this caches the (self-contained)
 * HTML and layers a retail flow over the video:
 *
 *   attract  — the video playlist keeps playing as the attract loop; we show a
 *              centred "tap to explore" prompt, deliberately away from the four
 *              corners (those stay reserved for the staff-unlock gesture — see
 *              [com.smartech.screens.staff.CornerUnlockOverlay], which is now
 *              corners-only so the rest of the screen is interactive).
 *   engaged  — a tap opens the cached experience fullscreen in a locked-down
 *              kiosk WebView. Navigation is pinned to the loaded file, so it
 *              can't wander to the open web.
 *   idle     — after [IDLE_TIMEOUT_MS] with no touch it returns to attract, so
 *              the screen resets itself for the next customer with no staff.
 *
 * Offline: the WebView loads from the on-disk cache ([ExperienceCache]), so once
 * the file has been pulled once the whole experience runs with no network.
 *
 * Mounted INSIDE PlayerScreen (below the corners-only unlock catcher) so corner
 * taps still reach staff unlock while everything else drives the experience.
 */
@Composable
fun ExperienceOverlay(
    experienceUrl: String?,
    /** v0.1.95: "top" (default) or "bottom" — operator-selectable per screen. */
    promptPosition: String = "top",
) {
    if (experienceUrl.isNullOrBlank()) return

    val context = LocalContext.current
    val cache = remember { (context.applicationContext as ScreensApp).experienceCache }
    val touch = remember { InputMode.hasTouch(context) }

    // Cache the HTML (refresh when online, fall back to the on-disk copy).
    // Re-runs if the URL changes; null until we have a usable local file.
    val cachedFile by produceState<File?>(initialValue = cache.cached(experienceUrl), key1 = experienceUrl) {
        value = cache.ensure(experienceUrl) ?: cache.cached(experienceUrl)
    }

    // On a touch screen we start in attract and wait for a tap. On a non-touch
    // display (no way to tap) we run the experience directly — it becomes a
    // dedicated demo station with no idle-return.
    var launched by remember(experienceUrl) { mutableStateOf(!touch) }
    var interactionTick by remember { mutableIntStateOf(0) }

    // Idle-return: each WebView touch bumps interactionTick, restarting this
    // delay; when it finally elapses we drop back to attract. Touch screens
    // only — a non-touch station has nothing to idle out of.
    LaunchedEffect(launched, interactionTick) {
        if (launched && touch) {
            delay(IDLE_TIMEOUT_MS)
            LogBuffer.i(TAG, "Guided experience idle — returning to attract loop")
            launched = false
        }
    }

    // Back returns to attract rather than leaving the kiosk; only active while
    // engaged so the normal player Back behaviour is untouched otherwise.
    BackHandler(enabled = launched) { launched = false }

    val file = cachedFile
    if (launched && file != null) {
        ExperienceWebView(
            file = file,
            onInteraction = { interactionTick++ },
        )
    } else if (!launched && file != null) {
        // Attract prompt — top or bottom, always clear of the corners.
        AttractPrompt(position = promptPosition, onLaunch = { launched = true })
    }
    // file == null: still fetching (or offline first-run with no cache) — show
    // nothing and let the video attract loop play until the cache lands.
}

@Composable
private fun AttractPrompt(position: String, onLaunch: () -> Unit) {
    val atBottom = position.equals("bottom", ignoreCase = true)
    val pulse = rememberInfiniteTransition(label = "attractPulse")
    val glow by pulse.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "attractGlow",
    )
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = if (atBottom) 0.dp else 24.dp, bottom = if (atBottom) 24.dp else 0.dp),
        contentAlignment = if (atBottom) Alignment.BottomCenter else Alignment.TopCenter,
    ) {
        // A small pill at the top or bottom — deliberately NOT in a corner: all
        // four corners are the staff-unlock zones (see CornerUnlockOverlay,
        // 180dp each), and a tap landing in one would drive the unlock sequence
        // instead of opening the experience. Horizontally centring the pill
        // keeps it clear of those zones at either edge, at any sane screen
        // width, while leaving the video attract loop almost fully visible.
        Text(
            "TAP TO EXPLORE",
            color = Color(0xFFE8A33D),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE6101010))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onLaunch() }
                // ~44dp tall overall — still a comfortable touch target.
                .padding(horizontal = 26.dp, vertical = 14.dp)
                .alpha(glow),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun ExperienceWebView(
    file: File,
    onInteraction: () -> Unit,
) {
    val fileUrl = remember(file) { "file://${file.absolutePath}" }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isHorizontalScrollBarEnabled = false
                    with(settings) {
                        javaScriptEnabled = true          // the demo is JS-driven
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        mediaPlaybackRequiresUserGesture = false
                        // Load the cached file, but lock the WebView down: no
                        // reaching other local files, no content:// providers,
                        // no cross-file universal access. The content is ours
                        // and self-contained, so it needs none of these.
                        allowFileAccess = true
                        allowContentAccess = false
                        @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
                    }
                    webViewClient = KioskWebViewClient(fileUrl)
                    // Reset the idle timer on every touch without consuming it,
                    // so scrolling/tapping the demo still works.
                    setOnTouchListener { v, _ -> onInteraction(); v.performClick(); false }
                }
            },
            update = { wv ->
                if (wv.url != fileUrl) wv.loadUrl(fileUrl)
            },
            onRelease = { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            },
        )
    }
}

/**
 * Pins the kiosk WebView to the loaded file. Same-file navigations (including
 * `#anchor` jumps within the single-page demo) are allowed; anything else —
 * http/https, other files, intents — is cancelled, so a mis-authored link or a
 * stray tap can never take a shop-floor screen to the open web.
 */
private class KioskWebViewClient(private val allowedFileUrl: String) : WebViewClient() {
    private val allowedBase = allowedFileUrl.substringBefore('#')

    private fun isBlocked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url == "about:blank") return false
        if (url.startsWith("file://")) {
            return url.substringBefore('#') != allowedBase
        }
        return true   // block every non-file scheme in the kiosk
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
        isBlocked(url)

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        isBlocked(request?.url?.toString())
}

private const val TAG = "ExperienceOverlay"
/** How long the engaged experience waits, with no touch, before returning to
 *  the attract loop. 90s is long enough to read a section without a customer
 *  feeling rushed, short enough that an abandoned session resets promptly. */
private const val IDLE_TIMEOUT_MS = 90_000L
