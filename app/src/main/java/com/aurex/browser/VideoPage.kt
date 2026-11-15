package com.aurex.browser

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Finds out WHICH video a tab is showing right now.
 *
 * YouTube (m.youtube.com) is a single-page app: opening a video, or playing one in the mini-player,
 * changes the address with history.pushState and fires no page-load callback. The address Aurex
 * stored for the tab could therefore still be the home page while a video plays, and handing that
 * address to the downloader / built-in player gave "Unsupported URL" / "not a playable video file".
 *
 * This asks the page itself (player API, canonical link, player response) for the video id and the
 * real title, and returns a clean https://www.youtube.com/watch?v=<id> link plus the title for the
 * file name. Instagram is resolved from the ADDRESS alone (instagramPermalink): no script of ours ever
 * runs inside an Instagram page. Other sites just return their live address and the cleaned page title.
 */
object VideoPage {
    private val ID_RX = Regex("^[A-Za-z0-9_-]{11}$")

    /** Runs inside the page. Returns a JSON string {id, title, url}. */
    private const val INFO_JS = """
(function(){
  function vid(s){var m=(s||'').match(/[?&]v=([A-Za-z0-9_-]{11})/);return m?m[1]:'';}
  try{
    var id='',title='';
    var p=document.getElementById('movie_player')||document.getElementById('shorts-player')||document.querySelector('.html5-video-player');
    var d=(p&&p.getVideoData)?p.getVideoData():null;
    if(d){id=d.video_id||'';title=d.title||'';}
    if(!id){var sh=location.pathname.match(/^\/(?:shorts|live|embed)\/([A-Za-z0-9_-]{11})/);if(sh)id=sh[1];}
    if(!id)id=vid(location.href);
    if(!id){var c=document.querySelector('link[rel=canonical]');if(c)id=vid(c.href);}
    if(!id){var o=document.querySelector('meta[property="og:url"]');if(o)id=vid(o.content);}
    if(!id&&window.ytInitialPlayerResponse&&ytInitialPlayerResponse.videoDetails){
      id=ytInitialPlayerResponse.videoDetails.videoId||'';
      title=title||ytInitialPlayerResponse.videoDetails.title||'';
    }
    if(!title){var t=document.querySelector('meta[property="og:title"]');title=(t&&t.content)||document.title||'';}
    return JSON.stringify({id:id,title:title,url:location.href});
  }catch(e){
    return JSON.stringify({id:'',title:document.title||'',url:location.href});
  }
})()
"""


    private val IG_ITEM = Regex(
        """^https?://(?:[a-z0-9-]+\.)*instagram\.com/(?:[\w.]+/)?(reel|reels|p|tv)/([\w-]+)""", RegexOption.IGNORE_CASE
    )
    private val IG_SHARE = Regex(
        """^https?://(?:[a-z0-9-]+\.)*instagram\.com/share/(reel|p)/([\w-]+)""", RegexOption.IGNORE_CASE
    )
    private val IG_STORY = Regex(
        """^https?://(?:[a-z0-9-]+\.)*instagram\.com/stories/([\w.]+)/(\d+)""", RegexOption.IGNORE_CASE
    )

    /**
     * Clean permalink when [url] is the address of ONE Instagram reel / post / tv / story, else null
     * (home feed, profile ... where the address does not say which post is meant). Pure string work.
     */
    fun instagramPermalink(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // First: "/share/reel/<code>" would otherwise be read as a user called "share" with a reel; a share
        // code is NOT the reel's own code, the engine follows the share link itself.
        IG_SHARE.find(url)?.let { m ->
            return "https://www.instagram.com/share/${m.groupValues[1].lowercase()}/${m.groupValues[2]}/"
        }
        IG_ITEM.find(url)?.let { m ->
            val kind = when (m.groupValues[1].lowercase()) { "p" -> "p"; "tv" -> "tv"; else -> "reel" }
            val code = m.groupValues[2]
            if (code.equals("audio", true) || code.equals("explore", true)) return null
            return "https://www.instagram.com/$kind/$code/"
        }
        IG_STORY.find(url)?.let { m ->
            return "https://www.instagram.com/stories/${m.groupValues[1]}/${m.groupValues[2]}/"
        }
        return null
    }

    /** Page title made fit for a file name: no " - YouTube" tail, no "(3) " notification count. */
    fun clean(title: String?): String {
        var t = (title ?: "").trim()
        t = t.replace(Regex("^\\(\\d+\\)\\s*"), "")
        t = t.replace(Regex("\\s*[-|–—]\\s*(YouTube|YouTube Music)\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), " ").replace(Regex("\\s+"), " ").trim().trim('.')
        if (t.equals("YouTube", true) || t.equals("New tab", true) || t.equals("Private tab", true)) return ""
        return t.take(100)
    }

    /**
     * Calls [done] (main thread) with the page URL to hand to the engine and the video title
     * (blank when unknown). For non-YouTube pages it answers at once with the live address.
     */
    fun resolve(tab: Tab?, done: (pageUrl: String, title: String) -> Unit) {
        val web = tab?.web
        val live = runCatching { web?.url }.getOrNull()?.takeIf { it.startsWith("http", true) }
            ?: tab?.url.orEmpty()
        val fallbackTitle = clean(tab?.title)
        val ig = YtDlpEngine.isInstagramUrl(live)
        if (web == null || (!ig && !YtDlpEngine.isYoutubeUrl(live))) {
            done(live, fallbackTitle)
            return
        }
        // Instagram: the address is the only source. Nothing is ever run inside the page (the page is the
        // account's live session); a feed / profile address simply has no single post and is handed back as is.
        if (ig) {
            done(instagramPermalink(live) ?: live, fallbackTitle)
            return
        }
        var answered = false
        fun finish(url: String, title: String) {
            if (answered) return
            answered = true
            done(url, title)
        }
        runCatching {
            web.evaluateJavascript(INFO_JS) { raw ->
                val parsed = runCatching {
                    val s = JSONTokener(raw).nextValue() as? String ?: ""
                    JSONObject(s)
                }.getOrNull()
                val id = parsed?.optString("id").orEmpty()
                val t = clean(parsed?.optString("title")).ifBlank { fallbackTitle }
                val pageNow = parsed?.optString("url").orEmpty().ifBlank { live }
                when {
                    ID_RX.matches(id) -> finish("https://www.youtube.com/watch?v=$id", t)
                    else -> finish(pageNow, t)
                }
            }
        }.onFailure { finish(live, fallbackTitle) }
    }
}
