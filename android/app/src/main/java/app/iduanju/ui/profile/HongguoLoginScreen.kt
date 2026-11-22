package app.iduanju.ui.profile

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.iduanju.IdjApp
import app.iduanju.data.HongguoWebAccount
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val BASE_LOGIN_STYLE_CSS =
    "*, *::before, *::after { animation: none !important; transition: none !important; " +
        "-webkit-backdrop-filter: none !important; backdrop-filter: none !important; } " +
        "video { display: none !important; } " +
        "[role=\"dialog\"], [class*=\"pc-login-dialog\"] { " +
        "-webkit-backdrop-filter: none !important; backdrop-filter: none !important; " +
        "max-height: calc(100% - 32px) !important; " +
        "opacity: 1 !important; visibility: visible !important; }"

private val VIEWPORT_META_RE = Regex("<meta\\b[^>]*\\bname\\s*=\\s*[\"']?viewport[\"']?[^>]*>", RegexOption.IGNORE_CASE)
private val HEAD_OPEN_RE = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)

private val desktopHtmlHttp = OkHttpClient.Builder()
    .callTimeout(15, TimeUnit.SECONDS)
    .build()

private fun isMainEntryUrl(url: Uri?): Boolean {
    if (url == null) return false
    if (!url.scheme.equals("https", ignoreCase = true)) return false
    if (!url.host.equals("hongguoduanju.com", ignoreCase = true)) return false
    val path = url.path
    return path.isNullOrEmpty() || path == "/"
}

private fun forceDesktopViewport(html: String): String {
    val desktopMeta = "<meta name=\"viewport\" content=\"width=1280\">"
    if (VIEWPORT_META_RE.containsMatchIn(html)) {
        return VIEWPORT_META_RE.replace(html, desktopMeta)
    }
    val headMatch = HEAD_OPEN_RE.find(html)
    return if (headMatch != null) {
        html.replaceRange(
            headMatch.range.last + 1,
            headMatch.range.last + 1,
            desktopMeta,
        )
    } else {
        "$desktopMeta$html"
    }
}

private fun fetchDesktopHtmlResponse(url: String): WebResourceResponse? {
    return try {
        val cm = CookieManager.getInstance()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", HongguoWebAccount.PC_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        val cookie = cm.getCookie(url)
        if (!cookie.isNullOrBlank()) {
            reqBuilder.header("Cookie", cookie)
        }
        desktopHtmlHttp.newCall(reqBuilder.build()).execute().use { resp ->
            Log.i("IDJ-HGLogin", "fetchDesktopHtmlResponse code=${resp.code} url=$url")
            if (!resp.isSuccessful) return null
            resp.headers("Set-Cookie").forEach { sc ->
                cm.setCookie(url, sc)
            }
            val html = resp.body?.string()
            if (html.isNullOrBlank()) return null
            val patched = forceDesktopViewport(html)
            val vpMatch = VIEWPORT_META_RE.find(patched)?.value
            Log.i("IDJ-HGLogin", "fetchDesktopHtmlResponse len=${html.length} vp=$vpMatch")
            WebResourceResponse("text/html", "UTF-8", patched.byteInputStream())
        }
    } catch (t: Throwable) {
        Log.w("IDJ-HGLogin", "fetchDesktopHtmlResponse failed for $url", t)
        null
    }
}

private val BRIDGE_JS: String by lazy {
    val cssLiteral = JSONObject.quote(BASE_LOGIN_STYLE_CSS)
    """
(function() {
  try {
    function ensureFixStyle() {
      var st = document.getElementById('idj-login-fix');
      if (!st) {
        st = document.createElement('style');
        st.id = 'idj-login-fix';
        (document.head || document.documentElement).appendChild(st);
      }
      var css = $cssLiteral;
      if (st.textContent !== css) st.textContent = css;
    }
    ensureFixStyle();

    function isElVisible(el) {
      if (!el) return false;
      if (el.offsetWidth <= 0 || el.offsetHeight <= 0) return false;
      var cs = getComputedStyle(el);
      return cs.display !== 'none' && cs.visibility !== 'hidden' && cs.opacity !== '0';
    }

    function findDialog() {
      return document.querySelector('[role="dialog"], [class*="pc-login-dialog"]');
    }

    function diag(msg) {
      try {
        if (window.IdjNativeLogin && window.IdjNativeLogin.logDiag) {
          window.IdjNativeLogin.logDiag(String(msg));
        }
      } catch (e) {}
    }

    window.__idjActions = {
      refreshQr: function() {
        var dlg = findDialog();
        if (dlg) {
          var mask = dlg.querySelector('.web-login-scan-code__content__qrcode-wrapper__mask, [class*="qrcode-wrapper__mask"]');
          if (mask && isElVisible(mask)) {
            mask.click();
            var innerBtn = mask.querySelector('button, [role="button"], svg, span');
            if (innerBtn) innerBtn.click();
            return 'clicked';
          }
        }
        location.reload();
        return 'reloaded';
      }
    };

    var vpEl = document.querySelector('meta[name="viewport"]');
    diag('init iw=' + window.innerWidth + ' ih=' + window.innerHeight + ' vp=' + (vpEl ? vpEl.getAttribute('content') : 'none'));

    if (window.__idjLoginOpenTimer) clearInterval(window.__idjLoginOpenTimer);
    var openTries = 0;
    window.__idjLoginOpenTimer = setInterval(function() {
      openTries++;
      try {
        ensureFixStyle();
        var dlg = findDialog();
        var rect = dlg ? dlg.getBoundingClientRect() : null;
        if (dlg && rect.width > 0 && rect.height > 0) {
          diag('dlgOpen iw=' + window.innerWidth + ' rect=' + Math.round(rect.width) + 'x' + Math.round(rect.height));
          clearInterval(window.__idjLoginOpenTimer);
          window.__idjLoginOpenTimer = null;
          return;
        }
        if (openTries % 2 === 1) {
          var clicked = false;
          var selectors = ['button', 'a, [role="button"]', 'span, div'];
          for (var s = 0; s < selectors.length && !clicked; s++) {
            var btns = document.querySelectorAll(selectors[s]);
            for (var i = 0; i < btns.length; i++) {
              var el = btns[i];
              if ((el.textContent || '').trim() === '登录') {
                el.click();
                clicked = true;
                break;
              }
            }
          }
        }
      } catch (e) { diag('openErr=' + e); }
      if (openTries >= 40) {
        diag('openTimeout iw=' + window.innerWidth);
        clearInterval(window.__idjLoginOpenTimer);
        window.__idjLoginOpenTimer = null;
      }
    }, 500);

    window.__idjLastQrKey = null;
    if (window.__idjStateObserver) clearInterval(window.__idjStateObserver);
    window.__idjStateObserver = setInterval(function() {
      try {
        ensureFixStyle();
        var dlg = findDialog();

        // QR image + status
        var qrSrc = '';
        var qrStatus = 'loading';
        if (dlg) {
          var imgs = dlg.querySelectorAll('img');
          for (var i = 0; i < imgs.length; i++) {
            var im = imgs[i];
            var src = im.getAttribute('src') || im.src || '';
            if (src.indexOf('data:image/') === 0 && (im.naturalWidth >= 100 || !!im.closest('.web-login-scan-code') || (im.alt || '').indexOf('二维码') >= 0)) {
              qrSrc = src;
              break;
            }
          }
          if (!qrSrc) {
            var scanImg = dlg.querySelector('.web-login-scan-code img, [alt*="二维码"]');
            if (scanImg) {
              var ss = scanImg.getAttribute('src') || scanImg.src || '';
              if (ss.indexOf('data:image/') === 0) qrSrc = ss;
            }
          }
          if (!qrSrc) {
            var canvas = dlg.querySelector('.web-login-scan-code canvas');
            if (canvas && canvas.width >= 100) {
              try { qrSrc = canvas.toDataURL('image/png'); } catch (ce) {}
            }
          }

          var dlgText = dlg.textContent || '';
          var mask = dlg.querySelector('.web-login-scan-code__content__qrcode-wrapper__mask, [class*="qrcode-wrapper__mask"]');
          if (dlgText.indexOf('已扫描') >= 0 || dlgText.indexOf('请在手机') >= 0) {
            qrStatus = 'scanned';
          } else if (isElVisible(mask) || dlgText.indexOf('点击刷新') >= 0 || dlgText.indexOf('已过期') >= 0 || dlgText.indexOf('二维码失效') >= 0) {
            qrStatus = 'expired';
          } else if (qrSrc) {
            qrStatus = 'ready';
          }
        }

        // Push QR updates on change
        var qrKey = qrStatus + '|' + qrSrc;
        if (qrKey !== window.__idjLastQrKey) {
          window.__idjLastQrKey = qrKey;
          if (window.IdjNativeLogin && window.IdjNativeLogin.onQrUpdate) {
            window.IdjNativeLogin.onQrUpdate(qrSrc, qrStatus);
          }
        }
      } catch (e) {}
    }, 1200);
  } catch (e) {}
})();
""".trimIndent()
}

private class IdjNativeLoginBridge(
    private val onQr: (base64DataUrl: String, status: String) -> Unit,
) {
    @JavascriptInterface
    fun onQrUpdate(base64DataUrl: String, status: String) {
        Log.i("IDJ-HGLogin", "onQrUpdate status=$status len=${base64DataUrl.length}")
        onQr(base64DataUrl, status)
    }

    @JavascriptInterface
    fun logDiag(msg: String) {
        Log.i("IDJ-HGLogin", "JS: $msg")
    }
}

private fun decodeDataUrlBitmap(dataUrl: String): Bitmap? {
    if (dataUrl.isBlank()) return null
    return runCatching {
        val b64 = dataUrl.substringAfter("base64,", "").trim()
        if (b64.isEmpty()) return null
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

private fun saveQrBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    return runCatching {
        val resolver = context.contentResolver
        val fileName = "hongguo_qr_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/iDuanJu")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Bitmap compress failed")
                }
            } ?: throw IllegalStateException("Unable to open output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            true
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }.getOrDefault(false)
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun HongguoLoginScreen(onBack: () -> Unit) {
    val c = IdjThemeColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = IdjApp.instance.hgWebAccount
    val currentOnBack by rememberUpdatedState(onBack)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loggedInName by remember { mutableStateOf<String?>(null) }

    // QR state
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrStatus by remember { mutableStateOf("loading") }
    val qrSeq = remember { AtomicLong(0L) }
    var savingQr by remember { mutableStateOf(false) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.removeJavascriptInterface("IdjNativeLogin")
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2500)
            val p = account.refreshProfile()
            if (p != null) {
                loggedInName = p.nickname
                delay(600)
                currentOnBack()
                break
            }
        }
    }

    fun refreshQr() {
        saveNotice = null
        qrStatus = "loading"
        val wv = webView ?: return
        wv.evaluateJavascript(
            "(function(){ if (window.__idjActions) { return window.__idjActions.refreshQr(); } location.reload(); return 'reloaded'; })();",
        ) { result ->
            if (result?.contains("reloaded") == true) {
                qrBitmap = null
            }
        }
    }

    fun saveQrToGallery() {
        val bmp = qrBitmap ?: return
        if (savingQr) return
        savingQr = true
        saveNotice = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { saveQrBitmapToGallery(context, bmp) }
            savingQr = false
            if (ok) {
                val msg = "已保存到相册，可在红果 App 扫一扫中选择相册图片"
                saveNotice = msg
                saveError = false
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } else {
                val msg = "保存失败，请检查相册权限后重试"
                saveNotice = msg
                saveError = true
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Native Top Bar
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = c.text)
            }
            Text(
                "登录红果账号",
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.hairline)

        if (loggedInName != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.okSoft)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "已登录红果账号「$loggedInName」，正在返回…",
                    color = c.ok,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = c.hairline)
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Hidden WebView engine mounted continuously at full layout size behind the native UI.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        visibility = View.INVISIBLE
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setOnTouchListener { _, _ -> true }
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = HongguoWebAccount.PC_USER_AGENT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(
                            IdjNativeLoginBridge(
                                onQr = { dataUrl, status ->
                                    val seq = qrSeq.incrementAndGet()
                                    scope.launch {
                                        val decoded = if (dataUrl.isNotBlank()) {
                                            withContext(Dispatchers.Default) {
                                                decodeDataUrlBitmap(dataUrl)
                                            }
                                        } else {
                                            null
                                        }
                                        if (seq != qrSeq.get()) return@launch
                                        qrStatus = status
                                        if (decoded != null) {
                                            qrBitmap = decoded
                                        } else if (status == "loading" && dataUrl.isBlank()) {
                                            qrBitmap = null
                                        }
                                    }
                                },
                            ),
                            "IdjNativeLogin",
                        )
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                                if (cm != null && cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                    Log.w("IDJ-HGLogin", "console[ERROR]: ${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})")
                                }
                                return super.onConsoleMessage(cm)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                if (request == null || !request.isForMainFrame) return null
                                if (!request.method.equals("GET", ignoreCase = true)) return null
                                val url = request.url ?: return null
                                val isEntry = isMainEntryUrl(url)
                                Log.i("IDJ-HGLogin", "shouldInterceptRequest mainFrame=$url isEntry=$isEntry")
                                if (!isEntry) return null
                                return fetchDesktopHtmlResponse(url.toString())
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                super.onPageCommitVisible(view, url)
                                Log.i("IDJ-HGLogin", "onPageCommitVisible url=$url")
                                view?.evaluateJavascript(BRIDGE_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.i("IDJ-HGLogin", "onPageFinished url=$url")
                                view?.evaluateJavascript(BRIDGE_JS, null)
                            }
                        }
                        loadUrl(HongguoWebAccount.WEB_ORIGIN)
                        webView = this
                    }
                },
            )

            // Opaque 100% native Compose UI layer covering the hidden WebView
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.bg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        QrLoginCard(
                            qrBitmap = qrBitmap,
                            qrStatus = qrStatus,
                            savingQr = savingQr,
                            saveNotice = saveNotice,
                            saveError = saveError,
                            onRefresh = { refreshQr() },
                            onSave = { saveQrToGallery() },
                        )

                        Text(
                            "登录后看剧记录与进度将自动同步到你的红果账号",
                            color = c.secondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrLoginCard(
    qrBitmap: Bitmap?,
    qrStatus: String,
    savingQr: Boolean,
    saveNotice: String?,
    saveError: Boolean,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
) {
    val c = IdjThemeColors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(0.5.dp, c.hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "使用红果短剧 App 扫码登录",
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "打开红果短剧 App →「我的」右上角 ≡ → 扫一扫",
                color = c.secondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        // White QR Code Container
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .size(220.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(1.dp, c.hairline, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "红果登录二维码",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentScale = ContentScale.Fit,
                )

                if (qrStatus == "expired") {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.72f))
                            .clickable(onClick = onRefresh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "二维码已过期",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Button(
                                onClick = onRefresh,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = c.accent,
                                    contentColor = c.onAccent,
                                ),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Text("点击刷新", fontSize = 13.sp)
                            }
                        }
                    }
                } else if (qrStatus == "scanned") {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xE614281D))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = c.ok,
                                modifier = Modifier.size(34.dp),
                            )
                            Text(
                                "已扫码，请在手机上点击确认",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color(0xFF286DEF),
                        strokeWidth = 2.5.dp,
                    )
                    Text(
                        "正在获取二维码…",
                        color = Color(0xFF5F6672),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        val statusCaption = when (qrStatus) {
            "scanned" -> "请在红果短剧 App 中确认登录"
            "expired" -> "二维码已过期，请点击刷新"
            "ready" -> "二维码有效，请使用红果短剧 App 扫描"
            else -> "正在连接红果登录服务…"
        }
        Text(
            statusCaption,
            color = if (qrStatus == "scanned") c.ok else c.secondary,
            fontSize = 12.sp,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = SolidColor(c.hairline),
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = c.text,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("刷新二维码", color = c.text, fontSize = 13.sp, maxLines = 1)
            }

            Button(
                onClick = onSave,
                enabled = qrBitmap != null && !savingQr,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.onAccent,
                    disabledContainerColor = c.accent.copy(alpha = 0.35f),
                    disabledContentColor = c.onAccent.copy(alpha = 0.6f),
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (savingQr) "保存中…" else "保存二维码",
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }

        if (!saveNotice.isNullOrBlank()) {
            Text(
                saveNotice,
                color = if (saveError) c.accentWarm else c.ok,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
