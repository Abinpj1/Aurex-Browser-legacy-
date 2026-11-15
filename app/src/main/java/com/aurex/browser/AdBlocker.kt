package com.aurex.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-source ad blocker:
 *  - FilterEngine: EasyList (incl. its adult-site ad sections), EasyPrivacy, uBlock filters + badware,
 *    AdGuard Base + Mobile Ads - real Adblock-Plus syntax rules and cosmetic filters, cached, refreshed every 2 days
 *  - bundled hosts + StevenBlack list + YoYo ad-servers list (cached, weekly refresh)
 *  - URL pattern rules for trackers/analytics
 *  - cosmetic CSS (hides empty ad slots)
 *  - YouTube network + DOM rules that skip and hide pre/mid-roll ads
 */
class AdBlocker(private val ctx: Context) {
    private val hosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cache = File(ctx.filesDir, "ab_hosts.txt")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    val engine = FilterEngine()

    var hostCount by mutableIntStateOf(0)
        private set

    /** Network + cosmetic rules currently active in the filter engine. */
    var ruleCount by mutableIntStateOf(0)
        private set

    /** When the downloaded filter lists were last refreshed (0 = never). */
    var lastUpdate by mutableLongStateOf(0L)
        private set

    var updating by mutableStateOf(false)
        private set

    /** Runs on the main thread each time the rule set was rebuilt (tabs re-arm their start scripts). */
    @Volatile var onEngineReady: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    private fun listFile(l: FilterList) = File(ctx.filesDir, "fl_${l.id}.txt")

    init {
        runCatching {
            ctx.assets.open("hosts.txt").bufferedReader().useLines { lines ->
                lines.forEach { parseHostsLine(it) }
            }
        }
        BUILTIN.split('|').forEach { addHost(it) }
        hostCount = hosts.size
        // The cached lists are large; parsing them here used to block app start.
        scope.launch {
            if (cache.exists()) {
                runCatching {
                    cache.bufferedReader().useLines { lines -> lines.forEach { parseHostsLine(it) } }
                }
                hostCount = hosts.size
            }
            rebuildEngine()
            refresh(false)
        }
    }

    fun refresh(force: Boolean = false) {
        scope.launch {
            if (!busy.compareAndSet(false, true)) return@launch
            try {
                updating = true
                val now = System.currentTimeMillis()
                if (force || !cache.exists() || now - cache.lastModified() >= 7L * 86400_000L) {
                    val out = StringBuilder()
                    LISTS.forEach { url ->
                        runCatching {
                            val req = Request.Builder().url(url)
                                .header("User-Agent", "Mozilla/5.0 AurexBrowser/2.0")
                                .build()
                            http.newCall(req).execute().use { r ->
                                if (r.isSuccessful) r.body?.string()?.let { out.append(it).append('\n') }
                            }
                        }
                    }
                    if (out.isNotEmpty()) {
                        cache.writeText(out.toString())
                        out.lineSequence().forEach { parseHostsLine(it) }
                        hostCount = hosts.size
                    }
                }
                var changed = false
                for (l in FILTER_LISTS) {
                    val f = listFile(l)
                    if (!force && f.exists() && now - f.lastModified() < 2L * 86400_000L) continue
                    if (download(l.url, f)) changed = true
                }
                if (changed) rebuildEngine()
            } finally {
                updating = false
                busy.set(false)
            }
        }
    }

    /** Downloads one list to disk (streamed, 304-aware). Returns true when new content was stored. */
    private fun download(url: String, f: File): Boolean {
        return try {
            val rb = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 AurexBrowser/2.0")
            if (f.exists() && f.length() > 2000) rb.header("If-Modified-Since", httpDate(f.lastModified()))
            http.newCall(rb.build()).execute().use { r ->
                if (r.code == 304) {
                    f.setLastModified(System.currentTimeMillis())
                    return@use false
                }
                if (!r.isSuccessful) return@use false
                val body = r.body ?: return@use false
                val tmp = File(f.parentFile, f.name + ".tmp")
                var total = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total > MAX_LIST_BYTES) break
                        }
                    }
                }
                val ok = total in 2000..MAX_LIST_BYTES && looksLikeList(tmp)
                if (ok) {
                    f.delete()
                    tmp.renameTo(f)
                } else tmp.delete()
                ok
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun looksLikeList(f: File): Boolean = runCatching {
        f.bufferedReader().use { r ->
            val head = CharArray(400)
            val n = r.read(head)
            if (n <= 0) false else !String(head, 0, n).trimStart().startsWith("<")
        }
    }.getOrDefault(false)

    private fun httpDate(t: Long): String {
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return f.format(java.util.Date(t))
    }

    private fun rebuildEngine() {
        val inputs = ArrayList<() -> java.io.InputStream?>()
        inputs.add { ctx.assets.open("filters_builtin.txt") }
        var newest = 0L
        for (l in FILTER_LISTS) {
            val f = listFile(l)
            if (f.exists()) {
                inputs.add { f.inputStream() }
                if (f.lastModified() > newest) newest = f.lastModified()
            }
        }
        engine.rebuild(inputs)
        ruleCount = engine.ruleCount
        lastUpdate = newest
        val cb = onEngineReady
        if (cb != null) main.post { cb() }
    }

    private fun parseHostsLine(raw: String) {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return
        if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1")) {
            line = line.substringAfter(' ').trim()
        }
        if (line.startsWith("||")) {
            line = line.removePrefix("||").substringBefore('^').substringBefore('/')
        }
        if (line.isEmpty() || !line.contains('.') || line.contains(' ') || line.contains('/')) return
        if (line.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return
        addHost(line)
    }

    private fun addHost(domain: String) {
        val h = domain.trim().lowercase().removePrefix("www.").trim('.')
        if (h.isNotEmpty() && h.contains('.')) hosts.add(h)
    }

    /** Checks host plus every parent domain: ads.youtube.com -> youtube.com. */
    fun blocks(host: String): Boolean {
        var h = host.lowercase().removePrefix("www.")
        while (h.contains('.')) {
            if (h in hosts) return true
            h = h.substringAfter('.')
        }
        return false
    }

    private val urlCache = android.util.LruCache<String, Boolean>(2500)

    fun blocksUrl(url: String): Boolean {
        urlCache.get(url)?.let { return it }
        val r = URL_RULES.any { it.containsMatchIn(url) }
        urlCache.put(url, r)
        return r
    }

    /** Main-frame redirect / popup check: the target of such a jump is by definition a third party. */
    fun shouldBlock(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host ?: "" }.getOrDefault("")
        if (host.isNotEmpty() && engine.check(url, host, "", 0, true) == 1) return true
        return (host.isNotEmpty() && blocks(host)) || blocksUrl(url)
    }

    /**
     * Sub-resource check. The filter lists decide first (an @@ exception beats the older heuristics,
     * so a site the lists whitelist is not broken by them); then the host list / URL patterns.
     */
    fun shouldBlockRequest(url: String, reqHost: String, pageHost: String, type: Int): Boolean {
        if (isEssential(reqHost)) return false
        val r = engine.check(url, reqHost, pageHost, type, false)
        if (r == 1) return true
        if (r == 2) return false
        return blocks(reqHost) || blocksUrl(url)
    }

    /** Captcha, login and payment hosts: a page cannot work without them, so no list or heuristic may block them. */
    private fun isEssential(host: String): Boolean {
        val h = host.lowercase()
        return ESSENTIAL.any { h == it || h.endsWith(".$it") }
    }

    /** Best guess of the resource type from the file extension / Accept header (0 = unknown). */
    fun inferType(url: String, accept: String?): Int {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        when (path.substringAfterLast('.', "")) {
            "js", "mjs" -> return FilterEngine.T_SCRIPT
            "css" -> return FilterEngine.T_CSS
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> return FilterEngine.T_IMAGE
            "mp4", "webm", "m3u8", "mp3", "m4s", "m4a", "mpd", "ogg", "aac" -> return FilterEngine.T_MEDIA
            "woff", "woff2", "ttf", "otf", "eot" -> return FilterEngine.T_FONT
        }
        if (accept != null) {
            if (accept.contains("text/html")) return FilterEngine.T_SUBDOC
            if (accept.startsWith("image/")) return FilterEngine.T_IMAGE
            if (accept.contains("text/css")) return FilterEngine.T_CSS
        }
        return 0
    }

    /** Document-start script with the generic cosmetic filters of the downloaded lists. */
    fun genericScript(): String = engine.genericScript()

    /** Script that hides this site's own ad containers (domain-specific cosmetic filters), or null. */
    fun hostCosmeticJs(host: String): String? {
        val css = engine.hostCss(host)
        if (css.isEmpty()) return null
        return "(function(){try{var s=document.getElementById('lq-cos');if(s)s.remove();" +
            "s=document.createElement('style');s.id='lq-cos';s.textContent=" + JSONObject.quote(css) +
            ";(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
    }

    private class FilterList(val id: String, val url: String)

    companion object {
        private val ESSENTIAL = listOf(
            "recaptcha.net", "hcaptcha.com", "challenges.cloudflare.com", "gstatic.com", "accounts.google.com",
            "login.microsoftonline.com", "appleid.apple.com", "razorpay.com", "paytm.com", "phonepe.com", "stripe.com",
            "stripe.network", "paypal.com", "payu.in", "cashfree.com", "billdesk.com", "juspay.in", "ccavenue.com"
        )
        private const val MAX_LIST_BYTES = 40L * 1024 * 1024

        /** Real-world filter lists (Adblock-Plus syntax). EasyList already contains the adult-site ad sections. */
        private val FILTER_LISTS = listOf(
            FilterList("easylist", "https://easylist.to/easylist/easylist.txt"),
            FilterList("easyprivacy", "https://easylist.to/easylist/easyprivacy.txt"),
            FilterList("ubo_filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt"),
            FilterList("ubo_badware", "https://ublockorigin.github.io/uAssets/filters/badware.txt"),
            FilterList("ubo_unbreak", "https://ublockorigin.github.io/uAssets/filters/unbreak.txt"),
            FilterList("adg_base", "https://filters.adtidy.org/extension/ublock/filters/2.txt"),
            FilterList("adg_mobile", "https://filters.adtidy.org/extension/ublock/filters/11.txt")
        )

        private val LISTS = listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"
        )

        private val URL_RULES = listOf(
            Regex("""/pagead/|/adsbygoogle|/adserver|/adservice|/get_midroll|/api/stats/ads""", RegexOption.IGNORE_CASE),
            Regex("""[-_./](ad|ads|adv|advert|advertisement|sponsor|sponsored)[-_./=]""", RegexOption.IGNORE_CASE),
            Regex("""[-_./](track|tracker|tracking|pixel|beacon|telemetry|analytics|collect|metrics)[-_./=]""", RegexOption.IGNORE_CASE),
            Regex("""[-_./](popunder|popup|interstitial|prebid|bidder|rtb|impression|clicktrack|clickid|affiliate)[-_./=]""", RegexOption.IGNORE_CASE),
            Regex("""\.(gif|png|jpg)(\?|#)?.*(utm_|ad_|adid|click|impression|track)""", RegexOption.IGNORE_CASE),
            Regex("""(doubleclick|googlesyndication|googleadservices|googleads|adnxs|taboola|outbrain|popads|propellerads|exoclick|adsterra|amazon-adsystem|adsrvr|rubiconproject|pubmatic|openx|criteo|moatads|teads|adform|yieldmo|smartadserver)\.(net|com|org|io)""", RegexOption.IGNORE_CASE),
            Regex("""(youtube\.com|youtube-nocookie\.com)/(pagead|get_midroll_info|api/stats/ads|pcs/activeview|api/stats/atr|ptracking)""", RegexOption.IGNORE_CASE),
            Regex("""youtube\.com/api/stats/ads|youtube\.com/pagead/""", RegexOption.IGNORE_CASE),
            Regex("""(googleads\.g\.doubleclick\.net|securepubads\.g\.doubleclick\.net|static\.doubleclick\.net|adservice\.google\.|ads\.youtube\.com|imasdk\.googleapis\.com|s0\.2mdn\.net|static\.2mdn\.net)""", RegexOption.IGNORE_CASE),
            Regex("""(/vast(/|\.xml|\?)|/vpaid|/adrule|/adrequest|/adcall|/adsystem|/adunit|/adserver/|/openads/)""", RegexOption.IGNORE_CASE),
            Regex("""facebook\.com/tr|connect\.facebook\.net/.*/sdk|analytics\.twitter\.com|scorecardresearch\.com|quantserve\.com|chartbeat\.com""", RegexOption.IGNORE_CASE),
            Regex("""(googletagmanager|google-analytics|hotjar|mixpanel|segment\.io|amplitude|fullstory|smartlook|mouseflow|luckyorange|clarity\.ms|bugsnag|sentry\.io)""", RegexOption.IGNORE_CASE),
            Regex("""(adclick|adredirect|redirector|redir\.|/click\?|/clk\?|/go\?|aff_id=|affid=|utm_campaign=ad)""", RegexOption.IGNORE_CASE),
            Regex("""(syndication|feedads|adzone|adframe|adbox|banner_ad|sponsorbox|sponsor-box)""", RegexOption.IGNORE_CASE)
        )

        private const val BUILTIN =
            "doubleclick.net|googlesyndication.com|googleadservices.com|adservice.google.com|adnxs.com|" +
            "taboola.com|outbrain.com|popads.net|propellerads.com|exoclick.com|adsterra.com|" +
            "amazon-adsystem.com|facebook.com/tr|scorecardresearch.com|quantserve.com|chartbeat.com|" +
            "criteo.com|criteo.net|pubmatic.com|rubiconproject.com|openx.net|appnexus.com|" +
            "moatads.com|adsrvr.org|smartadserver.com|yieldmo.com|teads.tv|adform.net|" +
            "bing.com/bat.js|clarity.ms|hotjar.com|mixpanel.com|segment.io|amplitude.com|" +
            "yandex.ru/ads|mail.ru/ads|ads.youtube.com|pagead2.googlesyndication.com|" +
            "google-analytics.com|googletagmanager.com|googletagservices.com|" +
            "imasdk.googleapis.com|s0.2mdn.net|static.2mdn.net|ad.doubleclick.net|" +
            "adcolony.com|applovin.com|unityads.unity3d.com|vungle.com|chartboost.com|" +
            "inmobi.com|mopub.com|startapp.com|tapjoy.com|supersonicads.com|ironsrc.com|" +
            "popcash.net|popmyads.com|adcash.com|clickadu.com|hilltopads.net|" +
            "mgid.com|revcontent.com|zemanta.com|adroll.com|perfectaudience.com|" +
            "sharethrough.com|triplelift.com|indexexchange.com|casalemedia.com|" +
            "contextweb.com|gumgum.com|sovrn.com|33across.com|districtm.io|" +
            "advertising.com|bluekai.com|demdex.net|krxd.net|exelator.com|" +
            "everesttech.net|agkn.com|rlcdn.com|tapad.com|drawbridge.com|" +
            "mathtag.com|turn.com|simpli.fi|zeotap.com|" +
            "bidswitch.net|adskeeper.com|media.net|adroll.com|outbrain.com|" +
            "natpal.com|clickcertain.com|trafficjunky.net|juicyads.com|" +
            "adnium.com|trafficstars.com|tsyndicate.com|adspyglass.com|" +
            "1rx.io|360yield.com|3lift.com|4info.com|a-mo.net|a3cloud.net|" +
            "ad-delivery.net|ad-score.com|ad-stir.com|adap.tv|adblade.com|" +
            "adcolony.com|addthis.com|adhigh.net|adition.com|adjuggler.com|" +
            "adlooxtracking.com|admantx.com|admarketplace.net|admedo.com|" +
            "admeta.com|admob.com|adnami.io|adobe-dcfs.com|adotmob.com|" +
            "adperium.com|adpushup.com|adquantum.com|adrta.com|adsafeprotected.com|" +
            "adsnative.com|adspeed.net|adswizz.com|adtech.de|adtechus.com|" +
            "adtelligent.com|advertising.com|advertserve.com|adview.cn|" +
            "adx1.com|adxpose.com|adzerk.net|affec.tv|affili.net|" +
            "aggregateknowledge.com|agkn.com|aimatch.com|airpush.com|" +
            "alexametrics.com|alooma.com|am15.net|amazon-adsystem.com|" +
            "ambient-platform.com|amobee.com|aniview.com|apester.com|" +
            "appier.net|appnexus.com|apptornado.com|aralego.com|" +
            "areyouahuman.com|atdmt.com|atwola.com|audienceinsights.net|" +
            "audiencemanager.de|audiencerate.com|avazutracking.net|" +
            "awesomeapi.com|awin1.com|ayads.co|b2c.com|bannerflow.com|" +
            "bat.bing.com|bebi.com|beeswax.com|betweendigital.com|" +
            "bidr.io|bidswitch.net|bidtheatre.com|bidvertiser.com|" +
            "bluekai.com|bnmla.com|bombora.com|bongacams.com|" +
            "boo-box.com|bttrack.com|buysellads.com|buzzoola.com|" +
            "c1exchange.com|calltrk.com|carbonads.net|casalemedia.com|" +
            "cedexis.com|chango.com|channeladvisor.com|chartbeat.com|" +
            "chitika.net|clarity.ms|clickagy.com|clickbooth.com|" +
            "clickcertain.com|clickfuse.com|clickinc.com|clicksor.com|" +
            "clicktale.net|clicky.com|cluep.com|cobaltgroup.com|" +
            "cogocast.net|colossusssp.com|connatix.com|connexity.net|" +
            "contextweb.com|conversantmedia.com|convertro.com|cquotient.com|" +
            "crazyegg.com|creafi-online-media.com|criteo.com|criteo.net|" +
            "crwdcntrl.net|ctnsnet.com|cxense.com|dable.io|" +
            "de17a.com|deepintent.com|demdex.net|deployads.com|" +
            "dgmatix.com|distictive.com|districtm.io|dmtry.com|" +
            "domdex.com|dotomi.com|doubleclick.net|dsp.io|" +
            "dsply.com|e-planning.net|e-volution.ai|ebz.io|" +
            "edadvertising.net|effectivemeasure.net|emxdgt.com|" +
            "engagebdr.com|erne.co|exponential.com|" +
            "eyeota.net|facebook.com/tr|fastclick.net|fatchilli.media|" +
            "fbcdn.net/tr|flashtalking.com|fout.jp|freewheel.tv|" +
            "fwmrm.net|genieessp.com|getintent.com|gigya.com|" +
            "gmossp-sp.jp|googleadservices.com|googlesyndication.com|" +
            "gumgum.com|haloscan.com|heapanalytics.com|hilltopads.net|" +
            "histats.com|hotjar.com|hs-analytics.net|hs-scripts.com|" +
            "ibillboard.com|im-apps.net|imrworldwide.com|inmobi.com|" +
            "innity.com|innovid.com|insightexpressai.com|" +
            "instinctiveads.com|intellitxt.com|intergient.com|" +
            "intermarkets.net|interworksmedia.com|ipredictive.com|" +
            "ispot.tv|jads.co|jivox.com|juicyads.com|" +
            "justpremium.com|kargo.com|keywee.co|klaviyo.com|" +
            "krxd.net|ladsp.com|ligatus.com|linkprice.com|" +
            "listrakbi.com|liveramp.com|loopme.me|lqcdn.com|" +
            "luckyorange.com|luminate.com|madadsmedia.com|" +
            "marfeel.com|marketo.net|mathtag.com|mdotm.com|" +
            "media.net|media6degrees.com|mediaplex.com|mediamath.com|" +
            "mediavine.com|mgid.com|microad.jp|" +
            "mixpanel.com|mookie1.com|moatads.com|mobclix.com|" +
            "mobpro.com|monetate.net|mopub.com|mydas.mobi|" +
            "nativeads.com|navdmp.com|nend.net|" +
            "netmng.com|neustar.biz|newrelic.com|nextmillennium.io|" +
            "nuffnang.com.my|nzme-ads.co.nz|oath.com|omappapi.com|" +
            "omnitagjs.com|onaudience.com|onclickads.net|onead.com.tw|" +
            "onetag-sys.com|openx.net|openxenterprise.com|optmnstr.com|" +
            "optmstr.com|outbrain.com|outbrainimg.com|owneriq.net|" +
            "pagefair.com|parsely.com|perfectaudience.com|permutive.com|" +
            "petametrics.com|pippio.com|pixel.ad|platform161.com|" +
            "playercdn.net|plista.com|popads.net|popcash.net|" +
            "popmyads.com|poptm.com|presage.io|pro-market.net|" +
            "projectwonderful.com|propellerads.com|pubmatic.com|" +
            "pubmine.com|pulsepoint.com|quadas-us.com|quantserve.com|" +
            "quantum-advertising.com|recreativ.ru|" +
            "reklamport.com|revcontent.com|revenuehits.com|" +
            "revsci.net|rfihub.com|rlcdn.com|rnmd.net|" +
            "rocketfuel.com|roq.ad|rtbhouse.com|rubiconproject.com|" +
            "sape.ru|sc-static.net|scorecardresearch.com|searchforce.net|" +
            "segment.io|serving-sys.com|sharethrough.com|siftscience.com|" +
            "simpli.fi|sizmek.com|skimresources.com|smaato.net|" +
            "smartadserver.com|smartclip.net|smartyads.com|snapads.com|" +
            "socdm.com|sonobi.com|sovrn.com|sponsorads.de|" +
            "spotxchange.com|springserve.com|stackadapt.com|" +
            "stickyadstv.com|storygize.net|supersonicads.com|" +
            "survata.com|syncad.net|taboola.com|tapad.com|" +
            "tapjoy.com|teads.tv|tealiumiq.com|telaria.com|" +
            "themoneytizer.com|tns-counter.ru|tradedoubler.com|" +
            "tradehouse.media|trafficjunky.net|trafficstars.com|" +
            "tribalfusion.com|triplelift.com|trkkn.com|tsyndicate.com|" +
            "turn.com|twiago.com|uberads.com|undertone.com|" +
            "unrulymedia.com|usemax.de|ve.com|veruta.com|" +
            "vidoomy.com|vidora.com|viewbix.com|vimond.com|" +
            "vindicosuite.com|visualrevenue.com|vungle.com|" +
            "weborama.fr|webtrends.com|widespace.com|" +
            "wt-eu02.net|wunderloop.net|xaxis.com|yieldlab.net|" +
            "yieldmo.com|yieldoptimizer.com|yldbt.com|" +
            "yumenetworks.com|zemanta.com|zeotap.com|zergnet.com|" +
            "zqtk.net|zxcdn.com|exosrv.com|exdynsrv.com|realsrv.com|magsrv.com|adtng.com|" +
            "trafficjunky.com|trafficfactory.biz|ero-advertising.com|plugrush.com|adxpansion.com|popunder.net"

        // Cosmetic hiding + MutationObserver. Kept narrow so normal content stays visible.
        const val HIDE_JS = """(function(){
if(window.__liquidAdStyle)return;window.__liquidAdStyle=1;
var s=document.createElement('style');s.id='liquid-adblock';
s.textContent='ins.adsbygoogle,[data-ad-slot],[data-ad-client],[id^="google_ads"],[id^="div-gpt-ad"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],iframe[src*="/ads/"],.adsbygoogle,[class*="advert"],[class*="sponsored"],[id*="sponsored"],[class*="taboola"],[class*="outbrain"],[id*="taboola"],[id*="outbrain"],amp-ad,amp-sticky-ad,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer{display:none!important;height:0!important;max-height:0!important;overflow:hidden!important}';
(document.head||document.documentElement).appendChild(s);
var kill=function(root){
  if(!root||!root.querySelectorAll)return;
  root.querySelectorAll('[class*="advert"],[id*="advert"],[class*="sponsored"],[id*="sponsored"],[class*="taboola"],[class*="outbrain"]').forEach(function(e){
    var t=(e.textContent||'').trim();
    if(t.length<3||e.querySelector('video,article,p'))e.style.display='none';
  });
};
kill(document);
var q=[],sch=0,idle=window.requestIdleCallback||function(f){return setTimeout(f,120);};
function flush(){
  sch=0;var n,c=0;
  while(c++<40&&(n=q.shift())){
    if(!n.isConnected)continue;
    kill(n);
    var cl=(typeof n.className==='string'?n.className:'')+' '+(n.id||'');
    if(/adsbygoogle|sponsored|taboola|outbrain|advert/i.test(cl))n.style.display='none';
  }
  if(q.length)sch=idle(flush);
}
try{new MutationObserver(function(ms){
  for(var i=0;i<ms.length;i++){var a=ms[i].addedNodes;for(var j=0;j<a.length;j++){if(a[j].nodeType===1&&q.length<400)q.push(a[j]);}}
  if(!sch&&q.length)sch=idle(flush);
}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})()"""

        // Extra popup/click-hijack guard. Native onCreateWindow already blocks
        // gestureless windows; this also stops scripted ad popunders.
        const val POPUP_JS = """(function(){
if(window.__liquidPopup)return;window.__liquidPopup=1;
var AD=/(doubleclick|googlesyndication|googleadservices|adservice\.google|ads\.youtube|popads|popcash|propellerads|exoclick|adsterra|onclickads|clickadu|hilltopads|adcash|popmyads|trafficjunky|juicyads|tsyndicate|adsterra)\./i;
try{
  var open=window.open;
  window.open=function(u,n,f){
    try{
      var s=String(u||'');
      var ua=navigator.userActivation;
      var active=ua?ua.isActive:false;
      // No gesture means no new window. Advertising URLs are never allowed.
      if(!active)return null;
      if(AD.test(s))return null;
      return open.apply(window,arguments);
    }catch(e){return null;}
  };
}catch(e){}
document.addEventListener('click',function(e){
  try{
    var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;
    if(!a||!a.href)return;
    if(AD.test(a.href)||/\/click\?|\/clk\?|aff_id=|affid=/i.test(a.href)){
      e.preventDefault();e.stopPropagation();
    }
  }catch(e){}
},true);
document.addEventListener('click',function(e){
  try{
    var t=e.target;
    if(!t||t===document.body||t===document.documentElement||!t.tagName)return;
    var tag=t.tagName;
    if(tag!=='A'&&tag!=='IFRAME'&&tag!=='DIV'&&tag!=='SPAN')return;
    var cs=getComputedStyle(t);
    if(cs.position!=='fixed'&&cs.position!=='absolute')return;
    if((parseInt(cs.zIndex,10)||0)<2000000000)return;
    var r=t.getBoundingClientRect();
    if(r.width<innerWidth*0.8||r.height<innerHeight*0.8)return;
    var clear=parseFloat(cs.opacity)<0.08||cs.backgroundColor==='rgba(0, 0, 0, 0)'||cs.backgroundColor==='transparent';
    if(clear&&!(t.innerText||'').trim()&&!t.querySelector('video,img,input,button')){
      e.preventDefault();e.stopPropagation();t.remove();
    }
  }catch(x){}
},true);
document.addEventListener('auxclick',function(e){
  try{
    var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;
    if(a&&a.href&&AD.test(a.href)){e.preventDefault();e.stopPropagation();}
  }catch(e){}
},true);
})()"""

        /**
         * Document-start script (every frame): the smart popup / redirect guard. The page side only GATHERS FACTS
         * (was there a real tap, on what, is the target an invisible full-screen layer, did this tap already open a
         * window) and asks the native side (PopupGuard via AurexAdGuard.popup) for the verdict BEFORE anything opens.
         * Also: removes invisible click-hijack layers on the first tap, hands scripts a harmless stand-in instead of
         * null (null makes ad scripts fall back to redirecting the page), blocks script-made link clicks and
         * form.submit() into new windows, and stops history-stacking back-button traps.
         * Sets window.__liquidPopup so the older POPUP_JS (a late safety net) stays out of the way.
         */
        const val POPUP_GUARD_JS = """(function(){try{
if(window.__aurexPG)return;
var B=window.AurexAdGuard;
if(!B||typeof B.popup!=='function')return;
window.__aurexPG=true;window.__liquidPopup=true;
try{if(B.exempt()===true)return;}catch(e){}
function T(){return Date.now();}
var tap={t:0,last:0,gid:0,used:0,target:null,link:null};
function linkOf(n){try{return n&&n.closest?n.closest('a[href]'):null;}catch(e){return null;}}
function trim(u){var s=String(u||'').split('#')[0].toLowerCase();while(s.length&&s.charAt(s.length-1)==='/')s=s.slice(0,-1);return s;}
function sameHref(a,b){return trim(a)===trim(b);}
function sameOrigin(h){try{return new URL(String(h),location.href).origin===location.origin;}catch(e){return true;}}
function note(k){try{B.note(k);}catch(e){}}

/* Invisible full-screen layer put over the page to catch the first tap (click-hijack / pop-under trigger). */
function isOverlay(el){
 try{
  var n=el;
  for(var i=0;n&&n.nodeType===1&&i<4;i++,n=n.parentElement){
   if(n===document.body||n===document.documentElement)break;
   var cs=getComputedStyle(n);
   if(cs.position!=='fixed'&&cs.position!=='absolute')continue;
   if((parseInt(cs.zIndex,10)||0)<99999)continue;
   var r=n.getBoundingClientRect();
   if(r.width<innerWidth*0.5||r.height<innerHeight*0.5)continue;
   var bg=cs.backgroundColor,clear=parseFloat(cs.opacity)<0.08||bg==='rgba(0, 0, 0, 0)'||bg==='transparent';
   if(!clear)continue;
   if(document.fullscreenElement)continue;
   if(n.querySelector&&n.querySelector('video,audio,canvas,input,textarea,select,button,img,svg'))continue;
   if(n.tagName!=='IFRAME'&&(n.innerText||'').replace(/\s+/g,'')!=='')continue;
   return n;
  }
 }catch(e){}
 return null;
}

/* What the user really touched, recorded before any page script sees the event. */
function mark(e){
 try{
  if(!e.isTrusted)return;
  var t=T();
  if(t-tap.last>1000)tap.gid++;
  tap.last=t;tap.t=t;tap.target=e.target;tap.link=linkOf(e.target);
  var ty=e.type;
  if(ty==='pointerdown'||ty==='mousedown'||ty==='click'){
   var o=isOverlay(e.target);
   if(o){
    e.stopImmediatePropagation();
    if(e.cancelable)e.preventDefault();
    try{o.remove();}catch(x){}
    note(2);
    return;
   }
  }
  if(ty==='click'){
   var a=tap.link,h=a?(a.href||''):'';
   if(a&&/^https?:/i.test(h)&&!sameOrigin(h)){
    if(B.popup(h,4|1|16)===true){
     e.preventDefault();e.stopImmediatePropagation();note(1);return;
    }
   }
   if(a)B.clicked(h);
  }
 }catch(x){}
}
var ev=['pointerdown','mousedown','click','auxclick','keydown'];
for(var i=0;i<ev.length;i++)window.addEventListener(ev[i],mark,{capture:true,passive:false});
window.addEventListener('touchstart',mark,{capture:true,passive:true});
window.addEventListener('touchend',mark,{capture:true,passive:true});

/* Facts for the native side: was it a recent tap, on what, did it already open a window. */
function flagsFor(abs){
 var f=0,ua=navigator.userActivation,recent=(T()-tap.t)<4000;
 if(recent&&(ua?ua.isActive:true))f|=4;
 if(tap.used&&tap.used===tap.gid)f|=8;
 var tg=tap.target;
 if(recent&&tg&&tg.nodeType===1){
  var a=tap.link;
  if(a&&sameHref(a.href,abs)&&!isOverlay(a))f|=17;
  else if(tg.closest&&tg.closest('a[href],button,input,select,summary,label,[role=button],[role=link],[role=menuitem],[role=tab],[onclick]'))f|=1;
  if(isOverlay(tg))f|=2;
 }
 return f;
}
function decide(u){
 try{
  var s=String(u==null?'':u),abs=s;
  if(s!==''){try{abs=new URL(s,location.href).href;}catch(e){}}
  if(B.popup(abs,flagsFor(abs))===true){note(1);return false;}
  tap.used=tap.gid;
  return true;
 }catch(e){return true;}
}
/* A closed-looking null makes ad scripts fall back to redirecting the page, so they get a harmless stand-in. */
function decoy(){
 var f=function(){};
 return {closed:false,opener:null,focus:f,blur:f,close:f,postMessage:f,print:f,stop:f,
  location:{href:'',assign:f,replace:f,reload:f},
  document:{write:f,writeln:f,open:f,close:f,body:{},head:{},documentElement:{},
   createElement:function(){return {style:{},setAttribute:f,appendChild:f};},getElementById:function(){return null;}}};
}
var ro=window.open;
if(typeof ro==='function'){
 window.open=function(u,n){
  var allow=true;
  try{
   var tn=String(n==null?'':n).toLowerCase();
   if(tn!=='_self'&&tn!=='_top'&&tn!=='_parent')allow=decide(u);
  }catch(e){}
  if(!allow)return decoy();
  return ro.apply(window,arguments);
 };
 try{window.open.toString=function(){return 'function open() { [native code] }';};}catch(e){}
}

/* Script-made link clicks (a.click(), dispatchEvent) and form.submit() into a new window. */
function linkOk(a){
 try{
  var h=a.href||'';
  if(!/^https?:/i.test(h)||a.hasAttribute('download'))return true;
  var tg=String(a.target||'').toLowerCase();
  if(tg!=='_blank'&&tg!=='_new'&&sameOrigin(h))return true;
  if(tap.link===a&&(T()-tap.t)<4000)return true;
  return decide(h);
 }catch(e){return true;}
}
try{
 var ac=HTMLAnchorElement.prototype.click;
 HTMLAnchorElement.prototype.click=function(){
  if(!linkOk(this))return;
  return ac.apply(this,arguments);
 };
}catch(e){}
try{
 var de=EventTarget.prototype.dispatchEvent;
 EventTarget.prototype.dispatchEvent=function(x){
  try{if(x&&x.type==='click'&&x.isTrusted===false&&this instanceof HTMLAnchorElement&&!linkOk(this))return false;}catch(e){}
  return de.apply(this,arguments);
 };
}catch(e){}
try{
 var fs=HTMLFormElement.prototype.submit;
 HTMLFormElement.prototype.submit=function(){
  try{
   var tg=String(this.target||'').toLowerCase();
   if((tg==='_blank'||tg==='_new')&&!decide(this.action||location.href))return;
  }catch(e){}
  return fs.apply(this,arguments);
 };
}catch(e){}

/* History trap: a page that stacks history entries by itself so Back never leaves. */
try{
 var ps=history.pushState,hb=[];
 history.pushState=function(){
  try{
   var t=T();
   hb=hb.filter(function(x){return t-x<3000;});
   if(t-tap.t>2000){
    hb.push(t);
    if(hb.length>5)return history.replaceState.apply(history,arguments);
   }
  }catch(e){}
  return ps.apply(history,arguments);
 };
}catch(e){}
}catch(e){}})();"""

        /**
         * Document-start script (every frame). The Android WebView cannot answer a blocked request with a
         * network error - only with an (empty) 200 - and a page that sends the request from JavaScript
         * (fetch / XHR / sendBeacon, i.e. every tracker and every "adblock test") then sees SUCCESS and
         * concludes "not blocked". Here the same decision as the network layer (AurexAdGuard.blocked ->
         * AdBlocker.shouldBlockRequest) is applied BEFORE the request is sent, and the page gets what
         * uBlock gives it: fetch() rejects with a TypeError, XHR / <script> / <img> fail with a real
         * error event (they are pointed at a revoked blob: URL, which never touches the network).
         * Same-origin requests are skipped (the network layer still filters those).
         */
        const val NET_GUARD_JS = """(function(){try{
if(window.__aurexNetGuard)return;
var B=window.AurexAdGuard;
if(!B||typeof B.blocked!=='function')return;
window.__aurexNetGuard=true;
var seen={},cnt=0,dead='';
try{var bu=URL.createObjectURL(new Blob(['']));URL.revokeObjectURL(bu);dead=bu;}catch(e){}
function absUrl(u){try{return new URL(String(u),location.href).href;}catch(e){return '';}}
function blk(u,kind){
 try{
  var a=absUrl(u);
  if(a.charCodeAt(0)!==104)return false;
  var o=location.origin;
  if(o&&o!=='null'&&a.indexOf(o+'/')===0)return false;
  var k=kind+'|'+a,c=seen[k];
  if(c!==undefined)return c;
  var r=B.blocked(a,kind)===true;
  if(cnt++>3000){seen={};cnt=0;}
  seen[k]=r;
  return r;
 }catch(e){return false;}
}
var F=window.fetch;
if(typeof F==='function'){
 var nf=function(i,init){
  var hit=false;
  try{hit=blk((i&&typeof i==='object'&&typeof i.url==='string')?i.url:i,8);}catch(e){}
  if(hit)return Promise.reject(new TypeError('Failed to fetch'));
  return F.apply(this,arguments);
 };
 try{nf.toString=function(){return 'function fetch() { [native code] }';};}catch(e){}
 window.fetch=nf;
}
var XP=window.XMLHttpRequest&&XMLHttpRequest.prototype;
if(XP&&dead){
 var oo=XP.open;
 XP.open=function(m,u){
  var a=arguments,hit=false;
  try{hit=blk(u,8);}catch(e){}
  if(hit){a=Array.prototype.slice.call(arguments);a[1]=dead;}
  return oo.apply(this,a);
 };
}
var NB=navigator.sendBeacon;
if(typeof NB==='function'){
 navigator.sendBeacon=function(u){
  var hit=false;
  try{hit=blk(u,8);}catch(e){}
  if(hit)return false;
  return NB.apply(navigator,arguments);
 };
}
function guardSrc(proto,kind){
 try{
  var d=Object.getOwnPropertyDescriptor(proto,'src');
  if(!d||!d.set||!dead)return;
  Object.defineProperty(proto,'src',{configurable:true,enumerable:d.enumerable,get:d.get,set:function(v){
   var hit=false;
   try{hit=blk(v,kind);}catch(e){}
   d.set.call(this,hit?dead:v);
  }});
 }catch(e){}
}
guardSrc(HTMLScriptElement.prototype,1);
guardSrc(HTMLImageElement.prototype,2);
var SA=Element.prototype.setAttribute;
Element.prototype.setAttribute=function(n,v){
 try{
  if(dead&&typeof n==='string'&&n.length===3&&n.toLowerCase()==='src'){
   var t=this.tagName;
   if((t==='SCRIPT'&&blk(v,1))||(t==='IMG'&&blk(v,2)))return SA.call(this,n,dead);
  }
 }catch(e){}
 return SA.apply(this,arguments);
};
}catch(e){}})();
"""

        /**
         * Document-start script for YouTube (web, mobile and embeds): removes the ad data from the player /
         * next responses before the player reads it (the same idea as uBlock's json-prune rules), so the
         * pre-roll / mid-roll never starts instead of being skipped after it started. The in-page skipper
         * (YT_JS) stays as the safety net for ads the server stitches into the stream.
         */
        const val YT_PRUNE_JS = """(function(){
try{
var h=location.hostname;
if(!/(^|\.)youtube(-nocookie)?\.com$/.test(h)&&h!=='youtu.be')return;
if(window.__lqYtPrune)return;window.__lqYtPrune=1;
var K=['adPlacements','adSlots','playerAds','adBreakHeartbeatParams'];
function clean(o){
  try{
    if(!o||typeof o!=='object')return o;
    if(Array.isArray(o)){if(o.length<50){for(var j=0;j<o.length;j++)clean(o[j]);}return o;}
    for(var i=0;i<K.length;i++){if(K[i] in o){try{delete o[K[i]];}catch(e){}}}
    if(o.playerResponse)clean(o.playerResponse);
    if(o.response)clean(o.response);
  }catch(e){}
  return o;
}
// Shorts / feed ads are not a key of the response, they are ITEMS of a list (the reel sequence "entries", feed
// "contents"): the ad items are taken out of every list, deep inside the response.
var AK=['adSlotRenderer','promotedSparklesWebRenderer','promotedSparklesTextSearchRenderer','promotedVideoRenderer','compactPromotedVideoRenderer','displayAdRenderer','inFeedAdLayoutRenderer','adBreakServiceRenderer'];
function adItem(x){
  try{
    if(!x||typeof x!=='object')return false;
    var i;
    for(i=0;i<AK.length;i++){if(AK[i] in x)return true;}
    var c=x.richItemRenderer&&x.richItemRenderer.content;
    if(c&&typeof c==='object'){for(i=0;i<AK.length;i++){if(AK[i] in c)return true;}}
    var e=(x.command&&x.command.reelWatchEndpoint)||x.reelWatchEndpoint;
    if(e&&e.adClientParams&&e.adClientParams.isAd)return true;
  }catch(e){}
  return false;
}
function deep(o,d){
  if(!o||typeof o!=='object'||d>16)return;
  if(Array.isArray(o)){
    for(var j=o.length-1;j>=0;j--){if(adItem(o[j]))o.splice(j,1);else deep(o[j],d+1);}
    return;
  }
  for(var i=0;i<K.length;i++){if(K[i] in o){try{delete o[K[i]];}catch(e){}}}
  for(var k in o){
    if(Object.prototype.hasOwnProperty.call(o,k)){var v=o[k];if(v&&typeof v==='object')deep(v,d+1);}
  }
}
function cleanBig(o){try{deep(o,0);}catch(e){}return o;}
var JP=JSON.parse;
JSON.parse=function(){var x=JP.apply(this,arguments);return(x&&typeof x==='object')?clean(x):x;};
try{
  var v;
  Object.defineProperty(window,'ytInitialPlayerResponse',{
    get:function(){return v;},
    set:function(x){v=clean(x);},
    configurable:true
  });
}catch(e){}
try{
  var vd;
  Object.defineProperty(window,'ytInitialData',{
    get:function(){return vd;},
    set:function(x){vd=cleanBig(x);},
    configurable:true
  });
}catch(e){}
var F=window.fetch;
if(typeof F==='function'){
  window.fetch=function(a){
    var p=F.apply(this,arguments);
    try{
      var u=(typeof a==='string')?a:((a&&a.url)||'');
      if(u.indexOf('/youtubei/v1/player')<0&&u.indexOf('/youtubei/v1/next')<0&&
         u.indexOf('/youtubei/v1/get_watch')<0&&u.indexOf('/youtubei/v1/reel/')<0&&
         u.indexOf('/youtubei/v1/browse')<0&&u.indexOf('/youtubei/v1/search')<0)return p;
      return p.then(function(res){
        try{
          return res.clone().text().then(function(t){
            try{
              var j=cleanBig(clean(JP.call(JSON,t)));
              return new Response(JSON.stringify(j),{status:res.status,statusText:res.statusText,headers:res.headers});
            }catch(e){return res;}
          },function(){return res;});
        }catch(e){return res;}
      });
    }catch(e){return p;}
  };
}
}catch(e){}
})();
"""

        const val YT_JS = """(function(){
if(window.__liquidYt)return;window.__liquidYt=1;
var css=document.createElement('style');css.id='liquid-yt';
css.textContent='.ytp-ad-module,.ytp-ad-overlay-slot,.ytp-ad-overlay-slot,.ytp-ad-overlay-container,.ytp-ad-text,.ytp-ad-text-overlay,.ytp-ad-image,.ytp-ad-image-overlay,.ytp-ad-progress,.ytp-ad-progress-list,.ytp-ad-preview-container,.ytp-ad-survey,.ytp-ad-survey-answers,.ytp-ad-skip-button-slot,#masthead-ad,#player-ads,.ytd-mealbar-promo-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,ytd-promoted-video-renderer,ytd-promoted-sparkles-web-renderer,ytd-promoted-sparkles-text-search-renderer,ytd-ad-slot-renderer,ytd-display-ad-renderer,ytd-in-feed-ad-layout-renderer,ytd-reel-video-renderer[is-ad],ytd-ad-hover-text-button-renderer,ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"],ytd-ads-engagement-panel-content-renderer,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-companion-slot,ytm-ad-slot-renderer,ytm-in-feed-ad-layout-renderer,ytm-display-ad-renderer,ytm-brand-video-singleton-renderer,ytm-promoted-sparkles-text-search-renderer,ytd-merch-shelf-renderer,#clarify-box,.ytp-suggested-action,.ytp-featured-product,.ytp-ad-badge,ytd-reel-video-renderer:has(ytd-ad-slot-renderer),ytd-reel-video-renderer:has(ad-badge-view-model),ytm-reel-item-renderer:has(ytm-ad-slot-renderer),ytm-reel-item-renderer:has(ad-badge-view-model),ytm-reel-item-renderer[is-ad],ytm-reel-player-renderer[is-ad],ytm-shorts-lockup-view-model:has(ad-badge-view-model),.reel-video-in-sequence:has(ytm-ad-slot-renderer),.reel-video-in-sequence-new:has(ytm-ad-slot-renderer),.reel-video-in-sequence:has(ad-badge-view-model),.reel-video-in-sequence-new:has(ad-badge-view-model){display:none!important;height:0!important;max-height:0!important;overflow:hidden!important}';
(document.head||document.documentElement).appendChild(css);
var API=window.__liquidYtApi=window.__liquidYtApi||{};
function playerEl(){return document.getElementById('movie_player')||document.querySelector('.html5-video-player');}
// Shorts: a sponsored short that is on screen (the data prune and the CSS did not catch it) is swiped past, at most
// once every 1.5 s. Only on /shorts pages, and only when an ad badge / ad slot is really visible.
var shortsAt=0;
API.shorts=function(){
  try{
    if(location.pathname.indexOf('/shorts')!==0)return;
    var now=Date.now();
    if(now-shortsAt<1500)return;
    var m=document.querySelectorAll('ytd-ad-slot-renderer,ytm-ad-slot-renderer,ad-badge-view-model,ytm-ad-badge');
    var vh=window.innerHeight||0,hit=false;
    for(var i=0;i<m.length&&!hit;i++){
      var r=m[i].getBoundingClientRect();
      if(r.height>0&&r.width>0&&r.bottom>0&&r.top<vh)hit=true;
    }
    if(!hit)return;
    shortsAt=now;
    var nb=document.querySelector('#navigation-button-down button,button[aria-label="Next video"],button[aria-label="Next"]');
    if(nb){nb.click();return;}
    var ev=new KeyboardEvent('keydown',{key:'ArrowDown',code:'ArrowDown',keyCode:40,which:40,bubbles:true});
    (document.activeElement||document.body).dispatchEvent(ev);
    document.dispatchEvent(ev);
  }catch(e){}
};
// Cheap: runs 4x a second, touches only the player element.
API.cheap=function(){
  try{API.shorts();}catch(e){}
  try{
    var player=playerEl();
    if(!player)return;
    var showing=!!(player.classList&&player.classList.contains('ad-showing'))||
      !!player.querySelector('.ytp-ad-player-overlay,.ytp-ad-player-overlay-layout,.ytp-ad-preview-container,.ytp-ad-text-overlay');
    var v=player.querySelector('video')||document.querySelector('video');
    if(showing&&v){
      if(!v.muted){v.muted=true;v.dataset.lqmuted='1';}
      if(v.playbackRate<16)v.playbackRate=16;
      if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.25){
        v.currentTime=v.duration;
      }
    }else if(v&&v.dataset.lqmuted){
      v.muted=false;
      if(v.playbackRate>4)v.playbackRate=1;
      delete v.dataset.lqmuted;
    }
    if(showing){
      var b=player.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button,.ytp-ad-skip-button-slot button');
      if(b){b.click();}
      var x=player.querySelector('.ytp-ad-overlay-close-button,.ytp-ad-overlay-close-container button,.ytp-ad-overlay-close-button-container button');
      if(x){x.click();}
    }
  }catch(e){}
};
// Heavy: whole-page sweeps, throttled so a busy YouTube DOM never makes playback stutter.
var heavyAt=0,heavyTimer=0;
API.heavy=function(){
  heavyAt=Date.now();
  try{
    document.querySelectorAll('ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-video-renderer,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-reel-video-renderer[is-ad],ytd-merch-shelf-renderer').forEach(function(e){e.remove();});
    document.querySelectorAll('ytd-enforcement-message-view-model').forEach(function(e){
      var d=e.closest?e.closest('tp-yt-paper-dialog,ytd-popup-container'):null;
      if(d&&d.remove)d.remove();else e.remove();
    });
  }catch(e){}
};
API.skip=function(){API.cheap();if(Date.now()-heavyAt>2500)API.heavy();};
function laterHeavy(){
  if(heavyTimer||window.__lqPipOn)return;
  heavyTimer=setTimeout(function(){heavyTimer=0;API.heavy();},900);
}
var pt=0;API.timer=setInterval(function(){if(window.__lqPipOn&&(++pt&3))return;API.cheap();},250);
API.timer2=setInterval(function(){if(!window.__lqPipOn&&!document.hidden)API.heavy();},4000);
document.addEventListener('yt-navigate-finish',function(){setTimeout(API.skip,150);},{passive:true});
document.addEventListener('yt-page-data-updated',function(){setTimeout(API.skip,250);},{passive:true});
document.addEventListener('play',function(){setTimeout(API.cheap,80);},true);
try{new MutationObserver(laterHeavy).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
API.skip();
})()"""
    }
}
