package com.aurex.browser

import java.util.concurrent.ConcurrentHashMap

/**
 * Smart popup / redirect guard.
 *
 * The filter lists only know ad hosts that somebody already reported. This class judges the
 * BEHAVIOUR of a jump as well, using what is known when it happens:
 *   - did the user tap, and did the tap point at this very address?
 *   - is the target another site (registrable domain), a site this tab already visited
 *     (login / payment round-trip), a big trusted site, or a site the user trusted?
 *   - does the address look like an ad (click-id parameters, abused TLDs, IP literals, random names)?
 *
 * Two entry points, both pure (no Android types) so they can be reasoned about and tested:
 *   assess()     - a main-frame navigation or the first address of a window a page opened
 *   assessOpen() - window.open() / script-made link click, asked by the page script BEFORE it happens
 *                  (see AdBlocker.POPUP_GUARD_JS), with facts the script gathered as flag bits.
 */
class PopupGuard {

    enum class Kind { NAVIGATION, WINDOW }

    class Verdict(val block: Boolean, val score: Int, val why: String)

    // ── Per-tab memory ──────────────────────────────────────────────────────────────
    private class Trail {
        val sites = ArrayDeque<String>()
        @Volatile var clickHref = ""
        @Volatile var clickAt = 0L
    }

    private val trails = ConcurrentHashMap<Int, Trail>()
    private fun trail(tab: Int): Trail = trails.getOrPut(tab) { Trail() }

    /** The tab just started loading a page on [host]: remember its site (a later jump BACK to it is a round-trip). */
    fun visit(tab: Int, host: String) {
        val r = registrable(host)
        if (r.isEmpty()) return
        val t = trail(tab)
        synchronized(t.sites) {
            t.sites.remove(r)
            t.sites.addLast(r)
            while (t.sites.size > 8) t.sites.removeFirst()
        }
    }

    private fun visited(tab: Int, host: String): Boolean {
        val t = trails[tab] ?: return false
        val r = registrable(host)
        return synchronized(t.sites) { r in t.sites }
    }

    /** A real tap landed on a link: remembered for a few seconds so the navigation it causes is recognised as intended. */
    fun click(tab: Int, href: String) {
        val t = trail(tab)
        t.clickHref = norm(href)
        t.clickAt = System.currentTimeMillis()
    }

    private fun clickMatches(tab: Int, url: String): Boolean {
        val t = trails[tab] ?: return false
        if (System.currentTimeMillis() - t.clickAt > 4000) return false
        return t.clickHref.isNotEmpty() && t.clickHref == norm(url)
    }

    fun forget(tab: Int) {
        trails.remove(tab)
    }

    // ── Sites the user trusted (thread safe copy; the UI keeps its own observable list) ──
    private val userTrusted = ConcurrentHashMap.newKeySet<String>()

    fun setUserTrusted(sites: Collection<String>) {
        userTrusted.clear()
        sites.forEach { val r = registrable(it); if (r.isNotEmpty()) userTrusted.add(r) }
    }

    fun isUserTrusted(host: String): Boolean {
        if (host.isEmpty()) return false
        return registrable(host) in userTrusted
    }

    // ── The verdicts ────────────────────────────────────────────────────────────────

    /**
     * [kind] NAVIGATION: the tab is about to load [url] on its own. WINDOW: first address of a window a page opened.
     * [listHit]: the filter lists / host list say this address is an ad or tracker.
     * [strict]: lower threshold, blocks every cross-site jump that did not come from a plain link tap.
     */
    fun assess(
        kind: Kind, tab: Int, url: String, pageHost: String,
        gesture: Boolean, redirect: Boolean, listHit: Boolean, strict: Boolean,
        adult: Boolean = false
    ): Verdict {
        val host = hostOf(url)
        if (host.isEmpty()) return ALLOW
        val matches = clickMatches(tab, url)
        // The user tapped exactly this link: only a listed address is still stopped, and only if it is a redirect hop.
        if (listHit && !(gesture && !redirect && matches)) return Verdict(true, 100, "filter list")
        if (isUserTrusted(pageHost) || isUserTrusted(host)) return ALLOW
        val page = registrable(pageHost)
        val target = registrable(host)
        if (page.isNotEmpty() && page == target) return ALLOW
        if (isTrusted(host) || isTrusted(pageHost)) return ALLOW
        if (kind == Kind.NAVIGATION && visited(tab, host)) return ALLOW
        // amazon.in -> amazon.com, hdfcbank.com -> hdfcbank.net: same brand, not an ad.
        if (sameBrand(page, target)) return ALLOW
        // Login / payment / return-to-app hops carry their own parameters: never an ad.
        if (AUTH_FLOW.containsMatchIn(url.lowercase())) return ALLOW
        // Honest link shorteners (t.co, bit.ly ...) only ever follow a tap.
        if (target in SHORTENERS && (gesture || matches)) return ALLOW

        var s = 0
        val why = StringBuilder()
        fun add(n: Int, w: String) { s += n; why.append(w).append(' ') }

        if (kind == Kind.NAVIGATION) {
            when {
                matches -> add(-40, "tapped-link")
                page.isEmpty() -> add(0, "no-source")
                redirect && !gesture -> add(20, "redirect")
                redirect -> add(0, "redirect-after-tap")
                !gesture -> add(50, "no-tap")
                else -> add(25, "tap-mismatch")
            }
        } else {
            when {
                matches -> add(-40, "tapped-link")
                redirect -> add(10, "redirect-in-window")
                else -> add(35, "script-window")
            }
        }
        s += signals(host, url, why)
        // A normal page that sends you to an adult site on its own is an adult ad, whatever the address looks like.
        if (adult) add(50, "adult-target")
        if (kind == Kind.NAVIGATION && pageHost.isNotEmpty() && TRUST_WORDS.any { it in host }) add(-20, "auth-pay-name")
        // Clean-looking address (no ad signal at all) right after a tap = a normal link, give it the benefit of the doubt.
        if (kind == Kind.NAVIGATION && gesture && !redirect && signals(host, url, StringBuilder()) == 0) add(-15, "clean-after-tap")
        val limit = if (strict) 20 else 50
        return Verdict(s >= limit, s, why.toString().trim())
    }

    /** Flag bits the page script sends with [assessOpen]. */
    companion object {
        const val F_INTERACTIVE = 1   // the tap landed on a link / button / form control
        const val F_OVERLAY = 2       // the tap landed on an invisible full-screen layer
        const val F_ACTIVE = 4        // a real tap happened a moment ago
        const val F_REUSED = 8        // this tap already opened one window
        const val F_ANCHOR = 16       // the tap landed on a visible link whose address is this URL

        private val ALLOW = Verdict(false, 0, "")
    }

    /** window.open() / script-made link click, asked BEFORE it happens. Returns block = true to stop it. */
    fun assessOpen(url: String, pageHost: String, flags: Int, listHit: Boolean, strict: Boolean, adult: Boolean = false): Verdict {
        val host = hostOf(url)
        val overlay = flags and F_OVERLAY != 0
        val anchor = flags and F_ANCHOR != 0
        if (listHit) return Verdict(true, 100, "filter list")
        if (overlay) return Verdict(true, 90, "invisible overlay")
        if (isUserTrusted(pageHost)) return ALLOW
        if (flags and F_REUSED != 0) return Verdict(true, 80, "second window for one tap")
        if (flags and F_ACTIVE == 0) return Verdict(true, 80, "no tap")
        // A plain visible link the user tapped is theirs to follow.
        if (anchor) return ALLOW
        if (adult) return Verdict(true, 85, "adult window")
        if (host.isEmpty()) return if (flags and F_INTERACTIVE != 0) ALLOW else Verdict(true, 60, "blank window without tap target")
        if (isUserTrusted(host)) return ALLOW
        val page = registrable(pageHost)
        if (page.isNotEmpty() && page == registrable(host)) return ALLOW
        if (isTrusted(host) || isTrusted(pageHost)) return ALLOW
        if (sameBrand(page, registrable(host)) || AUTH_FLOW.containsMatchIn(url.lowercase())) return ALLOW
        // Cross-site window after a tap on empty page area = the classic click-anywhere pop-under.
        if (flags and F_INTERACTIVE == 0) return Verdict(true, 70, "click-anywhere")
        if (strict) return Verdict(true, 50, "script window (strict)")
        val why = StringBuilder()
        var s = signals(host, url, why)
        if (TRUST_WORDS.any { it in host }) s -= 20
        return Verdict(s >= 40, s, why.toString().trim())
    }

    /** Ad / pop-under networks that are never worth a window, whatever the lists say. */
    fun knownPopNetwork(url: String): Boolean {
        val r = registrable(hostOf(url))
        return r.isNotEmpty() && r in POP_NETWORKS
    }

    // ── Address heuristics ──────────────────────────────────────────────────────────

    private fun signals(host: String, url: String, why: StringBuilder): Int {
        var s = 0
        if (isIp(host)) { s += 20; why.append("ip ") }
        if (tldOf(host) in RISKY_TLD) { s += 20; why.append("tld ") }
        if (looksRandom(host)) { s += 15; why.append("random-name ") }
        val tail = url.substringAfter(host, "").lowercase()
        if (STRONG_PATH.containsMatchIn(tail)) { s += 25; why.append("ad-params ") }
        else if (WEAK_PATH.containsMatchIn(tail)) { s += 10; why.append("redirect-path ") }
        return s
    }

    private fun sameBrand(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || isIp(a) || isIp(b)) return false
        val x = a.substringBefore('.'); val y = b.substringBefore('.')
        return x.length >= 4 && x == y
    }

    private fun looksRandom(host: String): Boolean {
        val label = registrable(host).substringBefore('.')
        if (label.length < 10) return label.count { it == '-' } >= 3
        val digits = label.count { it.isDigit() }
        if (digits * 100 / label.length >= 40) return true
        if (label.count { it == '-' } >= 3) return true
        val vowels = label.count { it in "aeiou" }
        return label.length >= 16 && vowels * 100 / label.length < 20
    }

    private fun isIp(host: String): Boolean =
        host.contains(':') || (host.isNotEmpty() && host.all { it.isDigit() || it == '.' })

    private fun tldOf(host: String): String = host.substringAfterLast('.', "").lowercase()

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    fun hostOf(url: String): String {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return ""
        val auth = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val h = if (auth.startsWith("[")) auth.substringBefore(']').trimStart('[') else auth.substringBefore(':')
        return h.lowercase().trim('.')
    }

    private fun norm(u: String): String = u.substringBefore('#').trimEnd('/').lowercase()

    /** eTLD+1 without a full public-suffix list: the common two-level suffixes and hosting platforms are listed. */
    fun registrable(host: String): String {
        val h = host.lowercase().trim('.')
        if (h.isEmpty() || isIp(h)) return h
        val p = h.split('.')
        if (p.size <= 2) return h
        val last2 = p[p.size - 2] + "." + p.last()
        return if (last2 in SUFFIX2) p.takeLast(3).joinToString(".") else last2
    }

    fun isTrusted(host: String): Boolean {
        if (host.isEmpty()) return false
        val h = host.lowercase()
        if (h.endsWith(".gov.in") || h.endsWith(".nic.in") || h.endsWith(".gov") || h.endsWith(".edu")) return true
        return registrable(h) in TRUSTED
    }

    private val TRUST_WORDS = listOf("pay", "bank", "secure", "login", "auth", "sso", "oauth", "account", "3ds", "gateway", "checkout")

    private val RISKY_TLD = setOf(
        "xyz", "top", "click", "icu", "buzz", "cyou", "monster", "rest", "cfd", "sbs", "gq", "tk", "ml", "ga", "cf",
        "loan", "bid", "men", "party", "racing", "accountant", "cricket", "zip", "mov"
    )

    private val AUTH_FLOW = Regex(
        "([?&](redirect_uri|redirecturi|return_url|returnurl|returnto|continue|callback|saml(request|response)?|oauth_token|" +
            "id_token|access_token|client_id|response_type|txnid|txn_id|order_id|orderid|payment_id|paymentid|merchant|sessionid)=)" +
            "|(/(oauth2?|authorize|sso|saml|login|signin|checkout|payment|pay)([/?.]|\\z))"
    )

    private val SHORTENERS = setOf("t.co", "bit.ly", "goo.gl", "lnkd.in", "amzn.to", "fb.me", "youtu.be", "wa.me", "tinyurl.com", "rb.gy", "g.co")

    private val STRONG_PATH = Regex(
        "([?&](clickid|click_id|subid|sub_id|zoneid|zone_id|campaignid|campaign_id|aff_sub|pubid|pub_id|adid|ad_id|trk_id|tracking_id)=)" +
            "|(/(popunder|pop|adserve|adserver|ads)([/?.]|\\z))"
    )
    private val WEAK_PATH = Regex("/(click|clk|redirect|redir|jump|prize|winner|survey|lucky)([/?.]|\\z)")

    private val SUFFIX2 = setOf(
        "co.in", "net.in", "org.in", "ac.in", "gov.in", "nic.in", "res.in", "firm.in", "gen.in", "ind.in",
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "edu.au", "co.nz", "co.jp", "co.kr",
        "co.za", "co.id", "com.br", "com.cn", "com.mx", "com.ar", "com.tr", "com.sg", "com.my", "com.hk", "com.tw",
        "com.pk", "com.bd", "com.ng", "com.eg", "com.sa",
        "github.io", "blogspot.com", "vercel.app", "netlify.app", "pages.dev", "herokuapp.com", "web.app",
        "firebaseapp.com", "workers.dev", "onrender.com", "appspot.com", "azurewebsites.net", "cloudfront.net",
        "amazonaws.com", "wordpress.com", "weebly.com", "wixsite.com"
    )

    private val TRUSTED = setOf(
        "google.com", "gstatic.com", "googleapis.com", "googleusercontent.com", "google.co.in", "youtube.com", "youtu.be",
        "apple.com", "icloud.com", "microsoft.com", "live.com", "microsoftonline.com", "office.com", "bing.com",
        "facebook.com", "instagram.com", "whatsapp.com", "wa.me", "t.me", "telegram.org", "x.com", "twitter.com",
        "linkedin.com", "pinterest.com", "reddit.com", "discord.com", "github.com", "gitlab.com", "wikipedia.org",
        "paypal.com", "stripe.com", "razorpay.com", "paytm.com", "payu.in", "payumoney.com", "ccavenue.com",
        "billdesk.com", "cashfree.com", "phonepe.com", "juspay.in", "instamojo.com", "pinelabs.com", "npci.org.in",
        "bhimupi.org.in", "visa.com", "mastercard.com", "amazon.in", "amazon.com", "flipkart.com",
        "okta.com", "auth0.com", "cloudflare.com", "hcaptcha.com", "recaptcha.net", "sharepoint.com", "dropbox.com", "zoom.us",
        "slack.com", "notion.so", "irctc.co.in", "bookmyshow.com", "swiggy.com", "zomato.com", "myntra.com", "netflix.com", "spotify.com",
        "hotstar.com", "twitch.tv", "telegram.me", "digilocker.gov.in", "sbi.co.in", "onlinesbi.sbi", "hdfcbank.com", "icicibank.com", "axisbank.com", "kotak.com"
    )

    private val POP_NETWORKS = setOf(
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "exoclick.com", "exosrv.com", "exdynsrv.com",
        "juicyads.com", "trafficjunky.net", "adsterra.com", "onclickads.net", "clickadu.com", "hilltopads.net",
        "adcash.com", "popmyads.com", "tsyndicate.com", "clickaine.com", "galaksion.com", "evadav.com", "monetag.com",
        "admaven.com", "bidvertiser.com", "revenuehits.com", "adk2.com", "a-ads.com", "richads.com", "pushhouse.io",
        "ad-maven.com", "adspyglass.com", "popunderjs.com", "trafficstars.com", "tsyndicate.com", "mondiad.com", "profitablecpmrate.com",
        "highperformanceformat.com", "effectivegatecpm.com", "pubfuture.com", "clickadilla.com", "adxpansion.com"
    )
}
