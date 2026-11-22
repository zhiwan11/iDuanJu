package app.iduanju.manga

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.iduanju.IdjApp

/** 阅读模式：条漫(卷纸) / 左翻页 / 右翻页(日漫) / 双页(跨页). */
enum class MangaReadMode(val key: String, val label: String, val short: String) {
    WEBTOON("webtoon", "条漫模式", "条漫"),
    LTR("ltr", "左翻页", "左翻"),
    RTL("rtl", "右翻页（日漫）", "日漫"),
    DUAL("dual", "双页模式", "双页");
    val paged get() = this != WEBTOON
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
}

/** 九宫格点击区域布局。 */
enum class MangaTapLayout(val key: String, val label: String) {
    LR("lr", "左右"),
    L_SHAPE("l", "L 形"),
    OFF("off", "关闭");
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: LR }
}

/** 翻页效果 for the horizontal paged modes (左翻 / 日漫 / 双页). */
enum class MangaPageAnim(val label: String) {
    CURL("仿真翻页"),
    COVER("覆盖"),
    SLIDE("平滑"),
    FADE("淡入");
    companion object { fun of(k: String?) = entries.firstOrNull { it.name == k } ?: CURL }
}

/** 「图片背景」: what fills the margins around a fitted page (and, for 纹理, the paper the page is printed on). */
enum class MangaPageBg(val label: String) {
    SYSTEM("跟随系统"),
    WHITE("白色"),
    BLACK("黑色"),
    IMMERSIVE("沉浸式"),
    TEXTURE("纹理");
    companion object { fun of(k: String?) = entries.firstOrNull { it.name == k } ?: BLACK }
}

/** 纹理 paper styles (纹理设置 · 样式). */
enum class MangaPaperStyle(val label: String, val tint: Long) {
    CLASSIC("经典", 0xFFF1EADB),
    WARM("暖黄", 0xFFEBDDBF),
    RICE("宣纸", 0xFFF5F2EA);
    companion object { fun of(k: String?) = entries.firstOrNull { it.name == k } ?: CLASSIC }
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
    /** 图片背景 + 纹理 样式 / 透明度. */
    var pageBg by mutableStateOf(MangaPageBg.of(s("pageBg")))
    var paperStyle by mutableStateOf(MangaPaperStyle.of(s("paperStyle")))
    var textureOpacity by mutableFloatStateOf(f("textureOpacity", 0.7f).coerceIn(0f, 1f))
    /** 漫画超分：0 关闭 · 1 标准（即时 GPU 着色器）· 2 增强（离线 Real-ESRGAN 神经网络）. */
    var superResLevel by mutableIntStateOf(i("superRes", 0).coerceIn(0, 2))

    // 沉浸氛围（雨天窗景 / 列车车窗 / 新年烟火 / 雪夜）+ 环境音效
    var atmosphere by mutableStateOf(MangaAtmosphere.entries.firstOrNull { it.name == s("atmosphere") } ?: MangaAtmosphere.OFF)
    var atmosphereSound by mutableStateOf(b("atmoSound", true))
    var atmosphereVolume by mutableFloatStateOf(f("atmoVolume", 0.55f).coerceIn(0f, 1f))

    // 翻页 / 手势
    var tapLayout by mutableStateOf(MangaTapLayout.of(s("tap")))
    var tapInvert by mutableStateOf(b("tapInvert", false))
    var volumeKeys by mutableStateOf(b("volume", true))
    /** 翻页动画开关 (the old `pageAnim` boolean key): off = tap / volume-key turns jump instantly. */
    var turnAnim by mutableStateOf(b("pageAnim", true))
    /** 翻页效果 for paged modes; default 仿真翻页. */
    var pageAnim by mutableStateOf(MangaPageAnim.of(s("pageAnimFx")))
    /** 自动阅读速度 0..1：条漫 = 滚动速度，翻页 = 翻页间隔。 */
    var autoSpeed by mutableFloatStateOf(f("autoSpeed", 0.35f))
    /** 翻页音效 (page_flip.wav); also plays whenever 沉浸氛围 环境音效 is on. */
    var pageFlipSound by mutableStateOf(b("flipSound", false))

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
    fun updateTurnAnim(v: Boolean) { turnAnim = v; put("pageAnim", v) }
    fun updatePageAnim(v: MangaPageAnim) { pageAnim = v; put("pageAnimFx", v.name) }
    fun updatePageBg(v: MangaPageBg) { pageBg = v; put("pageBg", v.name) }
    fun updatePaperStyle(v: MangaPaperStyle) { paperStyle = v; put("paperStyle", v.name) }
    fun updateTextureOpacity(v: Float) { textureOpacity = v.coerceIn(0f, 1f); put("textureOpacity", textureOpacity) }
    fun updateSuperResLevel(v: Int) { superResLevel = v.coerceIn(0, 2); put("superRes", superResLevel) }
    fun updateAtmosphere(v: MangaAtmosphere) { atmosphere = v; put("atmosphere", v.name) }
    fun updateAtmosphereSound(v: Boolean) { atmosphereSound = v; put("atmoSound", v) }
    fun updateAtmosphereVolume(v: Float) { atmosphereVolume = v.coerceIn(0f, 1f); put("atmoVolume", atmosphereVolume) }
    fun updatePageFlipSound(v: Boolean) { pageFlipSound = v; put("flipSound", v) }
    fun updateAutoSpeed(v: Float) { autoSpeed = v.coerceIn(0f, 1f); put("autoSpeed", autoSpeed) }
    fun updateKeepScreenOn(v: Boolean) { keepScreenOn = v; put("keepOn", v) }
    fun updateShowSystemBar(v: Boolean) { showSystemBar = v; put("sysBar", v) }
    fun updateShowFooter(v: Boolean) { showFooter = v; put("footer", v) }
    fun updatePreload(v: Int) { preload = v.coerceIn(0, 10); put("preload", preload) }
}
