package app.iduanju.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.ui.LocalNavigate
import app.iduanju.ui.theme.IdjThemeColors

/**
 * iOS SourcesView (我的 → 片源管理): 内置 sources, adult gate (with 18+ confirmation), 插件与采集站 link,
 * playback options. The 红果 device override is an advanced option, folded at the bottom.
 */
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val go = LocalNavigate.current
    var adStrip by remember { mutableStateOf(app.iduanju.core.hls.HLSAdFilter.enabled) }
    var prefetch by remember { mutableStateOf(ia.settings.prefetchEnabled) }
    var cacheMB by remember { mutableIntStateOf(ia.settings.playbackCacheMB) }

    SettingsPage("片源管理", onBack) {
        Group("内置") {
            BuiltinRow("红果短剧", ProfileIcons.play)
        }
        Group("插件与采集站") {
            LinkRow("插件管理", ProfileIcons.plugin, sub = "JS 插件、订阅、苹果 CMS 采集站") { go("plugins") }
        }
        Group("播放") {
            SwitchRow("去广告", ProfileIcons.adblock, "去掉 HLS 片源里插播的广告片段（adjump、异域名短片段、重复片段）", adStrip) {
                adStrip = it; app.iduanju.core.hls.HLSAdFilter.enabled = it
                ia.settings.adStripEnabled = it
            }
            SwitchRow("预加载下一集", ProfileIcons.download, "播放时提前缓存下一集，可减少切集等待", prefetch) {
                prefetch = it; ia.settings.prefetchEnabled = it
            }
            Text("播放缓存上限：${cacheMB} MB", color = c.text, modifier = Modifier.padding(14.dp))
            androidx.compose.material3.Slider(value = cacheMB.toFloat(), onValueChange = { cacheMB = (it / 100).toInt() * 100 },
                onValueChangeFinished = { ia.settings.playbackCacheMB = cacheMB }, valueRange = 100f..2000f, steps = 18, modifier = Modifier.padding(horizontal = 14.dp))
        }
        DeviceOverrideGroup()
    }
}

@Composable
private fun BuiltinRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, sub: String? = null) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = c.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 16.sp)
            sub?.let { Text(it, color = c.secondary, fontSize = 12.sp) }
        }
        Tag("内置")
    }
}

/** 高级：红果播放设备覆盖 (iOS `stv.hg.purePlayerDevice`). Collapsed by default; empty = register automatically. */
@Composable
private fun DeviceOverrideGroup() {
    val c = IdjThemeColors
    val settings = IdjApp.instance.settings
    var open by remember { mutableStateOf(settings.playerDeviceOverride() != null) }
    var did by remember { mutableStateOf(settings.playerDeviceOverride()?.first ?: "") }
    var iid by remember { mutableStateOf(settings.playerDeviceOverride()?.second ?: "") }
    var saved by remember { mutableStateOf<String?>(null) }
    Group("高级", footer = if (open) "留空时自动注册一台播放设备（只保存在内存里）；填写后所有取流都使用这台设备，不会写进源码。" else null) {
        LinkRow("红果播放设备", ProfileIcons.device, trailing = if (settings.playerDeviceOverride() != null) "自定义" else "自动") { open = !open }
        if (open) Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = TextFieldDefaults.colors(focusedContainerColor = c.bg, unfocusedContainerColor = c.bg)
            OutlinedTextField(did, { did = it }, label = { Text("device_id") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = colors)
            OutlinedTextField(iid, { iid = it }, label = { Text("iid") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = colors)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("保存", enabled = did.isNotBlank() && iid.isNotBlank()) { settings.setPlayerDeviceOverride(did.trim(), iid.trim()); saved = "已保存，之后取流使用这台设备" }
                TextAction("恢复自动") { did = ""; iid = ""; settings.setPlayerDeviceOverride(null, null); saved = "已恢复自动注册" }
            }
            saved?.let { Text(it, color = c.accent, fontSize = 12.sp) }
        }
    }
}
