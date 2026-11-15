package com.aurex.browser

import org.json.JSONObject
import java.io.InputStream

/**
 * Adblock-Plus / uBlock-Origin / AdGuard syntax filter engine.
 *
 * Understands the same list format as EasyList, EasyPrivacy (+ the adult-site ad sections that
 * EasyList ships), uBlock filters and AdGuard Base / Mobile lists:
 *  - network rules:  ||host^  |http://  /regex/  wildcards (*) and separators (^), @@ exceptions,
 *    $third-party $script $image $stylesheet $xmlhttprequest $subdocument $media $font $other
 *    $domain=a.com|~b.com $important, @@...$document / $elemhide / $generichide
 *  - cosmetic rules: ##selector, domain.com##selector, domain.com#@#selector
 * Rules it cannot honour (scriptlets, procedural :has-text() selectors, $csp, $removeparam, $popup ...)
 * are skipped, never half-applied.
 *
 * Matching is token indexed (like Adblock Plus / uBlock): a request only looks at the few rules that
 * share a word with its URL, so ~150k rules cost a handful of microseconds per request.
 * The rule set is built on a background thread and swapped in as one immutable snapshot.
 */
class FilterEngine {

    class NetRule(
        val segs: Array<String>,
        val regexSrc: String?,
        val hostAnchor: Boolean,
        val startAnchor: Boolean,
        val endAnchor: Boolean,
        val party: Int,
        val types: Int,
        val notTypes: Int,
        val domains: Array<String>?,
        val notDomains: Array<String>?,
        val important: Boolean
    ) {
        @Volatile var rx: Regex? = null
        @Volatile var rxBad = false
    }

    private class Data {
        val block = HashMap<String, ArrayList<NetRule>>(1 shl 16)
        val allow = HashMap<String, ArrayList<NetRule>>(1 shl 12)
        val blockNoTok = ArrayList<NetRule>()
        val allowNoTok = ArrayList<NetRule>()
        val generic = LinkedHashSet<String>()
        val specific = HashMap<String, ArrayList<String>>()
        val specificAllow = HashMap<String, HashSet<String>>()
        val noElemHide = HashSet<String>()
        val noGeneric = HashSet<String>()
        val allowSites = HashSet<String>()
        var hasImportant = false
        var netCount = 0
        var cosCount = 0
    }

    @Volatile private var data: Data? = null
    @Volatile private var genericJs: String = ""

    val ruleCount: Int get() = data?.let { it.netCount + it.cosCount } ?: 0

    /** Parses every list and publishes the result in one step. Safe to call from any thread. */
    fun rebuild(inputs: List<() -> InputStream?>) {
        val d = Data()
        for (open in inputs) {
            val st = runCatching { open() }.getOrNull() ?: continue
            runCatching {
                st.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.forEach { line ->
                        try { addLine(d, line) } catch (e: Exception) { /* one bad rule never stops the list */ }
                    }
                }
            }
        }
        genericJs = buildGenericJs(d)
        data = d
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun addLine(d: Data, raw: String) {
        val line = raw.trim()
        if (line.length < 3) return
        val c0 = line[0]
        if (c0 == '!' || c0 == '[') return
        val ci = findCosmetic(line)
        if (ci >= 0) {
            addCosmetic(d, line, ci)
            return
        }
        if (c0 == '#') return
        addNet(d, line)
    }

    private fun findCosmetic(line: String): Int {
        var i = line.indexOf('#')
        while (i >= 0) {
            if (line.startsWith("##", i) || line.startsWith("#@#", i) || line.startsWith("#?#", i) ||
                line.startsWith("#@?#", i) || line.startsWith("#$#", i) || line.startsWith("#@$#", i) ||
                line.startsWith("#%#", i) || line.startsWith("#@%#", i)
            ) return i
            i = line.indexOf('#', i + 1)
        }
        return -1
    }

    private fun addCosmetic(d: Data, line: String, i: Int) {
        val exc: Boolean
        val sel: String
        if (line.startsWith("##", i)) { exc = false; sel = line.substring(i + 2) }
        else if (line.startsWith("#@#", i)) { exc = true; sel = line.substring(i + 3) }
        else return // procedural / scriptlet / CSS-injection variants are not supported
        if (!okSelector(sel)) return
        val domPart = line.substring(0, i)
        if (domPart.isEmpty()) {
            if (!exc) { d.generic.add(sel); d.cosCount++ }
            return
        }
        var any = false
        for (raw in domPart.split(',')) {
            val dom = raw.trim().lowercase()
            if (dom.isEmpty() || dom.startsWith("~") || dom.contains('*') || dom.contains('/')) continue
            any = true
            if (exc) d.specificAllow.getOrPut(dom) { HashSet() }.add(sel)
            else { d.specific.getOrPut(dom) { ArrayList(4) }.add(sel); d.cosCount++ }
        }
        if (!any && !exc && !domPart.contains('*') && !domPart.contains('/')) {
            // only negated domains: behaves as a generic rule
            d.generic.add(sel); d.cosCount++
        }
    }

    private fun okSelector(s: String): Boolean {
        if (s.isEmpty() || s.length > 300) return false
        val c = s[0]
        if (c == '+' || c == '^') return false
        if (s.indexOf('{') >= 0 || s.indexOf('}') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('<') >= 0) return false
        for (m in PSEUDO.findAll(s)) {
            if (m.groupValues[1].lowercase() !in OK_PSEUDO) return false
        }
        return true
    }

    private fun addNet(d: Data, line: String) {
        var s = line
        var exception = false
        if (s.startsWith("@@")) { exception = true; s = s.substring(2) }
        var opts = ""
        val di = s.lastIndexOf('$')
        if (di >= 0) {
            val cand = s.substring(di + 1)
            if (cand.isNotEmpty() && OPTS_OK.matches(cand)) {
                opts = cand
                s = s.substring(0, di)
            }
        }
        val isRegex = s.length > 2 && s[0] == '/' && s.endsWith("/")
        if (!isRegex && s.isEmpty() && opts.isEmpty()) return

        var party = 0
        var types = 0
        var notTypes = 0
        var important = false
        var doms: ArrayList<String>? = null
        var nDoms: ArrayList<String>? = null
        var elemhide = false
        var generichide = false
        var docAllow = false
        if (opts.isNotEmpty()) {
            for (o in opts.split(',')) {
                val opt = o.trim()
                if (opt.isEmpty()) continue
                val neg = opt.startsWith("~")
                val name = if (neg) opt.substring(1) else opt
                val eq = name.indexOf('=')
                val key = (if (eq >= 0) name.substring(0, eq) else name).lowercase()
                val v = if (eq >= 0) name.substring(eq + 1) else ""
                when (key) {
                    "third-party", "3p" -> party = if (neg) -1 else 1
                    "first-party", "1p" -> party = if (neg) 1 else -1
                    "important" -> important = true
                    "match-case", "all" -> {}
                    "domain", "from" -> {
                        for (dv in v.lowercase().split('|')) {
                            val x = dv.trim()
                            if (x.isEmpty() || x.contains('*') || x.contains('/')) continue
                            if (x.startsWith("~")) {
                                if (nDoms == null) nDoms = ArrayList()
                                nDoms.add(x.substring(1))
                            } else {
                                if (doms == null) doms = ArrayList()
                                doms.add(x)
                            }
                        }
                    }
                    "elemhide", "ehide" -> elemhide = true
                    "generichide", "ghide" -> generichide = true
                    "document", "doc" -> docAllow = true
                    "redirect", "empty", "mp4", "media-redirect" -> {}
                    else -> {
                        val bit = typeBit(key)
                        if (bit == 0) return // $popup, $csp, $removeparam, $replace, $header ... : not supported
                        if (neg) notTypes = notTypes or bit else types = types or bit
                    }
                }
            }
        }

        // Whole-site / cosmetic exceptions are bookkeeping only.
        if (exception && (elemhide || generichide || docAllow)) {
            val hosts = ArrayList<String>()
            if (s.startsWith("||")) {
                val h = s.substring(2).lowercase().substringBefore('^').substringBefore('/')
                if (h.isNotEmpty() && !h.contains('*')) hosts.add(h)
            }
            if (doms != null) hosts.addAll(doms)
            for (h in hosts) {
                if (docAllow) { d.allowSites.add(h); d.noElemHide.add(h) }
                if (elemhide) d.noElemHide.add(h)
                if (generichide) d.noGeneric.add(h)
            }
            return
        }
        if (elemhide || generichide || docAllow) return

        var rule: NetRule
        var token: String? = null
        if (isRegex) {
            val src = s.substring(1, s.length - 1)
            if (src.isEmpty()) return
            rule = NetRule(arrayOf(""), src, false, false, false, party, types, notTypes,
                doms?.toTypedArray(), nDoms?.toTypedArray(), important)
        } else {
            var p = s.lowercase()
            var host = false
            var start = false
            var end = false
            if (p.startsWith("||")) { host = true; p = p.substring(2) }
            else if (p.startsWith("|")) { start = true; p = p.substring(1) }
            if (p.endsWith("|")) { end = true; p = p.substring(0, p.length - 1) }
            if (p.startsWith("*")) { host = false; start = false }
            if (p.endsWith("*")) end = false
            val parts = p.split('*').filter { it.isNotEmpty() }
            if (parts.isEmpty()) {
                if (doms == null) return // matches everything: too dangerous without a domain
                rule = NetRule(arrayOf(""), null, false, false, false, party, types, notTypes,
                    doms.toTypedArray(), nDoms?.toTypedArray(), important)
            } else {
                rule = NetRule(parts.toTypedArray(), null, host, start, end, party, types, notTypes,
                    doms?.toTypedArray(), nDoms?.toTypedArray(), important)
                token = pickToken(p, host || start, end)
            }
        }

        if (exception) {
            d.netCount++
            if (token != null) d.allow.getOrPut(token) { ArrayList(2) }.add(rule) else d.allowNoTok.add(rule)
        } else {
            d.netCount++
            if (important) d.hasImportant = true
            if (token != null) d.block.getOrPut(token) { ArrayList(2) }.add(rule) else d.blockNoTok.add(rule)
        }
    }

    private fun typeBit(k: String): Int = when (k) {
        "script" -> T_SCRIPT
        "image" -> T_IMAGE
        "stylesheet", "css" -> T_CSS
        "xmlhttprequest", "xhr" -> T_XHR
        "subdocument", "frame" -> T_SUBDOC
        "media" -> T_MEDIA
        "font" -> T_FONT
        "object", "object-subrequest", "ping", "beacon", "websocket", "other", "webrtc" -> T_OTHER
        else -> 0
    }

    private fun isTok(c: Char): Boolean = (c in 'a'..'z') || (c in '0'..'9') || c == '%'

    /** Longest word of the pattern that is guaranteed to be a whole word of any URL it matches. */
    private fun pickToken(p: String, startBound: Boolean, endBound: Boolean): String? {
        var best: String? = null
        var bestStop: String? = null
        var i = 0
        val n = p.length
        while (i < n) {
            if (isTok(p[i])) {
                var j = i
                while (j < n && isTok(p[j])) j++
                val leftOk = if (i == 0) startBound else p[i - 1] != '*'
                val rightOk = if (j == n) endBound else p[j] != '*'
                if (leftOk && rightOk && j - i >= 2) {
                    val t = p.substring(i, j)
                    if (t in STOP_TOKENS) {
                        if (bestStop == null || t.length > bestStop.length) bestStop = t
                    } else if (best == null || t.length > best.length) best = t
                }
                i = j
            } else i++
        }
        return best ?: bestStop
    }

    // ── Network matching ─────────────────────────────────────────────────────

    /**
     * 0 = no rule matches, 1 = blocked, 2 = a rule matched but an exception (@@) allows it.
     * [type] is one of the T_* bits or 0 when unknown (type restrictions are then ignored).
     */
    fun check(url0: String, reqHost: String, pageHost: String, type: Int, assumeThird: Boolean = false): Int {
        val d = data ?: return 0
        if (d.netCount == 0) return 0
        val url = (if (url0.length > 2500) url0.substring(0, 2500) else url0).lowercase()
        if (pageHost.isNotEmpty() && d.allowSites.isNotEmpty() && hostIn(pageHost, d.allowSites)) return 0
        val sch = url.indexOf("://")
        val hs = if (sch >= 0) sch + 3 else 0
        var he = url.length
        var k = hs
        while (k < url.length) {
            val ch = url[k]
            if (ch == '/' || ch == '?' || ch == '#') { he = k; break }
            k++
        }
        val reqH = if (reqHost.isNotEmpty()) reqHost.lowercase() else url.substring(hs, he)
        val third = assumeThird || (pageHost.isNotEmpty() && regDomain(reqH) != regDomain(pageHost))

        val tokens = ArrayList<String>(24)
        tokenize(url, tokens)

        var hit = false
        for (t in tokens) {
            val l = d.block[t] ?: continue
            for (r in l) {
                if (netMatch(r, url, hs, he, pageHost, third, type)) {
                    if (r.important) return 1
                    hit = true
                    if (!d.hasImportant) break
                }
            }
            if (hit && !d.hasImportant) break
        }
        if (!hit || d.hasImportant) {
            for (r in d.blockNoTok) {
                if (netMatch(r, url, hs, he, pageHost, third, type)) {
                    if (r.important) return 1
                    hit = true
                    if (!d.hasImportant) break
                }
            }
        }
        if (!hit) return 0
        for (t in tokens) {
            val l = d.allow[t] ?: continue
            for (r in l) if (netMatch(r, url, hs, he, pageHost, third, type)) return 2
        }
        for (r in d.allowNoTok) if (netMatch(r, url, hs, he, pageHost, third, type)) return 2
        return 1
    }

    private fun tokenize(url: String, out: ArrayList<String>) {
        val n = url.length
        var i = 0
        while (i < n && out.size < 120) {
            if (isTok(url[i])) {
                var j = i + 1
                while (j < n && isTok(url[j])) j++
                if (j - i >= 2) out.add(url.substring(i, j))
                i = j
            } else i++
        }
    }

    private fun netMatch(
        r: NetRule, url: String, hs: Int, he: Int,
        pageHost: String, third: Boolean, type: Int
    ): Boolean {
        if (r.party == 1 && !third) return false
        if (r.party == -1 && third) return false
        if (type != 0) {
            if (r.types != 0 && (r.types and type) == 0) return false
            if (r.notTypes != 0 && (r.notTypes and type) != 0) return false
        }
        val dm = r.domains
        if (dm != null && (pageHost.isEmpty() || !domainIn(pageHost, dm))) return false
        val nd = r.notDomains
        if (nd != null && pageHost.isNotEmpty() && domainIn(pageHost, nd)) return false
        return patternMatch(r, url, hs, he)
    }

    private fun patternMatch(r: NetRule, url: String, hs: Int, he: Int): Boolean {
        if (r.regexSrc != null || r.endAnchor) return regexMatch(r, url)
        val segs = r.segs
        if (r.hostAnchor) {
            var p = hs
            while (p <= he) {
                val e = segAt(url, p, segs[0])
                if (e >= 0 && restMatch(segs, url, e)) return true
                val dot = url.indexOf('.', p)
                if (dot < 0 || dot >= he) break
                p = dot + 1
            }
            return false
        }
        if (r.startAnchor) {
            val e = segAt(url, 0, segs[0])
            return e >= 0 && restMatch(segs, url, e)
        }
        if (segs[0].isEmpty()) return true
        if (segs[0].indexOf('^') < 0) {
            var from = 0
            while (true) {
                val k = url.indexOf(segs[0], from)
                if (k < 0) return false
                if (restMatch(segs, url, k + segs[0].length)) return true
                from = k + 1
            }
        }
        var i = 0
        while (i <= url.length) {
            val e = segAt(url, i, segs[0])
            if (e >= 0 && restMatch(segs, url, e)) return true
            i++
        }
        return false
    }

    private fun restMatch(segs: Array<String>, url: String, from: Int): Boolean {
        var pos = from
        var j = 1
        while (j < segs.size) {
            val e = search(url, pos, segs[j])
            if (e < 0) return false
            pos = e
            j++
        }
        return true
    }

    private fun search(url: String, from: Int, seg: String): Int {
        if (seg.indexOf('^') < 0) {
            val k = url.indexOf(seg, from)
            return if (k < 0) -1 else k + seg.length
        }
        var i = from
        while (i <= url.length) {
            val e = segAt(url, i, seg)
            if (e >= 0) return e
            i++
        }
        return -1
    }

    /** Matches [seg] exactly at [pos]; '^' is a separator character or the end of the URL. Returns the end or -1. */
    private fun segAt(url: String, pos: Int, seg: String): Int {
        var u = pos
        val n = url.length
        for (c in seg) {
            if (c == '^') {
                if (u == n) continue
                if (isSep(url[u])) u++ else return -1
            } else {
                if (u >= n || url[u] != c) return -1
                u++
            }
        }
        return u
    }

    private fun isSep(ch: Char): Boolean =
        !((ch in 'a'..'z') || (ch in '0'..'9') || ch == '_' || ch == '-' || ch == '.' || ch == '%')

    private fun regexMatch(r: NetRule, url: String): Boolean {
        var rx = r.rx
        if (rx == null) {
            if (r.rxBad) return false
            rx = buildRegex(r)
            if (rx == null) { r.rxBad = true; return false }
            r.rx = rx
        }
        return try { rx.containsMatchIn(url) } catch (e: Exception) { false }
    }

    private fun buildRegex(r: NetRule): Regex? = try {
        val src = r.regexSrc
        if (src != null) {
            Regex(src, RegexOption.IGNORE_CASE)
        } else {
            val sb = StringBuilder()
            if (r.hostAnchor) sb.append("^[a-z][a-z0-9+.\\-]*://(?:[^/?#]*\\.)?")
            else if (r.startAnchor) sb.append('^')
            for (i in r.segs.indices) {
                if (i > 0) sb.append(".*")
                for (ch in r.segs[i]) {
                    if (ch == '^') sb.append("(?:[^a-z0-9_.%\\-]|$)")
                    else {
                        if ("\\.+?()[]{}|$".indexOf(ch) >= 0) sb.append('\\')
                        sb.append(ch)
                    }
                }
            }
            if (r.endAnchor) sb.append('$')
            Regex(sb.toString())
        }
    } catch (e: Exception) {
        null
    }

    private fun domainIn(host: String, list: Array<String>): Boolean {
        for (x in list) if (host == x || host.endsWith(".$x")) return true
        return false
    }

    private fun hostIn(host: String, set: Set<String>): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    private fun regDomain(h: String): String {
        if (h.isEmpty()) return h
        val parts = h.split('.')
        if (parts.size <= 2) return h
        if (parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' } }) return h // IPv4
        val last = parts[parts.size - 1]
        val second = parts[parts.size - 2]
        val n = if (last.length == 2 && second in SLD) 3 else 2
        return parts.subList(parts.size - n, parts.size).joinToString(".")
    }

    // ── Cosmetic filtering ───────────────────────────────────────────────────

    /** Document-start script: hides generic ad selectors (skipped on hosts that opted out). */
    fun genericScript(): String = genericJs

    /** CSS for one host: the host's own selectors plus its parents' (example.com rules cover sub.example.com). */
    fun hostCss(host0: String): String {
        val d = data ?: return ""
        val host = host0.lowercase()
        if (hostIn(host, d.noElemHide)) return ""
        val sels = LinkedHashSet<String>()
        val allow = HashSet<String>()
        var h = host
        while (true) {
            d.specificAllow[h]?.let { allow.addAll(it) }
            d.specific[h]?.let { sels.addAll(it) }
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
            if (!h.contains('.')) {
                d.specificAllow[h]?.let { allow.addAll(it) }
                d.specific[h]?.let { sels.addAll(it) }
                break
            }
        }
        if (sels.isEmpty()) return ""
        val sb = StringBuilder()
        for (s in sels) {
            if (s in allow) continue
            sb.append(s).append("{display:none!important}\n")
            if (sb.length > 200_000) break
        }
        return sb.toString()
    }

    private fun buildGenericJs(d: Data): String {
        if (d.generic.isEmpty()) return ""
        val css = StringBuilder()
        var n = 0
        for (s in d.generic) {
            css.append(s).append("{display:none!important}\n")
            if (++n >= 6000 || css.length > 300_000) break
        }
        val skip = StringBuilder("[")
        var first = true
        for (h in d.noGeneric + d.noElemHide) {
            if (!first) skip.append(',')
            skip.append(JSONObject.quote(h))
            first = false
        }
        skip.append(']')
        return "(function(){try{if(window.__lqGen)return;var X=" + skip + ";var p=location.hostname;" +
            "while(p){if(X.indexOf(p)>=0)return;var i=p.indexOf('.');if(i<0)break;p=p.substring(i+1);}" +
            "window.__lqGen=1;var C=" + JSONObject.quote(css.toString()) + ";" +
            "function add(){var r=document.head||document.documentElement;if(!r)return false;" +
            "var s=document.createElement('style');s.id='lq-generic';s.textContent=C;r.appendChild(s);return true;}" +
            "if(!add()){var o=new MutationObserver(function(){if(add())o.disconnect();});" +
            "o.observe(document,{childList:true,subtree:true});}}catch(e){}})();\n"
    }

    companion object {
        const val T_SCRIPT = 1
        const val T_IMAGE = 2
        const val T_CSS = 4
        const val T_XHR = 8
        const val T_SUBDOC = 16
        const val T_MEDIA = 32
        const val T_FONT = 64
        const val T_OTHER = 128

        private val OPTS_OK = Regex("^[~a-zA-Z0-9_,=|.*:%\\-]+$")
        private val PSEUDO = Regex(":{1,2}([a-zA-Z-]+)")
        private val OK_PSEUDO = setOf(
            "not", "is", "where", "has", "nth-child", "nth-of-type", "nth-last-child", "nth-last-of-type",
            "first-child", "last-child", "first-of-type", "last-of-type", "only-child", "only-of-type",
            "empty", "link", "visited", "hover", "root", "checked", "disabled", "enabled", "after",
            "before", "first-line", "first-letter", "lang", "focus", "active"
        )
        private val STOP_TOKENS = setOf("http", "https", "www", "com", "net", "org", "html", "php", "js", "css")
        private val SLD = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")
    }
}
