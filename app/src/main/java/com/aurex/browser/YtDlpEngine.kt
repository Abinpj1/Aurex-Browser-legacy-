package com.aurex.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI

/** What the user can ask the universal downloader for. */
enum class YtQuality(val label: String, val audio: Boolean, val ext: String) {
    BEST("Max", false, "mp4"),
    P1080("1080p", false, "mp4"),
    P720("720p", false, "mp4"),
    P480("480p", false, "mp4"),
    MP3("MP3", true, "mp3"),
    M4A("M4A", true, "m4a")
}

/**
 * Internal yt-dlp engine (bundled with youtubedl-android: Python + yt-dlp + ffmpeg).
 *
 * Nothing here is visible to the user as "yt-dlp": the browser simply gets a universal
 * video/audio downloader that understands YouTube, Instagram, X, TikTok, Facebook, Vimeo,
 * Reddit and 1000+ other sites. [DownloadManager] drives it like any other download.
 *
 * The library class is reached through a small reflection adapter because its entry points
 * changed shape between releases (`getInstance()` vs. Kotlin `object`, callback type, extra
 * execute() parameters). The adapter works with all of them, so a library bump never breaks
 * the build.
 */
object YtDlpEngine {
    private const val YT_CLASS = "com.yausername.youtubedl_android.YoutubeDL"
    private const val FF_CLASS = "com.yausername.ffmpeg.FFmpeg"
    private const val PREFS = "liquid"
    private const val KEY_LAST_UPDATE = "ytdlpLastUpdate"
    private const val UPDATE_EVERY_MS = 6L * 60L * 60L * 1000L
    private const val KEY_STABLE_ONLY = "ytdlpStableOnly"
    private const val KEY_AUTO_UPDATE = "ytdlpAuto"
    /** Release tag of a yt-dlp that [updateDirect] installed (the library's own record is then stale). */
    private const val KEY_DIRECT_TAG = "ytdlpDirectTag"
    private const val DIRECT_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    private const val BUSY = "busy"

    @Volatile private var ready = false
    private val lock = Any()
    @Volatile private var updatedThisSession = false
    @Volatile private var updateTries = 0
    private val warmDone = java.util.concurrent.CountDownLatch(1)
    /** yt-dlp processes currently running; the engine is never swapped under one of them. */
    private val running = java.util.concurrent.atomic.AtomicInteger(0)
    /** True while the engine file is being replaced: new yt-dlp processes wait until it is done. */
    @Volatile private var swapping = false
    /** A download hit an outdated-engine error while others were running: update as soon as they end. */
    @Volatile private var updatePending = false
    @Volatile private var appCtx: Context? = null

    // ── State shown (read-only) in Settings ──────────────────────────────────
    var engineVersion by mutableStateOf("")

    /** True while a manual or automatic update is running (drives the "Update now" row). */
    var updating by mutableStateOf(false)
        private set

    /** Result of the last update attempt as one short line: "", "Checking…", "Up to date", ... */
    var updateStatus by mutableStateOf("")
        private set

    /** Time (ms) of the last successful update check, 0 = never. */
    var lastChecked by mutableStateOf(0L)
        private set

    /** Why the engine cannot start on this phone ("" = no known problem). Shown in Settings. */
    var engineProblem by mutableStateOf("")
        private set

    /** True while the engine files are being unpacked again. */
    var repairing by mutableStateOf(false)
        private set

    // ── Reflection adapter ───────────────────────────────────────────────────
    private fun singleton(cls: Class<*>): Any {
        runCatching { cls.getMethod("getInstance").invoke(null) }.getOrNull()?.let { return it }
        runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()?.let { return it }
        // shrunk / renamed builds: any static field or static no-arg method of the class' own type
        cls.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == cls }?.let { f ->
            f.isAccessible = true
            f.get(null)?.let { return it }
        }
        cls.declaredMethods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == cls
        }?.let { m ->
            m.isAccessible = true
            m.invoke(null)?.let { return it }
        }
        return cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }

    private inline fun <T> unwrap(block: () -> T): T =
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }

    private fun yt(): Any = singleton(Class.forName(YT_CLASS))

    /** Unpacks Python / yt-dlp / ffmpeg on first use. Safe to call many times and from any thread. */
    fun ensureReady(ctx: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            try {
                val app = ctx.applicationContext
                appCtx = app
                val y = yt()
                unwrap { y.javaClass.getMethod("init", Context::class.java).invoke(y, app) }
                runCatching {
                    val f = singleton(Class.forName(FF_CLASS))
                    unwrap { f.javaClass.getMethod("init", Context::class.java).invoke(f, app) }
                }
                ready = true
                AppLog.info("Engine", "ready")
            } catch (e: Throwable) {
                AppLog.error("Engine", "init failed", e)
                throw IllegalStateException(
                    "The downloader engine could not start on this device (${e.message ?: e.javaClass.simpleName})"
                )
            }
        }
    }

    /** Reads the installed yt-dlp version for the Settings card (also moves old installs to Stable). */
    fun loadState(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // v19/v20 could track the nightly channel. From now on only Stable is used: force one check
        // so a nightly build that is still installed gets replaced by the stable release.
        if (!prefs.getBoolean(KEY_STABLE_ONLY, false)) {
            prefs.edit().putBoolean(KEY_STABLE_ONLY, true).putLong(KEY_LAST_UPDATE, 0L).apply()
        }
        engineVersion = installedVersion(ctx)
        lastChecked = prefs.getLong(KEY_LAST_UPDATE, 0L)
    }

    /** Automatic background updates (every 6 h). On by default; "Update now" always works. */
    fun autoUpdate(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdate(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_UPDATE, on).apply()
    }

    /**
     * Manual update ("Update now" in Settings). Runs on its own thread, refuses to swap the engine
     * while a download is using it, and reports "updated", "current", "busy" or "failed" on the
     * main thread through [onDone].
     */
    fun updateNow(ctx: Context, onDone: (String) -> Unit) {
        if (updating) return
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (running.get() > 0) {
            updateStatus = "Busy · finish downloads first"
            main.post { onDone("busy") }
            return
        }
        updating = true
        updateStatus = "Checking…"
        Thread {
            val res = try { swapEngine(app) } catch (_: Throwable) { null }
            if (res != null && res != BUSY) {
                val now = System.currentTimeMillis()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_UPDATE, now).apply()
                lastChecked = now
            }
            engineVersion = installedVersion(app)
            updateStatus = when (res) {
                "updated" -> "Updated"
                "current" -> "Up to date"
                BUSY -> "Busy · finish downloads first"
                else -> "Couldn't update · check your connection"
            }
            updating = false
            main.post { onDone(res ?: "failed") }
        }.apply { name = "aurex-ytdlp-update"; isDaemon = true }.start()
    }

    /**
     * Called when "Video download" is switched off: nothing may stay in memory for a feature that is
     * not in use. Running processes are stopped by the caller (DownloadManager.pauseVideo); this
     * drops the looked-up stream links.
     */
    fun release() {
        resolveCache.clear()
        updateStatus = ""
    }

    /** Tag of the yt-dlp build that was downloaded last (null while the bundled one is in use). */
    fun installedVersion(ctx: Context): String {
        // a yt-dlp installed by the fallback updater is newer than what the library has on record
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DIRECT_TAG, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            val y = yt()
            val m = y.javaClass.getMethod("version", Context::class.java)
            unwrap { m.invoke(y, ctx.applicationContext) } as? String
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "bundled build"
    }

    /**
     * Warm-up + yt-dlp update. Sites change constantly and the copy that ships inside the app is
     * months old, so the newest STABLE yt-dlp is pulled on first start and then every 6 hours.
     * Downloads wait for this check ([awaitWarm]) so they never run on a stale extractor.
     */
    fun warmUpAsync(ctx: Context) {
        Thread {
            try {
                ensureReady(ctx)
                loadState(ctx)
                checkForUpdate(ctx)
            } catch (_: Throwable) {
            } finally {
                warmDone.countDown()
            }
        }.apply { name = "aurex-ytdlp-warmup"; isDaemon = true }.start()
    }

    /** Pulls the newest STABLE yt-dlp when the last check is older than [UPDATE_EVERY_MS]. */
    private fun checkForUpdate(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_UPDATE, true)) return     // manual updates only
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - last <= UPDATE_EVERY_MS) return
        if (running.get() > 0 || updating) {
            // e.g. a page lookup is running: try again the moment the engine is idle instead of
            // staying on the old build until the next app start
            updatePending = true
            return
        }
        val r = swapEngine(ctx)
        if (r != null && r != BUSY) {
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
            lastChecked = now
        }
        engineVersion = installedVersion(ctx)
    }

    /**
     * Replaces the engine, but only while no yt-dlp process is alive (swapping the file under a running
     * Python process makes it crash half way through a download). New processes wait in
     * [waitWhileSwapping] until this is over. Returns "updated", "current", [BUSY] or null (failed).
     */
    private fun swapEngine(ctx: Context): String? {
        swapping = true
        try {
            if (running.get() > 0) return BUSY
            return updateInternal(ctx)
        } finally {
            swapping = false
        }
    }

    /** Called by every yt-dlp run right after it registered itself in [running]. */
    private fun waitWhileSwapping() {
        var n = 0
        while (swapping && n++ < 300) Thread.sleep(100)
    }

    /** Runs the update that [updateOnce] had to postpone, as soon as the last yt-dlp process ends. */
    private fun runPendingUpdate() {
        if (!updatePending || updating || updatedThisSession || updateTries >= 2) return
        val c = appCtx ?: return
        updatePending = false
        Thread { runCatching { updateOnce(c) } }
            .apply { name = "aurex-ytdlp-pending"; isDaemon = true }.start()
    }

    /** Blocks (bounded) until the start-up update check is over. */
    fun awaitWarm(timeoutMs: Long = 25_000L) {
        runCatching { warmDone.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /**
     * Pulls the newest STABLE yt-dlp. Returns "updated", "current", or null when it failed.
     * The library's updater goes first; when it fails (GitHub's API refuses shared mobile IPs after a
     * few calls, so it often fails with no visible reason) the release file is fetched directly.
     */
    private fun updateInternal(ctx: Context): String? {
        val r = updateInternalImpl(ctx)
        AppLog.info("Engine", "update result=$r")
        return r
    }

    private fun updateInternalImpl(ctx: Context): String? {
        val viaLibrary = updateViaLibrary(ctx)
        if (viaLibrary != null) {
            // the library's own copy is the current one again: forget a hand-installed tag
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_DIRECT_TAG).apply()
            return viaLibrary
        }
        return updateDirect(ctx)
    }

    private fun openGet(url: String, follow: Boolean): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = follow
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        }

    /**
     * Fallback updater: downloads the latest yt-dlp release file from GitHub's release CDN (no API call,
     * so no rate limit), checks it and swaps it into the library's engine folder. "updated", "current"
     * or null. The old file stays untouched unless the new one passed every check.
     */
    private fun updateDirect(ctx: Context): String? {
        val app = ctx.applicationContext
        val target = File(app.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        val dir = target.parentFile ?: return null
        if (!target.isFile) return null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tmp = File(dir, "yt-dlp.download")
        var first: java.net.HttpURLConnection? = null
        var second: java.net.HttpURLConnection? = null
        return try {
            // First hop only: its Location is .../releases/download/<tag>/yt-dlp, which names the release.
            val c1 = openGet(DIRECT_URL, false)
            first = c1
            val location = c1.getHeaderField("Location") ?: return null
            val tag = Regex("""/download/([^/]+)/yt-dlp""").find(location)?.groupValues?.get(1)
            if (tag != null && tag == prefs.getString(KEY_DIRECT_TAG, null)) return "current"
            val c2 = openGet(location, true)
            second = c2
            if (c2.responseCode != 200) return null
            c2.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            val head = ByteArray(2)
            val got = tmp.inputStream().use { it.read(head) }
            // the release file is a zip app: it starts with a "#!" line or straight with "PK"
            val looksRight = got == 2 &&
                ((head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) ||
                    (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()))
            if (!looksRight || tmp.length() < 1_000_000L) return null
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
            if (tag != null) prefs.edit().putString(KEY_DIRECT_TAG, tag).apply()
            "updated"
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { first?.disconnect() }
            runCatching { second?.disconnect() }
            runCatching { tmp.delete() }
        }
    }

    private fun updateViaLibrary(ctx: Context): String? {
        return try {
            ensureReady(ctx)
            val y = yt()
            // Prefer the overload that takes the channel; fall back to the shorter ones (the library
            // updates the stable channel by default).
            val candidates = y.javaClass.methods.filter { it.name == "updateYoutubeDL" }
                .sortedByDescending { it.parameterTypes.size }
            if (candidates.isEmpty()) error("update is not available")
            var last: Throwable? = null
            for (m in candidates) {
                val args = Array<Any?>(m.parameterTypes.size) { null }
                var usable = true
                m.parameterTypes.forEachIndexed { i, t ->
                    if (Context::class.java.isAssignableFrom(t)) {
                        args[i] = ctx.applicationContext
                    } else {
                        args[i] = stableChannel(t)
                        if (args[i] == null) usable = false
                    }
                }
                if (!usable) continue
                try {
                    val res = unwrap { m.invoke(y, *args) }
                    return if (res?.toString() == "ALREADY_UP_TO_DATE") "current" else "updated"
                } catch (e: Throwable) {
                    last = e
                }
            }
            throw last ?: IllegalStateException("update failed")
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * A forced update, used when an extractor looks outdated (stable releases only). Returns true
     * only when a NEWER engine was installed, i.e. when repeating the failed attempt makes sense.
     * Called after the failed yt-dlp run has ended, so any process still counted belongs to another
     * download: the update is then postponed until it ends ([runPendingUpdate]).
     */
    fun updateOnce(ctx: Context): Boolean {
        if (updatedThisSession || updateTries >= 2) return false
        if (!autoUpdate(ctx)) return false     // the user chose manual updates only
        if (updating) return false
        updateTries++
        val r = swapEngine(ctx)
        if (r == BUSY) {
            updateTries--
            updatePending = true
            return false
        }
        if (r != null) {
            updatedThisSession = true
            val now = System.currentTimeMillis()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_UPDATE, now).apply()
            lastChecked = now
            engineVersion = installedVersion(ctx)
        }
        return r == "updated"
    }

    /** The library's "stable" channel constant (its spelling differs between releases). */
    private fun stableChannel(type: Class<*>): Any? {
        runCatching { return type.getField("_STABLE").get(null) }
        runCatching { return singleton(Class.forName(type.name + "\$STABLE")) }
        runCatching { return type.getField("STABLE").get(null) }
        return null
    }

    /**
     * One plain `--version` run WITHOUT automatic repair, for the bug log: returns what the engine printed,
     * or the complete error text (so a linker message is never cut short).
     */
    fun probe(ctx: Context): String = try {
        ensureReady(ctx)
        val req = YoutubeDLRequest("https://example.com/")
        req.addOption("--version")
        val r = executeRawOnce(req, "aurex-probe-" + System.nanoTime()) { _, _, _ -> }
        val out = runCatching { r?.javaClass?.getMethod("getOut")?.invoke(r) as? String }.getOrNull().orEmpty()
        "OK · yt-dlp " + out.trim().take(60)
    } catch (e: Throwable) {
        "FAILED · " + e.javaClass.simpleName + ": " + (e.message ?: e.cause?.message ?: "").take(2500)
    }

    // ── Self-repair ──────────────────────────────────────────────────────────
    // "CANNOT LINK EXECUTABLE ..." means Android's linker could not start the bundled Python / ffmpeg
    // (typically: a library of the unpacked engine is missing or unreadable on that phone). The unpacked
    // copy lives in the app's private folder and is only ever unpacked once, so a damaged copy stays
    // damaged forever. Wiping it and unpacking again fixes that; the failed call is then repeated once.
    private val repairLock = Any()
    @Volatile private var repairGen = 0
    @Volatile private var autoRepairs = 0

    fun isLinkError(message: String?): Boolean =
        message?.contains("cannot link executable", ignoreCase = true) == true

    /** Sets the library's own "already initialised" flag back so its init() unpacks again. */
    private fun resetLibraryInit(className: String): Boolean = try {
        val c = Class.forName(className)
        val f = c.declaredFields.firstOrNull { it.name == "initialized" }
        if (f == null) false else {
            f.isAccessible = true
            val inst: Any? = if (java.lang.reflect.Modifier.isStatic(f.modifiers)) null else singleton(c)
            f.setBoolean(inst, false)
            true
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Deletes the unpacked Python / ffmpeg packages and unpacks them again.
     * [seenGen] = the repair generation the failing call started under: when another thread has already
     * repaired since, nothing is repeated and the caller just retries. [force] = manual repair from Settings.
     * Returns true when the engine was (or just got) rebuilt, so the failed call can be repeated.
     */
    private fun repairEngine(seenGen: Int, force: Boolean = false): Boolean {
        val app = appCtx ?: return false
        return synchronized(repairLock) {
            if (!force && repairGen != seenGen) return@synchronized true
            repairing = true
            val ok = try {
                rebuildEngine(app)
            } catch (e: Throwable) {
                engineProblem = friendlyError(e.message)
                false
            } finally {
                swapping = false
                repairing = false
            }
            if (ok) repairGen++
            AppLog.info("Engine", "repair finished ok=$ok")
            ok
        }
    }

    private fun rebuildEngine(app: Context): Boolean {
        // downloads that are still running keep the files in use: give them a moment to end
        var n = 0
        while (running.get() > 0 && n++ < 200) Thread.sleep(100)
        swapping = true
        if (running.get() > 0) return false
        synchronized(lock) {
            val root = File(app.noBackupFilesDir, "youtubedl-android/packages")
            root.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            // the library's own "what I unpacked" record, and ours about the yt-dlp file
            runCatching {
                app.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit().clear().apply()
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_DIRECT_TAG).putLong(KEY_LAST_UPDATE, 0L).apply()
            val resetOk = resetLibraryInit(YT_CLASS)
            resetLibraryInit(FF_CLASS)
            ready = false
            if (!resetOk) {
                engineProblem = "Engine files were reset. Close Aurex completely, open it again and retry."
                return false
            }
            ensureReady(app)
        }
        return true
    }

    /** "Repair engine" in Settings. [onDone] gets true / false on the main thread. */
    fun repairNow(ctx: Context, onDone: (Boolean) -> Unit) {
        if (repairing) return
        appCtx = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val ok = try { repairEngine(repairGen, force = true) } catch (_: Throwable) { false }
            if (ok) engineProblem = ""
            main.post { onDone(ok) }
        }.apply { name = "aurex-ytdlp-repair"; isDaemon = true }.start()
    }

    /** Runs [block]; when the engine cannot be linked it is repaired and [block] is repeated once. */
    private inline fun <T> withRepair(block: () -> T): T {
        val gen = repairGen
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!isLinkError(e.message) && !isLinkError(e.cause?.message)) throw e
            if (autoRepairs >= 3) throw e
            autoRepairs++
            AppLog.warn("Engine", "link error, repairing (try $autoRepairs): " + (e.message ?: e.cause?.message ?: "").take(800))
            if (!repairEngine(gen)) {
                AppLog.warn("Engine", "repair did not finish")
                engineProblem = friendlyError(e.message ?: e.cause?.message)
                throw e
            }
            try {
                val r = block()
                engineProblem = ""
                return r
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Throwable) {
                if (isLinkError(e2.message) || isLinkError(e2.cause?.message)) {
                    engineProblem = friendlyError(e2.message ?: e2.cause?.message)
                }
                throw e2
            }
        }
    }

    fun cancel(id: Int) {
        runCatching {
            val y = yt()
            y.javaClass.getMethod("destroyProcessById", String::class.java).invoke(y, processId(id))
        }
    }

    fun processId(id: Int) = "aurex-$id"

    /**
     * Runs one download and blocks until it finishes. [onLine] gets (percent, eta seconds, raw line)
     * for every line yt-dlp prints. Throws [CancellationException] when [cancel] killed the process.
     */
    fun execute(req: YoutubeDLRequest, processId: String, onLine: (Float, Long, String) -> Unit) {
        withRepair { executeOnce(req, processId, onLine) }
    }

    private fun executeOnce(req: YoutubeDLRequest, processId: String, onLine: (Float, Long, String) -> Unit) {
        val y = yt()
        val m = y.javaClass.methods
            .filter { it.name == "execute" && it.parameterTypes.firstOrNull() == YoutubeDLRequest::class.java }
            .maxByOrNull { it.parameterTypes.size }
            ?: throw IllegalStateException("Downloader engine has no execute()")
        val args = Array<Any?>(m.parameterTypes.size) { null }
        args[0] = req
        m.parameterTypes.forEachIndexed { i, t ->
            if (i == 0) return@forEachIndexed
            args[i] = when {
                t == String::class.java -> processId
                t == java.lang.Boolean.TYPE || t == java.lang.Boolean::class.java -> false
                t.name == "kotlin.jvm.functions.Function3" ->
                    object : Function3<Float, Long, String, Unit> {
                        override fun invoke(p1: Float, p2: Long, p3: String) {
                            onLine(p1, p2, p3)
                        }
                    }
                t.isInterface -> Proxy.newProxyInstance(t.classLoader, arrayOf(t)) { _, method, a ->
                    if (method.name == "onProgressUpdate") {
                        val p = (a?.getOrNull(0) as? Number)?.toFloat() ?: 0f
                        val e = (a?.getOrNull(1) as? Number)?.toLong() ?: -1L
                        val line = (a?.getOrNull(2) as? String).orEmpty()
                        onLine(p, e, line)
                    }
                    null
                }
                else -> null
            }
        }
        running.incrementAndGet()
        try {
            waitWhileSwapping()
            unwrap { m.invoke(y, *args) }
        } catch (e: Throwable) {
            if (e.javaClass.simpleName.contains("Cancel", ignoreCase = true) ||
                e is InterruptedException
            ) {
                throw CancellationException("download cancelled")
            }
            AppLog.warn("yt-dlp", "run failed [$processId]: " + (e.message ?: e.javaClass.simpleName).take(700))
            throw e
        } finally {
            if (running.decrementAndGet() == 0) runPendingUpdate()
        }
    }

    fun executeRaw(req: YoutubeDLRequest, processId: String, onLine: (Float, Long, String) -> Unit): Any? =
        withRepair { executeRawOnce(req, processId, onLine) }

    private fun executeRawOnce(req: YoutubeDLRequest, processId: String, onLine: (Float, Long, String) -> Unit): Any? {
        val y = yt()
        val m = y.javaClass.methods
            .filter { it.name == "execute" && it.parameterTypes.firstOrNull() == YoutubeDLRequest::class.java }
            .maxByOrNull { it.parameterTypes.size }
            ?: throw IllegalStateException("Downloader engine has no execute()")
        val args = Array<Any?>(m.parameterTypes.size) { null }
        args[0] = req
        m.parameterTypes.forEachIndexed { i, t ->
            if (i == 0) return@forEachIndexed
            args[i] = when {
                t == String::class.java -> processId
                t == java.lang.Boolean.TYPE || t == java.lang.Boolean::class.java -> false
                t.name == "kotlin.jvm.functions.Function3" ->
                    object : Function3<Float, Long, String, Unit> {
                        override fun invoke(p1: Float, p2: Long, p3: String) {
                            onLine(p1, p2, p3)
                        }
                    }
                t.isInterface -> Proxy.newProxyInstance(t.classLoader, arrayOf(t)) { _, method, a ->
                    if (method.name == "onProgressUpdate") {
                        val p = (a?.getOrNull(0) as? Number)?.toFloat() ?: 0f
                        val e = (a?.getOrNull(1) as? Number)?.toLong() ?: -1L
                        val line = (a?.getOrNull(2) as? String).orEmpty()
                        onLine(p, e, line)
                    }
                    null
                }
                else -> null
            }
        }
        running.incrementAndGet()
        try {
            waitWhileSwapping()
            return unwrap { m.invoke(y, *args) }
        } catch (e: Throwable) {
            if (e.javaClass.simpleName.contains("Cancel", ignoreCase = true) ||
                e is InterruptedException
            ) {
                throw CancellationException("download cancelled")
            }
            AppLog.warn("yt-dlp", "run failed [$processId]: " + (e.message ?: e.javaClass.simpleName).take(700))
            throw e
        } finally {
            if (running.decrementAndGet() == 0) runPendingUpdate()
        }
    }

    /** One selectable quality: a muxed stream, or a video-only stream paired with the best audio. */
    class StreamChoice(
        val label: String,
        val height: Int,
        val video: String,
        val audio: String?,
        val vHeaders: Map<String, String> = emptyMap(),
        val aHeaders: Map<String, String> = emptyMap(),
        /** True when video (+ audio) can be joined into a plain MP4 without the full engine. */
        val mp4Safe: Boolean = false,
        val sizeBytes: Long = 0L,
        /** An HLS rendition (muxed, plays in the built-in player; never used by the fast MP4 downloader). */
        val hls: Boolean = false,
        /** VP9 + Opus (SDR): joined natively into a .webm, so 1440p / 4K / 8K also use the fast parallel path. */
        val webmSafe: Boolean = false,
        val vcodec: String = "",
        /** Real picture size / frame rate (0 = unknown): needed to judge ultra-wide / portrait 4K and 4K60 decoding. */
        val width: Int = 0,
        val fps: Int = 0
    )

    /** [heights] = every video height the site offers (any protocol), for the quality picker. */
    class ResolvedMedia(
        val title: String,
        val choices: List<StreamChoice>,
        val userAgent: String?,
        val heights: List<Int> = emptyList(),
        val sizeByHeight: Map<Int, Long> = emptyMap(),
        val durationSec: Double = 0.0,
        val audioBytes: Long = 0L,
        /** Heights (>= 1080) that also exist as an HDR stream. */
        val hdrHeights: List<Int> = emptyList()
    )

    private class Cached(val at: Long, val media: ResolvedMedia)
    private val resolveCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private val resolveInflight =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<ResolvedMedia>>()
    private const val RESOLVE_TTL_MS = 20L * 60L * 1000L

    /**
     * [resolveStreams] with a cache and de-duplication: the player, the quality picker and the
     * downloader all ask for the same page, so only the first one pays for the lookup. Signed
     * links stay valid for hours; 20 minutes is a safe reuse window.
     */
    fun resolveCached(ctx: Context, pageUrl: String, cookieHeader: String?): ResolvedMedia {
        val now = System.currentTimeMillis()
        resolveCache[pageUrl]?.let { if (now - it.at < RESOLVE_TTL_MS) return it.media }
        val task = java.util.concurrent.FutureTask<ResolvedMedia> { resolveStreams(ctx, pageUrl, cookieHeader) }
        val existing = resolveInflight.putIfAbsent(pageUrl, task)
        val use = existing ?: task
        if (existing == null) use.run()
        try {
            val media = use.get()
            if (resolveCache.size > 12) resolveCache.clear()
            resolveCache[pageUrl] = Cached(System.currentTimeMillis(), media)
            return media
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            if (existing == null) resolveInflight.remove(pageUrl)
        }
    }

    /** Looks the page up in the background so the next tap on Play / Download is instant. */
    fun prefetch(ctx: Context, pageUrl: String, cookieHeader: String?) {
        val fresh = resolveCache[pageUrl]?.let { System.currentTimeMillis() - it.at < RESOLVE_TTL_MS } == true
        if (fresh || resolveInflight.containsKey(pageUrl)) return
        Thread {
            runCatching { resolveCached(ctx, pageUrl, cookieHeader) }
        }.apply { name = "aurex-prefetch"; isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }.start()
    }

    /** Forget a lookup whose links stopped working (expired / refused), so the next one is fresh. */
    fun invalidate(pageUrl: String) { resolveCache.remove(pageUrl) }

    fun peekCached(pageUrl: String): ResolvedMedia? =
        resolveCache[pageUrl]?.takeIf { System.currentTimeMillis() - it.at < RESOLVE_TTL_MS }?.media

    private fun headersOf(f: org.json.JSONObject): Map<String, String> {
        val h = f.optJSONObject("http_headers") ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        h.keys().forEach { k ->
            val v = h.optString(k)
            if (v.isNotBlank() && !k.equals("Accept-Encoding", true)) m[k] = v
        }
        return m
    }

    /** Real size when the site gives one, else predicted from the bitrate and the duration. */
    private fun sizeOf(f: org.json.JSONObject, dur: Double = 0.0): Long {
        val a = f.optLong("filesize", 0L)
        if (a > 0) return a
        val b = f.optLong("filesize_approx", 0L)
        if (b > 0) return b
        val tbr = f.optDouble("tbr", 0.0)
        return if (!tbr.isNaN() && tbr > 0 && dur > 0) (tbr * 1000.0 / 8.0 * dur).toLong() else 0L
    }

    private fun org.json.JSONObject.str(k: String): String? =
        if (!has(k) || isNull(k)) null else runCatching { getString(k) }.getOrNull()

    private fun isHdrFormat(f: org.json.JSONObject): Boolean {
        val dr = f.str("dynamic_range")
        return (dr != null && dr != "SDR") || (f.str("vcodec") ?: "").startsWith("vp09.02")
    }

    /**
     * True when this phone has a video decoder that accepts this stream's codec at its real size AND frame rate.
     * [hardwareOnly] = hardware decoders only (smooth, cool); false = software ones count too.
     */
    private fun deviceDecodes(f: org.json.JSONObject, hardwareOnly: Boolean = false): Boolean {
        val h = f.optInt("height", 0)
        val w = f.optInt("width", 0).takeIf { it > 0 } ?: (h * 16 / 9)
        return DeviceCaps.decodesRaw(f.str("vcodec").orEmpty(), w, h, f.optInt("fps", 0), hardwareOnly)
    }

    private fun codecScore(vc: String?): Int = when {
        vc == null -> 0
        vc.startsWith("avc1") || vc.startsWith("h264") -> 3   // hardware decoded everywhere
        vc.startsWith("vp09") || vc.startsWith("vp9") -> 2
        vc.startsWith("av01") -> 1
        else -> 0
    }

    /** True when a (non-live) YouTube answer holds no adaptive video-only stream at all: only muxed 360p / 720p, no 1440p / 4K. */
    private fun ytLooksLimited(root: org.json.JSONObject): Boolean {
        val live = root.str("live_status")
        if (root.optBoolean("is_live", false) || live == "is_live" || live == "is_upcoming" || live == "post_live") return false
        val fs = root.optJSONArray("formats") ?: return true
        for (i in 0 until fs.length()) {
            val f = fs.optJSONObject(i) ?: continue
            val vc = f.str("vcodec"); val ac = f.str("acodec")
            if (vc != null && vc != "none" && (ac == null || ac == "none") && f.optInt("height", 0) > 0) return false
        }
        return true
    }

    /** Asks other YouTube clients (the ones that work without a PO token) until one gives the full adaptive list. */
    private fun lookupBetterYoutube(
        ctx: Context, pageUrl: String, cookieHeader: String?, first: org.json.JSONObject
    ): org.json.JSONObject {
        for (clients in listOf("web_embedded,tv,tv_downgraded", "visionos,web_safari")) {
            val r: org.json.JSONObject? = try {
                lookupJson(ctx, pageUrl, cookieHeader, "translated_subs,dash,hls", clients)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isTerminalError(e.message)) throw e
                null
            }
            if (r != null && !ytLooksLimited(r)) return r
        }
        return first
    }

    /** One `-J` lookup of [pageUrl]; [ytSkip] = YouTube data to leave out (null for other sites). */
    private fun lookupJson(
        ctx: Context, pageUrl: String, cookieHeader: String?, ytSkip: String?, ytClients: String? = null
    ): org.json.JSONObject {
        val req = YoutubeDLRequest(pageUrl)
        req.addOption("-J")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", "12")
        val igLookup = isMetaUrl(pageUrl)
        req.addOption("--retries", if (igLookup) "1" else "2")
        req.addOption("--extractor-retries", if (igLookup) "0" else "1")   // Instagram: one look, no hammering
        // YouTube: formats come from the player response; skipping the extra manifests and the
        // translated-subtitle lists makes the lookup lighter and faster.
        // (one combined option: a second --extractor-args for the same extractor is not guaranteed to merge)
        if (ytSkip != null) {
            req.addOption("--extractor-args", "youtube:skip=$ytSkip" + (if (ytClients != null) ";player_client=$ytClients" else ""))
        }
        val cookieFile = writeCookies(ctx, (System.nanoTime() and 0x3fffffffL).toInt(), pageUrl, cookieHeader)
        cookieFile?.let { req.addOption("--cookies", it.absolutePath) }
        val response = try {
            executeRaw(req, "aurex-resolve-" + System.nanoTime()) { _, _, _ -> }
        } finally {
            cookieFile?.delete()
        }
        val out = runCatching { response?.javaClass?.getMethod("getOut")?.invoke(response) as? String }
            .getOrNull().orEmpty()
        val start = out.indexOf('{')
        if (start < 0) throw IllegalStateException(friendlyError(out))
        return org.json.JSONObject(out.substring(start))
    }

    /**
     * Asks the internal engine for the REAL stream links of a page (YouTube, Instagram, X ...), so the
     * built-in player never has to guess from throttled / signed / chunked CDN requests it sniffed.
     * Blocks: call from a background thread.
     */
    fun resolveStreams(ctx: Context, pageUrl: String, cookieHeader: String?): ResolvedMedia {
        // No waiting for the background update here: it only slowed the first lookup down.
        ensureReady(ctx)
        // YouTube, fast pass: the HLS manifest is an extra network round trip and carries nothing the
        // DASH list (where 1440p / 4K / 8K live) does not already have. Live streams are the one case
        // that has only HLS, so an empty result repeats the lookup once with the manifest allowed.
        val root0: org.json.JSONObject = if (isYoutubeUrl(pageUrl)) {
            val fast = try {
                lookupJson(ctx, pageUrl, cookieHeader, "translated_subs,dash,hls")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isTerminalError(e.message)) throw e   // private / removed / unavailable: a second try cannot help
                null
            }
            if (fast != null && (fast.optJSONArray("formats")?.length() ?: 0) > 0) fast
            else lookupJson(ctx, pageUrl, cookieHeader, "translated_subs,dash")
        } else {
            lookupJson(ctx, pageUrl, cookieHeader, null)
        }
        // YouTube sometimes answers with a limited client response (muxed 360p / 720p only): 1440p / 4K are then missing.
        val root: org.json.JSONObject =
            if (isYoutubeUrl(pageUrl) && ytLooksLimited(root0)) lookupBetterYoutube(ctx, pageUrl, cookieHeader, root0) else root0

        val videoOnly = ArrayList<org.json.JSONObject>()
        val audioOnly = ArrayList<org.json.JSONObject>()
        val muxed = ArrayList<org.json.JSONObject>()
        val hlsMuxed = ArrayList<org.json.JSONObject>()
        val formats = root.optJSONArray("formats")
        val dur = root.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it }
        val vOnlyHeights = HashSet<Int>()
        val allHeights = java.util.TreeSet<Int>()
        val hdrHeights = java.util.TreeSet<Int>()
        val sizes = HashMap<Int, Long>()
        for (i in 0 until (formats?.length() ?: 0)) {
            val f = formats?.optJSONObject(i) ?: continue
            if (MediaTools.tooSharp(f.optInt("width", 0), f.optInt("height", 0))) continue   // 8K and above: never offered
            run {
                val vcAll = f.str("vcodec")
                val hh = f.optInt("height", 0)
                if (hh > 0 && vcAll != "none" && f.str("ext") != "mhtml" && DeviceCaps.supportsHeight(hh)) {
                    allHeights.add(hh)
                    if (hh >= 1080 && isHdrFormat(f)) hdrHeights.add(hh)
                    val sz = sizeOf(f, dur)
                    if (sz > (sizes[hh] ?: 0L)) { sizes[hh] = sz; if (f.str("acodec") == "none") vOnlyHeights.add(hh) else vOnlyHeights.remove(hh) }
                }
            }
            val u = f.str("url") ?: continue
            if (!u.startsWith("http")) continue
            if (f.str("ext") == "mhtml") continue
            if ((f.str("protocol") ?: "").contains("m3u8")) {
                // Many video sites (Pornhub, xHamster ...) only offer their qualities as HLS. Muxed renditions are kept
                // as a fallback for heights that have no plain file; YouTube keeps its own (DASH) list.
                if (!isYoutubeUrl(pageUrl) && f.optInt("height", 0) > 0 && f.str("vcodec") != "none" && f.str("acodec") != "none")
                    hlsMuxed.add(f)
                continue
            }
            val vc = f.str("vcodec"); val ac = f.str("acodec")
            val hasV = vc != null && vc != "none"
            val hasA = ac != null && ac != "none"
            when {
                hasV && !hasA -> videoOnly.add(f)
                !hasV && hasA -> audioOnly.add(f)
                else -> muxed.add(f)
            }
        }
        val bestAudio = audioOnly.maxWithOrNull(
            compareBy<org.json.JSONObject>({ if (it.str("ext") == "m4a") 1 else 0 },
                { it.optDouble("abr", it.optDouble("tbr", 0.0)) })
        )
        val bestWebmAudio = audioOnly.filter { it.str("ext") == "webm" && (it.str("acodec") ?: "").startsWith("opus") }
            .maxByOrNull { it.optDouble("abr", it.optDouble("tbr", 0.0)) }
        val choices = ArrayList<StreamChoice>()
        if (bestAudio != null) {
            videoOnly.filter { it.optInt("height", 0) > 0 }.groupBy { it.optInt("height", 0) }.forEach { (h, list) ->
                // 4K / 8K come in several codecs (VP9 / AV1). The player can only use the one this phone has a decoder for at that
                // size, so for 1440p and above a decodable stream always wins; before this the first codec was taken and 8K
                // was dropped to 4K even when the phone could decode the other one.
                // Order: a HARDWARE decoder for this codec + real size + frame rate (a phone with hardware AV1 but only software VP9
                // gets the AV1 stream; one that does 4K30 but not 4K60 gets the 30 fps stream), then any decoder, then the codec
                // preference, SDR before HDR, higher bitrate.
                val best = list.maxWithOrNull(
                    compareBy<org.json.JSONObject>({ if (deviceDecodes(it, true)) 1 else 0 }, { if (deviceDecodes(it, false)) 1 else 0 }, { codecScore(it.str("vcodec")) }, { if (isHdrFormat(it)) 0 else 1 }, { it.optDouble("tbr", 0.0) })
                ) ?: return@forEach
                val fps = best.optInt("fps", 0)
                val vc = best.str("vcodec").orEmpty()
                val ac = bestAudio.str("acodec").orEmpty()
                val safe = (vc.startsWith("avc1") || vc.startsWith("h264")) &&
                    best.str("ext") in listOf("mp4", "m4v") &&
                    (bestAudio.str("ext") == "m4a" || ac.startsWith("mp4a") || ac.startsWith("aac"))
                val webmOk = android.os.Build.VERSION.SDK_INT >= 26 && !safe && !isHdrFormat(best) &&
                    (vc.startsWith("vp09.00") || vc == "vp9") && best.str("ext") == "webm" && bestWebmAudio != null
                val aSel = if (webmOk && bestWebmAudio != null) bestWebmAudio else bestAudio
                choices.add(StreamChoice(
                    "${h}p" + if (fps > 30) "$fps" else "", h,
                    best.str("url").orEmpty(), aSel.str("url"),
                    headersOf(best), headersOf(aSel), safe,
                    sizeOf(best, dur) + sizeOf(aSel, dur),
                    webmSafe = webmOk, vcodec = vc,
                    width = best.optInt("width", 0), fps = fps
                ))
            }
        }
        muxed.filter { it.optInt("height", 0) > 0 }.groupBy { it.optInt("height", 0) }.forEach { (h, list) ->
            if (choices.none { it.height == h }) {
                val best = list.maxByOrNull { it.optDouble("tbr", 0.0) } ?: return@forEach
                val vc = best.str("vcodec").orEmpty()
                val safe = (vc.startsWith("avc1") || vc.startsWith("h264")) && best.str("ext") in listOf("mp4", "m4v")
                choices.add(StreamChoice(
                    "${h}p", h, best.str("url").orEmpty(), null,
                    headersOf(best), emptyMap(), safe, sizeOf(best, dur),
                    vcodec = vc,
                    width = best.optInt("width", 0), fps = best.optInt("fps", 0)
                ))
            }
        }
        hlsMuxed.groupBy { it.optInt("height", 0) }.forEach { (h, list) ->
            if (choices.none { it.height == h }) {
                val best = list.maxByOrNull { it.optDouble("tbr", 0.0) } ?: return@forEach
                choices.add(StreamChoice(
                    "${h}p", h, best.str("url").orEmpty(), null,
                    headersOf(best), emptyMap(), false, sizeOf(best, dur), hls = true
                ))
            }
        }
        if (choices.isEmpty()) {
            val direct = root.str("url")
            if (direct != null) choices.add(StreamChoice("Auto", 0, direct, null))
        }
        // 2K / 4K / 8K the phone cannot decode in hardware are never offered (keep at least one stream)
        choices.filter { it.height > 0 && !DeviceCaps.supportsHeight(it.height) }.let { bad ->
            if (bad.size < choices.size) choices.removeAll(bad.toSet())
        }
        // sharp -> soft by picture class (ultra-wide / portrait 4K sort as the 4K they are), then by height
        choices.sortWith(
            compareByDescending<StreamChoice> { DeviceCaps.tierOf(it.width, it.height) }.thenByDescending { it.height }
        )
        val ua = root.optJSONObject("http_headers")?.str("User-Agent")
        // video-only streams are joined with the best audio: its size belongs to the predicted total
        val audioSize = bestAudio?.let { sizeOf(it, dur) } ?: 0L
        vOnlyHeights.forEach { h -> val v = sizes[h] ?: 0L; if (v > 0) sizes[h] = v + audioSize }
        return ResolvedMedia(
            root.str("title").orEmpty(), choices, ua,
            allHeights.toList().sortedDescending(), sizes,
            durationSec = dur, audioBytes = audioSize,
            hdrHeights = hdrHeights.toList().sortedDescending()
        )
    }

    // ── Request building ─────────────────────────────────────────────────────
    fun buildRequest(
        url: String,
        dir: File,
        tag: String,
        q: YtQuality,
        cookies: File?,
        ua: String? = null,
        referer: String? = null,
        profile: Int = 0,
        height: Int = 0
    ): YoutubeDLRequest {
        val hdr = MediaTools.isHdr(height)
        val height = MediaTools.plainHeight(height)
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--ignore-config")
        // Instagram: the account is logged in on this same phone / IP while this runs, so the engine must look like
        // one quiet viewer, never a burst: one connection, one retry, a pause between requests. (Speed is not the
        // goal on Instagram; a clip is a few MB.) Every other site keeps the fast settings.
        val ig = isMetaUrl(url)
        r.addOption("--concurrent-fragments", if (ig) "1" else "16")
        r.addOption("--retries", if (ig) "1" else "5")
        r.addOption("--fragment-retries", if (ig) "1" else "10")
        r.addOption("--extractor-retries", if (ig) "1" else "3")
        if (ig) r.addOption("--sleep-requests", "1.5")
        r.addOption("--socket-timeout", "20")
        r.addOption("--buffer-size", "64K")
        // Sites with their own extractor (YouTube, Instagram ...) manage their own headers.
        // For every other site the browser's identity keeps cookies / CDN checks consistent.
        if (!ua.isNullOrBlank() && !isItemUrl(url)) r.addOption("--user-agent", ua)
        if (!referer.isNullOrBlank()) r.addOption("--referer", referer)
        // Retry ladder for YouTube (profile 0 = engine defaults, which yt-dlp keeps current with every
        // release). Later attempts ask for other clients, but ONLY ones whose streams work without a
        // PO token. Never list android_vr (every one of its formats answers 403 since Aug 2026), mweb,
        // ios or android here: they need a PO token, so asking for them only produces 403 links (and a
        // list without "default" also drops the newer default clients such as visionos / tv_samsung).
        // A client name an older engine does not know is skipped by yt-dlp, never an error.
        if (isYoutubeUrl(url)) {
            when (profile) {
                1 -> r.addOption("--extractor-args", "youtube:player_client=web_embedded,tv,tv_downgraded")
                2 -> r.addOption("--extractor-args", "youtube:player_client=visionos,web_safari")
            }
        }
        r.addOption("-o", dir.absolutePath + "/%(title).100B" + tag + ".%(ext)s")
        cookies?.let { r.addOption("--cookies", it.absolutePath) }
        // H.264 + AAC first: plays everywhere (including the built-in player) and is hardware decoded.
        when (q) {
            YtQuality.BEST -> {
                // 4K / 8K (and "best"): yt-dlp ranks AV1 above VP9, but most phones cannot decode AV1 at that size
                // (the saved 8K file then fails with DECODER_INIT_FAILED). VP9 first, AV1 only when nothing else exists.
                if (height >= 2160 || height <= 0) r.addOption("-f", "bv*[vcodec!^=av01]+ba/bv*+ba/b")
                else r.addOption("-f", "bv*+ba/b")
                when {
                    // HDR: highest HDR stream at that height; MKV keeps the HDR colour data intact.
                    hdr && height > 0 -> {
                        r.addOption("-S", "res:$height,hdr:12,acodec:m4a")
                        r.addOption("--merge-output-format", "mkv")
                    }
                    // a chosen height: plain SDR (plays everywhere) at that height
                    height > 0 -> {
                        r.addOption("-S", "res:$height,vcodec:h264,acodec:m4a,+hdr")
                        r.addOption("--merge-output-format", "mp4")
                    }
                    // "best": the sharpest picture the video has, up to 4K (8K is never downloaded), SDR first
                    else -> {
                        r.addOption("-S", "res:${MediaTools.MAX_SHORT_SIDE},vcodec:h264,acodec:m4a,+hdr")
                        r.addOption("--merge-output-format", "mp4")
                    }
                }
            }
            YtQuality.P1080, YtQuality.P720, YtQuality.P480 -> {
                val h = if (height > 0) height else when (q) { YtQuality.P1080 -> 1080; YtQuality.P720 -> 720; else -> 480 }
                r.addOption("-f", "bv*+ba/b")
                r.addOption("-S", "res:$h,vcodec:h264,acodec:m4a,+hdr")
                r.addOption("--merge-output-format", "mp4")
            }
            YtQuality.MP3 -> {
                r.addOption("-f", "ba/b")
                r.addOption("-x")
                r.addOption("--audio-format", "mp3")
                r.addOption("--audio-quality", "0")
            }
            YtQuality.M4A -> {
                r.addOption("-f", "ba[ext=m4a]/ba/b")
                r.addOption("-x")
                r.addOption("--audio-format", "m4a")
            }
        }
        return r
    }

    /**
     * The browser's own cookies, so logged-in / age-gated media works - EXCEPT on Instagram.
     * Replaying a signed-in Instagram session from yt-dlp (another TLS / request fingerprint)
     * gets the account flagged and banned, so Instagram is always looked up signed-out.
     */
    fun writeCookies(ctx: Context, id: Int, pageUrl: String, header: String?): File? {
        if (header.isNullOrBlank()) return null
        if (isHandsOffHost(pageUrl)) return null
        return runCatching {
            val host = URI(pageUrl).host ?: return null
            val domain = "." + host.removePrefix("www.").removePrefix("m.")
            val secure = if (pageUrl.startsWith("https", true)) "TRUE" else "FALSE"
            val exp = System.currentTimeMillis() / 1000 + 31_536_000L
            val body = StringBuilder("# Netscape HTTP Cookie File\n")
            header.split(';').forEach { part ->
                val kv = part.trim()
                val i = kv.indexOf('=')
                if (i > 0) {
                    body.append(domain).append("\tTRUE\t/\t").append(secure).append('\t').append(exp)
                        .append('\t').append(kv.substring(0, i)).append('\t').append(kv.substring(i + 1))
                        .append('\n')
                }
            }
            File(ctx.cacheDir, "ytdlp_cookies_$id.txt").also { it.writeText(body.toString()) }
        }.getOrNull()
    }

    // ── Which pages the downloader should take over ──────────────────────────
    private val ITEM_PATTERNS = listOf(
        Regex("""^https?://([a-z0-9-]+\.)*youtube\.com/(watch\?|shorts/|live/|embed/)""", RegexOption.IGNORE_CASE),
        Regex("""^https?://youtu\.be/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://([a-z0-9-]+\.)*instagram\.com/(([\w.]+/)?(p|reel|tv)/[\w-]+|reels/[\w-]+|share/(reel|p)/[\w-]+|stories/[\w.]+/\d+)""", RegexOption.IGNORE_CASE),
        Regex("""^https?://([a-z0-9-]+\.)*facebook\.com/(watch/?\?|reel/|[^/]+/videos/|share/(v|r)/)""", RegexOption.IGNORE_CASE),
        Regex("""^https?://fb\.watch/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://([a-z0-9-]+\.)*tiktok\.com/(@[^/]+/video/|t/|v/)""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(vm|vt)\.tiktok\.com/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://([a-z0-9-]+\.)*(twitter|x)\.com/[^/]+/status/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://([a-z0-9-]+\.)*vimeo\.com/\d+""", RegexOption.IGNORE_CASE),
        Regex("""^https?://([a-z0-9-]+\.)*reddit\.com/r/[^/]+/comments/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://([a-z0-9-]+\.)*dailymotion\.com/video/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://([a-z0-9-]+\.)*twitch\.tv/(videos/|[^/]+/clip/)""", RegexOption.IGNORE_CASE)
    )

    /** A page for ONE video/post on a site where raw stream links are separate, throttled or expiring. */
    fun isItemUrl(url: String?): Boolean =
        !url.isNullOrBlank() && ITEM_PATTERNS.any { it.containsMatchIn(url) }

    /** Adult / video sites that offer several qualities and are covered by the engine. */
    private val QUALITY_HOSTS = Regex(
        """^https?://([a-z0-9-]+\.)*(xhamster\d*\.(com|desktop)|xham\w*\.\w+|xvideos\.com|xnxx\.com|pornhub\.com|redtube\.com|youporn\.com|spankbang\.com|eporner\.com|tube8\.com|txxx\.com|hqporner\.com)/""",
        RegexOption.IGNORE_CASE
    )

    /** Pages where the quality picker is worth asking the engine for the available heights. */
    fun isQualitySite(url: String?): Boolean =
        !url.isNullOrBlank() && (isItemUrl(url) || QUALITY_HOSTS.containsMatchIn(url))

    /** Instagram or Facebook: the Meta sites share one account, so both get the same quiet, signed-out treatment. */
    fun isMetaUrl(url: String?): Boolean =
        isInstagramUrl(url) || (!url.isNullOrBlank() &&
            Regex("""^https?://([a-z0-9-]+\.)*(facebook\.com|fb\.watch|fb\.me)/""", RegexOption.IGNORE_CASE).containsMatchIn(url))

    fun isInstagramUrl(url: String?): Boolean =
        !url.isNullOrBlank() && Regex("""^https?://([a-z0-9-]+\.)*(instagram\.com|instagr\.am)/""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)

    fun isYoutubeUrl(url: String?): Boolean =
        !url.isNullOrBlank() && Regex("""^https?://([a-z0-9-]+\.)*(youtube\.com|youtu\.be)/""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)

    /** Signed / chunked / expiring CDN links: yt-dlp's generic extractor can only save junk from them. */
    private val SIGNED_CDN_HOSTS = listOf(
        "googlevideo.com", "ytimg.com", "ggpht.com", "youtube.com", "youtu.be",
        "fbcdn.net", "cdninstagram.com", "tiktokcdn.com", "twimg.com"
    )

    /**
     * May [candidate] (a link the browser sniffed) be used as a BACKUP source for [pageUrl]?
     * Never for sites that have their own extractor (the page itself is the right source and a raw
     * stream is only half a video), and never for signed CDN links such as YouTube's
     * googlevideo "videoplayback" - those produced tiny "unknown_video" files.
     */
    fun isBackupSource(pageUrl: String?, candidate: String?): Boolean {
        if (candidate.isNullOrBlank() || !canTry(candidate)) return false
        if (isItemUrl(pageUrl)) return false
        val host = runCatching { URI(candidate).host }.getOrNull()?.lowercase() ?: return false
        if (SIGNED_CDN_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        if (candidate.contains("/videoplayback", true)) return false
        return true
    }

    /** Errors that no retry (other client, no cookies, other engine build) can fix. */
    fun isTerminalError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("video unavailable") || m.contains("this video is not available") ||
            m.contains("private video") || m.contains("has been removed") ||
            m.contains("copyright") || m.contains("members-only") || m.contains("members only") ||
            m.contains("premieres in") || m.contains("live event will begin") ||
            m.contains("unsupported url") || m.contains("drm")
    }

    fun canTry(url: String?): Boolean =
        !url.isNullOrBlank() && (url.startsWith("http://", true) || url.startsWith("https://", true))

    // ── Result handling ──────────────────────────────────────────────────────
    private val TEMP_EXT = setOf("part", "ytdl", "temp", "tmp", "frag", "json", "meta")

    /** Smaller than this is an error body / stub, never a real video or audio file. */
    private const val MIN_MEDIA_BYTES = 2_048L

    /**
     * The finished file for [tag] (the template puts the tag right before the extension). The
     * extension may be odd (yt-dlp's generic extractor writes "unknown_video"), so any short word
     * is accepted here and [isUsableOutput] decides whether it is a real download.
     */
    fun findOutput(dir: File, tag: String): File? {
        val rx = Regex("""^.*${Regex.escape(tag)}\.([A-Za-z0-9_]{1,16})$""")
        return dir.listFiles()
            ?.filter { it.isFile && rx.matches(it.name) && it.extension.lowercase() !in TEMP_EXT }
            ?.maxByOrNull { it.length() }
    }

    /** True when [f] is a real media file and not a few-byte error page saved under a media name. */
    fun isUsableOutput(f: File?): Boolean = f != null && f.isFile && f.length() >= MIN_MEDIA_BYTES

    /** yt-dlp names unrecognised direct links *.unknown_video; players need a real extension. */
    fun normalizeExt(ext: String): String = when (ext.lowercase()) {
        "unknown_video", "unknown" -> "mp4"
        "unknown_audio" -> "m4a"
        else -> ext
    }

    fun deleteLeftovers(dir: File?, tag: String) {
        if (dir == null || tag.isBlank()) return
        runCatching { dir.listFiles()?.filter { it.name.contains(tag) }?.forEach { it.delete() } }
    }

    fun mimeFor(ext: String): String? = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        else -> null
    }

    /** yt-dlp breaks when a site changes; these messages mean "update and retry". */
    fun looksOutdated(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("unable to extract") || m.contains("nsig") ||
            m.contains("player response") || m.contains("signature extraction") ||
            m.contains("http error 403") || m.contains("please report this issue")
    }

    /** The linker's message names the file and the reason; show both in a short line. */
    private fun linkErrorText(text: String): String {
        val one = text.lines().firstOrNull { it.contains("CANNOT LINK EXECUTABLE", ignoreCase = true) } ?: text
        val exe = Regex("""/([^/"]+)":""").find(one)?.groupValues?.get(1) ?: "engine"
        val reason = one.substringAfter("\": ", "").trim()
        val missing = Regex("""library "([^"]+)" not found""").find(reason)?.groupValues?.get(1)
        val why = when {
            missing != null -> "missing $missing"
            reason.isNotBlank() -> reason.take(110)
            else -> "unknown reason"
        }
        return "Video engine can't start on this phone ($exe: $why). Settings → Video engine → Repair engine, then retry."
    }

    /** One readable line instead of a Python traceback. */
    fun friendlyError(raw: String?): String {
        val text = raw.orEmpty()
        if (text.contains("CANNOT LINK EXECUTABLE", ignoreCase = true)) return linkErrorText(text)
        val line = text.lines().lastOrNull { it.contains("ERROR", ignoreCase = true) }
            ?.substringAfter("ERROR:", text)?.trim()
            ?: text.lines().lastOrNull { it.isNotBlank() }?.trim()
            ?: "Download failed"
        val l = line.lowercase()
        return when {
            l.contains("sign in to confirm") || l.contains("not a bot") ->
                "The site asked for a sign-in check. Sign in to it in the browser, then retry."
            l.contains("[instagram]") && (l.contains("login") || l.contains("log in") || l.contains("private") ||
                l.contains("rate-limit") || l.contains("cookies")) ->
                "Instagram needs a login for this post. Aurex never uses your Instagram account for downloads, so only public posts can be saved."
            l.contains("[facebook]") && (l.contains("login") || l.contains("log in") || l.contains("private") ||
                l.contains("rate-limit") || l.contains("cookies")) ->
                "Facebook needs a login for this video. Aurex never uses your Facebook account for downloads, so only public videos can be saved."
            l.contains("login required") || l.contains("rate-limit reached") ||
                l.contains("private") || l.contains("log in") || l.contains("cookies") ->
                "This media needs a login. Sign in to the site in the browser, then retry."
            l.contains("http error 403") || l.contains("403: forbidden") ->
                "The site refused the video stream (HTTP 403). Open Settings → Downloads → Update now, then retry."
            l.contains("[youtube]") && (l.contains("no video formats") || l.contains("requested format is not available")) ->
                "YouTube gave no playable formats for this video (" + line.substringAfter("] ").take(110) + ")"
            l.contains("unsupported url") && (l.contains("youtube.com") || l.contains("youtu.be")) ->
                "Open a YouTube video (not the home page or a feed) and try again."
            l.contains("unsupported url") || l.contains("no video formats") || l.contains("no video could be found") ->
                "No downloadable video was found on this page."
            l.contains("ffmpeg") && (l.contains("not found") || l.contains("not installed")) ->
                "Audio/video merging is unavailable on this device."
            l.contains("unable to resolve host") || l.contains("timed out") || l.contains("network") ->
                "Network problem. Check the connection and retry."
            else -> line.take(160)
        }
    }
}
