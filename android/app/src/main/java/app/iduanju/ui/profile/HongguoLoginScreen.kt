package app.iduanju.ui.profile

import android.annotation.SuppressLint
import android.webkit.CookieManager
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

private const val AUTO_OPEN_LOGIN_JS = """
setTimeout(function() {
  try {
    var text = (document.body && document.body.innerText) || '';
    if (text.indexOf('扫码登录') !== -1) return;
    var els = document.querySelectorAll('button, a, div, span');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if ((el.innerText || '').trim() === '登录') {
        el.click();
        break;
      }
    }
  } catch (e) {}
}, 800);
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
                    webViewClient = object : WebViewClient() {
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
