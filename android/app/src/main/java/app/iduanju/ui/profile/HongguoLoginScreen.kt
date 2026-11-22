package app.iduanju.ui.profile

import android.annotation.SuppressLint
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.iduanju.IdjApp
import app.iduanju.data.HongguoWebAccount
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val LOGIN_STYLE_CSS =
    "* { -webkit-backdrop-filter: none !important; backdrop-filter: none !important; } " +
        "[role=\"dialog\"], [class*=\"pc-login-dialog\"] { " +
        "-webkit-backdrop-filter: none !important; backdrop-filter: none !important; " +
        "max-height: calc(100% - 32px) !important; " +
        "background: #222424 !important; opacity: 1 !important; visibility: visible !important; }"

private const val AUTO_OPEN_LOGIN_JS = """
(function() {
  try {
    function ensureFixStyle() {
      if (!document.getElementById('idj-login-fix')) {
        var st = document.createElement('style');
        st.id = 'idj-login-fix';
        st.textContent = '$LOGIN_STYLE_CSS';
        (document.head || document.documentElement).appendChild(st);
      }
    }
    ensureFixStyle();
    if (window.__idjLoginTimer) clearInterval(window.__idjLoginTimer);
    var tries = 0;
    window.__idjLoginTimer = setInterval(function() {
      tries++;
      try {
        ensureFixStyle();
        var dlg = document.querySelector('[role="dialog"], [class*="pc-login-dialog"]');
        if (dlg && dlg.getBoundingClientRect().width > 0 && dlg.getBoundingClientRect().height > 0) {
          clearInterval(window.__idjLoginTimer);
          window.__idjLoginTimer = null;
          return;
        }
        // Only click once every ~1s until dialog opens (React hydration may lag SSR).
        if (tries % 2 === 1) {
          var clicked = false;
          var selectors = ['button', 'a, [role="button"]', 'span, div'];
          for (var s = 0; s < selectors.length && !clicked; s++) {
            var btns = document.querySelectorAll(selectors[s]);
            for (var i = 0; i < btns.length; i++) {
              var el = btns[i];
              if ((el.innerText || '').trim() === '登录') {
                el.click();
                clicked = true;
                break;
              }
            }
          }
        }
      } catch (e) {}
      if (tries >= 40) {
        clearInterval(window.__idjLoginTimer);
        window.__idjLoginTimer = null;
      }
    }, 500);
  } catch (e) {}
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HongguoLoginScreen(onBack: () -> Unit) {
    val c = IdjThemeColors
    val account = IdjApp.instance.hgWebAccount
    val currentOnBack by rememberUpdatedState(onBack)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loggedInName by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
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

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = c.text)
            }
            Text(
                "登录红果网页端",
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(
                onClick = { webView?.reload() },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(Icons.Filled.Refresh, "刷新", tint = c.text)
            }
        }

        val bannerText = loggedInName?.let { "已登录红果账号「$it」，正在返回…" }
            ?: "支持使用红果短剧 App 扫码或手机号验证码登录，登录后看剧进度将自动同步到红果账号。"
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (loggedInName != null) c.okSoft else c.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                bannerText,
                color = if (loggedInName != null) c.ok else c.secondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.hairline)

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    // Software layer avoids emulator/GPU compositor dropouts on fixed backdrop-blur dialogs.
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = HongguoWebAccount.PC_USER_AGENT
                        useWideViewPort = true
                        loadWithOverviewMode = false
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            super.onPageCommitVisible(view, url)
                            view?.evaluateJavascript(AUTO_OPEN_LOGIN_JS, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(AUTO_OPEN_LOGIN_JS, null)
                        }
                    }
                    loadUrl(HongguoWebAccount.WEB_ORIGIN)
                    webView = this
                }
            },
        )
    }
}
