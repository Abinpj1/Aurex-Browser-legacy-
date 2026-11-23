package com.aurex.browser

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent event log for bug reports. It lives in the app's private folder (survives restarts and
 * crashes), is capped in size, and never stores cookies or URL query strings. Settings → General →
 * "Copy log" puts the device info, the state of the video engine files and the recent events on the clipboard.
 */
object AppLog {
    private const val MAX_FILE_BYTES = 192 * 1024L
    private const val MAX_MSG = 3200
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(ctx: Context) {
        synchronized(lock) {
            if (file != null) return
            file = File(ctx.applicationContext.filesDir, "aurex_log.txt")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            try { error("Crash", "uncaught exception in thread ${t.name}", ex) } catch (_: Throwable) {}
            previous?.uncaughtException(t, ex)
        }
        info("App", "start · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
    }

    fun info(tag: String, msg: String) = add("I", tag, msg)
    fun warn(tag: String, msg: String) = add("W", tag, msg)
    fun error(tag: String, msg: String, t: Throwable? = null) {
        val extra = if (t == null) "" else "\n" + stack(t)
        add("E", tag, msg + extra)
    }

    /** A page address without its query string (those can carry tokens), except YouTube's video id. */
    fun url(u: String?): String {
        if (u.isNullOrBlank()) return "-"
        return try {
            val p = android.net.Uri.parse(u)
            val v = p.getQueryParameter("v")
            (p.host ?: "?") + (p.path ?: "") + (if (v != null) "?v=$v" else "")
        } catch (_: Throwable) {
            "?"
        }
    }

    private fun stack(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString().take(3000)
    }

    private fun add(level: String, tag: String, msg: String) {
        try {
            android.util.Log.println(
                when (level) { "E" -> android.util.Log.ERROR; "W" -> android.util.Log.WARN; else -> android.util.Log.INFO },
                "Aurex/$tag", msg.take(MAX_MSG)
            )
            val f = file ?: return
            synchronized(lock) {
                f.appendText(fmt.format(Date()) + " " + level + "/" + tag + ": " + msg.take(MAX_MSG) + "\n")
                if (f.length() > MAX_FILE_BYTES) {
                    // keep the newer half, starting at a line boundary
                    val text = f.readText()
                    val cut = text.indexOf('\n', text.length / 2)
                    f.writeText(if (cut >= 0) text.substring(cut + 1) else "")
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun clear() {
        try {
            synchronized(lock) { file?.writeText("") }
        } catch (_: Throwable) {
        }
    }

    private fun tail(maxChars: Int = 120_000): String = try {
        synchronized(lock) { file?.takeIf { it.isFile }?.readText().orEmpty() }.takeLast(maxChars)
    } catch (_: Throwable) {
        ""
    }

    private fun sizeOf(f: File): Long = try {
        if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) {
        -1L
    }

    private fun countFiles(f: File): Int = try {
        if (f.isDirectory) f.walkTopDown().count { it.isFile } else -1
    } catch (_: Throwable) {
        -1
    }

    /** Device, app and video-engine state: what is needed to understand a "works on my phone, not on theirs" bug. */
    private fun header(ctx: Context): String {
        val app = ctx.applicationContext
        val sb = StringBuilder()
        fun line(k: String, v: String) { sb.append(k).append(": ").append(v).append('\n') }
        try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            line("App", "${pi.versionName} (code ${PackageInfoCompat.getLongVersionCode(pi)}) · updated ${Date(pi.lastUpdateTime)}")
        } catch (_: Throwable) {
        }
        line("Device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · ${Build.HARDWARE}")
        line("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · patch ${Build.VERSION.SECURITY_PATCH}")
        line("ROM build", Build.DISPLAY)
        line("ABIs", Build.SUPPORTED_ABIS.joinToString(", ") +
            " · 64-bit: ${Build.SUPPORTED_64_BIT_ABIS.joinToString(",")} · 32-bit: ${Build.SUPPORTED_32_BIT_ABIS.joinToString(",")}")
        try {
            line("Page size", "${android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)} bytes")
        } catch (_: Throwable) {
        }
        try {
            val mi = ActivityManager.MemoryInfo()
            (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            line("RAM", "${mi.totalMem / 1048576} MB total · ${mi.availMem / 1048576} MB free")
        } catch (_: Throwable) {
        }
        line("Free storage", "${app.filesDir.usableSpace / 1048576} MB")
        try {
            line("WebView", androidx.webkit.WebViewCompat.getCurrentWebViewPackage(app)?.let { "${it.packageName} ${it.versionName}" } ?: "?")
        } catch (_: Throwable) {
        }
        line("Locale", Locale.getDefault().toString())
        line("Video download", if (VideoDl.enabled) "on" else "off")
        line("Engine version", YtDlpEngine.engineVersion.ifBlank { "?" })
        line("Engine problem", YtDlpEngine.engineProblem.ifBlank { "none recorded" })

        // Files of the video engine: the "library not found" failures are decided by what is on disk.
        try {
            val libDir = File(app.applicationInfo.nativeLibraryDir)
            line("Native lib dir", libDir.absolutePath)
            val libs = libDir.listFiles { f -> f.name.startsWith("libpython") || f.name.startsWith("libffmpeg") || f.name.startsWith("libaria") }
            line("Engine executables", libs?.sortedBy { it.name }?.joinToString(", ") { "${it.name}=${it.length()}" } ?: "none")
        } catch (_: Throwable) {
        }
        try {
            val base = File(app.noBackupFilesDir, "youtubedl-android")
            line("Engine folder", if (base.isDirectory) "present (${sizeOf(base) / 1024} KB)" else "MISSING")
            for (name in listOf("python", "ffmpeg")) {
                val dir = File(base, "packages/$name")
                val lib = File(dir, "usr/lib")
                val names = lib.list()?.sorted().orEmpty()
                line(
                    "Package $name",
                    if (!dir.isDirectory) "MISSING" else
                        "${countFiles(dir)} files · usr/lib has ${names.size} entries" +
                            (if (name == "python") " · libandroid-support.so=" + (if ("libandroid-support.so" in names) "yes" else "NO") +
                                " · libpython*=" + names.filter { it.startsWith("libpython") }.joinToString(",").ifBlank { "NONE" } else "")
                )
            }
            val yt = File(base, "yt-dlp")
            line("yt-dlp folder", if (yt.isDirectory) "present (${sizeOf(yt) / 1024} KB)" else "MISSING")
        } catch (_: Throwable) {
        }
        return sb.toString()
    }

    /**
     * Builds the whole report on a background thread (it includes one live engine test, without any
     * automatic repair, so the real error text is captured) and hands it to [onReady] on the main thread.
     */
    fun collect(ctx: Context, onReady: (String) -> Unit) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val box = arrayOfNulls<String>(1)
            if (VideoDl.enabled) {
                val t = Thread { box[0] = YtDlpEngine.probe(app) }
                t.isDaemon = true
                t.start()
                t.join(20_000L)
                if (box[0] == null) box[0] = "TIMEOUT: no answer from the engine within 20 s"
            } else {
                box[0] = "skipped (video download is off)"
            }
            val text = StringBuilder()
                .append("=== Aurex log · ").append(Date()).append(" ===\n")
                .append(header(app))
                .append("\n--- Engine test ---\n").append(box[0]).append('\n')
                .append("\n--- Events (newest last) ---\n").append(tail())
                .toString()
            main.post { onReady(text) }
        }.apply { name = "aurex-log"; isDaemon = true }.start()
    }

    fun copy(ctx: Context, text: String): Boolean = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        // the clipboard travels through Binder (about 1 MB): keep the newest part
        cm.setPrimaryClip(ClipData.newPlainText("Aurex log", text.takeLast(400_000)))
        true
    } catch (_: Throwable) {
        false
    }
}
