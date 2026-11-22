package app.iduanju.data

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HongguoWebAccount(ctx: Context) {

    data class Profile(val nickname: String, val avatarUrl: String)

    companion object {
        const val WEB_ORIGIN = "https://hongguoduanju.com"
        const val PC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val PREF_NAME = "idj"
        private const val PREF_PROFILE_KEY = "stv.hg.web.profile"
        private const val TAG = "IDJ-HGWeb"
        private const val SESSION_TAG_TTL_MS = 15 * 60_000L
        private const val REPORT_THROTTLE_MS = 30_000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val TAG_RE_PLAIN = Regex(""""pcHistorySessionTag"\s*:\s*"([^"]+)"""")
        private val TAG_RE_ESCAPED = Regex("""\\"pcHistorySessionTag\\"\s*:\s*\\"([^\\"]+)\\"""")
    }

    private val sp = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionTags = ConcurrentHashMap<String, Pair<String, Long>>()
    private val lastReportedAt = ConcurrentHashMap<String, Long>()

    var profile by mutableStateOf<Profile?>(null)
        private set

    var syncing by mutableStateOf(false)
        private set

    var lastSyncNote by mutableStateOf<String?>(null)
        private set

    val isLoggedIn: Boolean get() = profile != null

    init {
        runCatching {
            val raw = sp.getString(PREF_PROFILE_KEY, null)
            if (!raw.isNullOrBlank()) {
                val j = JSONObject(raw)
                val nickname = j.optString("nickname").trim()
                val avatarUrl = j.optString("avatarUrl").trim()
                if (nickname.isNotEmpty()) {
                    profile = Profile(nickname, avatarUrl)
                }
            }
        }
    }

    fun cookies(): String? = runCatching {
        CookieManager.getInstance().getCookie(WEB_ORIGIN)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun saveProfile(next: Profile?) {
        if (next == null) {
            sp.edit().remove(PREF_PROFILE_KEY).apply()
        } else {
            val json = JSONObject()
                .put("nickname", next.nickname)
                .put("avatarUrl", next.avatarUrl)
                .toString()
            sp.edit().putString(PREF_PROFILE_KEY, json).apply()
        }
        withContext(Dispatchers.Main + NonCancellable) {
            profile = next
        }
    }

    suspend fun refreshProfile(): Profile? = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { syncing = true }
        try {
            runCatching { CookieManager.getInstance().flush() }
            val cookie = cookies()
            if (cookie == null) {
                saveProfile(null)
                return@withContext null
            }

            val req = Request.Builder()
                .url("$WEB_ORIGIN/api/pc/profile")
                .header("Cookie", cookie)
                .header("User-Agent", PC_USER_AGENT)
                .header("Referer", "$WEB_ORIGIN/")
                .header("Accept", "application/json")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 200) {
                    val p = JSONObject(body).optJSONObject("profile")
                    if (p == null) {
                        saveProfile(null)
                        return@withContext null
                    }
                    val nickname = p.optString("nickname").ifBlank { p.optString("name") }.trim()
                    val avatarUrl = p.optString("avatarUrl").ifBlank { p.optString("avatar_url") }.trim()
                    if (nickname.isEmpty()) {
                        saveProfile(null)
                        return@withContext null
                    }
                    val next = Profile(nickname, avatarUrl)
                    saveProfile(next)
                    return@withContext next
                }
            }
            profile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refreshProfile failed", e)
            profile
        } finally {
            withContext(Dispatchers.Main + NonCancellable) { syncing = false }
        }
    }

    fun logout() {
        runCatching {
            val cm = CookieManager.getInstance()
            val raw = cm.getCookie(WEB_ORIGIN)
            if (!raw.isNullOrBlank()) {
                raw.split(";").forEach { pair ->
                    val name = pair.substringBefore("=").trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie(WEB_ORIGIN, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                        cm.setCookie(WEB_ORIGIN, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.hongguoduanju.com; Path=/")
                    }
                }
            }
            cm.flush()
        }.onFailure {
            Log.w(TAG, "logout cookie clear failed", it)
        }
        sessionTags.clear()
        lastReportedAt.clear()
        profile = null
        sp.edit().remove(PREF_PROFILE_KEY).apply()
    }

    fun reportProgressAsync(
        seriesId: String,
        vid: String,
        episodeNumber: Int,
        positionMs: Long,
        accumulatedMs: Long,
        force: Boolean = false,
    ) {
        if (!isLoggedIn ||
            seriesId.isEmpty() || !seriesId.all { it.isDigit() } ||
            vid.isEmpty() || !vid.all { it.isDigit() } ||
            episodeNumber <= 0 || positionMs < 0
        ) {
            return
        }

        val key = "$seriesId:$vid"
        val now = System.currentTimeMillis()
        if (!force && now - (lastReportedAt[key] ?: 0L) < REPORT_THROTTLE_MS) {
            return
        }
        lastReportedAt[key] = now

        scope.launch {
            try {
                val cookie = cookies() ?: return@launch
                val sessionTag = fetchSessionTag(seriesId, vid, cookie)
                if (sessionTag == null) {
                    Log.w(TAG, "reportProgressAsync: missing pcHistorySessionTag for $seriesId/$vid")
                    withContext(Dispatchers.Main + NonCancellable) { lastSyncNote = "未获取到网页同步会话，请尝试重新登录" }
                    return@launch
                }

                val payload = JSONObject()
                    .put("book_id", seriesId)
                    .put("book_type", 2)
                    .put("is_delete", false)
                    .put("read_timestamp_ms", System.currentTimeMillis().toString())
                    .put("vid", vid)
                    .put("vid_index", episodeNumber.toString())
                    .put("current_play_position", positionMs.coerceAtLeast(0L).toString())
                    .put("player_accumulate_total_time", accumulatedMs.coerceAtLeast(1000L).toString())
                    .toString()

                val req = Request.Builder()
                    .url("$WEB_ORIGIN/api/pc/watch-history")
                    .header("Cookie", cookie)
                    .header("User-Agent", PC_USER_AGENT)
                    .header("Origin", WEB_ORIGIN)
                    .header("Referer", "$WEB_ORIGIN/player/$seriesId/$vid")
                    .header("X-PC-History-Session", sessionTag)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    val json = runCatching { JSONObject(respBody) }.getOrNull()
                    val ok = json?.optBoolean("isSuccess") == true
                    Log.i(TAG, "watch-history HTTP ${resp.code} ok=$ok body=${respBody.take(160)}")
                    if (ok) {
                        withContext(Dispatchers.Main + NonCancellable) {
                            lastSyncNote = "已同步第 $episodeNumber 集到红果"
                        }
                    } else {
                        if (resp.code == 401 || resp.code == 403 || !ok) {
                            sessionTags.remove(seriesId)
                        }
                        val reason = json?.optString("reason")?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                        withContext(Dispatchers.Main + NonCancellable) {
                            lastSyncNote = "同步未生效（$reason）"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "reportProgressAsync failed", e)
            }
        }
    }

    private fun fetchSessionTag(seriesId: String, vid: String, cookie: String): String? {
        val now = System.currentTimeMillis()
        val cached = sessionTags[seriesId]
        if (cached != null && now - cached.second < SESSION_TAG_TTL_MS) {
            return cached.first
        }

        for (pageUrl in listOf("$WEB_ORIGIN/player/$seriesId/$vid", "$WEB_ORIGIN/player/$seriesId")) {
            val req = Request.Builder()
                .url(pageUrl)
                .header("Cookie", cookie)
                .header("User-Agent", PC_USER_AGENT)
                .header("Referer", "$WEB_ORIGIN/")
                .get()
                .build()

            val tag = runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val html = resp.body?.string().orEmpty()
                    TAG_RE_PLAIN.find(html)?.groupValues?.getOrNull(1)
                        ?: TAG_RE_ESCAPED.find(html)?.groupValues?.getOrNull(1)
                }
            }.getOrNull()
            if (!tag.isNullOrBlank()) {
                sessionTags[seriesId] = tag to System.currentTimeMillis()
                return tag
            }
        }
        return null
    }
}
