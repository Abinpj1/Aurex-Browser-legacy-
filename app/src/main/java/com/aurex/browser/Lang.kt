package com.aurex.browser

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One language the person can pick. [web] is the tag sent to websites (Accept-Language / navigator.language). */
class Lang(val code: String, val native: String, val english: String, val web: String = code)

object Langs {
    /** The most widely used languages that the on-device translator supports. English first (= no translation). */
    val ALL = listOf(
        Lang("en", "English", "English", "en"),
        Lang("hi", "हिन्दी", "Hindi"),
        Lang("es", "Español", "Spanish"),
        Lang("zh", "中文 (简体)", "Chinese", "zh-CN"),
        Lang("ar", "العربية", "Arabic"),
        Lang("pt", "Português", "Portuguese"),
        Lang("bn", "বাংলা", "Bengali"),
        Lang("ru", "Русский", "Russian"),
        Lang("ja", "日本語", "Japanese"),
        Lang("fr", "Français", "French"),
        Lang("de", "Deutsch", "German"),
        Lang("ur", "اردو", "Urdu"),
        Lang("id", "Bahasa Indonesia", "Indonesian"),
        Lang("tr", "Türkçe", "Turkish"),
        Lang("ko", "한국어", "Korean"),
        Lang("it", "Italiano", "Italian"),
        Lang("vi", "Tiếng Việt", "Vietnamese"),
        Lang("mr", "मराठी", "Marathi"),
        Lang("te", "తెలుగు", "Telugu"),
        Lang("ta", "தமிழ்", "Tamil"),
        Lang("gu", "ગુજરાતી", "Gujarati"),
        Lang("kn", "ಕನ್ನಡ", "Kannada"),
        Lang("fa", "فارسی", "Persian"),
        Lang("th", "ไทย", "Thai"),
        Lang("pl", "Polski", "Polish"),
        Lang("uk", "Українська", "Ukrainian"),
        Lang("nl", "Nederlands", "Dutch"),
        Lang("ms", "Bahasa Melayu", "Malay"),
        Lang("tl", "Filipino", "Filipino", "fil"),
        Lang("sw", "Kiswahili", "Swahili"),
        Lang("ro", "Română", "Romanian"),
        Lang("el", "Ελληνικά", "Greek"),
        Lang("he", "עברית", "Hebrew"),
        Lang("cs", "Čeština", "Czech"),
        Lang("sv", "Svenska", "Swedish"),
        Lang("hu", "Magyar", "Hungarian")
    )

    fun byCode(code: String?): Lang? = ALL.firstOrNull { it.code == code }
}

/** Where the offline pack of one language stands (its model + its translated app strings). */
enum class LangPhase { IDLE, QUEUED, DOWNLOADING, TRANSLATING, READY, FAILED }

/** [progress] is only meaningful while [phase] is TRANSLATING (0..1); DOWNLOADING (the ML Kit model) has no byte count. */
@Immutable
class LangDl(val phase: LangPhase, val progress: Float = 0f)

/**
 * Texts of the start-up setup page. English only on purpose: the page is shown while the language is not on the phone yet,
 * so there is nothing to translate with (and tools/gen_ui_literals.py skips this file, so they are not translated needlessly).
 */
object LangSetupText {
    const val WAITING = "Getting ready…"
    const val DOWNLOADING = "Downloading language pack…"
    const val PREPARING = "Preparing your app…"
    const val READY = "All set"
    const val FAILED = "Couldn't download the language pack. Check your connection and try again."
    const val NOTE = "One-time download, about 30 MB. After this it works offline."
    const val RETRY = "Try again"
    const val SKIP = "Continue in English"
}

/** A language change that is shown behind a veil (LangUi.kt): the veil covers the screen, THEN the strings change. */
class LangVeil(val id: Int, val choice: String, val code: String, val viaDownload: Boolean) {
    var committed = false
}

/** The full-screen "preparing your language" screen shown at start when the language is not on the phone yet. */
class LangSetup(val choice: String, val code: String)

/**
 * App-wide language. Two independent settings, both default to "Auto":
 *
 *  - App language: "Auto" = the device language (first language of the phone's list that the translator supports,
 *    otherwise English), or any language of [Langs.ALL]. Every user-visible string of the app (listed in
 *    assets/ui_literals.txt, made by tools/gen_ui_literals.py) is translated ON THE PHONE with ML Kit (a ~30 MB
 *    language pack is downloaded once). Translations are cached in files, so the next start is instant and works
 *    offline. Anything a page, a file name or a person typed is never touched: only the known app strings are
 *    translated. English = no translation.
 *
 *    A language only becomes the app language when its pack is COMPLETE: picking one that is not on the phone
 *    downloads it first (progress on its own row) and switches afterwards behind a veil, never half translated.
 *    The download button on a row downloads only; at start a missing language shows a full-screen setup page.
 *  - Website language: "Auto" = the app language, or any language of [Langs.ALL]. It is sent to sites as
 *    Accept-Language and as navigator.language(s). When both settings are "Auto" the browser is left alone,
 *    which already is the device language.
 */
object AppLang {
    private const val P_UI = "uiLang"
    private const val P_WEB = "webLang"
    /** + language code = how many app strings the finished pack holds (absent = pack not complete). */
    private const val P_READY = "uiReady_"
    /** The language whose start-up setup page the person skipped (not shown again for it until they pick it). */
    private const val P_SKIP = "uiSetupSkip"
    private const val PH = '\u0001'

    /** The value of the "Auto" choice (both settings). */
    const val AUTO = "auto"

    /** What the person picked for the app: [AUTO] (= device language) or a language code. */
    var uiChoice by mutableStateOf(AUTO); private set
    /** The language the app is really shown in: a code of [Langs.ALL]; "en" = untranslated. Changes only when its pack is complete. */
    var ui by mutableStateOf("en"); private set
    /** What the person picked for websites: [AUTO] (= the app language) or a language code. */
    var web by mutableStateOf(AUTO); private set
    /** Bumped whenever the shown translations changed, so the screen redraws with them. */
    var version by mutableIntStateOf(0); private set

    /** Pack state per language code. English is always ready and has no entry. */
    private val packs = mutableStateMapOf<String, LangDl>()
    /** The choice that switches by itself once its download is finished (the row the person tapped); null = none. */
    var pending by mutableStateOf<String?>(null); private set
    /** The veil of a language change in progress (null = none). The host (LangOverlayHost) animates it and commits the change. */
    var veil by mutableStateOf<LangVeil?>(null); private set
    /** The start-up setup page (null = not shown). */
    var setup by mutableStateOf<LangSetup?>(null); private set
    /** True while LangOverlayHost is on screen; without it a change is applied at once (nothing could play the veil). */
    @Volatile var hostAttached = false

    /** Called on the main thread when the language changed or its translations finished: the notifications redraw (AurexApp). */
    @Volatile var onReady: (() -> Unit)? = null
    /** Called on the main thread when what websites are told may have changed (BrowserState re-installs its page scripts). */
    @Volatile private var onWebChanged: (() -> Unit)? = null

    fun setWebListener(l: () -> Unit) { onWebChanged = l }
    fun clearWebListener(l: () -> Unit) { if (onWebChanged === l) onWebChanged = null }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** One pack is built at a time (the translator is CPU heavy); the others wait as QUEUED. */
    private val gate = Mutex()
    private val jobs = HashMap<String, Job>()
    /** Per code: id of the newest run. A late result of a cancelled / replaced run never touches the state. */
    private val runs = HashMap<String, Int>()
    private var runSeq = 0
    private var veilSeq = 0
    /** Translations of the language on screen. */
    @Volatile private var cache: Map<String, String> = emptyMap()   // source string (with \u0001 for values) -> translation
    /** Finished / loaded packs kept in memory so the switch itself never reads a file on the main thread. */
    private val warm = ConcurrentHashMap<String, Map<String, String>>()
    private var exact: Set<String> = emptySet()
    private var patterns: List<Pat> = emptyList()
    private var order: List<String> = emptyList()
    private val memo = HashMap<String, Hit>()
    private val NONE = Hit("", null)
    /** The device language list as last seen (to notice a change made in the phone's settings while the app lives). */
    private var lastDevice = ""

    private class Pat(val key: String, val parts: List<String>) {
        fun match(t: String): List<String>? {
            val first = parts.first(); val last = parts.last()
            if (t.length < first.length + last.length) return null
            if (!t.startsWith(first) || !t.endsWith(last)) return null
            var pos = first.length
            val end = t.length - last.length
            val g = ArrayList<String>(parts.size - 1)
            for (i in 1 until parts.size - 1) {
                val lit = parts[i]
                val idx = t.indexOf(lit, pos)
                if (idx < 0 || idx + lit.length > end) return null
                g.add(t.substring(pos, idx)); pos = idx + lit.length
            }
            if (pos > end) return null
            g.add(t.substring(pos, end))
            return g
        }
    }

    private class Hit(val key: String, val groups: List<String>?)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val p = prefs()
        uiChoice = p.getString(P_UI, AUTO)?.takeIf { it == AUTO || Langs.byCode(it) != null } ?: AUTO
        // an older version also stored "app" (= same as the app): that is what "Auto" means now
        val storedWeb = p.getString(P_WEB, AUTO)
        web = storedWeb?.takeIf { Langs.byCode(it) != null } ?: AUTO
        if (storedWeb != web) p.edit().putString(P_WEB, web).apply()
        lastDevice = deviceKey()
        loadLiterals()
        loadPackStates()
        val code = effectiveUi(uiChoice)
        if (code == "en" || isReady(code)) {
            ui = code
            cache = if (code == "en") emptyMap() else loadCache(code)
            applyWebLocale()
            // the app got new strings since the pack was made: top it up quietly, the screen keeps what it has
            if (code != "en" && p.getInt(P_READY + code, 0) != order.size) {
                val run = newRun(code)
                jobs[code] = scope.launch { runPack(code, run, silent = true) }
            }
        } else {
            // The language is not on this phone yet: the app starts in English under a full-screen setup page,
            // and switches the moment the pack is complete. (A skipped setup is not shown again for that language.)
            ui = "en"
            applyWebLocale()
            if (p.getString(P_SKIP, null) != code) {
                setup = LangSetup(uiChoice, code)
                startDownload(code, uiChoice)
            }
        }
    }

    private fun prefs() = app.getSharedPreferences("liquid", Context.MODE_PRIVATE)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ── the device language ─────────────────────────────────────────────────────

    /** The phone's own language list. The system resources are used on purpose: unlike Locale.getDefault() they never carry our website override. */
    private fun deviceLocales(): LocaleList {
        val l = runCatching { Resources.getSystem().configuration.locales }.getOrNull()
        return if (l != null && !l.isEmpty) l else LocaleList.getDefault()
    }

    private fun deviceKey(): String = deviceLocales().toLanguageTags()

    /** The code of [Langs.ALL] for a device locale, or null when the app cannot be shown in it. */
    private fun codeOf(l: Locale): String? {
        val c = when (val x = l.language.lowercase(Locale.ROOT)) {
            "in" -> "id"      // legacy Java codes
            "iw" -> "he"
            "fil" -> "tl"
            else -> x
        }
        return c.takeIf { Langs.byCode(it) != null && TranslateLanguage.fromLanguageTag(it) != null }
    }

    /** What "Auto" means for the app: the first device language that can be translated to, else English. */
    private fun deviceUi(): String {
        val d = deviceLocales()
        for (i in 0 until d.size()) codeOf(d.get(i))?.let { return it }
        return "en"
    }

    private fun effectiveUi(choice: String): String = if (choice == AUTO) deviceUi() else choice

    /** Name of the device's main language, in that language (for the "Auto" rows). */
    fun deviceName(): String {
        val l = deviceLocales().get(0)
        return l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }.ifBlank { l.toLanguageTag() }
    }

    /** True when the device's main language is one the app can be shown in. */
    fun deviceSupported(): Boolean = codeOf(deviceLocales().get(0)) != null

    /** The phone's language was changed in its settings while the app was running (AurexApp). */
    fun onSystemLocaleChanged() {
        if (!::app.isInitialized) return
        val now = deviceKey()
        if (now == lastDevice) return
        lastDevice = now
        // "Auto" is worked out again; a language that is not on the phone downloads first, then switches
        if (uiChoice == AUTO && deviceUi() != ui) pick(AUTO) else {
            applyWebLocale()
            if (web == AUTO && uiChoice == AUTO) onWebChanged?.invoke()
        }
    }

    // ── packs: download, state ──────────────────────────────────────────────────

    /** The language code that [choice] ([AUTO] or a code) means right now. */
    fun codeFor(choice: String): String = effectiveUi(choice)

    /** Pack state of a language code (English: always ready). */
    fun packOf(code: String): LangDl = if (code == "en") READY_DL else packs[code] ?: IDLE_DL

    fun isReady(code: String): Boolean = code == "en" || packs[code]?.phase == LangPhase.READY

    private val READY_DL = LangDl(LangPhase.READY, 1f)
    private val IDLE_DL = LangDl(LangPhase.IDLE)

    private fun loadPackStates() {
        val p = prefs()
        for (l in Langs.ALL) {
            if (l.code == "en") continue
            if (p.getInt(P_READY + l.code, 0) > 0 && cacheFile(l.code).exists()) packs[l.code] = LangDl(LangPhase.READY, 1f)
        }
        // Older versions kept no "complete" mark: a language that was translated (nearly) fully by then counts as complete,
        // so nobody who already uses a language is sent through the setup page again.
        val active = effectiveUi(uiChoice)
        if (active != "en" && packs[active] == null && cacheFile(active).exists() && order.isNotEmpty()) {
            if (loadCache(active).size >= order.size * 0.9) {
                p.edit().putInt(P_READY + active, order.size).apply()
                packs[active] = LangDl(LangPhase.READY, 1f)
            }
        }
    }

    private fun newRun(code: String): Int { val r = ++runSeq; runs[code] = r; return r }

    /** The download button of a row: download only, the language on screen stays as it is. */
    fun download(code: String) = startDownload(code, null)

    /** Stops a download / a waiting one (the row's cancel button). A model that was already being fetched just finishes in the background. */
    fun cancel(code: String) {
        runs[code] = ++runSeq
        jobs.remove(code)?.cancel()
        packs.remove(code)
        if (pending?.let { effectiveUi(it) } == code) pending = null
    }

    /** [switchChoice] != null: the row was tapped, switch by itself once the pack is complete. */
    private fun startDownload(code: String, switchChoice: String?) {
        if (code == "en" || !::app.isInitialized) return
        if (switchChoice != null) pending = switchChoice
        val cur = packs[code]
        if (cur?.phase == LangPhase.READY) {
            if (switchChoice != null) requestSwitch(switchChoice, false)
            return
        }
        if (jobs[code]?.isActive == true && cur != null && cur.phase != LangPhase.FAILED) return   // already on its way
        packs[code] = LangDl(LangPhase.QUEUED)
        val run = newRun(code)
        jobs[code] = scope.launch { runPack(code, run, silent = false) }
    }

    private fun setPack(code: String, run: Int, dl: LangDl) = onMain { if (runs[code] == run) packs[code] = dl }

    /** Model download, then translating every app string; the result is published only when it is complete. */
    private suspend fun CoroutineScope.runPack(code: String, run: Int, silent: Boolean) {
        val target = TranslateLanguage.fromLanguageTag(code)
        if (target == null) { if (!silent) setPack(code, run, LangDl(LangPhase.FAILED)); return }
        val map = loadCache(code)       // this run's own map: a run of another language can never write into it
        val todo = order.filter { !map.containsKey(it) }
        gate.withLock {
            if (!silent) setPack(code, run, LangDl(LangPhase.DOWNLOADING))
            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(target)
                    .build()
            )
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                if (!silent && todo.isNotEmpty()) setPack(code, run, LangDl(LangPhase.TRANSLATING, 0f))
                var done = 0
                var lastPost = 0L
                for (key in todo) {
                    ensureActive()
                    // values (${...}) travel as numbers (9001, 9002 ...): a translator keeps those untouched
                    var src = key
                    var n = 0
                    while (src.indexOf(PH) >= 0) { src = src.replaceFirst(PH.toString(), (9001 + n).toString()); n++ }
                    val raw = try { translator.translate(src).await() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { null }
                    val out = raw?.let { restoreValues(digitsToAscii(it), n) }
                    if (out != null && out.isNotBlank()) map[key] = out
                    done++
                    val now = System.currentTimeMillis()
                    if (!silent && now - lastPost > 120) {
                        lastPost = now
                        setPack(code, run, LangDl(LangPhase.TRANSLATING, done.toFloat() / todo.size))
                    }
                }
                saveCache(code, map)
                prefs().edit().putInt(P_READY + code, order.size).apply()
                onMain { if (runs[code] == run) finishPack(code, map, silent) }
            } catch (e: CancellationException) {
                saveCache(code, map)       // what was translated so far is kept for the next try
                throw e
            } catch (e: Exception) {
                saveCache(code, map)
                onMain { if (runs[code] == run) failPack(code, silent) }
            } finally {
                translator.close()
            }
        }
    }

    private fun finishPack(code: String, map: Map<String, String>, silent: Boolean) {
        jobs.remove(code)
        warm[code] = map
        if (code == ui) {                       // the language on screen got its missing strings: shown at once, nothing else moves
            cache = map
            synchronized(memo) { memo.clear() }
            version = version + 1
            onReady?.invoke()
        }
        if (silent) return
        packs[code] = LangDl(LangPhase.READY, 1f)
        val want = pending
        if (want != null && effectiveUi(want) == code) {
            val s = setup
            if (s != null) { if (!hostAttached) { commitSetup(); endSetup() } }   // else: the setup page plays its finish, then commits
            else requestSwitch(want, true)
        } else if (setup == null) {
            Haptics.fire(null, Hx.SUCCESS)      // a plain download finished
        }
    }

    private fun failPack(code: String, silent: Boolean) {
        jobs.remove(code)
        if (silent) return
        packs[code] = LangDl(LangPhase.FAILED)
        if (pending?.let { effectiveUi(it) } == code) pending = null
        Haptics.fire(null, Hx.ERROR)
    }

    // ── choosing ────────────────────────────────────────────────────────────────

    /**
     * A language row was tapped. [choice]: [AUTO] or a language code.
     * On the phone already: switches (behind the veil). Not yet: downloads FIRST, shows the progress on its row and
     * switches when it is complete. The language never changes half way.
     */
    fun pick(choice: String) {
        if (choice != AUTO && Langs.byCode(choice) == null) return
        val code = effectiveUi(choice)
        if (choice == uiChoice && code == ui) { pending = null; return }
        if (isReady(code)) { pending = null; requestSwitch(choice, false) }
        else {
            prefs().edit().remove(P_SKIP).apply()
            startDownload(code, choice)
        }
    }

    private fun requestSwitch(choice: String, viaDownload: Boolean) {
        pending = null
        val code = effectiveUi(choice)
        if (!hostAttached) { commit(choice); return }
        if (code != "en" && !warm.containsKey(code)) scope.launch { warm[code] = loadCache(code) }   // read off the main thread while the veil comes in
        val v = LangVeil(++veilSeq, choice, code, viaDownload)
        veil = v
        // safety: if the host never answers, the change still happens
        main.postDelayed({ if (veil?.id == v.id) commitVeil(v) }, 1500L)
    }

    /** The veil is fully opaque: change the language now (host). */
    fun commitVeil(v: LangVeil) {
        if (v.committed) return
        v.committed = true
        commit(v.choice)
    }

    /** The veil has faded out again (host). */
    fun endVeil(v: LangVeil) { if (veil?.id == v.id) veil = null }

    /** The pack is complete behind the setup page: change the language now (host). */
    fun commitSetup() {
        val s = setup ?: return
        pending = null
        commit(s.choice)
    }

    fun endSetup() { setup = null }

    /** "Continue in English" on the setup page: stop the download, do not show the page again for this language. */
    fun skipSetup() {
        val s = setup ?: return
        prefs().edit().putString(P_SKIP, s.code).apply()
        cancel(s.code)
        pending = null
        setup = null
    }

    fun retrySetup() {
        val s = setup ?: return
        startDownload(s.code, s.choice)
    }

    /** The one place where the language really changes (always with a complete pack). */
    private fun commit(choice: String) {
        val code = effectiveUi(choice)
        uiChoice = choice
        prefs().edit().putString(P_UI, choice).apply()
        val m: Map<String, String> = if (code == "en") emptyMap() else warm[code] ?: loadCache(code)
        warm.keys.retainAll(setOf(code))
        cache = m
        synchronized(memo) { memo.clear() }
        ui = code
        version = version + 1
        applyWebLocale()
        onReady?.invoke()
        if (web == AUTO) onWebChanged?.invoke()
    }

    /** [code]: [AUTO] (= the app language) or a language code. */
    fun updateWeb(code: String) {
        if (code != AUTO && Langs.byCode(code) == null) return
        web = code
        prefs().edit().putString(P_WEB, code).apply()
        applyWebLocale()
        onWebChanged?.invoke()
    }

    /** Native name of the language the app is shown in. */
    fun nameOfUi(): String = Langs.byCode(ui)?.native ?: "English"

    /** Native name of what the website "Auto" choice gives right now: the app language (the device language while the app is on Auto). */
    fun nameOfWebAuto(): String = if (uiChoice == AUTO) deviceName() else nameOfUi()

    /** Native name of the language websites are asked for. */
    fun nameOfWeb(): String = if (web == AUTO) nameOfWebAuto() else Langs.byCode(web)?.native ?: nameOfWebAuto()

    // ── websites ────────────────────────────────────────────────────────────────

    /** The language tag told to websites; null = the browser's own, which already is the device language. */
    private fun webTag(): String? = when {
        web != AUTO -> Langs.byCode(web)?.web
        uiChoice == AUTO -> null                  // website = app = device
        else -> Langs.byCode(ui)?.web             // website = app
    }

    /** The default locale decides the Accept-Language WebView sends with every request. */
    private fun applyWebLocale() {
        runCatching {
            val dev = deviceLocales()
            val tag = webTag()
            if (tag == null) {
                LocaleList.setDefault(dev)
            } else {
                val loc = Locale.forLanguageTag(tag)
                val all = ArrayList<Locale>()
                all.add(loc)
                for (i in 0 until dev.size()) {
                    val l = dev.get(i)
                    if (l.language != loc.language) all.add(l)
                }
                LocaleList.setDefault(LocaleList(*all.toTypedArray()))
            }
        }
    }

    /** Value for the Accept-Language header of a top-level load, or null (leave the browser's own). */
    fun webHeader(): String? {
        val tag = webTag() ?: return null
        val base = tag.substringBefore('-')
        val sb = StringBuilder(tag)
        if (base != tag) sb.append(',').append(base).append(";q=0.9")
        if (base != "en") sb.append(",en;q=0.5")
        return sb.toString()
    }

    /** Document-start script: pages that read navigator.language(s) see the chosen language. */
    fun webScript(): String {
        val tag = webTag() ?: return ""
        val base = tag.substringBefore('-')
        val list = JSONArray().apply { put(tag); if (base != tag) put(base) }.toString()
        return "\n;(function(){try{var L=" + list + ";" +
            "Object.defineProperty(Navigator.prototype,'language',{get:function(){return L[0];},configurable:true});" +
            "Object.defineProperty(Navigator.prototype,'languages',{get:function(){return L.slice();},configurable:true});" +
            "}catch(e){}})();\n"
    }

    // ── translating the app ─────────────────────────────────────────────────────

    private fun loadLiterals() {
        val ex = HashSet<String>(); val pats = ArrayList<Pat>(); val ord = ArrayList<String>()
        runCatching {
            app.assets.open("ui_literals.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { raw ->
                    if (raw.isEmpty()) return@forEach
                    val line = unescape(raw)
                    ord.add(line)
                    if (line.indexOf(PH) < 0) ex.add(line)
                    else {
                        val parts = line.split(PH)
                        val n = parts.size - 1
                        if (n in 1..9 && parts.subList(1, parts.size - 1).none { it.isEmpty() } &&
                            parts.sumOf { it.length } >= 4) pats.add(Pat(line, parts))
                    }
                }
            }
        }
        exact = ex; patterns = pats; order = ord
    }

    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(if (s[i + 1] == 'n') '\n' else s[i + 1]); i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun resolve(text: String): Hit? {
        if (text.length < 2 || text.length > 400) return null
        synchronized(memo) {
            memo[text]?.let { return if (it === NONE) null else it }
            if (memo.size > 4000) memo.clear()
        }
        var hit: Hit = NONE
        if (exact.contains(text)) hit = Hit(text, null)
        else if (text.any { it.isLetter() }) {
            for (p in patterns) {
                val g = p.match(text) ?: continue
                hit = Hit(p.key, g); break
            }
        }
        synchronized(memo) { memo[text] = hit }
        return if (hit === NONE) null else hit
    }

    /** The translation of an app string, or the string itself (not an app string / not translated yet). */
    fun translate(text: String): String {
        if (ui == "en") return text
        val hit = resolve(text) ?: return text
        val tr = cache[hit.key] ?: return text
        val g = hit.groups ?: return tr
        val sb = StringBuilder(tr.length + 16)
        var i = 0
        while (i < tr.length) {
            val c = tr[i]
            if (c == PH && i + 1 < tr.length && tr[i + 1] in '0'..'8') {
                sb.append(g.getOrElse(tr[i + 1] - '0') { "" }); i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun cacheFile(code: String) = File(app.filesDir, "ui_tr_$code.json")

    private fun loadCache(code: String): ConcurrentHashMap<String, String> {
        val m = ConcurrentHashMap<String, String>()
        runCatching {
            val f = cacheFile(code)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.keys().forEach { k -> m[k] = o.getString(k) }
            }
        }
        return m
    }

    private fun saveCache(code: String, m: Map<String, String>) {
        runCatching {
            val o = JSONObject()
            m.forEach { (k, v) -> o.put(k, v) }
            cacheFile(code).writeText(o.toString())
        }
    }

    private fun digitsToAscii(s: String): String {
        if (s.all { it.code < 128 }) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            val d = Character.digit(c, 10)
            sb.append(if (d >= 0 && c.code >= 128) ('0' + d) else c)
        }
        return sb.toString()
    }

    /** Turns 9001 / 9002 back into value markers; null when the translator lost one. */
    private fun restoreValues(t: String, n: Int): String? {
        var out = t
        for (i in 0 until n) {
            val tok = (9001 + i).toString()
            if (!out.contains(tok)) return null
            out = out.replace(tok, PH.toString() + ('0' + i))
        }
        return out
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }
}

val LocalTextStyle = compositionLocalOf { TextStyle.Default }

/**
 * Pure Foundation Text with automatic on-device translation (zero Material3 overhead).
 * The app's own strings are shown in the chosen language; user content stays untouched.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    val shown = if (AppLang.ui == "en") text else { AppLang.version; AppLang.translate(text) }
    val resolvedColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> LocalPalette.current.textPrimary
    }
    val merged = style.merge(
        TextStyle(
            color = resolvedColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign ?: TextAlign.Unspecified,
            lineHeight = lineHeight
        )
    )
    BasicText(
        text = shown,
        modifier = modifier,
        style = merged,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines
    )
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    val resolvedColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> LocalPalette.current.textPrimary
    }
    val merged = style.merge(
        TextStyle(
            color = resolvedColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign ?: TextAlign.Unspecified,
            lineHeight = lineHeight
        )
    )
    BasicText(
        text = text,
        modifier = modifier,
        style = merged,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines
    )
}
