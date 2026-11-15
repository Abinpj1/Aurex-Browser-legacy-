package com.aurex.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updates from the project's GitHub Releases (no account, no server of ours).
 *
 *  - A release is a tag "vX.Y.Z" (same number as the app's versionName) with the signed APK attached.
 *  - [check] lists the published releases and counts how many are newer than the installed version.
 *  - One release behind = a polite dialog (Later works); [FORCE_BEHIND] or more behind = a blocking dialog.
 *  - The APK is downloaded to the cache, checked against GitHub's SHA-256 when it publishes one, and handed to
 *    Android's installer (which only accepts an update signed with the same key as the installed app).
 */
object Updater {
    const val REPO = "aman-nitb-cse/Aurex-Browser"
    /** This many releases (or more) behind: the update can no longer be postponed. */
    const val FORCE_BEHIND = 2
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    private const val KEY_CACHE = "upd_cache"
    const val KEY_AUTO = "upd_auto"
    const val KEY_LAST = "upd_last"

    class Info(
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val size: Long,
        val sha256: String?,
        val pageUrl: String,
        /** Every published version (newest first): lets the app recount "how far behind" without the network. */
        val all: List<String>,
        val behind: Int
    ) {
        val force: Boolean get() = behind >= FORCE_BEHIND
    }

    sealed class Result {
        object UpToDate : Result()
        class Available(val info: Info) : Result()
        class Failed(val reason: String) : Result()
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ── Versions ─────────────────────────────────────────────────────────────

    fun parse(v: String): List<Int> =
        Regex("\\d+").findAll(v.trim().removePrefix("v").removePrefix("V").substringBefore('-')).map { it.value.toIntOrNull() ?: 0 }.toList()

    fun cmp(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun installedVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun behindOf(all: List<String>, installed: String): Int {
        val cur = parse(installed)
        return all.count { cmp(parse(it), cur) > 0 }
    }

    // ── Check ────────────────────────────────────────────────────────────────

    /** Background thread; [onDone] runs on the main thread. */
    fun check(ctx: Context, onDone: (Result) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val res = runCatching { fetch(app) }.getOrElse { Result.Failed(it.message ?: "No connection") }
            main.post { onDone(res) }
        }, "AurexUpdateCheck").start()
    }

    private fun fetch(app: Context): Result {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AurexBrowser")
            .build()
        val body = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return Result.Failed("GitHub answered ${r.code}")
            r.body?.string().orEmpty()
        }
        val arr = JSONArray(body)
        class Rel(val ver: String, val o: JSONObject)
        val rels = ArrayList<Rel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
            val tag = o.optString("tag_name")
            if (parse(tag).isEmpty()) continue
            rels.add(Rel(tag.trim().removePrefix("v").removePrefix("V"), o))
        }
        rels.sortWith { a, b -> cmp(parse(b.ver), parse(a.ver)) }      // newest first
        val installed = installedVersion(app)
        val all = rels.map { it.ver }
        val behind = behindOf(all, installed)
        val prefs = app.getSharedPreferences("liquid", Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        if (behind == 0 || rels.isEmpty()) {
            prefs.edit().remove(KEY_CACHE).apply()
            return Result.UpToDate
        }
        val latest = rels.first()
        val apk = latest.o.optJSONArray("assets")?.let { assets ->
            var pick: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", true)) {
                    if (pick == null || a.optString("name").contains("aurex", true)) pick = a
                }
            }
            pick
        }
        val notes = latest.o.optString("body").replace(Regex("[#*`>]"), "").trim().take(700)
        val info = Info(
            version = latest.ver,
            notes = notes,
            apkUrl = apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://") },
            size = apk?.optLong("size", -1L) ?: -1L,
            sha256 = apk?.optString("digest")?.removePrefix("sha256:")?.takeIf { it.length == 64 },
            pageUrl = latest.o.optString("html_url").ifBlank { "https://github.com/$REPO/releases" },
            all = all,
            behind = behind
        )
        save(prefs, info)
        return Result.Available(info)
    }

    private fun save(prefs: android.content.SharedPreferences, i: Info) {
        val o = JSONObject()
            .put("v", i.version).put("n", i.notes).put("u", i.apkUrl ?: "").put("s", i.size)
            .put("h", i.sha256 ?: "").put("p", i.pageUrl).put("a", JSONArray(i.all))
        prefs.edit().putString(KEY_CACHE, o.toString()).apply()
    }

    /** The last answer from GitHub, recounted against the version installed now (so going offline never lifts a forced update). */
    fun cached(ctx: Context): Info? = runCatching {
        val prefs = ctx.getSharedPreferences("liquid", Context.MODE_PRIVATE)
        val o = JSONObject(prefs.getString(KEY_CACHE, null) ?: return null)
        val a = o.optJSONArray("a") ?: return null
        val all = List(a.length()) { a.optString(it) }
        val behind = behindOf(all, installedVersion(ctx))
        if (behind == 0) return null
        Info(
            o.optString("v"), o.optString("n"), o.optString("u").ifBlank { null }, o.optLong("s", -1L),
            o.optString("h").ifBlank { null }, o.optString("p"), all, behind
        )
    }.getOrNull()

    // ── Download / install ───────────────────────────────────────────────────

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fileFor(ctx: Context, i: Info) = File(File(ctx.cacheDir, "update").apply { mkdirs() }, "Aurex-${i.version}.apk")

    /** Background thread; callbacks on the main thread. [cancel] stops it. */
    fun download(
        ctx: Context, info: Info, cancel: AtomicBoolean,
        onProgress: (Float) -> Unit, onDone: (File?, String?) -> Unit
    ) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            var err: String? = null
            var out: File? = null
            try {
                val url = info.apkUrl ?: throw IllegalStateException("This release has no APK")
                val dst = fileFor(app, info)
                // The same release downloaded before and still intact: nothing to fetch.
                if (dst.exists() && info.sha256 != null && sha256(dst).equals(info.sha256, true)) {
                    main.post { onProgress(1f) }
                    out = dst
                } else {
                    dst.parentFile?.listFiles()?.forEach { if (it != dst) it.delete() }       // older updates are not needed
                    val part = File(dst.path + ".part")
                    val req = Request.Builder().url(url).header("User-Agent", "AurexBrowser").build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("Download failed (${r.code})")
                        val total = r.body?.contentLength()?.takeIf { it > 0 } ?: info.size
                        var got = 0L
                        var lastPct = -1
                        r.body!!.byteStream().use { input ->
                            part.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (cancel.get()) throw IllegalStateException("Cancelled")
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    o.write(buf, 0, n)
                                    got += n
                                    if (total > 0) {
                                        val pct = (got * 100 / total).toInt()
                                        if (pct != lastPct) { lastPct = pct; main.post { onProgress(pct / 100f) } }
                                    }
                                }
                            }
                        }
                    }
                    if (info.sha256 != null && !sha256(part).equals(info.sha256, true)) {
                        part.delete()
                        throw IllegalStateException("The file did not match its checksum. Try again.")
                    }
                    dst.delete()
                    if (!part.renameTo(dst)) throw IllegalStateException("Couldn't save the update")
                    out = dst
                }
            } catch (t: Throwable) {
                err = if (t is java.io.IOException) "No connection. Check your network and try again." else (t.message ?: "Download failed")
            }
            main.post { onDone(out, err) }
        }, "AurexUpdateDownload").start()
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(ctx: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun openPage(ctx: Context, url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** What the update dialog and the About row show. One instance per [BrowserState]. */
class UpdateState(private val app: Context) {
    companion object { const val IDLE = 0; const val DOWNLOADING = 1; const val READY = 2 }

    private val prefs = app.getSharedPreferences("liquid", Context.MODE_PRIVATE)
    private val cancel = AtomicBoolean(false)

    var info by mutableStateOf(Updater.cached(app))
        private set
    var checking by mutableStateOf(false)
        private set
    /** "Later" was tapped (only possible while the update is optional). */
    var snoozed by mutableStateOf(false)
    var phase by mutableIntStateOf(IDLE)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var file by mutableStateOf<File?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    /** Result line of the last manual check ("Up to date" / "Couldn't check"). */
    var status by mutableStateOf<String?>(null)
    var auto by mutableStateOf(prefs.getBoolean(Updater.KEY_AUTO, true))
        private set

    val visible: Boolean get() = info != null && (info?.force == true || !snoozed)

    fun toggleAuto(on: Boolean) {
        auto = on
        prefs.edit().putBoolean(Updater.KEY_AUTO, on).apply()
    }

    /** Automatic checks are rare (every 3 h at most); a manual check always asks. */
    fun check(manual: Boolean = false, onDone: ((Updater.Result) -> Unit)? = null) {
        if (checking) return
        if (!manual) {
            if (!auto) return
            if (System.currentTimeMillis() - prefs.getLong(Updater.KEY_LAST, 0L) < 3L * 3600_000L) return
        }
        checking = true
        Updater.check(app) { res ->
            checking = false
            when (res) {
                is Updater.Result.Available -> {
                    if (info?.version != res.info.version) { snoozed = false; phase = IDLE; file = null }
                    info = res.info
                    status = "v${res.info.version} available"
                    if (manual) snoozed = false
                }
                Updater.Result.UpToDate -> { info = null; status = "Up to date" }
                is Updater.Result.Failed -> if (manual) status = "Couldn't check"
            }
            onDone?.invoke(res)
        }
    }

    fun download() {
        val i = info ?: return
        if (phase == DOWNLOADING) return
        message = null
        progress = 0f
        phase = DOWNLOADING
        cancel.set(false)
        Updater.download(app, i, cancel, { progress = it }) { f, err ->
            if (f != null) { file = f; phase = READY } else { phase = IDLE; message = err }
        }
    }

    /** Install button: asks for the "install unknown apps" switch once, then hands the APK to Android. */
    fun install() {
        val f = file ?: return
        if (!Updater.canInstall(app)) {
            message = "Allow Aurex to install updates in the screen that just opened, come back, and tap Install."
            Updater.openInstallPermission(app)
            return
        }
        message = null
        if (!Updater.install(app, f)) message = "Couldn't open the installer. Try again."
    }
}

@Composable
fun UpdatePrompt(u: UpdateState) {
    val info = u.info ?: return
    if (!u.visible) return
    val ctx = LocalContext.current
    val force = info.force
    // The file is ready: open the installer straight away (once per download).
    LaunchedEffect(u.phase) { if (u.phase == UpdateState.READY) u.install() }
    Dialog(
        onDismissRequest = { if (!force) u.snoozed = true },
        properties = DialogProperties(
            dismissOnBackPress = !force,
            dismissOnClickOutside = !force,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(Modifier.fillMaxSize().background(scrim(0.60f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .padding(24.dp)
                    .widthIn(max = 380.dp)
                    .liquidGlass(CardShape, 1.1f)
                    .padding(20.dp)
            ) {
                Text(
                    if (force) "Update required" else "Update available",
                    color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (force) "Aurex ${info.version} is out. Your version is ${info.behind} releases behind, so it needs an update before you continue."
                    else "Aurex ${info.version} is ready to install.",
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        info.notes, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState())
                    )
                }
                if (u.phase == UpdateState.DOWNLOADING) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { u.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = LocalGlassStyle.current.accent,
                        trackColor = Color(0x33808080)
                    )
                }
                u.message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Bad, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    if (!force) GlassPillButton("Later", { u.snoozed = true })
                    when {
                        info.apkUrl == null -> GlassPillButton("Open release page", { Updater.openPage(ctx, info.pageUrl) }, accent = true)
                        u.phase == UpdateState.DOWNLOADING ->
                            GlassPillButton("Downloading ${(u.progress * 100).toInt()}%", {}, accent = true, enabled = false)
                        u.phase == UpdateState.READY -> GlassPillButton("Install", { u.install() }, accent = true)
                        else -> GlassPillButton(
                            if (u.message != null) "Try again" else "Update", { u.download() }, accent = true
                        )
                    }
                }
            }
        }
    }
}
