package app.iduanju.manga

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.iduanju.IdjApp

/** 可达阅读器 2.4.3 阅读模式：条漫(卷纸) / 左翻页 / 右翻页(日漫) / 双页(跨页). */
enum class MangaReadMode(val key: String, val label: String, val short: String) {
    WEBTOON("webtoon", "条漫模式", "条漫"),
    LTR("ltr", "左翻页", "左翻"),
    RTL("rtl", "右翻页（日漫）", "日漫"),
    DUAL("dual", "双页模式", "双页");
    val paged get() = this != WEBTOON
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
}

/** 可达式九宫格点击区域布局。 */
enum class MangaTapLayout(val key: String, val label: String) {
    LR("lr", "左右"),
    L_SHAPE("l", "L 形"),
    OFF("off", "关闭");
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: LR }
}

/** Reader preferences (Compose-observable, persisted to the shared "idj" prefs under `manga.reader.*`). */
object MangaReaderPrefs {
    private val sp by lazy { IdjApp.instance.getSharedPreferences("idj", Context.MODE_PRIVATE) }
    private fun b(k: String, d: Boolean) = runCatching { sp.getBoolean("manga.reader.$k", d) }.getOrDefault(d)
    private fun f(k: String, d: Float) = runCatching { sp.getFloat("manga.reader.$k", d) }.getOrDefault(d)
    private fun i(k: String, d: Int) = runCatching { sp.getInt("manga.reader.$k", d) }.getOrDefault(d)
    private fun s(k: String) = runCatching { sp.getString("manga.reader.$k", null) }.getOrNull()

    // 阅读模式 — migrates the old `stv.manga.vertical` boolean.
    var mode by mutableStateOf(MangaReadMode.of(s("mode")) ?: if (runCatching { sp.getBoolean("stv.manga.vertical", true) }.getOrDefault(true)) MangaReadMode.WEBTOON else MangaReadMode.LTR)
    var dualCoverOffset by mutableStateOf(b("dualCover", true))
    var dualRtl by mutableStateOf(b("dualRtl", true))
    var webtoonGap by mutableStateOf(b("gap", false))

    // 画面
    var brightness by mutableFloatStateOf(f("brightness", 1f))
    var warm by mutableFloatStateOf(f("warm", 0f))
    var autoCrop by mutableStateOf(b("autoCrop", false))
    var splitWide by mutableStateOf(b("splitWide", false))
    var invert by mutableStateOf(b("invert", false))
    var grayscale by mutableStateOf(b("gray", false))

    // 翻页 / 手势
    var tapLayout by mutableStateOf(MangaTapLayout.of(s("tap")))
    var tapInvert by mutableStateOf(b("tapInvert", false))
    var volumeKeys by mutableStateOf(b("volume", true))
    var pageAnim by mutableStateOf(b("pageAnim", true))
    /** 自动阅读速度 0..1：条漫 = 滚动速度，翻页 = 翻页间隔。 */
    var autoSpeed by mutableFloatStateOf(f("autoSpeed", 0.35f))

    // 阅读设置
    var keepScreenOn by mutableStateOf(b("keepOn", true))
    var showSystemBar by mutableStateOf(b("sysBar", false))
    var showFooter by mutableStateOf(b("footer", true))
    var preload by mutableIntStateOf(i("preload", 4))

    private fun put(k: String, v: Any) = sp.edit().apply {
        when (v) { is Boolean -> putBoolean("manga.reader.$k", v); is Float -> putFloat("manga.reader.$k", v); is Int -> putInt("manga.reader.$k", v); else -> putString("manga.reader.$k", v.toString()) }
    }.apply()

    fun updateMode(v: MangaReadMode) { mode = v; put("mode", v.key) }
    fun updateDualCover(v: Boolean) { dualCoverOffset = v; put("dualCover", v) }
    fun updateDualRtl(v: Boolean) { dualRtl = v; put("dualRtl", v) }
    fun updateGap(v: Boolean) { webtoonGap = v; put("gap", v) }
    fun updateBrightness(v: Float) { brightness = v.coerceIn(0.15f, 1f); put("brightness", brightness) }
    fun updateWarm(v: Float) { warm = v.coerceIn(0f, 1f); put("warm", warm) }
    fun updateAutoCrop(v: Boolean) { autoCrop = v; put("autoCrop", v) }
    fun updateSplitWide(v: Boolean) { splitWide = v; put("splitWide", v) }
    fun updateInvert(v: Boolean) { invert = v; put("invert", v) }
    fun updateGrayscale(v: Boolean) { grayscale = v; put("gray", v) }
    fun updateTapLayout(v: MangaTapLayout) { tapLayout = v; put("tap", v.key) }
    fun updateTapInvert(v: Boolean) { tapInvert = v; put("tapInvert", v) }
    fun updateVolumeKeys(v: Boolean) { volumeKeys = v; put("volume", v) }
    fun updatePageAnim(v: Boolean) { pageAnim = v; put("pageAnim", v) }
    fun updateAutoSpeed(v: Float) { autoSpeed = v.coerceIn(0f, 1f); put("autoSpeed", autoSpeed) }
    fun updateKeepScreenOn(v: Boolean) { keepScreenOn = v; put("keepOn", v) }
    fun updateShowSystemBar(v: Boolean) { showSystemBar = v; put("sysBar", v) }
    fun updateShowFooter(v: Boolean) { showFooter = v; put("footer", v) }
    fun updatePreload(v: Int) { preload = v.coerceIn(0, 10); put("preload", preload) }
}
