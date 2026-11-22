package app.iduanju.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.ui.LocalNavigate
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * iOS ProfileView: 我的 · 继续观看 · 我的收藏, then grouped link rows —
 * (观看历史 / 剧星榜 / 我的收藏) (Telegram 频道 / 漫画 / 下载管理) (片源管理 / 插件 / 开源许可) (maintenance), footer.
 * Settings live on their own pages (片源管理, 插件) instead of being laid out inline.
 */
@Composable
fun ProfileScreen(onOpenDetail: (String) -> Unit = {}) {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val lib = ia.library
    val go = LocalNavigate.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { ia.hgWebAccount.refreshProfile() }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp).padding(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text("我的", color = c.text, fontWeight = FontWeight.Black, fontSize = 32.sp, modifier = Modifier.padding(horizontal = 16.dp))
        if (lib.history.isNotEmpty()) app.iduanju.ui.home.ContinueRow(lib.continueWatching, onOpenDetail)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的收藏", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("全部 ${lib.favorites.size}", color = c.secondary, fontSize = 15.sp, modifier = Modifier.clickable { go("library/favorites") })
            }
            val favs = lib.favorites.entries.sortedByDescending { it.value.third }.take(6)
            if (favs.isEmpty()) Text("在播放页右侧点 ♥ 收藏", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            else Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                favs.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (sid, v) ->
                            Column(Modifier.weight(1f).clickable { onOpenDetail(sid) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)).background(c.elevated)) {
                                    coil.compose.AsyncImage(v.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Text(v.first, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Group("红果账号") {
                val hgProfile = ia.hgWebAccount.profile
                if (hgProfile == null) {
                    LinkRow("登录红果账号", ProfileIcons.key, sub = "使用红果 App 扫码登录，看剧进度同步到红果", trailing = "去登录") { go("hongguo-login") }
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { confirmLogout = true }.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(c.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hgProfile.avatarUrl.isNotEmpty()) {
                                coil.compose.AsyncImage(hgProfile.avatarUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Text(hgProfile.nickname.take(1), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("红果账号 · ${hgProfile.nickname}", color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ia.hgWebAccount.lastSyncNote ?: "已登录 · 看剧进度自动同步到红果", color = c.secondary, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        Text("退出", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Group {
                LinkRow("观看历史", ProfileIcons.history, trailing = lib.history.size.takeIf { it > 0 }?.toString()) { go("library/history") }
                RowDivider()
                LinkRow("剧星榜", ProfileIcons.star) { go("actorRank") }
                RowDivider()
                LinkRow("我的收藏", ProfileIcons.heart, trailing = "${lib.favorites.size}") { go("library/favorites") }
            }
            Group {
                LinkRow("Telegram 频道", ProfileIcons.telegram, trailing = "${ia.tg.visible(false).size} 个") { go("home/channels") }
                RowDivider()
                LinkRow("漫画", ProfileIcons.manga, trailing = ia.manga.favorites.size.takeIf { it > 0 }?.let { "收藏 $it" }) { go("home/manga") }
                RowDivider()
                LinkRow("小说", ProfileIcons.manga, sub = "TXT、阅读书源、搜索与发现") { go("novel") }
                RowDivider()
                LinkRow("下载管理", ProfileIcons.download, trailing = "暂未支持") { message = "Android 版暂不支持离线下载" }
            }
            Group("外观") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("玻璃质感", color = c.text, fontSize = 16.sp)
                    app.iduanju.ui.components.SegmentedTabs(
                        items = app.iduanju.ui.components.GlassStyle.entries,
                        selected = app.iduanju.ui.components.GlassStylePref.style,
                        label = { it.label },
                        onSelect = { app.iduanju.ui.components.GlassStylePref.update(it) },
                    )
                    if (app.iduanju.ui.components.GlassStylePref.style == app.iduanju.ui.components.GlassStyle.LIQUID) {
                        val strength = app.iduanju.ui.components.GlassStylePref.strength
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("通透度", color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("${(strength * 100).roundToInt()}%", color = c.secondary, fontSize = 13.sp)
                        }
                        Slider(
                            value = strength,
                            onValueChange = { app.iduanju.ui.components.GlassStylePref.updateStrength(it) },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.text.copy(alpha = 0.12f)),
                            modifier = Modifier.fillMaxWidth().testTag("profile.glass.strength"),
                        )
                    }
                }
            }
            Group {
                LinkRow("片源管理", ProfileIcons.sources, trailing = "红果") { go("sources") }
                RowDivider()
                val n = ia.plugins.plugins.count { !it.adult } + ia.cms.sites.count { !it.adult }
                LinkRow("插件", ProfileIcons.plugin, trailing = n.takeIf { it > 0 }?.let { "$it 个" }) { go("plugins") }
                RowDivider()
                LinkRow("开源许可", ProfileIcons.license) { go("licenses") }
                RowDivider()
                val up = ia.updater
                LinkRow("检查更新", ProfileIcons.update, trailing = if (up.checking) "检查中…" else up.currentVersion.joinToString(".")) {
                    scope.launch { up.manualCheck()?.let { message = it } }
                }
            }
            Group {
                ActRow("重置红果设备注册", ProfileIcons.reset, scope, { message = it }) { withContext(Dispatchers.IO) { ia.hongguo.resetDevice() }; "已重置红果设备" }
                RowDivider()
                ActRow("清空封面缓存", ProfileIcons.photos, scope, { message = it }) {
                    coil.Coil.imageLoader(ctx).let { l -> l.memoryCache?.clear(); withContext(Dispatchers.IO) { l.diskCache?.clear() } }; "封面缓存已清空"
                }
                RowDivider()
                ActRow("清空播放缓存", ProfileIcons.trash, scope, { message = it }) { withContext(Dispatchers.IO) { java.io.File(ctx.cacheDir, "hg-play").deleteRecursively() }; "播放缓存已清空" }
            }
            val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
            Text(message ?: "所有数据由本机直连上游，观看记录和收藏只保存在本机。\ni短剧 $ver",
                color = if (message != null) c.accent else c.secondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出红果账号？") },
            text = { Text("退出后将停止同步看剧进度到红果网页端，本地观看记录不受影响。") },
            confirmButton = {
                TextButton({
                    ia.hgWebAccount.logout()
                    confirmLogout = false
                    message = "已退出红果账号"
                }) { Text("退出", color = c.accentWarm) }
            },
            dismissButton = { TextButton({ confirmLogout = false }) { Text("取消") } },
        )
    }
}

/** Maintenance row: runs [work] and reports its message in the footer. */
@Composable
private fun ActRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, scope: kotlinx.coroutines.CoroutineScope,
    onDone: (String) -> Unit, work: suspend () -> String) =
LinkRow(title, icon) { scope.launch { onDone(runCatching { work() }.getOrElse { it.message ?: "失败" }) } }
