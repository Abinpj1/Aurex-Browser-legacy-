package com.aurex.browser

import org.json.JSONObject
import org.json.JSONTokener

// Compiled once; building a Regex on every call is wasted work on hot paths.
private val TAB_COUNT_PREFIX_RE = Regex("^\\(\\d+\\)\\s*")
private val YT_TITLE_TAIL_RE = Regex("\\s*[-|–—]\\s*(YouTube|YouTube Music)\\s*$", RegexOption.IGNORE_CASE)
private val ILLEGAL_FILENAME_RE = Regex("[\\\\/:*?\"<>|\\r\\n\\t]+")
private val WS_RUN_RE = Regex("\\s+")

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


    /**
     * ok.ru: a clicked video opens as a layer over the feed, so the address names no video, and its player is a
     * blob / MSE stream with no link to sniff. The page's own metadata still carries the video's address.
     */
    private const val OK_INFO_JS = """
(function(){
  function idIn(s){
    if(!s)return '';
    s=String(s);
    var m=s.match(/\/(?:video|videoembed|live)\/(-?\d{6,})/)||s.match(/[?&](?:st\.)?mvId=(-?\d+)/)||
          s.match(/"?(?:movieId|mvId|movie_id)"?\s*[:=]\s*"?(-?\d{6,})/);
    return m?m[1]:'';
  }
  /* A feed post / status page names no video in its address: walk up from the video that is playing (or the biggest one
     on screen) and take the id from the first card that holds exactly one video link / player. Several different ids
     in one container means "cannot tell which", and nothing is guessed. */
  function near(){
    var vs=document.getElementsByTagName('video'),el=null,best=-1;
    for(var i=0;i<vs.length;i++){
      var b=vs[i].getBoundingClientRect();
      var a=Math.max(0,Math.min(b.right,innerWidth)-Math.max(b.left,0))*Math.max(0,Math.min(b.bottom,innerHeight)-Math.max(b.top,0));
      if(!vs[i].paused)a+=1e9;
      if(a>best){best=a;el=vs[i];}
    }
    var n=el;
    for(var d=0;n&&d<16;d++,n=n.parentElement){
      var at=n.attributes;
      for(var k=0;at&&k<at.length;k++){var r=idIn(at[k].value);if(r)return r;}
      var ids={},cnt=0,
          nodes=n.querySelectorAll?n.querySelectorAll('a[href*="/video/"],a[href*="mvId="],[data-options*="movieId"]'):[];
      for(var j=0;j<nodes.length&&j<40;j++){
        var r2=idIn(nodes[j].getAttribute('href'))||idIn(nodes[j].getAttribute('data-options'));
        if(r2&&!ids[r2]){ids[r2]=1;cnt++;}
      }
      if(cnt===1){for(var key in ids)return key;}
      if(cnt>1)return '';
    }
    return '';
  }
  try{
    var c=document.querySelector('link[rel=canonical]'),
        o=document.querySelector('meta[property="og:url"]'),
        v=document.querySelector('meta[property="og:video"],meta[property="og:video:url"]'),
        f=document.querySelector('iframe[src*="/videoembed/"]'),
        t=document.querySelector('meta[property="og:title"]'),
        nr='';
    try{nr=near();}catch(e){}
    return JSON.stringify({
      canonical:c?c.href:'', ogUrl:o?o.content:'', ogVideo:v?v.content:'', player:f?f.src:'', near:nr,
      title:(t&&t.content)||document.title||''
    });
  }catch(e){return JSON.stringify({});}
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
        t = t.replace(TAB_COUNT_PREFIX_RE, "")
        t = t.replace(YT_TITLE_TAIL_RE, "")
        t = t.replace(ILLEGAL_FILENAME_RE, " ").replace(WS_RUN_RE, " ").trim().trim('.')
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
        // ok.ru: the video id comes from the address (video layer: st.mvId) or, failing that, from the page's metadata.
        if (YtDlpEngine.isOkUrl(live)) {
            YtDlpEngine.okVideoUrl(live)?.let { done(it, fallbackTitle); return }
            if (web == null) { done(live, fallbackTitle); return }
            var okAnswered = false
            fun finishOk(url: String, title: String) {
                if (okAnswered) return
                okAnswered = true
                done(url, title)
            }
            runCatching {
                web.evaluateJavascript(OK_INFO_JS) { raw ->
                    val parsed = runCatching {
                        JSONObject(JSONTokener(raw).nextValue() as? String ?: "")
                    }.getOrNull()
                    val t = clean(parsed?.optString("title")).ifBlank { fallbackTitle }
                    val found = listOf("canonical", "ogUrl", "ogVideo", "player")
                        .firstNotNullOfOrNull { k -> YtDlpEngine.okVideoUrl(parsed?.optString(k)) }
                        ?: parsed?.optString("near")?.takeIf { it.isNotBlank() }?.let { "https://ok.ru/video/$it" }
                    if (found == null) AppLog.warn("OkRu", "no video id found for page ${AppLog.url(live)}")
                    finishOk(found ?: live, t)
                }
            }.onFailure { finishOk(live, fallbackTitle) }
            return
        }
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
