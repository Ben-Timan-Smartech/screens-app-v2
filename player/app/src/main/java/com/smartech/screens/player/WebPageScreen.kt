package com.smartech.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.data.WebPageConfig
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/**
 * v0.2.16: web page mode — one live URL full screen instead of the playlist.
 * First use is the London office board (meetings, signings, sales, clocks,
 * Tube status) on dashboard.smartechworld.com.
 *
 * Shares ExperienceOverlay's WebView setup but differs in three ways: the page
 * owns the whole screen, there's no touch prompt (it's display-only — touches
 * are swallowed, and the staff unlock still works because MainActivity
 * detects it before dispatch), and the page loads live from the network
 * rather than from ExperienceCache's on-disk copy.
 *
 * The board must never show a white screen, so a watchdog runs alongside it
 * ([WebPageSession]): a failed load, HTTP error, TLS error, load timeout, a
 * page that renders blank, or a dead/hung renderer all put up a quiet
 * "Reconnecting…" cover and retry with backoff (5 s doubling to 5 min). If
 * the network drops while the page is up, the page stays visible (its clocks
 * keep ticking) with a small "Reconnecting…" pill, and it's reloaded when the
 * network comes back.
 *
 * The URL carries a display token — it's never logged. Log lines and the
 * health reported to the CMS carry the host only.
 */
@Composable
fun WebPageScreen(config: WebPageConfig, repository: PlayerRepository) {
    // CMS "Restart player" → a brand-new WebView (fresh renderer + JS heap).
    val rebuildRequests by repository.webRebuildRequests.collectAsState()
    // Our own rebuilds (renderer crashed / hung, or WebView failed to start).
    var rebuildNonce by remember { mutableIntStateOf(0) }
    // Consecutive rebuilds without a good load in between — backs off the
    // next start so a renderer that dies on load can't spin.
    var rebuildStreak by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        repository.reportWebShowing(true)
        LogBuffer.i(TAG, "Web page mode on (${config.host})")
        onDispose {
            repository.reportWebShowing(false)
            repository.reportWebStatus(null)
            LogBuffer.i(TAG, "Web page mode off — back to the playlist")
        }
    }

    Box(Modifier.fillMaxSize().background(COVER_BG)) {
        // Keyed on the whole config: a new URL, reload interval or page width
        // from the CMS starts a fresh session with it.
        key(config, rebuildRequests, rebuildNonce) {
            LiveWebPage(
                config = config,
                repository = repository,
                startDelayMs = if (rebuildStreak == 0) 0L else backoffMs(rebuildStreak),
                onLoaded = { rebuildStreak = 0 },
                onNeedsRebuild = {
                    rebuildStreak++
                    rebuildNonce++
                },
            )
        }
    }
}

@Composable
private fun LiveWebPage(
    config: WebPageConfig,
    repository: PlayerRepository,
    startDelayMs: Long,
    onLoaded: () -> Unit,
    onNeedsRebuild: () -> Unit,
) {
    val context = LocalContext.current
    // Creating a WebView can throw on a box whose WebView package is missing
    // or mid-update. Treat that like a crashed renderer: cover + retry.
    val session = remember {
        runCatching {
            WebPageSession(context, config, repository, startDelayMs, onLoaded, onNeedsRebuild)
        }.onFailure {
            LogBuffer.w(TAG, "WebView unavailable: ${it.javaClass.simpleName}: ${it.message}")
            repository.reportWebError("WebView unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    if (session == null) {
        LaunchedEffect(Unit) {
            delay(WEBVIEW_UNAVAILABLE_RETRY_MS)
            onNeedsRebuild()
        }
        Cover("Reconnecting…")
        return
    }

    DisposableEffect(session) {
        session.start()
        onDispose { session.stop() }
    }

    // CMS "Refresh" → reload the page. Ignore the counter's value at mount;
    // only a bump while we're showing counts.
    val reloadRequests by repository.webReloadRequests.collectAsState()
    val reloadsAtMount = remember { reloadRequests }
    LaunchedEffect(reloadRequests) {
        if (reloadRequests != reloadsAtMount) session.load("CMS refresh")
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { session.webView },
        onRelease = { session.destroyWebView() },
    )

    when (session.status) {
        WebStatus.LOADING -> Cover("Connecting…")
        WebStatus.RECONNECTING -> Cover("Reconnecting…")
        WebStatus.OFFLINE -> OfflinePill()
        WebStatus.SHOWING -> Unit
    }
}

/** Full-screen dark cover — hides the WebView whenever it has nothing good to
 *  show (first load, or an error/blank page), so the board is never white. */
@Composable
private fun Cover(label: String) {
    Box(
        Modifier.fillMaxSize().background(COVER_BG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color(0x99E8ECEF),
            fontSize = 18.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.alpha(pulse()),
        )
    }
}

/** Network down, page still up (stale data, live clocks): a small pill in the
 *  corner rather than covering the board. */
@Composable
private fun OfflinePill() {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        Text(
            "Reconnecting…",
            color = Color(0xCCE8ECEF),
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xCC07090B))
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .alpha(pulse()),
        )
    }
}

@Composable
private fun pulse(): Float {
    val t = rememberInfiniteTransition(label = "webPulse")
    val a by t.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "webPulseAlpha",
    )
    return a
}

private enum class WebStatus { LOADING, SHOWING, OFFLINE, RECONNECTING }

/**
 * Owns one WebView and its watchdog. Everything runs on the main thread
 * (WebView callbacks, the [handler] timers, network callbacks re-posted).
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private class WebPageSession(
    context: Context,
    private val config: WebPageConfig,
    private val repository: PlayerRepository,
    private val startDelayMs: Long,
    private val onLoaded: () -> Unit,
    private val onNeedsRebuild: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val host = config.host
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var status by mutableStateOf(WebStatus.LOADING)
        private set

    private var stopped = false
    private var rendererDead = false
    private var loadInFlight = false
    private var failedThisLoad = false
    private var attempt = 0                 // consecutive failures
    private var networkUp = isNetworkUpNow()
    private var blankStreak = 0
    private var probePending = false
    private var missedProbes = 0

    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(android.graphics.Color.BLACK)
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        // Display-only: no focus (a remote's D-pad mustn't scroll or click
        // the board) and every touch swallowed. Staff unlock is detected in
        // MainActivity before dispatch, so it still works.
        isFocusable = false
        isFocusableInTouchMode = false
        setOnTouchListener { _, _ -> true }
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // v0.2.17: the page is scaled down to fit (see applyPageWidth),
            // so no text autosizing or system text zoom on top — the board's
            // text must stay the size its layout expects.
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            // Live, not cached: every load (and every reload) goes to the
            // network, so a reload after a dashboard deploy can't serve
            // stale HTML.
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webViewClient = Client()
    }

    init {
        // A new size (first layout, or a rotation change) needs a new scale.
        webView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) applyPageWidth()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private val up = mutableSetOf<Network>()   // touched on main only
        override fun onAvailable(network: Network) {
            handler.post { up += network; onNetwork(true) }
        }
        override fun onLost(network: Network) {
            handler.post { up -= network; onNetwork(up.isNotEmpty()) }
        }
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    fun start() {
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkCallback,
            )
        }.onFailure { LogBuffer.w(TAG, "Network callback unavailable: ${it.message}") }
        updateStatus(WebStatus.LOADING)
        handler.postDelayed({ load("start") }, startDelayMs)
        handler.postDelayed(probeTick, PROBE_INTERVAL_MS)
        scheduleRefresh()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
    }

    fun destroyWebView() {
        stop()
        // After a renderer death the WebView must only be destroyed —
        // anything else may crash the app.
        if (!rendererDead) {
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        runCatching { webView.destroy() }
    }

    // ── loading + watchdog ─────────────────────────────────────────────

    fun load(reason: String) {
        if (stopped || rendererDead) return
        handler.removeCallbacks(retry)
        handler.removeCallbacks(loadTimeout)
        loadInFlight = true
        failedThisLoad = false
        blankStreak = 0
        LogBuffer.i(TAG, "Loading web page ($host) — $reason")
        webView.loadUrl(config.url)
        handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
    }

    private fun loaded() {
        handler.removeCallbacks(loadTimeout)
        loadInFlight = false
        if (attempt > 0) LogBuffer.i(TAG, "Web page recovered ($host) after $attempt failed attempt(s)")
        attempt = 0
        missedProbes = 0
        updateStatus(if (networkUp) WebStatus.SHOWING else WebStatus.OFFLINE)
        repository.reportWebLoaded(host)
        onLoaded()
        handler.postDelayed(reportViewport, VIEWPORT_REPORT_DELAY_MS)
    }

    /**
     * v0.2.17: lay the page out [WebPageConfig.pageWidth] CSS px wide, scaled
     * to fit the screen. Android otherwise lays a page out at the box's
     * density-adjusted width — 960 px on a 1080p box at 2× — which crops a
     * board built for 1920×1080. WebView has no setting for that, so this
     * rewrites the page's viewport meta tag (and keeps it rewritten if the
     * page changes it). Idempotent: run on commit, on finish and on resize.
     */
    private fun applyPageWidth() {
        val width = config.pageWidth
        if (width <= 0 || stopped || rendererDead) return
        val metrics = webView.resources.displayMetrics
        val viewPx = webView.width.takeIf { it > 0 } ?: metrics.widthPixels
        val scale = String.format(Locale.US, "%.4f", viewPx / metrics.density / width)
        val content = "width=$width, initial-scale=$scale, minimum-scale=$scale, " +
            "maximum-scale=$scale, user-scalable=no"
        runCatching { webView.evaluateJavascript(viewportJs(content), null) }
    }

    /** Tell the CMS what size the page actually sees, so a wrong layout can
     *  be spotted remotely. */
    private val reportViewport = Runnable {
        if (stopped || rendererDead) return@Runnable
        runCatching {
            webView.evaluateJavascript(VIEWPORT_JS) { result -> parseViewport(result)?.let(repository::reportWebViewport) }
        }
    }

    /** Anything that leaves the screen without a good page. [reason] goes to
     *  the CMS — codes/descriptions only, never the URL. */
    private fun fail(reason: String) {
        if (stopped || failedThisLoad) return
        failedThisLoad = true
        loadInFlight = false
        handler.removeCallbacks(loadTimeout)
        handler.removeCallbacks(retry)
        attempt++
        updateStatus(WebStatus.RECONNECTING)
        repository.reportWebError(reason)
        val wait = backoffMs(attempt)
        LogBuffer.w(TAG, "Web page problem ($host): $reason — retry ${attempt} in ${wait / 1000}s")
        runCatching { webView.stopLoading() }
        handler.postDelayed(retry, wait)
    }

    private val retry = Runnable { load("retry $attempt") }

    private val loadTimeout = Runnable {
        if (loadInFlight) fail("Load timed out after ${LOAD_TIMEOUT_MS / 1000}s")
    }

    private fun scheduleRefresh() {
        if (config.reloadEveryMin > 0) {
            handler.postDelayed(scheduledRefresh, config.reloadEveryMin * 60_000L)
        }
    }

    /** The optional full reload (default every 4 h) — clears whatever state a
     *  long-running dashboard has accumulated. Skipped mid-recovery: the
     *  retry loop is already reloading. */
    private val scheduledRefresh = Runnable {
        if (status == WebStatus.SHOWING && !loadInFlight) load("scheduled reload")
        scheduleRefresh()
    }

    /** Every [PROBE_INTERVAL_MS] while a page is up: is it blank, and is the
     *  renderer still answering? Two blank probes in a row → reload; three
     *  unanswered probes in a row → the renderer is hung, rebuild the WebView. */
    private val probeTick = object : Runnable {
        override fun run() {
            if (stopped) return
            if (!loadInFlight && (status == WebStatus.SHOWING || status == WebStatus.OFFLINE)) {
                if (probePending) missedProbes++ else missedProbes = 0
                if (missedProbes >= 3) {
                    LogBuffer.w(TAG, "Web page renderer not responding ($host) — rebuilding the WebView")
                    repository.reportWebError("Renderer not responding")
                    stopped = true
                    onNeedsRebuild()
                    return
                }
                probePending = true
                runCatching {
                    webView.evaluateJavascript(BLANK_PROBE_JS) { result ->
                        probePending = false
                        parseViewport(result)?.let(repository::reportWebViewport)
                        if (result?.contains("blank") == true) {
                            blankStreak++
                            if (blankStreak >= 2) fail("Page rendered blank")
                        } else {
                            blankStreak = 0
                        }
                    }
                }
            }
            handler.postDelayed(this, PROBE_INTERVAL_MS)
        }
    }

    // ── network ────────────────────────────────────────────────────────

    private fun isNetworkUpNow(): Boolean = runCatching {
        val n = connectivity.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private fun onNetwork(up: Boolean) {
        if (stopped || up == networkUp) return
        networkUp = up
        if (!up) {
            // A short grace so a wifi blip doesn't flash the pill.
            handler.postDelayed(offlineGrace, OFFLINE_GRACE_MS)
        } else {
            handler.removeCallbacks(offlineGrace)
            if (status == WebStatus.OFFLINE || status == WebStatus.RECONNECTING) {
                LogBuffer.i(TAG, "Network back — reloading web page ($host)")
                handler.removeCallbacks(retry)
                handler.postDelayed({ load("network back") }, NETWORK_SETTLE_MS)
            }
        }
    }

    private val offlineGrace = Runnable {
        if (!networkUp && status == WebStatus.SHOWING) {
            LogBuffer.w(TAG, "Network lost — keeping the page up, will reload when it returns ($host)")
            repository.reportWebError("Network lost")
            updateStatus(WebStatus.OFFLINE)
        }
    }

    private fun updateStatus(s: WebStatus) {
        status = s
        repository.reportWebStatus(s.name.lowercase())
    }

    // ── WebView client ─────────────────────────────────────────────────

    private inner class Client : WebViewClient() {

        /** Keep the board on its own host. Sub-frames (embedded widgets) may
         *  navigate freely; the top-level page may not wander off. */
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (request == null || !request.isForMainFrame) return false
            val target = request.url
            if (target.scheme == "about") return false
            val ok = target.scheme == "https" && target.host.equals(host, ignoreCase = true)
            if (!ok) LogBuffer.w(TAG, "Blocked navigation away from $host to ${target.host}")
            return !ok
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val target = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return true
            if (target.scheme == "about") return false
            return !(target.scheme == "https" && target.host.equals(host, ignoreCase = true))
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            // A navigation the page started itself (same host) — watch it
            // like one of ours so an error page can't sit there uncovered.
            if (!loadInFlight && url != null && !url.startsWith("about:")) {
                loadInFlight = true
                failedThisLoad = false
                handler.removeCallbacks(loadTimeout)
                handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
            }
        }

        /** The new page is about to draw — set its width as early as we can
         *  reach its document, so a reload barely shows the wrong layout. */
        override fun onPageCommitVisible(view: WebView?, url: String?) {
            if (url == null || url.startsWith("about:")) return
            applyPageWidth()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (url == null || url.startsWith("about:")) return
            applyPageWidth()
            if (loadInFlight && !failedThisLoad) loaded()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame != true) return
            fail("Load error ${error?.errorCode}: ${error?.description}")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
            if (request?.isForMainFrame != true) return
            val code = response?.statusCode ?: 0
            if (code >= 400) fail("HTTP $code")
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            // Never proceed past a bad certificate — the URL carries a token.
            handler?.cancel()
            fail("TLS error ${error?.primaryError}")
        }

        /** API 26+ only (never called on the legacy build). The renderer
         *  crashed or was killed for memory: this WebView is unusable, so ask
         *  for a fresh one. Returning true keeps the app alive. */
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            rendererDead = true
            stopped = true
            handler.removeCallbacksAndMessages(null)
            val why = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                detail?.didCrash() == false) "killed (low memory)" else "crashed"
            LogBuffer.w(TAG, "Web page renderer $why ($host) — rebuilding the WebView")
            repository.reportWebError("Renderer $why")
            Handler(Looper.getMainLooper()).post { onNeedsRebuild() }
            return true
        }
    }
}

private fun backoffMs(attempt: Int): Long {
    val exp = (attempt - 1).coerceIn(0, 10)
    return (RETRY_BASE_MS shl exp).coerceAtMost(RETRY_MAX_MS)
}

private const val TAG = "WebPage"
private val COVER_BG = Color(0xFF07090B)
private const val RETRY_BASE_MS = 5_000L
private const val RETRY_MAX_MS = 300_000L
private const val LOAD_TIMEOUT_MS = 45_000L
private const val PROBE_INTERVAL_MS = 30_000L
private const val OFFLINE_GRACE_MS = 10_000L
private const val NETWORK_SETTLE_MS = 2_000L
private const val WEBVIEW_UNAVAILABLE_RETRY_MS = 60_000L
private const val VIEWPORT_REPORT_DELAY_MS = 2_000L

/** "blank" when the page has no visible text and no media/graphics at all —
 *  what a crashed SPA or an empty error body looks like. Followed by the
 *  layout size the page sees ("ok 1920x1080") for the CMS — the root
 *  element's client size, which is the layout viewport (innerWidth can
 *  report a zoomed-out visual size instead). */
private const val BLANK_PROBE_JS = """
(function(){
  var e = document.documentElement, b = document.body, v = ' ' + e.clientWidth + 'x' + e.clientHeight;
  if (!b) return 'blank' + v;
  var t = (b.innerText || '').trim().length;
  var m = b.querySelectorAll('img,canvas,svg,video,iframe,object,embed').length;
  return ((t > 0 || m > 0) ? 'ok' : 'blank') + v;
})()
"""

private const val VIEWPORT_JS =
    "document.documentElement.clientWidth + 'x' + document.documentElement.clientHeight"
private val VIEWPORT_RE = Regex("""(\d+)x(\d+)""")

/** "1920x1080" out of an evaluateJavascript result (a JSON string). */
private fun parseViewport(result: String?): String? = result?.let { VIEWPORT_RE.find(it)?.value }

/**
 * Sets every viewport meta tag on the page to [content] (adding one if there's
 * none) and watches the head, so a page or framework that rewrites its own tag
 * gets ours back. Running it again just updates the content being enforced.
 */
private fun viewportJs(content: String): String = """
(function(c){
  var w = window, d = document;
  w.__screensViewport = c;
  function fix(){
    var v = w.__screensViewport, ms = d.querySelectorAll('meta[name="viewport"]');
    if (!ms.length) {
      var m = d.createElement('meta');
      m.setAttribute('name', 'viewport');
      (d.head || d.documentElement).appendChild(m);
      ms = [m];
    }
    for (var i = 0; i < ms.length; i++) {
      if (ms[i].getAttribute('content') !== v) ms[i].setAttribute('content', v);
    }
  }
  fix();
  if (!w.__screensViewportObserver && w.MutationObserver) {
    w.__screensViewportObserver = new MutationObserver(fix);
    w.__screensViewportObserver.observe(d.head || d.documentElement,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
  }
})(${JSONObject.quote(content)})
"""
