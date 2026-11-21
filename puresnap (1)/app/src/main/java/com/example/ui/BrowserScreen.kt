package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.privacy.BlockedRequestEvent
import com.example.privacy.PrivacySettings
import com.example.privacy.PrivacyShield
import java.io.ByteArrayInputStream

private class MediaSnifferBridge(
    private val onStreamFound: (String) -> Unit
) {
    @JavascriptInterface
    fun reportMediaUrl(url: String?) {
        if (!url.isNullOrBlank()) {
            onStreamFound(url)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    browserState: BrowserState,
    privacySettings: PrivacySettings,
    onNavigate: (String) -> Unit,
    onPageStarted: (String) -> Unit,
    onPageFinished: (String, String?) -> Unit,
    onTrackerBlocked: (BlockedRequestEvent) -> Unit,
    onStreamDetected: (String, String?) -> Unit,
    onTorrentLinkDetected: (String) -> Unit,
    onOpenDetectedStreams: () -> Unit,
    onClearSessionNotice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var addressText by remember(browserState.currentUrl) { mutableStateOf(browserState.currentUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    LaunchedEffect(browserState.currentUrl) {
        val webView = webViewRef ?: return@LaunchedEffect
        if (webView.url != browserState.currentUrl) {
            webView.loadUrl(browserState.currentUrl)
        }
    }

    DisposableEffect(privacySettings.privateBrowserMode) {
        onDispose {
            if (privacySettings.privateBrowserMode) {
                runCatching {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Privacy Browser Toolbar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = if (browserState.currentUrl.startsWith("https://")) {
                                    Icons.Filled.Lock
                                } else {
                                    Icons.Filled.Security
                                },
                                contentDescription = "Connection privacy",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        placeholder = { Text("Enter URL or search DuckDuckGo") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                keyboardController?.hide()
                                onNavigate(addressText)
                            }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("browser_address_bar")
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = "Page trackers blocked",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${browserState.pageBlockedCount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = { webViewRef?.goBack() },
                            enabled = canGoBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("browser_back")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        IconButton(
                            onClick = { webViewRef?.goForward() },
                            enabled = canGoForward,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("browser_forward")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                        }
                        IconButton(
                            onClick = { webViewRef?.reload() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("browser_reload")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload page")
                        }
                    }

                    Text(
                        text = if (privacySettings.privateBrowserMode) "Incognito • Ad-Free" else "Shield Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    IconButton(
                        onClick = {
                            webViewRef?.apply {
                                clearCache(true)
                                clearHistory()
                                clearFormData()
                            }
                            CookieManager.getInstance().removeAllCookies(null)
                            WebStorage.getInstance().deleteAllData()
                            onClearSessionNotice("Browser cookies, cache & history wiped")
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("browser_wipe_session")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CleaningServices,
                            contentDescription = "Wipe cookies and history",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (browserState.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(!privacySettings.privateBrowserMode)
                        cm.setAcceptThirdPartyCookies(this, false)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = !privacySettings.privateBrowserMode
                            mediaPlaybackRequiresUserGesture = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            cacheMode = if (privacySettings.privateBrowserMode) {
                                WebSettings.LOAD_NO_CACHE
                            } else {
                                WebSettings.LOAD_DEFAULT
                            }
                        }

                        addJavascriptInterface(
                            MediaSnifferBridge { detectedUrl ->
                                post {
                                    onStreamDetected(detectedUrl, null)
                                }
                            },
                            "PureSnapBridge"
                        )

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val target = request?.url?.toString() ?: return false
                                val lower = target.lowercase()
                                if (lower.startsWith("magnet:?") ||
                                    lower.substringBefore('?').endsWith(".torrent")
                                ) {
                                    view?.post { onTorrentLinkDetected(target) }
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (!url.isNullOrBlank()) {
                                    onPageStarted(url)
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (!url.isNullOrBlank()) {
                                    onPageFinished(url, view?.title)
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true

                                // Inspect DOM + hook dynamic video/audio elements & fetch/XHR for SPA social feeds
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                      try {
                                        function scanDom() {
                                          var els = document.querySelectorAll('video, audio, source');
                                          for (var i = 0; i < els.length; i++) {
                                            var s = els[i].currentSrc || els[i].src;
                                            if (s && s.indexOf('http') === 0) {
                                              PureSnapBridge.reportMediaUrl(s);
                                            }
                                          }
                                        }
                                        function checkUrl(u) {
                                          if (typeof u === 'string' && u.indexOf('http') === 0 &&
                                              /\.(mp4|m4v|webm|mkv|m3u8|mpd|mp3|m4a|aac|ogg|opus|flac)(\?|$)/i.test(u)) {
                                            PureSnapBridge.reportMediaUrl(u);
                                          }
                                        }
                                        scanDom();
                                        if (!window.__pureSnapHooked) {
                                          window.__pureSnapHooked = true;
                                          var obs = new MutationObserver(function() { scanDom(); });
                                          obs.observe(document.documentElement || document.body, { childList: true, subtree: true, attributes: true });
                                          var origFetch = window.fetch;
                                          if (origFetch) {
                                            window.fetch = function(input) {
                                              var u = (typeof input === 'string') ? input : (input && input.url);
                                              checkUrl(u);
                                              return origFetch.apply(this, arguments);
                                            };
                                          }
                                          var origOpen = XMLHttpRequest.prototype.open;
                                          XMLHttpRequest.prototype.open = function(method, url) {
                                            checkUrl(url);
                                            return origOpen.apply(this, arguments);
                                          };
                                        }
                                      } catch (e) {}
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                // 1. Block known ads, trackers, pixels, and analytics domains
                                if (privacySettings.trackerBlockerEnabled) {
                                    val blocked = PrivacyShield.matchBlockedHost(reqUrl)
                                    if (blocked != null) {
                                        view?.post { onTrackerBlocked(blocked) }
                                        return WebResourceResponse(
                                            "text/plain",
                                            "utf-8",
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                }

                                // 2. Sniff playable/downloadable video or audio stream URLs
                                if (PrivacyShield.isMediaStreamUrl(reqUrl)) {
                                    val accept = request.requestHeaders["Accept"]
                                    view?.post { onStreamDetected(reqUrl, accept) }
                                }

                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        loadUrl(browserState.currentUrl)
                        webViewRef = this
                    }
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.destroy()
                }
            )

            // Floating Media Stream Detected Pill
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = browserState.detectedStreams.isNotEmpty(),
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    ExtendedFloatingActionButton(
                        onClick = onOpenDetectedStreams,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("browser_download_detected_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Download detected media"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${browserState.detectedStreams.size} Media Stream(s) Ready"
                        )
                    }
                }
            }
        }
    }
}
