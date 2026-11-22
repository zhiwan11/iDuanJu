package app.iduanju.tvbox

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.launch
import java.net.URI

/**
 * 01 · TVBox 接入与多配置仓管理页
 * 视觉 100% 对齐设计稿：开放画布 + 单条生效配置摘要 + 多仓胶囊切换 + 细线点播/直播列表。
 */
@Composable
fun TVBoxConfigScreen(
    onBack: () -> Unit,
    onOpenLive: (repoId: String, liveId: String) -> Unit,
) {
    val c = IdjThemeColors
    val store = IdjApp.instance.tvbox
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var urlText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showManualSiteDialog by remember { mutableStateOf(false) }
    var confirmDeleteRepo by remember { mutableStateOf<TVBoxRepo?>(null) }

    fun runTask(work: suspend () -> String) {
        if (busy) return
        busy = true
        statusError = false
        statusMsg = "正在处理…"
        scope.launch {
            try {
                statusMsg = work()
                statusError = false
            } catch (e: Exception) {
                statusMsg = e.message ?: "操作失败"
                statusError = true
            } finally {
                busy = false
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runTask {
                val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("无法读取文件")
                val displayName = ctx.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }?.substringBeforeLast('.')
                val repo = store.importFromText(raw, displayName) { statusMsg = it }
                "已导入「${repo.name}」：${repo.sites.size} 个点播站 · ${repo.lives.size} 个直播源"
            }
        }
    }

    val activeRepo = store.activeRepo
    val visibleSites = activeRepo?.sites?.filter { !it.adult }.orEmpty()
    val lives = activeRepo?.lives.orEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .background(
                Brush.verticalGradient(
                    listOf(c.accent.copy(alpha = 0.10f), Color.Transparent),
                    endY = 520f,
                ),
            ),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 42.dp),
        ) {
            // 顶部导航栏
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(c.text.copy(0.07f))
                            .border(0.7.dp, c.text.copy(0.11f), CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("TVBox 配置与片源", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (activeRepo != null && activeRepo.sites.isNotEmpty()) {
                        Text(
                            if (busy) "处理中…" else "全部测速",
                            color = c.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(enabled = !busy) {
                                runTask {
                                    store.pingAllSites(activeRepo.id) { statusMsg = it }
                                    "测速完成"
                                }
                            },
                        )
                    }
                }
            }

            // 顶部单行 HTTPS 地址导入栏
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(c.text.copy(0.065f))
                        .border(0.8.dp, c.text.copy(0.11f), RoundedCornerShape(22.dp))
                        .padding(start = 14.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (urlText.isEmpty()) {
                            Text("输入 HTTPS 配置地址（JSON / M3U）", color = c.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        BasicTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            singleLine = true,
                            textStyle = TextStyle(color = c.text, fontSize = 13.sp),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(c.text)
                            .clickable(enabled = !busy && urlText.isNotBlank()) {
                                val u = urlText.trim()
                                runTask {
                                    val repo = store.importFromUrl(u) { statusMsg = it }
                                    urlText = ""
                                    "已解析「${repo.name}」：${repo.sites.size} 点播站 · ${repo.lives.size} 直播源"
                                }
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("解析", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 3 个轻量导入方式胶囊
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MethodPill(Icons.Filled.ContentPaste, "粘贴 JSON", Modifier.weight(1f)) { showPasteDialog = true }
                    MethodPill(Icons.Filled.Description, "导入文件", Modifier.weight(1f)) { filePicker.launch(arrayOf("*/*")) }
                    MethodPill(Icons.Filled.Add, "手动加站", Modifier.weight(1f)) { showManualSiteDialog = true }
                }
            }

            // 状态反馈条
            if (!statusMsg.isNullOrBlank()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (statusError) c.accentWarm.copy(0.14f) else c.accent.copy(0.12f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = c.accent)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            statusMsg!!,
                            color = if (statusError) c.accentWarm else c.text,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // 多配置仓切换胶囊条
            if (store.repos.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("已保存配置（${store.repos.size} 仓可切换）", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (activeRepo != null) {
                            Row(
                                Modifier.clickable { confirmDeleteRepo = activeRepo },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.DeleteOutline, null, tint = c.secondary, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("删除当前仓", color = c.secondary, fontSize = 12.sp)
                            }
                        }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(store.repos, key = { it.id }) { repo ->
                            val on = repo.id == activeRepo?.id
                            Row(
                                Modifier
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (on) c.accent.copy(0.15f) else c.text.copy(0.065f))
                                    .border(
                                        0.8.dp,
                                        if (on) c.accent.copy(0.42f) else c.text.copy(0.08f),
                                        RoundedCornerShape(16.dp),
                                    )
                                    .clickable { store.selectRepo(repo.id) }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (on) Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF5FD08A)))
                                Text(
                                    repo.name,
                                    color = if (on) c.accent else c.secondary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 当前生效配置摘要条（单条细边，无重卡堆叠）
            if (activeRepo != null) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.text.copy(0.06f))
                            .border(0.8.dp, c.text.copy(0.10f), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(activeRepo.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(Color(0xFF1E9E57).copy(0.16f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text("当前生效", color = Color(0xFF5FD08A), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.weight(1f))
                            if (!activeRepo.url.isNullOrBlank()) {
                                Text(
                                    "重新拉取",
                                    color = c.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable(enabled = !busy) {
                                        runTask {
                                            val r = store.refreshRepo(activeRepo.id) { statusMsg = it }
                                            "已更新「${r.name}」：${r.sites.size} 点播站 · ${r.lives.size} 直播源"
                                        }
                                    },
                                )
                            }
                        }
                        Text(
                            activeRepo.url ?: "本地 / 剪贴板导入",
                            color = c.secondary,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        HorizontalDivider(Modifier.padding(vertical = 9.dp), thickness = 0.6.dp, color = c.text.copy(0.08f))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${visibleSites.size} 点播站", color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("·", color = c.secondary, fontSize = 12.sp)
                            Text("${lives.size} 直播源", color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (activeRepo.skippedCount > 0) {
                                Text("·", color = c.secondary, fontSize = 12.sp)
                                Text("已跳过 ${activeRepo.skippedCount} 个 JAR/脚本源", color = c.secondary, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            } else {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有 TVBox 配置", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在上方粘贴 HTTPS 配置地址、JSON 文本或导入本地文件，支持同时保存多个配置仓自由切换。",
                            color = c.secondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            // 点播站点列表（开放式细线列表）
            if (visibleSites.isNotEmpty() && activeRepo != null) {
                item {
                    Text(
                        "点播站点（苹果 CMS API · ${visibleSites.size}）",
                        color = c.secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(visibleSites, key = { it.key }) { site ->
                    val host = remember(site.api) { runCatching { URI(site.api).host }.getOrNull() ?: "" }
                    val slow = site.latencyMs >= 400
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (!site.enabled) c.secondary.copy(0.4f) else if (slow) c.accentWarm else Color(0xFF5FD08A)),
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(site.name, color = c.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    MiniPillTag(if (slow) "较慢" else "m3u8", accent = !slow)
                                }
                                val pingLabel = when {
                                    site.latencyMs in 1..9998 -> " · ${site.latencyMs}ms"
                                    site.latencyMs >= 9999 -> " · 超时"
                                    else -> ""
                                }
                                Text(
                                    "$host · ${site.categories.size} 分类$pingLabel",
                                    color = if (slow) c.accentWarm else c.secondary,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Switch(
                                checked = site.enabled,
                                onCheckedChange = { store.setSiteEnabled(activeRepo.id, site.key, it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                            )
                        }
                        HorizontalDivider(thickness = 0.6.dp, color = c.text.copy(0.07f))
                    }
                }
            }

            // 直播列表（开放式细线列表）
            if (lives.isNotEmpty() && activeRepo != null) {
                item {
                    Text(
                        "直播列表（HTTPS M3U / TXT · ${lives.size}）",
                        color = c.secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(lives, key = { it.id }) { live ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF6B6B)))
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(live.name, color = c.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = if (live.channels.isNotEmpty()) "${live.channels.size} 个频道 · HTTPS 直播源" else "点击观看时自动拉取频道列表"
                                Text(sub, color = c.secondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(
                                Modifier
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(c.accent.copy(0.15f))
                                    .clickable { onOpenLive(activeRepo.id, live.id) }
                                    .padding(horizontal = 11.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("观看", color = c.accent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = live.enabled,
                                onCheckedChange = { store.setLiveEnabled(activeRepo.id, live.id, it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                            )
                        }
                        HorizontalDivider(thickness = 0.6.dp, color = c.text.copy(0.07f))
                    }
                }
            }

            // 诚实能力说明
            item {
                Text(
                    "仅解析 HTTPS 苹果 CMS 接口（type 0 / 1 / 4）与 M3U/TXT 直播源；JAR、CatVod 与 Python spider 不会在本地执行。",
                    color = c.secondary.copy(0.72f),
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }

    if (showPasteDialog) {
        PasteTVBoxDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { text ->
                showPasteDialog = false
                runTask {
                    val repo = store.importFromText(text) { statusMsg = it }
                    "已导入「${repo.name}」：${repo.sites.size} 点播站 · ${repo.lives.size} 直播源"
                }
            },
        )
    }

    if (showManualSiteDialog) {
        ManualCMSSiteDialog(
            onDismiss = { showManualSiteDialog = false },
            onConfirm = { name, api, adult ->
                showManualSiteDialog = false
                runTask {
                    val s = store.addManualSite(name, api, adult)
                    "已添加「${s.name}」· ${s.categories.size} 分类 · ${s.latencyMs}ms"
                }
            },
        )
    }

    confirmDeleteRepo?.let { repo ->
        AlertDialog(
            onDismissRequest = { confirmDeleteRepo = null },
            title = { Text("删除配置「${repo.name}」？") },
            text = { Text("删除后该仓下的站点和直播源将从 TVBox 中移除，可随时重新导入。") },
            confirmButton = {
                TextButton(onClick = {
                    store.removeRepo(repo.id)
                    confirmDeleteRepo = null
                    statusMsg = "已删除「${repo.name}」"
                }) { Text("删除", color = c.accentWarm) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteRepo = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MethodPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = IdjThemeColors
    Row(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(c.text.copy(0.065f))
            .border(0.7.dp, c.text.copy(0.08f), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MiniPillTag(text: String, accent: Boolean) {
    val c = IdjThemeColors
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (accent) c.accent.copy(0.15f) else c.text.copy(0.10f))
            .padding(horizontal = 6.dp, vertical = 1.5.dp),
    ) {
        Text(text, color = if (accent) c.accent else c.secondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PasteTVBoxDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴 TVBox JSON 或 M3U/TXT") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("配置内容") },
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                )
                TextButton(onClick = { clip.getText()?.text?.let { text = it } }) { Text("从剪贴板粘贴") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("解析导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ManualCMSSiteDialog(onDismiss: () -> Unit, onConfirm: (String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加苹果 CMS 站点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("站点名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = api, onValueChange = { api = it }, label = { Text("https://…/api.php/provide/vod/") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim(), api.trim(), false) }, enabled = api.trim().startsWith("https://")) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
