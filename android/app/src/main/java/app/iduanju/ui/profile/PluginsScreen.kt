package app.iduanju.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.plugin.PluginException
import app.iduanju.core.plugin.PluginManifest
import app.iduanju.plugin.PluginStore
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import app.iduanju.core.cms.TVBoxImport
import app.iduanju.core.cms.MacCMS
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS PluginsView (我的 → 插件): 已安装 (toggle, ⋯ remove), 添加插件 (网址 / 文件 / 粘贴 / 检查更新 — each opens
     * its own dialog), 订阅, 推荐插件（内置）, 采集站（苹果 CMS）. Adult entries only appear once the gate is open.
 */
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val c = IdjThemeColors
    val ia = IdjApp.instance
    val store = ia.plugins
    val cms = ia.cms
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }   // url | paste | sub | cms
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }  // kind:id to name

    fun report(ok: String?, err: String?) { message = ok ?: err; error = ok == null }
    fun run(work: suspend () -> String) {
        busy = true; message = null
        scope.launch {
            try { report(withContext(Dispatchers.IO) { work() }, null) }
            catch (e: PluginException) { report(null, e.friendly) } catch (e: Exception) { report(null, e.message ?: "失败") }
            busy = false
        }
    }
    var liveLists by remember { mutableStateOf(emptyList<TVBoxImport.Live>()) }
    suspend fun describe(r: PluginStore.Pasted) = when (r) {
        is PluginStore.Pasted.Plugin -> "已安装「${r.rec.name}」${r.rec.version}"
        is PluginStore.Pasted.Subscription -> "已添加订阅（${r.sub.plugins.size} 个插件 / ${r.sub.cms.size} 个采集站），在下方按需安装"
        is PluginStore.Pasted.TVBox -> {
            var added = 0
            var failed = 0
            withContext(Dispatchers.Main) { liveLists = r.config.lives }
            for (batch in r.config.sites.chunked(8)) {
                val results = coroutineScope { batch.map { site -> async { site to runCatching { MacCMS.fetchCategories(site.api) }.getOrNull() } }.awaitAll() }
                withContext(Dispatchers.Main) {
                    for ((site, cats) in results) {
                        if (cats == null) failed++ else { cms.saveValidated(site.name, site.api, site.adult, "TVBox", cats); added++ }
                    }
                    message = "正在验证采集站 ${added + failed}/${r.config.sites.size}"
                }
            }
            "TVBox API 站点：验证后添加 $added/${r.config.sites.size}${if (failed > 0) "，验证失败 $failed" else ""}${if (r.config.skipped > 0) "，跳过 ${r.config.skipped} 个脚本/重复/不兼容项" else ""}"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("读取文件失败")
            val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            describe(store.interpret(text, "file", name?.substringBeforeLast('.')))
        }
    }

    SettingsPage("插件", onBack) {
        val installed = store.plugins.filter { !it.adult }
        Group("已安装（${installed.size}）") {
            if (installed.isEmpty()) Text("还没有插件。可以从下面的网址 / 文件 / 订阅添加，或安装内置推荐。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
            installed.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().clickable { removing = "plugin:${p.id}" to p.name }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (p.type == "comic") ProfileIcons.manga else ProfileIcons.plugin, null, tint = c.accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(p.name, color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Tag(typeLabel(p.type))
                        }
                        Text("v${p.version} · ${originLabel(p.origin)} · ${if (p.enabled) "已启用" else "已停用"}", color = c.secondary, fontSize = 12.sp)
                    }
                    Switch(p.enabled, { store.setEnabled(p.id, it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
                }
            }
        }
        Group("添加插件", footer = "插件运行在 QuickJS 沙箱里，只能访问 manifest 声明的域名；未成年相关内容一律过滤。") {
            LinkRow("从网址添加", ProfileIcons.link) { dialog = "url" }
            RowDivider()
            LinkRow("从文件导入（插件 / M3U / TVBox JSON）", ProfileIcons.file) { picker.launch(arrayOf("*/*")) }
            RowDivider()
            LinkRow("粘贴插件内容", ProfileIcons.paste) { dialog = "paste" }
            RowDivider()
            LinkRow(if (busy) "正在检查更新…" else "检查更新", ProfileIcons.sync) {
                if (!busy) run { store.refreshSubscriptions(); "已检查订阅中的插件更新" }
            }
        }
        if (liveLists.isNotEmpty()) Group("TVBox 直播") {
            LinkRow("选择直播列表（${liveLists.size}）", ProfileIcons.link) { dialog = "live" }
        }
        Group("订阅") {
            store.subscriptions.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifEmpty { s.url }, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.error ?: s.url, color = if (s.error == null) c.secondary else c.accentWarm, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextAction("删除") { removing = "sub:${s.url}" to (s.name.ifEmpty { s.url }) }
                    }
                    s.plugins.filter { it.adult != true }.forEach { e ->
                        val inst = store.plugins.firstOrNull { it.id == e.id }
                        val newer = inst != null && e.version != null && PluginManifest.compare(e.version!!, inst.version) > 0
                        EntryRow(e.name, emptyList(), when { inst == null -> "安装"; newer -> "更新"; else -> null }) {
                            run { describe(store.interpret(e.manifest, "订阅：" + s.url)) }
                        }
                    }
                    s.cms.filter { it.adult != true }.forEach { e ->
                        val added = cms.sites.any { st -> runCatching { app.iduanju.core.cms.MacCMS.normalize(e.api) == st.api }.getOrDefault(false) }
                        EntryRow("采集站 · " + e.name, emptyList(), if (added) null else "添加") {
                            run { val st = cms.add(e.name, e.api, false, "订阅：" + s.url); "已添加采集站 ${st.name}" }
                        }
                    }
                }
            }
            if (store.subscriptions.isNotEmpty()) RowDivider()
            LinkRow("添加订阅地址", ProfileIcons.add) { dialog = "sub" }
        }
        val bundled = remember { store.bundledManifests() }
        val visibleBundled = bundled.filter { !it.second.adult }
        if (visibleBundled.isNotEmpty()) Group("推荐插件（App 内置）") {
            visibleBundled.forEachIndexed { i, (file, m) ->
                if (i > 0) RowDivider()
                val inst = store.plugins.firstOrNull { it.id == m.id }
                val newer = inst != null && PluginManifest.compare(m.version, inst.version) > 0
                EntryRow(m.name, listOfNotNull(m.type?.label), when { inst == null -> "安装"; newer -> "更新"; else -> null },
                    sub = m.description, padded = true) {
                    run { val r = store.install(store.bundledText(file), "内置"); "已安装「${r.name}」${r.version}" }
                }
            }
        }
        Group("采集站（苹果 CMS）", footer = "采集站使用内置的苹果 CMS v10 适配器（?ac=list / ?ac=detail），只播放直链 m3u8 / mp4 线路，仅 https；未成年相关内容一律过滤。") {
            cms.sites.filter { !it.adult }.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().clickable { removing = "cms:${s.key}" to s.name }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ProfileIcons.server, null, tint = c.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                        Text("${runCatching { java.net.URI(s.api).host }.getOrNull() ?: ""} · ${s.categories.size} 个分类", color = c.secondary, fontSize = 12.sp)
                    }
                    Switch(s.enabled, { cms.setEnabled(s.key, it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
                }
            }
            if (cms.sites.any { !it.adult }) RowDivider()
            LinkRow("添加采集站", ProfileIcons.server) { dialog = "cms" }
        }
        Note(message, error)
    }

    when (dialog) {
        "url", "sub" -> InputDialog(if (dialog == "url") "从网址添加" else "添加订阅地址",
            if (dialog == "url") "插件 / manifest / 订阅列表的 https 链接" else "订阅列表 https 链接", onDismiss = { dialog = null }) { text ->
            dialog = null; run { describe(store.interpret(text)) }
        }
        "live" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("选择直播列表") }, text = {
            androidx.compose.foundation.lazy.LazyColumn { items(liveLists.size) { i ->
                val live = liveLists[i]
                TextButton({ dialog = null; run { "已添加「${store.installLive(live).name}」" } }) { Text(live.name) }
            } }
        }, confirmButton = { TextButton({ dialog = null }) { Text("关闭") } })
        "paste" -> PasteDialog(onDismiss = { dialog = null }) { text -> dialog = null; run { describe(store.interpret(text, "paste")) } }
        "cms" -> CMSDialog(onDismiss = { dialog = null }) { name, api, isAdult ->
            dialog = null; run { val s = cms.add(name, api, isAdult); "已添加「${s.name}」· ${s.categories.size} 个分类" }
        }
    }
    removing?.let { (key, name) ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除「$name」？") },
            text = { Text(if (key.startsWith("sub:")) "只删除订阅，已安装的插件保留。" else "删除后可以重新添加。") },
            confirmButton = { TextButton({
                        val id = key.substringAfter(':')
                        when { key.startsWith("plugin:") -> store.remove(id); key.startsWith("cms:") -> cms.remove(id); else -> store.removeSubscription(id) }
                        removing = null; report("已删除「$name」", null)
                }) { Text("删除", color = c.accentWarm) } },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EntryRow(title: String, tags: List<String>, action: String?, sub: String? = null, padded: Boolean = false, onAction: () -> Unit) {
    val c = IdjThemeColors
    Row(Modifier.fillMaxWidth().padding(horizontal = if (padded) 14.dp else 0.dp, vertical = if (padded) 10.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                tags.forEach { Tag(it) }
            }
            sub?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (action != null) TextAction(action, onClick = onAction) else Text("已安装", color = c.secondary, fontSize = 12.sp)
    }
}

@Composable
private fun InputDialog(title: String, hint: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ onDone(text.trim()) }, enabled = text.trim().startsWith("https://")) { Text("添加") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("粘贴插件内容") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(".idjplugin、M3U 频道列表或 TVBox JSON 配置") }, maxLines = 8, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
                TextAction("从剪贴板粘贴") { clip.getText()?.text?.let { text = it } }
            }
        },
        confirmButton = { TextButton({ onDone(text) }, enabled = text.isNotBlank()) { Text("安装") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CMSDialog(onDismiss: () -> Unit, onDone: (String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("添加采集站") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(api, { api = it }, label = { Text("采集接口 https://…/api.php/provide/vod/") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ onDone(name.trim(), api.trim(), false) }, enabled = api.trim().startsWith("https://")) { Text("添加") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

private fun typeLabel(t: String) = when (t) { "video" -> "长视频"; "shortdrama" -> "短剧"; "comic" -> "漫画"; "live" -> "直播"; else -> t }
private fun originLabel(o: String) = when {
    o == "内置" -> "i短剧"; o == "paste" -> "粘贴"; o == "file" -> "文件"; o.startsWith("订阅：") -> "订阅"
    o.startsWith("https://") -> runCatching { java.net.URI(o).host }.getOrNull() ?: "网址"; else -> o
}
