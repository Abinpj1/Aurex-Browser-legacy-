package com.aurex.browser

import android.content.Context
import android.net.Uri
import android.util.LruCache
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Adult-content filter. Five layers, cheapest first:
 *   1. Domain list: ~180 bundled domains (assets/adult_domains.txt) + a downloaded list of tens of thousands
 *      (StevenBlack "porn-only" hosts, cached in filesDir, refreshed weekly). Subdomains are covered.
 *   2. Domain name heuristics: well-known site names as a label (so mirrors like xvideos.es / pornhub.org
 *      are caught), adult TLDs (.xxx .sex .porn .adult), names containing "porn" / "hentai".
 *   3. Address words: path and search terms ("/porn/", ?q=porn, "sex video" ...), compared word by word
 *      so "essex" or "sextant" never match.
 *   4. SafeSearch is forced on Google, Bing, DuckDuckGo and Yahoo result pages ([safeSearchUrl]).
 *   5. Page scan ([SCAN_JS]): the RTA "restricted to adults" label that adult sites put in their pages, an
 *      "adult" rating meta tag, or a weighted score of adult terms in the title / description / headline.
 * Smart about context, so useful pages are left alone:
 *   - informational text ("porn addiction help", "how to block adult sites", a news report, art history) is not
 *     blocked unless it also looks like someone WANTING the content ("free", "watch", "download", "live" ...);
 *   - the page scan needs at least one strong term and a high enough score; weak words alone (nude lipstick,
 *     Ford Escort, hardcore gaming, XXXTentacion) never block, and any health / news / legal word raises the bar;
 *   - health, news, reference and big mixed platforms ([skipsPageScan]) are exempt from the word rules and the scan;
 *   - a site the heuristics got wrong can be allowed by the person ([setUserAllowed]) - never a site on the list.
 * It also covers the "illegal" side of adult content: leak / deepfake / undress-AI / voyeur / fake-MMS sites and
 * wording (leaked MMS, nudify, upskirt ...), by name, by address words and by the page scan.
 */
object AdultFilter {

    private val LIST_URLS = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
        "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/alternates/porn-only/hosts"
    )
    private const val MAX_AGE_MS = 7L * 86_400_000L

    private val domains = ConcurrentHashMap.newKeySet<String>()
    private val verdicts = LruCache<String, Boolean>(4096)
    private val loading = AtomicBoolean(false)
    @Volatile private var appCtx: Context? = null
    @Volatile private var bigLoaded = false

    private val http by lazy {
        AurexHttp.shared.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** Number of adult domains currently known (bundled + downloaded). */
    val size: Int get() = domains.size

    /** Sites the person allowed after a wrong block (full hosts; subdomains are covered). */
    @Volatile private var userAllowed: Set<String> = emptySet()

    fun setUserAllowed(hosts: Set<String>) {
        userAllowed = hosts.map { it.lowercase().removePrefix("www.").trim('.') }.toSet()
        verdicts.evictAll()
    }

    private fun isUserAllowed(host: String): Boolean {
        val set = userAllowed
        if (set.isEmpty()) return false
        var h = host.lowercase().removePrefix("www.")
        while (h.contains('.')) {
            if (h in set) return true
            h = h.substringAfter('.')
        }
        return false
    }

    /** True when [host] is on the downloaded / bundled adult list (those can never be allowed). */
    fun isListed(host: String): Boolean = inSet(host.lowercase().removePrefix("www.").trim('.'))

    /** The blocked screen offers "Allow this site" only for sites the heuristics blocked, never search engines. */
    fun canAllow(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return false
        if (host.isBlank() || isListed(host)) return false
        val h = host
        return !(h.startsWith("google.") || h.contains(".google.") || h.endsWith("bing.com") ||
            h.endsWith("duckduckgo.com") || h.endsWith("yahoo.com") || h.startsWith("yandex.") ||
            h.endsWith("baidu.com") || h.endsWith("brave.com") || h.endsWith("ecosia.org") || h.endsWith("startpage.com"))
    }

    /** Hosts the page scan and the address-word rules never judge: health / news / reference, mixed platforms, allowed sites. */
    fun skipsPageScan(host: String): Boolean = isAllowlisted(host) || isMixedPlatform(host) || isUserAllowed(host)

    // ── List loading ────────────────────────────────────────────────────────────────

    /** Loads the bundled domains (a few KB, instant). Safe to call more than once. */
    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        runCatching {
            ctx.assets.open("adult_domains.txt").bufferedReader().useLines { lines -> lines.forEach { add(it) } }
        }
        verdicts.evictAll()
    }

    /**
     * Loads the cached big list and downloads it when it is missing or older than a week ([force] = now).
     * Runs on its own thread; only called while the filter is on, so the memory is not used otherwise.
     */
    fun loadBigList(force: Boolean = false) {
        val ctx = appCtx ?: return
        if (!loading.compareAndSet(false, true)) return
        thread(name = "adult-list", isDaemon = true) {
            try {
                val file = File(ctx.filesDir, "adult_hosts.txt")
                if (file.exists() && !bigLoaded) {
                    file.bufferedReader().useLines { lines -> lines.forEach { add(it) } }
                    bigLoaded = true
                    verdicts.evictAll()
                }
                val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() >= MAX_AGE_MS
                if (force || stale) download(file)
            } catch (_: Throwable) {
            } finally {
                loading.set(false)
            }
        }
    }

    private fun download(file: File) {
        val tmp = File(file.parentFile, file.name + ".part")
        try {
            var ok = false
            for (u in LIST_URLS) {
                val req = Request.Builder().url(u).header("User-Agent", "Mozilla/5.0 AurexBrowser").build()
                runCatching {
                    http.newCall(req).execute().use { r ->
                        val body = r.body
                        if (r.isSuccessful && body != null) {
                            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                            ok = tmp.length() >= 1024
                        }
                    }
                }
                if (ok) break
            }
            if (!ok || tmp.length() < 1024) {
                tmp.delete()
                return
            }
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            tmp.delete()
            file.bufferedReader().useLines { lines -> lines.forEach { add(it) } }
            bigLoaded = true
            verdicts.evictAll()
        } catch (_: Throwable) {
            tmp.delete()
        }
    }

    /** One line of a hosts file ("0.0.0.0 example.com"), a plain domain list or a comment. */
    private fun add(raw: String) {
        var l = raw.trim().lowercase()
        if (l.isEmpty() || l[0] == '#') return
        val hash = l.indexOf('#')
        if (hash > 0) l = l.substring(0, hash).trim()
        val parts = l.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val d = (if (parts.size >= 2) parts[1] else parts[0]).removePrefix("www.").trim('.')
        if (d.length < 4 || d.length > 120 || !d.contains('.')) return
        if (d == "localhost" || d.endsWith(".localdomain")) return
        if (d.all { it.isDigit() || it == '.' }) return
        for (c in d) {
            if (!(c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-')) return
        }
        domains.add(d)
    }

    // ── Host checks ─────────────────────────────────────────────────────────────────

    /** True when [host] (or a parent domain) is a known adult site, or its NAME says so. Cached. */
    fun isAdultHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.").trim('.')
        if (h.length < 4) return false
        verdicts.get(h)?.let { return it }
        val r = inSet(h) || (!isUserAllowed(h) && !isAllowlisted(h) && (labelsLookAdult(h) || illegalName(h)))
        verdicts.put(h, r)
        return r
    }

    private fun inSet(host: String): Boolean {
        var h = host
        while (h.contains('.')) {
            if (h in domains) return true
            h = h.substringAfter('.')
        }
        return false
    }

    private fun labelsLookAdult(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size >= 2 && labels.last() in ADULT_TLDS) return true
        for (i in 0 until labels.size - 1) {
            val raw = labels[i]
            val l = unleet(raw)
            if (raw in SITE_NAMES || l in SITE_NAMES) return true
            if (LABEL_PREFIXES.any { l.startsWith(it) }) return true
            if (l.length >= 6 && hasAdultWord(l)) return true
        }
        return false
    }

    /** Names of leak / deepfake / undress-AI / voyeur sites: the illegal side of adult content. */
    private fun illegalName(host: String): Boolean {
        val labels = host.split('.')
        for (i in 0 until labels.size - 1) {
            val l = unleet(labels[i])
            if (l in ILLEGAL_NAMES) return true
            if (ILLEGAL_WORDS.any { l.contains(it) }) return true
        }
        return false
    }

    private fun hasAdultWord(l0: String): Boolean {
        // names that merely contain an adult-looking word
        var l = l0
        for (ok in NOT_ADULT) l = l.replace(ok, "")
        if (l.contains("hentai")) return true
        if (l.contains("porn")) return true
        val i = l.indexOf("xxx")
        return i >= 0 && l.getOrNull(i + 3) != 'l'   // XXXL is a clothing size
    }

    /** Decodes leetspeak ("p0rn", "s3x", "xvid30s", "h3n7a1") and collapses 3+ repeated letters ("pooorrrnn" -> "porn"). */
    private fun unleet(w: String): String {
        if (w.length < 3 || w.length > 48) return w
        val hasLeet = w.any { it in "0134578@$" } && w.any { it in 'a'..'z' }
        var s = if (!hasLeet) w else buildString(w.length) {
            for (c in w) append(when (c) {
                '0' -> 'o'; '1' -> 'i'; '3' -> 'e'; '4', '@' -> 'a'; '5', '$' -> 's'; '7' -> 't'; '8' -> 'b'; else -> c
            })
        }
        // Collapse 3+ identical consecutive chars (e.g. "pooorrrnn" -> "poorrnn" -> checked cleanly; keep "xxx" intact!)
        if (s.length >= 5 && !s.contains("xxx")) {
            var runs = false
            for (i in 2 until s.length) {
                if (s[i] == s[i - 1] && s[i] == s[i - 2]) { runs = true; break }
            }
            if (runs) {
                val out = StringBuilder(s.length)
                for (c in s) {
                    if (out.isEmpty() || out[out.length - 1] != c) out.append(c)
                }
                s = out.toString()
            }
        }
        return s
    }

    private fun isMixedPlatform(host: String): Boolean {
        var h = host.lowercase().removePrefix("www.")
        while (h.contains('.')) {
            if (h in PLATFORMS) return true
            h = h.substringAfter('.')
        }
        return false
    }

    /** Health, news and reference sites: the address-word rules and the page scan leave them alone. */
    fun isAllowlisted(host: String): Boolean {
        var h = host.lowercase().removePrefix("www.")
        while (h.contains('.')) {
            if (h in ALLOWLIST) return true
            h = h.substringAfter('.')
        }
        return false
    }

    // ── Whole-address check ─────────────────────────────────────────────────────────

    /** Main-frame check: host, then words in the path and in search boxes of the address. */
    fun matches(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        if (host.isBlank()) return false
        if (isAdultHost(host)) return true
        if (isAllowlisted(host) || isUserAllowed(host)) return false
        val path = uri.path.orEmpty()
        if (isExplicitSubpath(host, path)) return true
        if (textIsAdult(path)) return true
        return runCatching {
            uri.queryParameterNames.any { name ->
                name.lowercase() in QUERY_PARAMS && textIsAdult(uri.getQueryParameter(name).orEmpty())
            }
        }.getOrDefault(false)
    }

    /**
     * Words, not substrings: "essex" and "sextant" do not match, "sex video" and "freeporn" do. Informational text
     * (addiction help, parental controls, news, law, art) stays open unless it also asks for the content itself.
     */
    private fun textIsAdult(text: String): Boolean {
        if (text.length < 3) return false
        val raw = text.lowercase().split(SPLIT).filter { it.isNotEmpty() }.take(40)
        val t = raw.map { unleet(it) }
        if (!hasAdultWords(t)) return false
        val informational = raw.any { it in SAFE_CONTEXT }
        val wantsIt = raw.any { it in INTENT }
        return !(informational && !wantsIt)
    }

    private fun hasAdultWords(t: List<String>): Boolean {
        for (i in t.indices) {
            val w = t[i]
            if (w in SOLO_WORDS || w in SITE_NAMES) return true
            if (w.length >= 4 && w.contains("porn") && !w.contains("sporn")) return true
            if (w.length in 7..20 && w.startsWith("sex") && w.substring(3) in SEX_NEXT) return true
            val next = t.getOrNull(i + 1) ?: continue
            when (w) {
                "sex" -> if (next in SEX_NEXT) return true
                "adult" -> if (next in ADULT_NEXT) return true
                "naked", "nude", "nudes" -> if (next in BODY_NEXT) return true
                "sexy" -> if (next in SEXY_NEXT) return true
                "bf" -> if (next in BF_NEXT) return true
                "blue" -> if (next == "film" || next == "films") return true
                "desi" -> if (next in DESI_NEXT) return true
                "mms" -> if (next in MMS_NEXT) return true
                "viral", "latest", "hot" -> if (next == "mms") return true
                "leaked", "leaks", "leak" -> if (next == "nudes" || next == "nude" || next == "onlyfans" || next == "mms") return true
                "escort", "escorts" -> if (next in ESCORT_NEXT) return true
                "call" -> if (next == "girl" || next == "girls") return true
                "deepfake", "deepfakes" -> if (next in DEEPFAKE_NEXT) return true
                "undress" -> if (next in UNDRESS_NEXT) return true
                "bhabhi", "aunty", "devar", "indian" -> if (next in DESI_PAIR_NEXT) return true
                "hot" -> if (next in HOT_NEXT) return true
                "gandi", "suhagrat", "nangi" -> if (next in GANDI_NEXT) return true
                "savita" -> if (next == "bhabhi") return true
                "rule" -> if (next == "34") return true
            }
        }
        return false
    }

    /** Catches explicit NSFW subreddits / feeds on mixed platforms without touching normal subreddits. */
    private fun isExplicitSubpath(host: String, path: String): Boolean {
        if (path.length < 4) return false
        val low = path.lowercase()
        if (host == "reddit.com" || host.endsWith(".reddit.com")) {
            val m = REDDIT_SUB_RE.find(low) ?: return false
            val sub = m.groupValues[1]
            if (sub in NSFW_SUBS) return true
            if (sub.contains("nsfw") || sub.contains("gonewild") || sub.contains("porn") ||
                sub.contains("hentai") || sub.contains("rule34") || sub.contains("onlyfans")
            ) return true
        }
        return false
    }

    // ── SafeSearch ──────────────────────────────────────────────────────────────────

    /** The same search address with the engine's strict SafeSearch switch set, or null when nothing is to be changed. */
    fun safeSearchUrl(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val name: String
        val value: String
        when {
            isGoogle(host) && (path == "/search" || path == "/m/search") -> { name = "safe"; value = "active" }
            (host == "bing.com" || host.endsWith(".bing.com")) &&
                (path == "/search" || path == "/images/search" || path == "/videos/search") -> { name = "adlt"; value = "strict" }
            (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) &&
                uri.getQueryParameter("q") != null -> { name = "kp"; value = "1" }
            host.endsWith("search.yahoo.com") && path == "/search" -> { name = "vm"; value = "r" }
            host == "search.brave.com" && (path == "/search" || path == "/images" || path == "/videos") -> { name = "safesearch"; value = "strict" }
            (host == "ecosia.org" || host.endsWith(".ecosia.org")) && path == "/search" -> { name = "safeSearch"; value = "strict" }
            (host.startsWith("yandex.") || host.contains(".yandex.")) && path.startsWith("/search") -> { name = "fyandex"; value = "1" }
            (host == "qwant.com" || host.endsWith(".qwant.com")) && uri.getQueryParameter("q") != null -> { name = "safesearch"; value = "2" }
            else -> return null
        }
        if (uri.getQueryParameter(name) == value) return null
        return setParam(url, name, value)
    }

    private fun isGoogle(host: String): Boolean {
        val h = host.removePrefix("www.")
        return h.startsWith("google.") && h.length <= 14
    }

    private fun setParam(url: String, name: String, value: String): String {
        val hashAt = url.indexOf('#')
        val frag = if (hashAt >= 0) url.substring(hashAt) else ""
        val base = if (hashAt >= 0) url.substring(0, hashAt) else url
        val q = base.indexOf('?')
        val head = if (q >= 0) base.substring(0, q) else base
        val kept = if (q >= 0) {
            base.substring(q + 1).split('&').filter { it.isNotEmpty() && !it.substringBefore('=').equals(name, true) }
        } else emptyList()
        return head + "?" + (kept + (name + "=" + value)).joinToString("&") + frag
    }

    // ── Word and name tables ────────────────────────────────────────────────────────

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Names that only ever belong to adult sites (as a domain label, so every mirror is caught). */
    private val SITE_NAMES = setOf(
        "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "tube8", "eporner", "motherless",
        "fapello", "rule34", "nhentai", "tsumino", "hanime", "thisav", "onlyfans", "fansly", "chaturbate",
        "myfreecams", "stripchat", "bongacams", "livejasmin", "camsoda", "camwhores", "cam4", "flirt4free",
        "brazzers", "bangbros", "realitykings", "spankbang", "pornmd", "hentaigene", "naughtyamerica",
        "bellesa", "realteens", "teamskeet", "xxnx", "txxx", "hqporner", "missav", "jable", "javhd",
        "supjav", "javbus", "javmost", "njav", "porntrex", "tnaflix", "empflix", "drtuber", "sunporno",
        "thumbzilla", "pornone", "upornia", "hclips", "hotmovs", "voyeurhit", "beeg", "efukt", "daftsex",
        "ixxx", "fuq", "youjizz", "jizzbunker", "vporn", "gotporn", "porn300", "fapvid", "okxxx",
        "sxyprn", "yourporn", "yespornplease", "desipapa", "antarvasna", "savitabhabhi", "velamma",
        "desixnxx", "indianporn", "xhamsterdesi", "fsiblog", "kamababa", "masalaseen", "phncdn",
        "xhcdn", "rdtcdn", "ypncdn", "t8cdn", "strpst", "bngpt"
    )

    private val LABEL_PREFIXES = listOf(
        "porn", "xxxvideo", "sexvideo", "hentai", "camgirl", "onlyfans", "fapello", "rule34",
        "nhentai", "tsumino", "hanime", "motherless", "chaturbate", "fansly"
    )

    private val ADULT_TLDS = setOf("xxx", "sex", "porn", "adult")

    /** Leak / deepfake / undress-AI / voyeur sites (illegal or non-consensual content). */
    private val ILLEGAL_NAMES = setOf(
        "thothub", "coomer", "kemono", "simpcity", "erome", "mrdeepfakes", "nudostar", "celebjihad", "leakedbb",
        "leakedzone", "thefappening", "clothoff", "deepnude", "nudify", "undressher", "desimms", "leakedmms"
    )
    private val ILLEGAL_WORDS = listOf("nudify", "deepnude", "undressai", "leakedmms", "mmsscandal", "upskirt", "creepshot")

    /** Names that merely contain an adult-looking word. */
    private val NOT_ADULT = listOf("sporn", "xxxtentacion", "pornstarmartini", "xxxl", "xxxxl")

    /** Big mixed-content platforms: only the address-word rules apply, never the page scan. */
    private val PLATFORMS = setOf(
        "reddit.com", "redd.it", "x.com", "twitter.com", "tumblr.com", "youtube.com", "youtu.be", "facebook.com",
        "instagram.com", "pinterest.com", "telegram.org", "t.me", "discord.com", "imgur.com", "medium.com",
        "quora.com", "blogspot.com", "wordpress.com", "google.com", "bing.com", "duckduckgo.com", "yahoo.com",
        "amazon.com", "amazon.in", "flipkart.com", "play.google.com", "apple.com", "microsoft.com", "linkedin.com"
    )

    /** Words that make a query / address informational, not a request for the content. */
    private val SAFE_CONTEXT = setOf(
        "addiction", "addicted", "quit", "quitting", "recovery", "effects", "harm", "harms", "harmful", "danger",
        "dangers", "block", "blocker", "blockers", "blocking", "filter", "filters", "filtering", "parental",
        "control", "controls", "law", "laws", "legal", "ban", "banned", "court", "police", "arrested", "arrest",
        "education", "educational", "awareness", "prevention", "research", "study", "studies", "statistics",
        "news", "report", "reports", "reporting", "therapy", "therapist", "counseling", "health", "symptoms",
        "protect", "protection", "protecting", "safety", "safe", "detox", "nofap", "abuse", "victim", "victims",
        "survivor", "survivors", "trafficking", "rights", "consent", "policy", "policies", "guidelines", "warning",
        "warnings", "verification", "rating", "ratings", "support", "help", "art", "painting", "paintings",
        "sculpture", "museum", "renaissance", "definition", "meaning", "history", "documentary"
    )

    /** Words that show someone wants the content itself; they override [SAFE_CONTEXT]. */
    private val INTENT = setOf(
        "free", "watch", "download", "downloads", "stream", "streaming", "hd", "4k", "1080p", "live", "full",
        "leaked", "leaks", "tube", "cam", "cams"
    )

    private val SEXY_NEXT = setOf("video", "videos", "bf", "movie", "movies", "scene", "scenes", "clip", "clips")
    private val BF_NEXT = setOf("video", "videos", "film", "films", "movie", "movies", "hd")
    private val DESI_NEXT = setOf("sex", "mms", "nude", "nudes", "chudai", "xxx")
    private val MMS_NEXT = setOf("video", "videos", "clip", "clips", "scandal", "leaked", "viral")
    private val ESCORT_NEXT = setOf("service", "services", "girl", "girls", "agency", "agencies")
    private val DEEPFAKE_NEXT = setOf("porn", "nude", "nudes", "celeb", "celebs", "sex", "xxx")
    private val UNDRESS_NEXT = setOf("ai", "app", "apps", "photo", "photos", "girl", "girls", "anyone")

    private val SOLO_WORDS = setOf(
        "porn", "porno", "pornos", "pornstar", "pornstars", "xxx", "hentai", "nsfw", "blowjob", "handjob",
        "bukkake", "creampie", "gangbang", "milf", "camgirl", "camgirls", "onlyfans", "chudai", "chudayi",
        "nudify", "deepnude", "upskirt", "creepshot", "creepshots", "callgirl", "callgirls",
        "desisex", "desiporn", "indiansex", "indianporn", "antarvasna", "savitabhabhi", "velamma",
        "redgifs", "fapello", "stripchat", "chaturbate", "bongacams", "xvideos", "xnxx", "xhamster",
        "pornhub", "spankbang", "nhentai", "hanime", "rule34", "deepfakes", "undressai"
    )
    private val DESI_PAIR_NEXT = setOf("sex", "porn", "xxx", "chudai", "mms", "nude", "nudes", "bf")
    private val HOT_NEXT = setOf("bhabhi", "aunty", "sex", "porn", "xxx", "mms", "nude", "nudes")
    private val GANDI_NEXT = setOf("video", "videos", "film", "films", "baat", "chudai", "sex", "tasveer", "photo", "photos")
    private val REDDIT_SUB_RE = Regex("^/r/([a-z0-9_]+)")
    private val NSFW_SUBS = setOf(
        "nsfw", "gonewild", "realgirls", "nsfwhardcore", "nsfw_gif", "nsfw_gifs", "boobs", "ass", "pawg",
        "milf", "hentai", "rule34", "ecchi", "nudes", "onlyfans101", "indianbabes", "desibabes", "indiansgonewild"
    )

    private val SEX_NEXT = setOf(
        "video", "videos", "movie", "movies", "tube", "cam", "cams", "chat", "clip", "clips", "scene", "scenes",
        "story", "stories", "pics", "photos", "images", "gif", "gifs"
    )
    private val ADULT_NEXT = setOf("video", "videos", "movie", "movies", "site", "sites", "content", "tube", "cam", "cams")
    private val BODY_NEXT = setOf("girl", "girls", "women", "woman", "pics", "photos", "images", "video", "videos", "selfie", "selfies", "celeb", "celebs")

    private val QUERY_PARAMS = setOf(
        "q", "query", "search", "s", "k", "p", "text", "keyword", "keywords", "term", "wd", "kw",
        "search_query", "searchterm", "find", "qs"
    )

    private val ALLOWLIST = setOf(
        "wikipedia.org", "wikimedia.org", "wiktionary.org", "britannica.com", "dictionary.com", "merriam-webster.com",
        "who.int", "nih.gov", "cdc.gov", "nhs.uk", "healthline.com", "webmd.com", "mayoclinic.org", "plannedparenthood.org",
        "bbc.com", "bbc.co.uk", "nytimes.com", "theguardian.com", "reuters.com", "apnews.com", "washingtonpost.com",
        "thehindu.com", "ndtv.com", "indiatimes.com", "hindustantimes.com", "indianexpress.com", "livemint.com",
        "github.com", "stackoverflow.com", "stackexchange.com", "gov.in", "nic.in",
        "yourbrainonporn.com", "fightthenewdrug.org", "rainn.org", "childhelp.org", "missingkids.org", "iwf.org.uk",
        "stopitnow.org", "thorn.org", "internetmatters.org", "commonsensemedia.org", "kidshealth.org",
        "medlineplus.gov", "clevelandclinic.org", "harvard.edu", "stanford.edu", "mit.edu", "ox.ac.uk", "cam.ac.uk",
        "ac.in", "edu.in", "scribd.com", "archive.org", "goodreads.com"
    )

    /**
     * Page scan, run once when a page has finished loading (top frame only). Asks the app to block the page when
     * it carries the RTA adult label, an explicit "adult" rating, or enough adult terms in its title / description /
     * keywords / headline: strong terms count 3, medium 2, weak 1, each distinct term once. It needs at least one
     * strong term and a total of 7; any health / news / legal / safety word raises the bar (10, then 14), so
     * reports, research and parental-control pages are left alone. Weak words alone never block.
     * Health / news hosts and big mixed platforms are exempted on the app side ([skipsPageScan]).
     */
    const val SCAN_JS = """(function(){try{
if(!window.AurexAdult)return;
var curUrl=location.href.split('#')[0];
if(window.__aurexAdultUrl===curUrl)return;
window.__aurexAdultUrl=curUrl;
function meta(a,n){try{var m=document.querySelector('meta['+a+'="'+n+'" i]');return m?String(m.getAttribute('content')||''):'';}catch(e){return '';}}
var rt=(meta('name','rating')+' '+meta('name','classification')+' '+meta('name','rta')).toLowerCase();
if(rt.indexOf('rta-5042-1996-1400-1577-rta')>=0||(/\badult\b|\bmature\b|\brestricted\b/.test(rt)&&!/\bnot adult\b|\bgeneral\b|\bsafe\b/.test(rt))){window.AurexAdult.flag('rating');return;}
try{
 var lds=document.querySelectorAll('script[type="application/ld+json"]');
 for(var li=0;li<lds.length&&li<6;li++){
  var lt=(lds[li].textContent||'').toLowerCase();
  if(/"isfamilyfriendly"\s*:\s*(false|"false")/.test(lt)){window.AurexAdult.flag('ldjson');return;}
 }
}catch(e){}
var h1='';try{var e1=document.querySelector('h1');if(e1)h1=String(e1.textContent||'').slice(0,180);}catch(e){}
var bodyPeek='';try{if(document.body)bodyPeek=String(document.body.innerText||'').slice(0,600);}catch(e){}
var t=[document.title,meta('name','description'),meta('name','keywords'),meta('property','og:title'),meta('property','og:description'),h1].join(' ').toLowerCase();
var S=/\b(porn|porno|pornstars?|xxx|hentai|blowjob|handjob|bukkake|creampie|gangbang|camgirls?|live sex|sex videos?|sex tube|adult videos?|leaked mms|desi mms|mms scandal|nudify|deepnude|upskirt|onlyfans leaks?|chudai|desi sex|indian porn|free porn)\b/g;
var M=/\b(nsfw|milf|nudes?|naked|fetish|erotic|free sex|hot sex|sex chat|sexcam|underage|leaked nudes?|adult content|sexually explicit|18\+ only|must be 18)\b/g;
var K=/\b(hardcore|escorts?|call girls?|sexy|adult entertainment|mature audience)\b/g;
function sc(re,w,src){var seen={},s=0,m;re.lastIndex=0;while((m=re.exec(src))!==null){var k=m[1];if(!seen[k]){seen[k]=1;s+=w;}}return s;}
var a=sc(S,3,t),b=sc(M,2,t),c=sc(K,1,t);
var gate=/(18\+|18 years|age verification|i am 18|enter only if you are 18)/i.test(bodyPeek)&&/(porn|adult|explicit|xxx|sexual|nsfw)/i.test(bodyPeek);
if(gate){window.AurexAdult.flag('agegate');return;}
if(a<3)return;
var sf=t.match(/\b(health|education|educational|news|research|study|doctor|medical|policy|law|legal|court|police|report|awareness|prevention|addiction|recovery|therapy|parental|block|blocker|filter|safety|victims?|survivors?|abuse|trafficking|rights|consent|museum|history)\b/g)||[];
var sd={},n=0;for(var i=0;i<sf.length;i++){if(!sd[sf[i]]){sd[sf[i]]=1;n++;}}
var need=7+(n>=1?3:0)+(n>=2?4:0);
if(a+b+c>=need)window.AurexAdult.flag('terms');
}catch(e){}})();"""
}
