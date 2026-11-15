package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.web.InternetExplorerEngine
import com.example.ui.theme.CodeFontFamily
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebExplorerSheet(
    explorer: InternetExplorerEngine,
    initialUrlOrQuery: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by explorer.state.collectAsStateWithLifecycle()

    var addressInput by remember(initialUrlOrQuery) {
        mutableStateOf(
            initialUrlOrQuery?.takeIf { it.isNotBlank() }?.let { explorer.resolveOmniboxInput(it) }
                ?: state.currentUrl,
        )
    }
    var pageProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        }
    }

    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString().orEmpty()
                return !(u.startsWith("https://") || u.startsWith("http://"))
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!url.isNullOrBlank()) {
                    addressInput = url
                    explorer.updateCurrentBrowserLocation(url, view?.title)
                }
                canGoBack = view?.canGoBack() == true
                canGoForward = view?.canGoForward() == true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!url.isNullOrBlank()) {
                    addressInput = url
                    explorer.updateCurrentBrowserLocation(url, view?.title)
                }
                canGoBack = view?.canGoBack() == true
                canGoForward = view?.canGoForward() == true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                pageProgress = newProgress
            }
        }
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(initialUrlOrQuery) {
        val target = initialUrlOrQuery?.takeIf { it.isNotBlank() }
            ?.let { explorer.resolveOmniboxInput(it) }
            ?: state.currentUrl
        addressInput = target
        webView.loadUrl(target)
    }

    // Keep live WebView synced to autonomous crawler visits while exploring
    LaunchedEffect(state.currentUrl, state.isExploring) {
        if (state.isExploring && state.currentUrl.startsWith("http")) {
            addressInput = state.currentUrl
            if (webView.url != state.currentUrl) {
                webView.loadUrl(state.currentUrl)
            }
        }
    }

    fun navigateAddress() {
        val target = explorer.resolveOmniboxInput(addressInput)
        addressInput = target
        webView.loadUrl(target)
    }

    fun learnCurrentRenderedPage() {
        val activeUrl = webView.url ?: addressInput
        val activeTitle = webView.title.orEmpty()
        val js = """
            (function() {
                var title = document.title || '';
                var text = document.body ? (document.body.innerText || '') : '';
                var links = Array.from(document.querySelectorAll('a[href]'))
                    .map(function(a) { return a.href; })
                    .filter(function(h) { return h && (h.startsWith('http://') || h.startsWith('https://')); })
                    .slice(0, 20);
                return JSON.stringify({ title: title, text: text.slice(0, 35000), links: links });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { rawJson ->
            scope.launch {
                val parsed = runCatching {
                    val decoded = Json.parseToJsonElement(rawJson ?: "\"\"")
                    val innerStr = decoded.jsonPrimitive.contentOrNull ?: rawJson.orEmpty()
                    Json.parseToJsonElement(innerStr).jsonObject
                }.getOrNull()

                val domTitle = parsed?.get("title")?.jsonPrimitive?.contentOrNull?.ifBlank { activeTitle } ?: activeTitle
                val domText = parsed?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                val links = parsed?.get("links")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

                runCatching {
                    explorer.learnFromWebViewDom(
                        url = activeUrl,
                        title = domTitle,
                        domText = domText,
                        outboundLinks = links,
                    )
                }.onSuccess { learned ->
                    statusBanner = "Learned & trained neural core from: $learned"
                }.onFailure { err ->
                    statusBanner = "Could not learn page: ${err.message}"
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = "Explore Internet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Visited ${state.pagesVisited} • Learned ${state.knowledgeLearned} • ${state.statusMessage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("explorer_close_button"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close browser")
                    }
                }

                // Omnibox URL or Topic Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        placeholder = { Text("Paste ANY link (https://...) or type a search topic") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigateAddress() }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("explorer_address_input"),
                    )
                    Button(
                        onClick = { navigateAddress() },
                        modifier = Modifier.testTag("explorer_go_button"),
                    ) {
                        Text("Go")
                    }
                }

                // Browser + Chrome + Auto-Learn Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(
                        onClick = { if (webView.canGoBack()) webView.goBack() },
                        enabled = canGoBack,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(
                        onClick = { if (webView.canGoForward()) webView.goForward() },
                        enabled = canGoForward,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }

                    Button(
                        onClick = { learnCurrentRenderedPage() },
                        enabled = !state.isLearningCurrentPage,
                        modifier = Modifier.testTag("explorer_learn_page_button"),
                    ) {
                        if (state.isLearningCurrentPage) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("Learn Page")
                    }

                    if (state.isExploring) {
                        OutlinedButton(
                            onClick = { explorer.stopExploring() },
                            modifier = Modifier.testTag("explorer_stop_auto_button"),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop Crawl")
                        }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                val target = addressInput.trim().ifBlank { null }
                                explorer.startAutonomousExplore(target, maxPages = 6)
                            },
                            modifier = Modifier.testTag("explorer_auto_crawl_button"),
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Auto-Explore")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val current = webView.url ?: addressInput
                            explorer.openInChrome(context, current)
                        },
                        modifier = Modifier.testTag("explorer_open_chrome_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open Chrome")
                    }
                }

                if (pageProgress in 1..99 || state.isExploring) {
                    LinearProgressIndicator(
                        progress = { if (pageProgress in 1..99) pageProgress / 100f else 0.5f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                statusBanner?.let { banner ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { statusBanner = null },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }

                // Live Chromium WebView
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Recent Autonomous Visits Strip
                if (state.recentVisits.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.recentVisits.take(8).forEach { visit ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.clickable {
                                    addressInput = visit.url
                                    webView.loadUrl(visit.url)
                                },
                            ) {
                                Text(
                                    text = "✓ ${visit.learnedEntryTitle.take(28)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = CodeFontFamily,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
