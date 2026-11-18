package com.aurex.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

// Compiled once; building a Regex on every call is wasted work on hot paths.
private val IPV4_HOST_RE = Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d+)?(/.*)?$""")

// ── Models ───────────────────────────────────────────────────────────────────

class Tab(val id: Int, val incognito: Boolean) {
    var title by mutableStateOf(if (incognito) "Private tab" else "New tab")
    var url by mutableStateOf("")
    var progress by mutableFloatStateOf(1f)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var findCurrent by mutableIntStateOf(0)
    var findTotal by mutableIntStateOf(0)
    var favicon by mutableStateOf<Bitmap?>(null)

    /** Colour sampled from this site's theme-color / favicon for the dynamic accent. */
    var siteAccent by mutableStateOf<Int?>(null)

    /** Media discovered through the network layer. */
    val media = mutableStateListOf<String>()
    /** Links a <video> element really loaded, in ANY frame (embed players too): trusted as media even with no file extension. */
    val videoSrcs = HashSet<String>()
    /** Address of the frame that requested a link (its Referer). Hotlink-protected hosts only answer with that one. */
    val mediaRef = HashMap<String, String>()

    /** Set when the page reports that a video/audio element actually started playing. */
    var videoActive by mutableStateOf(false)
    /** A real, visible <video> (not audio, not a hidden preview) is playing right now. Drives PiP. */
    var videoPlaying by mutableStateOf(false)
    /** The same video exists but is paused (PiP stays open and shows a play button). */
    var videoPaused by mutableStateOf(false)
    /**
     * A video is on this page. [videoActive] is only set by the script of the TOP frame; a player inside an <iframe>
     * (most streaming / blog sites) reports through [videoPlaying] / [videoPaused] instead, so all three count.
     */
    val hasVideo: Boolean get() = videoActive || videoPlaying || videoPaused
    /**
     * Epoch-ms when the current video FIRST became stably playing (not an ad thumbnail flip).
     * 0 means not playing. Used by auto-PiP to require ≥3 s of genuine play before entering
     * PiP — this stops YouTube mid-roll ads from firing PiP every time the user leaves the app.
     */
    var videoPlayingStableSince by mutableLongStateOf(0L)
    /** Epoch-ms when playback last stopped; a quick stop/start (buffering, seek, PiP return) keeps the stable clock. */
    var videoStoppedAt = 0L
    var activeMedia by mutableStateOf<String?>(null)
    /** Last playing video's intrinsic size and on-page bounds (0..1 fractions). */
    var videoWidth by mutableIntStateOf(0)
    var videoHeight by mutableIntStateOf(0)
    var videoRectLeft by mutableFloatStateOf(0f)
    var videoRectTop by mutableFloatStateOf(0f)
    var videoRectRight by mutableFloatStateOf(0f)
    var videoRectBottom by mutableFloatStateOf(0f)

    /** Tab that opened this one through window.open (so closing it returns there). */
    var openerId by mutableIntStateOf(-1)
    /** Until this time a window opened by a page is vetted against the ad lists. */
    var popupGuardUntil = 0L
    /** Host of the page that opened this window (the source of its first jump, see PopupGuard). */
    var openerHost = ""
    /** A window a page opened that is still hidden: it is shown once its first address passed the guard (no flash-and-vanish). */
    var hiddenPopup by mutableStateOf(false)
    /**
     * Addresses of pages in this tab's history that sent the tab on by themselves, without a tap (auto-redirect pages).
     * Back skips them (like Chrome's history-manipulation intervention), otherwise back lands on the page that
     * redirects again and the user is stuck.
     */
    val autoRedirectUrls = HashSet<String>()
    /** Set when the tab goes back to the start screen: the blank page's history is wiped so Back cannot ping-pong into the old page. */
    var wipeHistoryOnBlank = false

    var web: WebView? = null
    /** Restored tab whose page has not been requested yet (it loads when first shown). */
    var pendingUrl: String? = null
    val isHome: Boolean get() = url.isBlank()
}

data class Bookmark(val url: String, val title: String, val time: Long)
data class HistoryEntry(val url: String, val title: String, val time: Long)
data class SourceData(val url: String, val html: String)
data class ScreenshotData(val bitmap: Bitmap, val fullPage: Boolean, val title: String, val screens: Float = 1f)
data class Snack(val id: Long, val text: String)
data class JsDialogState(
    val title: String,
    val message: String,
    val defaultValue: String,
    val isPrompt: Boolean,
    /** Small caption under the title ("Website dialog" for pages, the app's own name for app prompts). */
    val source: String = "Website dialog",
    /** Hide the typed text (PIN prompts). */
    val secret: Boolean = false,
    val result: (Boolean, String?) -> Unit
)

/** Everything that can appear as a menu row or be pinned to the menu's quick area. */
enum class MenuAction(val id: String, val label: String) {
    DOWNLOADS("downloads", "Downloads"),
    TABS("tabs", "Tabs"),
    NEW_TAB("newtab", "New tab"),
    PRIVATE("private", "Private tab"),
    BOOKMARK("bookmark", "Bookmark"),
    HISTORY("history", "History"),
    SHARE("share", "Share"),
    FIND("find", "Find in page"),
    HOME("home", "Home"),
    DESKTOP("desktop", "Desktop site"),
    DARKSITE("darksite", "Dark mode"),
    READER("reader", "Reader mode"),
    SCREENSHOT("screenshot", "Long screenshot"),
    SETTINGS("settings", "Settings");

    companion object {
        fun byId(id: String): MenuAction? = values().firstOrNull { it.id == id }
        val defaultPinned = listOf("downloads", "tabs", "newtab", "screenshot", "bookmark", "settings")
    }
}

const val DEFAULT_TOOLBAR_ITEMS = "download,tabs"

/**
 * Sites Aurex leaves completely alone: no injected scripts (document-start or later), no ad /
 * tracker request filtering, no background look-ups with the account's cookies. Instagram (Meta)
 * checks patched page natives (fetch / XHR / media / visibility APIs) and blocked telemetry, and
 * flags the logged-in account when it sees them.
 */
internal fun isHandsOffHost(urlOrHost: String?): Boolean = hostMatches(urlOrHost, HANDS_OFF_DOMAINS)

/**
 * Instagram's own hosts and Meta's media CDNs only (no Facebook pages / tracker hosts). Used where a REQUEST is
 * judged on its own: a Facebook pixel or SDK on some other website must still be blocked as before.
 */
internal fun isMetaCdnHost(urlOrHost: String?): Boolean = hostMatches(urlOrHost, META_CDN_DOMAINS)

/**
 * The whole Meta family shares one login (Accounts Center, "Continue with Facebook", the same device cookies), and
 * Meta correlates its apps: a flag raised on Facebook or Threads follows the Instagram account. So every Meta
 * site is left alone as a PAGE: no scripts, no bridges, no cookies replayed, real user agent, real visibility.
 */
private val HANDS_OFF_DOMAINS = arrayOf(
    "instagram.com", "instagr.am", "cdninstagram.com", "fbcdn.net",     // Instagram videos also come from fbcdn
    "facebook.com", "fb.com", "fb.me", "fb.watch", "fbsbx.com",
    "meta.com", "threads.net", "threads.com", "messenger.com"
)
private val META_CDN_DOMAINS = arrayOf("instagram.com", "instagr.am", "cdninstagram.com", "fbcdn.net")

private fun hostMatches(urlOrHost: String?, domains: Array<String>): Boolean {
    if (urlOrHost.isNullOrBlank()) return false
    val host = (if (urlOrHost.contains("://")) runCatching { java.net.URI(urlOrHost).host }.getOrNull() else urlOrHost)
        ?.lowercase() ?: return false
    for (d in domains) if (host == d || host.endsWith(".$d")) return true
    return false
}

/**
 * The ONLY place WebView cookies are read for our own requests (downloads, player, look-ups).
 * Instagram gets none: its session must never be replayed by another client.
 */
internal fun siteCookie(url: String?): String? {
    if (url.isNullOrBlank() || isHandsOffHost(url)) return null
    return runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
}

/** Most controls the toolbar can hold, not counting the 3-dot menu button. */
const val TOOLBAR_MAX_ITEMS = 5

// ── Search engines + scripts ─────────────────────────────────────────────────

/** Desktop-site user agent; the Chrome major version follows the phone's real WebView so it is never stale. */
val DESKTOP_UA: String by lazy {
    val major = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 26)
            android.webkit.WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull()
        else null
    }.getOrNull()?.takeIf { it in 80..400 } ?: 124
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
}

data class SearchEngine(val name: String, val url: String)

val ENGINES = listOf(
    SearchEngine("Google", "https://www.google.com/search?q="),
    SearchEngine("DuckDuckGo", "https://duckduckgo.com/?q="),
    SearchEngine("Brave", "https://search.brave.com/search?q="),
    SearchEngine("Bing", "https://www.bing.com/search?q="),
    SearchEngine("Startpage", "https://www.startpage.com/sp/search?query="),
    SearchEngine("Ecosia", "https://www.ecosia.org/search?q="),
    SearchEngine("Yahoo", "https://search.yahoo.com/search?p=")
)

private const val THEME_COLOR_JS = """(function(){
try{
 var ms=document.querySelectorAll('meta[name="theme-color"],meta[name="msapplication-TileColor"]');
 for(var i=0;i<ms.length;i++){var c=ms[i].getAttribute('content');if(c&&c.trim())return c.trim();}
 var hdr=document.querySelector('header,nav,[role="banner"]');
 if(hdr){var hc=getComputedStyle(hdr).backgroundColor;if(hc&&hc!=='transparent'&&hc!=='rgba(0, 0, 0, 0)')return hc;}
 return '';
}catch(e){return '';}})()"""

private const val READER_JS = """(function(){
var a=document.querySelector('article')||document.querySelector('main');
if(!a){var best=document.body,max=0;document.querySelectorAll('div,section').forEach(function(e){var t=e.innerText?e.innerText.length:0;if(t>max&&t<40000){max=t;best=e;}});a=best;}
if(!a)return;
var c=a.cloneNode(true);
c.querySelectorAll('script,style,nav,header,footer,aside,form,iframe,button,select,input,video,audio,svg,[class*=share],[class*=comment],[class*=related],[class*=newsletter],[class*=cookie],[class*=advert],[class*=promo]').forEach(function(e){e.remove();});
document.body.innerHTML='';
document.body.appendChild(c);
var s=document.createElement('style');s.id='lq-reader';
s.textContent='html,body{background:#000000!important;color:#ffffff!important}body{max-width:720px!important;margin:0 auto!important;padding:28px 20px 80px!important;font:19px/1.75 Georgia,serif!important}#lq-reader,a{color:__ACCENT__!important;text-decoration:none!important}h1,h2,h3{color:#fff!important;line-height:1.25!important;letter-spacing:-0.5px!important}img{max-width:100%!important;height:auto!important;border-radius:14px!important;filter:none!important}pre,code{background:#1c1c1e!important;color:#f2f2f7!important;border-radius:10px!important;padding:2px 6px!important;white-space:pre-wrap!important}p{margin:0 0 1.1em!important}blockquote{border-left:3px solid __ACCENT__!important;margin:1em 0!important;padding-left:16px!important;color:#98989f!important}*{filter:none!important}';
(document.head||document.documentElement).appendChild(s);
window.scrollTo(0,0);
})()"""

private const val MEDIA_JS = """(function(){
if(window.__aurexMedia)return;window.__aurexMedia=1;
var seen={};
function isMedia(u){
 if(!u||u.indexOf('blob:')===0||u.indexOf('data:')===0)return false;
 if(/\.(m3u8|mpd|mp4|webm|mkv|m4v|mov|ts|mp3|m4a|aac|ogg|oga|opus|flac|wav)(\?|#|$)/i.test(u))return true;
 if(/googlevideo\.com\/(videoplayback|api\/manifest)|manifest\.googlevideo\.com/i.test(u))return true;
 if(/[?&](mime|type|format)=(video|audio|m3u8|mpd|mp4|webm|mpegurl)/i.test(u))return true;
 // Instagram CDN patterns (fbcdn / cdninstagram)
 if(/\.fbcdn\.net\/.+\.(mp4|m4v)/i.test(u))return true;
 if(/cdninstagram\.com\/.+\.(mp4|m4v)/i.test(u))return true;
 return false;
}
function send(u){
 try{
   if(!isMedia(u))return;
   var key=u;
   if(u.indexOf('googlevideo.com/videoplayback')>-1){
     key='gv:'+((u.match(/[?&]id=([^&]+)/)||[])[1]||'')+':'+((u.match(/[?&]itag=(\d+)/)||[])[1]||'');
   }
   if(seen[key])return;
   seen[key]=1;
   if(Object.keys(seen).length>200)seen={};
   window.AurexMedia.found(u);
 }catch(e){}
}
function playing(u,t,v){
 try{
  var src = u || (v && (v.currentSrc || v.src)) || window.location.href || 'playing';
  var w=0,h=0,r=null;
  if(v){
   w=v.videoWidth||0;h=v.videoHeight||0;
   try{var b=v.getBoundingClientRect(),iw=window.innerWidth||1,ih=window.innerHeight||1;
    r=[b.left/iw,b.top/ih,b.right/iw,b.bottom/ih];}catch(e){}
  }
  window.AurexMedia.playing(src,t||document.title||'',w||0,h||0,
   r?r[0]:0,r?r[1]:0,r?r[2]:0,r?r[3]:0);
 }catch(e){}
}
function notPlaying(){try{window.AurexMedia.stopped();}catch(e){}}
var resIdx=0,hasPO=false;
try{
 new PerformanceObserver(function(l){
  var es=l.getEntries();
  for(var i=0;i<es.length;i++)send(es[i].name);
 }).observe({type:'resource',buffered:true});
 hasPO=true;
}catch(e){}
function scanResources(){
 try{
   var es=performance.getEntriesByType('resource');
   if(es.length<resIdx)resIdx=0;
   var start=Math.max(resIdx,es.length-220);
   for(var i=start;i<es.length;i++)send(es[i].name);
   resIdx=es.length;
 }catch(e){}
}
function scanPlayerResponse(){
 try{
   var pr=window.ytInitialPlayerResponse||null;
   if(!pr&&window.ytplayer&&window.ytplayer.config)pr=window.ytplayer.config.args&&window.ytplayer.config.args.player_response;
   if(typeof pr==='string')pr=JSON.parse(pr);
   var sd=pr&&pr.streamingData;if(!sd)return;
   (sd.formats||[]).concat(sd.adaptiveFormats||[]).forEach(function(f){
     if(f&&f.url)send(f.url);
     // signatureCipher / cipher: URL is percent-encoded in the value
     var sig=f&&(f.signatureCipher||f.cipher);
     if(sig){try{var p=new URLSearchParams(sig);var su=p.get('url');if(su)send(decodeURIComponent(su));}catch(e){}}
   });
   // HLS manifest for live / premium content
   var hls=sd.hlsManifestUrl;if(hls)send(hls);
   var dash=sd.dashManifestUrl;if(dash)send(dash);
 }catch(e){}
}
// ── Instagram: scan _sharedData, __additionalDataLoaded, and IG API responses ──
function scanInstagram(){
 try{
   var host=location.hostname||'';
   if(host.indexOf('instagram')<0&&host.indexOf('instagr.am')<0)return;
   // Legacy sharedData
   var sd=window._sharedData;
   if(sd){
     try{JSON.stringify(sd).replace(/"video_url":"([^"]+)"/g,function(m,u){send(decodeURIComponent(u));});}catch(e){}
   }
   // SPA page data updates
   var addl=window.__additionalDataLoaded;
   if(!addl){
     window.__additionalDataLoaded=function(endpoint,data){
       try{JSON.stringify(data).replace(/"video_url":"([^"]+)"/g,function(m,u){send(decodeURIComponent(u));});}catch(e){}
     };
   }
 }catch(e){}
}
// ── Intercept XHR to catch YouTube /get_video_info and IG /graphql ──────────
(function(){
 try{
   var XHR=XMLHttpRequest.prototype;
   var open0=XHR.open,send0=XHR.send;
   XHR.open=function(method,url){this.__lqUrl=url;return open0.apply(this,arguments);};
   XHR.send=function(){
     var self=this,url=self.__lqUrl||'';
     var isYt=/youtube\.com\/(get_video_info|youtubei\/v1\/player)/i.test(url);
     var isIg=/instagram\.com\/(api\/v1\/media|graphql\/query)/i.test(url);
     if(isYt||isIg){
       var orig=self.onreadystatechange;
       self.onreadystatechange=function(){
         if(self.readyState===4){
           try{
             var body=self.responseText;
             if(isYt){
               try{var j=JSON.parse(body);var sd=j&&j.streamingData;
                if(sd){(sd.formats||[]).concat(sd.adaptiveFormats||[]).forEach(function(f){if(f&&f.url)send(f.url);});
                  if(sd.hlsManifestUrl)send(sd.hlsManifestUrl);}
               }catch(e){}
               // Legacy query-string format: url_encoded_fmt_stream_map
               body.replace(/url_encoded_fmt_stream_map=([^&]+)/i,function(m,v){
                 try{decodeURIComponent(v).split(',').forEach(function(s){var p=new URLSearchParams(s);var u=p.get('url');if(u)send(decodeURIComponent(u));});}catch(e){}
               });
             }
             if(isIg){
               try{body.replace(/"video_url":"([^"]+)"/g,function(m,u){send(decodeURIComponent(u));});}catch(e){}
             }
           }catch(ex){}
           if(orig)orig.call(self);
         } else {if(orig)orig.call(self);}
       };
     }
     return send0.apply(this,arguments);
   };
 }catch(e){}
})();
// ── Intercept fetch() (YouTube SPA navigation uses fetch, not XHR) ───────────
(function(){
 try{
   var origFetch=window.fetch;
   window.fetch=function(input,init){
     var url=typeof input==='string'?input:(input&&input.url)||'';
     var isYt=/youtube\.com\/youtubei\/v1\/player/i.test(url);
     var isIg=/instagram\.com\/(api\/v1\/media|graphql\/query)/i.test(url);
     var p=origFetch.apply(this,arguments);
     if(isYt||isIg){
       p.then(function(resp){
         var clone=resp.clone();
         clone.text().then(function(body){
           if(isYt){try{var j=JSON.parse(body);var sd=j&&j.streamingData;
            if(sd){(sd.formats||[]).concat(sd.adaptiveFormats||[]).forEach(function(f){if(f&&f.url)send(f.url);});
              if(sd.hlsManifestUrl)send(sd.hlsManifestUrl);}
           }catch(e){}}
           if(isIg){try{body.replace(/"video_url":"([^"]+)"/g,function(m,u){send(decodeURIComponent(u));});}catch(e){}}
         }).catch(function(){});
       }).catch(function(){});
     }
     return p;
   };
 }catch(e){}
})();
var wasActive=false;
function scan(){
 if(window.__lqPipOn||document.hidden)return;
 document.querySelectorAll('video,audio,source').forEach(function(e){send(e.currentSrc||e.getAttribute('src')||'');});
 if(!hasPO)scanResources();
 var active=null;
 document.querySelectorAll('video,audio').forEach(function(v){
   if(!v.paused&&!v.ended&&v.readyState>=2)active=v;
 });
 if(active){wasActive=true;playing(active.currentSrc||active.src||'',document.title||'',active);}
 else if(wasActive){wasActive=false;notPlaying();}
}
['play','playing','loadedmetadata','durationchange'].forEach(function(ev){
 document.addEventListener(ev,function(e){
   var v=e.target;
   if(v&&(v.tagName==='VIDEO'||v.tagName==='AUDIO')){
     send(v.currentSrc||v.src||'');
     if(ev==='play'||ev==='playing')playing(v.currentSrc||v.src,document.title||'',v);
   }
 },true);
});
['pause','ended'].forEach(function(ev){
 document.addEventListener(ev,function(e){
   var v=e.target;
   if(v&&(v.tagName==='VIDEO'||v.tagName==='AUDIO')){
     setTimeout(function(){
       var any=false;
       document.querySelectorAll('video,audio').forEach(function(m){if(!m.paused&&!m.ended&&m.readyState>=2)any=true;});
       if(!any)notPlaying();
     },220);
   }
 },true);
});
document.addEventListener('yt-navigate-finish',function(){setTimeout(scanPlayerResponse,400);},{passive:true});
scanPlayerResponse();scanInstagram();scan();setTimeout(scanPlayerResponse,2500);setInterval(scan,4000);
})()"""

/**
 * Runs before any script of the page. Marks the page as "manipulation" only, which removes the
 * double-tap-to-zoom wait: without it Chromium holds every tap back ~300 ms to see if a second
 * one follows (the "touch delay").
 */
private const val EARLY_TOUCH_JS = """(function(){
try{
 var CSS='html,body,a,button,input,select,textarea,label,summary,[role=button],[onclick]{touch-action:manipulation}';
 function add(){
  var de=document.documentElement;
  if(!de)return false;
  if(document.getElementById('lq-touch'))return true;
  var st=document.createElement('style');st.id='lq-touch';st.textContent=CSS;
  (document.head||de).appendChild(st);
  return true;
 }
 if(!add()){
  var mo=new MutationObserver(function(){if(add())mo.disconnect();});
  mo.observe(document,{childList:true,subtree:true});
 }
}catch(e){}
})();
"""

/**
 * YouTube only: hide VP9 / AV1 so the player picks H.264. Every phone decodes H.264 in hardware;
 * VP9 / AV1 fall back to the CPU on many devices, which is exactly what makes video (and PiP)
 * stop and go. H.264 tops out at 1080p, which is all a phone screen needs.
 */
private const val EARLY_H264_JS = """(function(){
try{
 var h=location.hostname||'';
 if(h.indexOf('youtube')<0&&h.indexOf('youtu.be')<0)return;
 var bad=/vp0?9|av01/i;
 var ms=window.MediaSource;
 if(ms&&ms.isTypeSupported){
  var o=ms.isTypeSupported.bind(ms);
  ms.isTypeSupported=function(t){try{if(t&&bad.test(String(t)))return false;}catch(e){}return o(t);};
 }
 var cp=HTMLMediaElement.prototype.canPlayType;
 HTMLMediaElement.prototype.canPlayType=function(t){try{if(t&&bad.test(String(t)))return '';}catch(e){}return cp.call(this,t);};
 var mc=navigator.mediaCapabilities;
 if(mc&&mc.decodingInfo){
  var od=mc.decodingInfo.bind(mc);
  mc.decodingInfo=function(c){
   try{var ct=c&&c.video&&c.video.contentType;
    if(ct&&bad.test(String(ct)))return Promise.resolve({supported:false,smooth:false,powerEfficient:false});}catch(e){}
   return od(c);
  };
 }
}catch(e){}
})();
"""

/**
 * JS expression: true when THIS frame is an Instagram frame, or sits inside one. The document-start script is
 * installed for every origin ("*"), so the test has to hold in every kind of frame: an about:blank / srcdoc /
 * blob frame that Instagram creates itself has an empty hostname (and is same-origin with the page, which can
 * reach into it), so the hostname alone is not enough - the frame's ancestors and referrer are checked too.
 */
private const val HANDS_OFF_FRAME_JS = """(function(){
 var re=/(^|\.)(instagram\.com|instagr\.am|cdninstagram\.com|fbcdn\.net|facebook\.com|fb\.com|fb\.me|fb\.watch|fbsbx\.com|meta\.com|threads\.net|threads\.com|messenger\.com)$/i;
 function host(o){try{return String(o).replace(/^[a-z-]+:\/\//i,'').split(/[\/:?#]/)[0];}catch(e){return '';}}
 try{if(re.test(location.hostname||''))return true;}catch(e){}
 try{var a=location.ancestorOrigins;if(a){for(var i=0;i<a.length;i++){if(re.test(host(a[i])))return true;}}}catch(e){}
 try{if(window.top!==window&&re.test(window.top.location.hostname||''))return true;}catch(e){}
 try{if(!location.hostname&&re.test(host(document.referrer)))return true;}catch(e){}
 return false;
})()"""

/**
 * Runs in EVERY frame (document-start script). A <video> reports the link it really loaded (currentSrc / src / <source>)
 * with the address of its own frame. Embed players (iframes on other hosts) were invisible before: the page script that
 * lists media ran in the top frame only, and a link with no file extension never passed the address test.
 */
private const val SRC_JS = """(function(){
if(window.__aurexSrc)return;window.__aurexSrc=1;
var seen={};
function abs(u){try{return new URL(u,document.baseURI).href;}catch(e){return String(u);}}
function frameUrl(){var h=location.href||'';return h.indexOf('http')===0?h:(document.referrer||'');}
function send(u){
 try{
  if(!u)return;u=abs(u);
  if(u.indexOf('http')!==0||seen[u])return;
  seen[u]=1;
  var M=window.AurexMedia;
  if(M&&M.src)M.src(u,frameUrl());
 }catch(e){}
}
function scan(v){
 try{
  send(v.currentSrc);send(v.getAttribute('src'));
  var s=v.getElementsByTagName('source');
  for(var i=0;i<s.length;i++)send(s[i].src||s[i].getAttribute('src'));
 }catch(e){}
}
['loadedmetadata','loadeddata','playing'].forEach(function(t){
 document.addEventListener(t,function(e){var v=e.target;if(v&&v.tagName==='VIDEO')scan(v);},true);
});
function scanAll(){try{var vs=document.getElementsByTagName('video');for(var i=0;i<vs.length;i++)scan(vs[i]);}catch(e){}}
setTimeout(scanAll,1500);setTimeout(scanAll,5000);
})();"""

private fun earlyScript(h264: Boolean, adScripts: String): String {
    val body = DarkEngine.JS + "\n;" + EARLY_TOUCH_JS + (if (h264) EARLY_H264_JS else "") + "\n;" + PIP_JS +
        "\n;" + SRC_JS +
        (if (adScripts.isEmpty()) "" else "\n;" + adScripts)
    // Installed for every origin, but nothing of it may run on Instagram (nor in a frame Instagram created).
    return "if(!" + HANDS_OFF_FRAME_JS + "){\n" + body + "\n}"
}

/**
 * Picture-in-picture helper. Reports ONLY real video playback (audio and hidden previews are
 * ignored) and, while the window is in PiP, stretches that one <video> over the whole page so
 * the small window shows nothing but the picture.
 */
private const val PIP_JS = """(function(){
if(window.__lqPip)return;
var ATTR='data-lq-pip',ANC='data-lq-pip-a';
var vid=null,st=null,last=null,sig='',pipOn=false,userPaused=false,marked=[];
var retUntil=0,retVid=null,retPlay=false,lastPlayAt=0;
var hold=false,holdVid=null,syncVid=null,syncIv=0;
/* ---- Frames: many streaming sites play inside an <iframe> (embed players). Every frame runs this helper and
   reports its video state UP to its parent (postMessage); the top frame merges the states, stretches the iframe
   that holds the video over the window and tells that frame to stretch its own <video>. Commands go DOWN. */
var isTop=(window===window.top);
var kids=[],kidRoute=null,frameMode=false,kidInfo='',kidInfoAt=0;
function frameElOf(win){
 try{
  var fs=document.getElementsByTagName('iframe');
  for(var i=0;i<fs.length;i++){if(fs[i].contentWindow===win)return fs[i];}
 }catch(e){}
 return null;
}
function kidState(onlyRoute){
 var now=Date.now(),b=null;
 for(var i=0;i<kids.length;i++){
  var k=kids[i];
  if(now-k.t>6000)continue;
  if(onlyRoute&&k.win!==kidRoute)continue;
  if(!b||(k.s===1&&b.s!==1)||(k.s===b.s&&k.t>=b.t))b=k;
 }
 return b;
}
function pickKid(pv,any){
 var now=Date.now(),best=null,ba=-1;
 for(var i=0;i<kids.length;i++){
  var k=kids[i];
  if(now-k.t>6000)continue;
  if(any?k.s===0:k.s!==1)continue;
  var el=frameElOf(k.win);if(!el)continue;
  var a=area(el);if(a>ba){ba=a;best={k:k,el:el};}
 }
 if(best&&pv&&area(pv)>=ba/1.3)return null;
 return best;
}
function fwd(f,a){
 if(!(frameMode&&pipOn&&kidRoute))return false;
 try{kidRoute.postMessage({lq:1,t:'cmd',f:f,a:a||[]},'*');}catch(e){}
 return true;
}
function emit(s,w,h){
 if(isTop){window.AurexMedia.vstate(s,w,h);}
 else{try{window.parent.postMessage({lq:1,t:'st',s:s,w:w,h:h},'*');}catch(e){}}
}
window.addEventListener('message',function(e){
 var d=e.data;if(!d||d.lq!==1)return;
 try{
  if(d.t==='st'){
   var k=null;
   for(var i=0;i<kids.length;i++){if(kids[i].win===e.source){k=kids[i];break;}}
   if(!k){k={win:e.source};kids.push(k);}
   k.s=d.s;k.w=d.w;k.h=d.h;k.t=Date.now();
   report();
  }else if(d.t==='info'){
   kidInfo=d.v;kidInfoAt=Date.now();
   if(!isTop)window.parent.postMessage(d,'*');
  }else if(!isTop&&e.source===window.parent){
   if(d.t==='enter')enter();
   else if(d.t==='exit')exit();
   else if(d.t==='cmd'&&window.__lqPip&&window.__lqPip[d.f])window.__lqPip[d.f].apply(null,d.a||[]);
  }
 }catch(x){}
},false);
function area(v){
 var r=v.getBoundingClientRect();
 var w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));
 var h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));
 return w*h;
}
function playingVideo(){
 var best=null,big=0,vs=document.getElementsByTagName('video');
 for(var i=0;i<vs.length;i++){
  var v=vs[i];
  if(v.paused||v.ended||v.readyState<2||!v.videoWidth||!v.videoHeight)continue;
  var a=area(v);
  if(a>=9600&&a>big){best=v;big=a;}
 }
 return best;
}
function cur(){
 if(vid&&vid.isConnected)return vid;
 return playingVideo()||(last&&last.isConnected?last:null);
}
function ownState(){
 var v=null,s=0;
 if(!isTop&&(innerWidth<200||innerHeight<110))return {s:0,w:0,h:0};   // tiny frames (ads, trackers) never count
 if(pipOn&&vid&&vid.isConnected&&!frameMode){
  // While the PiP window is open the state follows the element itself. Buffering dips
  // (readyState<3) used to read as "stopped", which flipped the play/pause button and made
  // the next tap pause a video that was really playing.
  v=vid;last=vid;s=(vid.paused||vid.ended)?2:1;
 }else{
  v=playingVideo();
  if(v){last=v;s=1;}
  else if(last&&last.isConnected&&last.paused&&!last.ended&&last.videoWidth>0){v=last;s=2;}
 }
 return {s:s,w:v?v.videoWidth:0,h:v?v.videoHeight:0};
}
function report(){
 try{
  var o=ownState(),s=o.s,w=o.w,h=o.h;
  if(frameMode&&pipOn){
   var kr=kidState(true);
   if(kr){s=kr.s;w=kr.w;h=kr.h;}else{s=1;}
  }else if(s!==1){
   var kb=kidState(false);
   if(kb&&(kb.s===1||s===0)){s=kb.s;w=kb.w;h=kb.h;}
  }
  var k=s+':'+(h?Math.round(w/h*40):0);
  if(k!==sig||!isTop){sig=k;emit(s,w,h);}
 }catch(e){}
}
function block(e){if(pipOn){e.stopImmediatePropagation();}}
document.addEventListener('visibilitychange',block,true);
window.addEventListener('pagehide',block,true);
window.addEventListener('blur',block,true);
/* Root cause of the play/pause/play/pause loop: the site (YouTube idle logic, Instagram's
   IntersectionObserver / autoplay manager) calls pause() on the PiP video and the old code
   answered with play() 80 ms later - an endless tug of war. Now only OUR code may change the
   playback state of the PiP video; the site's own pause()/play() calls are ignored. */
var allow=false,allowUntil=0;
function open_(){return allow||Date.now()<allowUntil;}
try{
 var MP=HTMLMediaElement.prototype,oPause=MP.pause,oPlay=MP.play;
 MP.pause=function(){
  if(pipOn&&this===vid&&!open_())return;
  /* Return guard: for 3.5 s after PiP -> browser the site may not pause the video that was playing
     (focus / visibility hand-over made YouTube pause it). A real touch ends the guard at once. */
  if(retUntil&&this===retVid&&Date.now()<retUntil&&!open_())return;
  return oPause.apply(this,arguments);
 };
 MP.play=function(){
  /* Built-in player owns playback right now: the site may not restart this video behind it. */
  if(hold&&this===holdVid&&!open_())return Promise.resolve();
  if(pipOn&&this===vid&&userPaused&&!open_())return Promise.resolve();
  return oPlay.apply(this,arguments);
 };
}catch(e){}
/* While our player (or the PiP window) owns the screen the site may not start ITS OWN fullscreen: many players call
   requestFullscreen() when the window turns landscape, and WebView then shows its system fullscreen view on top of
   the in-app controls (touch dead, picture jumping between the two). The call is answered as "done" and ignored. */
try{
 var blockFs=function(proto,names){
  names.forEach(function(n){
   var o=proto[n];if(typeof o!=='function')return;
   proto[n]=function(){
    if(pipOn){try{return Promise.resolve();}catch(e){return undefined;}}
    /* The page asks for fullscreen while its video plays: open OUR player right here instead. WebView's own fullscreen
       view moved the video to another surface and back (the short freeze), so it is never created for a playing video. */
    try{
     if(isTop&&!hold&&window.AurexMedia&&window.AurexMedia.fullscreen){
      var pv=playingVideo();
      if(pv&&(this===pv||(this.contains&&this.contains(pv))||(pv.contains&&pv.contains(this)))&&enter()){
       window.AurexMedia.fullscreen();
       return Promise.resolve();
      }
     }
    }catch(e){}
    return o.apply(this,arguments);
   };
  });
 };
 blockFs(Element.prototype,['requestFullscreen','webkitRequestFullscreen','webkitRequestFullScreen','mozRequestFullScreen','msRequestFullscreen']);
 blockFs(HTMLVideoElement.prototype,['webkitEnterFullscreen','webkitEnterFullScreen']);
}catch(e){}
/* when did the page video last move? (a pause right after that, with no touch, is the page's own fullscreen hiccup) */
document.addEventListener('timeupdate',function(e){var t=e.target;if(t&&t.tagName==='VIDEO'&&!t.paused)lastPlayAt=Date.now();},true);
function endRetGuard(){retUntil=0;}
document.addEventListener('touchstart',endRetGuard,true);
/* a touch on the page means the built-in player is gone: never leave the site locked */
document.addEventListener('touchstart',function(){hold=false;holdVid=null;},true);
document.addEventListener('keydown',endRetGuard,true);
function doPlay(v){allow=true;try{var p=oPlayCall(v);if(p&&p.catch)p.catch(function(){});}catch(e){}allow=false;}
function oPlayCall(v){return HTMLMediaElement.prototype.play.call(v);}
function doPause(v){allow=true;try{v.pause();}catch(e){}allow=false;}
/* A pause that still gets through (headset button, phone call, system media session) is a real
   user/system pause: remember it so nothing restarts the video behind the user's back. */
function onNativePause(){
 if(!pipOn||allow)return;
 if(vid&&vid.paused&&!vid.ended)userPaused=true;
 setTimeout(report,120);
}
function onNativePlay(){if(pipOn){userPaused=false;setTimeout(report,120);}}
function capQuality(on){
 /* Disabled: switching the quality range on enter / 2.5 s after the return made YouTube re-pick a rendition and
    rebuffer (the small blink + spinner after coming back). The PiP window decodes the normal stream. */
 if(true)return;
 try{
  var pl=document.getElementById('movie_player')||document.querySelector('.html5-video-player');
  if(!pl||!pl.setPlaybackQualityRange)return;
  // A thumbnail-sized window never needs more than 480p; decoding less keeps it smooth.
  if(on)pl.setPlaybackQualityRange('tiny','large');
  else pl.setPlaybackQualityRange('tiny','highres');
 }catch(e){}
}
/* ---- Layout that cannot be pulled away by the site ----------------------------------------------------------
   The stylesheet alone loses against site code that writes inline styles (many players re-lay the video out on every
   resize / frame) and against CSS transitions (the video visibly slides to its new size = jitter). So the same rules
   are ALSO written inline with !important (originals remembered, restored on exit), transitions / animations are
   switched off on the video and its ancestors, and a small watchdog re-applies the layout whenever the site moves it. */
var impEls=[],wdObs=null,wdRaf=0,wdIv=0,wdCount=0,wdT0=0;
function imp(el,prop,val){
 try{
  var sv=el.__lqSv;
  if(!sv){sv=el.__lqSv={};impEls.push(el);}
  if(!(prop in sv))sv[prop]=[el.style.getPropertyValue(prop),el.style.getPropertyPriority(prop)];
  if(el.style.getPropertyValue(prop)!==val||el.style.getPropertyPriority(prop)!=='important')el.style.setProperty(prop,val,'important');
 }catch(e){}
}
function restoreImp(){
 for(var i=0;i<impEls.length;i++){
  var el=impEls[i],sv=el.__lqSv;if(!sv)continue;
  try{for(var p in sv){var o=sv[p];if(o[0])el.style.setProperty(p,o[0],o[1]);else el.style.removeProperty(p);}}catch(e){}
  try{delete el.__lqSv;}catch(e){el.__lqSv=null;}
 }
 impEls=[];
}
var ANC_STY=[['transform','none'],['translate','none'],['scale','none'],['rotate','none'],['filter','none'],['perspective','none'],
 ['contain','none'],['container-type','normal'],['clip-path','none'],['mask','none'],['backdrop-filter','none'],['will-change','auto'],
 ['overflow','visible'],['opacity','1'],['transition','none'],['animation','none'],['content-visibility','visible']];
function applyLayout(){
 var v=vid;if(!v||!v.isConnected)return;
 var oz=(offX||offY)?true:false;
 var vs=[['position','fixed'],['left',offX+'px'],['top',offY+'px'],['right',oz?'auto':'0'],['bottom',oz?'auto':'0'],['width','100vw'],['height','100vh'],
  ['min-width','0'],['min-height','0'],['max-width','none'],['max-height','none'],['margin','0'],['padding','0'],['border','0'],['outline','0'],['box-sizing','border-box'],
  ['transform',zoomV===1?'none':'scale('+zoomV+')'],['object-fit',fitMode],['background','#000'],['z-index','2147483647'],
  ['opacity','1'],['visibility','visible'],['display','block'],['pointer-events','none'],['transition','none'],['animation','none'],
  ['filter','none'],['translate','none'],['scale','none'],['rotate','none']];
 var i,j;
 for(i=0;i<vs.length;i++)imp(v,vs[i][0],vs[i][1]);
 for(i=0;i<marked.length;i++)for(j=0;j<ANC_STY.length;j++)imp(marked[i],ANC_STY[j][0],ANC_STY[j][1]);
 compensate();
}
/* Some players (xHamster & co) keep the video inside an ancestor that becomes the containing block of a
   position:fixed child (content-visibility, a transform the page re-applies, ...). Then 'top:0' is measured from
   THAT box, not from the window: the picture sat low on the screen with black above it. After the layout is
   written, the real on-screen rectangle is measured and the difference to the window centre is cancelled, so the
   video is always exactly in the middle of the window whatever the page does around it. */
var offX=0,offY=0;
function compensate(){
 try{
  var v=vid;if(!v||!v.isConnected)return;
  for(var k=0;k<3;k++){
   var r=v.getBoundingClientRect();
   if(!(r.width>0)||!(r.height>0))return;
   var dx=(r.left+r.width/2)-innerWidth/2,dy=(r.top+r.height/2)-innerHeight/2;
   if(Math.abs(dx)<=1&&Math.abs(dy)<=1)return;
   offX-=dx;offY-=dy;
   imp(v,'left',offX+'px');imp(v,'top',offY+'px');imp(v,'right','auto');imp(v,'bottom','auto');
  }
 }catch(e){}
}
function layoutOk(){
 try{
  var v=vid;if(!v||!v.isConnected)return true;
  var cs=getComputedStyle(v);
  if(cs.position!=='fixed'||cs.visibility!=='visible'||cs.display==='none'||cs.transitionDuration.replace(/[0s,\s]/g,'')!=='')return false;
  var W=innerWidth,H=innerHeight;
  if(Math.abs(parseFloat(cs.width)-W)>1.5||Math.abs(parseFloat(cs.height)-H)>1.5)return false;
  if(zoomV===1){var r=v.getBoundingClientRect();if(Math.abs(r.left)>1.5||Math.abs(r.top)>1.5||Math.abs(r.width-W)>1.5||Math.abs(r.height-H)>1.5)return false;}
  return true;
 }catch(e){return true;}
}
function retarget(nv){
 try{
  if(vid){try{vid.removeEventListener('pause',onNativePause);vid.removeEventListener('play',onNativePlay);}catch(e){}}
  var n=document.querySelectorAll('['+ATTR+'],['+ANC+']');
  for(var i=0;i<n.length;i++){n[i].removeAttribute(ATTR);n[i].removeAttribute(ANC);}
  for(var j=0;j<marked.length;j++){try{marked[j].removeAttribute(ANC);marked[j].removeAttribute(ATTR);}catch(e){}}
  marked=[];restoreImp();stopWatchdog();
  vid=nv;last=nv;offX=0;offY=0;userPaused=nv.paused&&!nv.ended;
  vid.addEventListener('pause',onNativePause);vid.addEventListener('play',onNativePlay);
  vid.setAttribute(ATTR,'1');
  for(var el=vid.parentNode;el;el=el.parentNode||el.host){
   if(el.nodeType===1){if(el===document.documentElement)break;el.setAttribute(ANC,'1');marked.push(el);}
  }
  applyLayout();startWatchdog();
  setTimeout(report,60);
 }catch(e){}
}
function enforce(){
 if(!pipOn)return;
 if(!frameMode&&(!vid||!vid.isConnected||(vid.paused&&!vid.ended&&!userPaused))){
  var nv=playingVideo();
  if(nv&&nv!==vid){retarget(nv);return;}
 }
 if(!vid||!vid.isConnected)return;
 if(layoutOk())return;
 var now=Date.now();
 if(now-wdT0>1000){wdT0=now;wdCount=0;}
 /* a site that rewrites its layout on every frame: keep up, but never spin the CPU */
 if(++wdCount>120)return;
 applyLayout();
}
function schedEnforce(){
 if(wdRaf||!pipOn)return;
 try{wdRaf=requestAnimationFrame(function(){wdRaf=0;enforce();});}catch(e){wdRaf=0;enforce();}
}
function onLayoutEvent(){schedEnforce();setTimeout(schedEnforce,120);setTimeout(schedEnforce,450);}
function startWatchdog(){
 stopWatchdog();
 try{
  wdObs=new MutationObserver(enforce);   // microtask: corrected before the frame is drawn
  var cfg={attributes:true,attributeFilter:['style','class','hidden','width','height']};
  wdObs.observe(vid,cfg);
  for(var i=0;i<marked.length;i++)wdObs.observe(marked[i],cfg);
 }catch(e){wdObs=null;}
 window.addEventListener('resize',onLayoutEvent,true);
 window.addEventListener('orientationchange',onLayoutEvent,true);
 wdIv=setInterval(schedEnforce,400);
}
function stopWatchdog(){
 try{if(wdObs)wdObs.disconnect();}catch(e){}
 wdObs=null;
 window.removeEventListener('resize',onLayoutEvent,true);
 window.removeEventListener('orientationchange',onLayoutEvent,true);
 if(wdIv){clearInterval(wdIv);wdIv=0;}
 if(wdRaf){try{cancelAnimationFrame(wdRaf);}catch(e){}wdRaf=0;}
}
function enter(){
 if(pipOn&&vid&&vid.isConnected)return true;
 var pv=playingVideo(),kf=pickKid(pv,false);
 var v=kf?kf.el:(pv||(last&&last.isConnected?last:null));
 if(!v){kf=pickKid(null,true);if(kf)v=kf.el;}
 if(!v)return false;
 frameMode=!!kf;kidRoute=kf?kf.k.win:null;
 var hiccup=!frameMode&&v.paused&&!v.ended&&lastPlayAt>0&&Date.now()-lastPlayAt<1500;
 vid=v;pipOn=true;window.__lqPipOn=true;userPaused=frameMode||hiccup?false:(v.paused&&!v.ended);offX=0;offY=0;
 try{
  Object.defineProperty(document,'hidden',{configurable:true,get:function(){return false;}});
  Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){return 'visible';}});
 }catch(e){}
 if(!frameMode){vid.addEventListener('pause',onNativePause);vid.addEventListener('play',onNativePlay);}
 st=document.createElement('style');st.id='__lqPipStyle';
 // Everything except the video is made INVISIBLE, not removed from layout. display:none used to
 // collapse YouTube's player / control bar to 0x0 while PiP was open; YouTube measured and cached those
 // sizes, so after the return its icons and controls came back mis-sized, shifted or missing.
 // visibility:hidden keeps every box (and so every measurement) intact and still shows only the picture.
 st.textContent='html,body{overflow:hidden!important;background:#000!important;filter:none!important;-webkit-filter:none!important;backdrop-filter:none!important}'
  +'html>*:not(body):not(head){display:none!important}'
  +'body *,body *::before,body *::after{visibility:hidden!important}'
  +'['+ANC+']{transform:none!important;filter:none!important;perspective:none!important;contain:none!important;content-visibility:visible!important;clip-path:none!important;backdrop-filter:none!important;will-change:auto!important;overflow:visible!important;opacity:1!important;background:none!important;border:0!important;box-shadow:none!important}'
  +'html body video['+ATTR+'],html body iframe['+ATTR+']{visibility:visible!important;display:block!important;position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;transform:none!important;object-fit:contain!important;background:#000!important;z-index:2147483647!important;opacity:1!important;pointer-events:none!important;filter:none!important;-webkit-filter:none!important;will-change:auto!important}'
  +'html,body{overscroll-behavior:none!important;touch-action:none!important;scroll-behavior:auto!important}'
  +'body *,body *::before,body *::after{transition:none!important;animation:none!important}'
  +'video['+ATTR+']::-webkit-media-controls,video['+ATTR+']::-webkit-media-controls-enclosure,video['+ATTR+']::cue{display:none!important}';
 (document.head||document.documentElement).appendChild(st);
 vid.setAttribute(ATTR,'1');
 capQuality(true);
 // Walk up through shadow roots too, so players living inside custom elements are covered.
 for(var el=vid.parentNode;el;el=el.parentNode||el.host){
  if(el.nodeType===1){if(el===document.documentElement)break;el.setAttribute(ANC,'1');marked.push(el);}
 }
 applyLayout();
 startWatchdog();
 try{window.scrollTo(0,0);}catch(e){}
 try{window.dispatchEvent(new Event('resize'));}catch(e){}
 if(frameMode&&kidRoute){try{kidRoute.postMessage({lq:1,t:'enter'},'*');}catch(e){}}
 if(hiccup){doPlay(v);setTimeout(function(){if(pipOn&&vid===v&&v.paused&&!v.ended&&!userPaused)doPlay(v);},250);}
 return true;
}
function exit(){
 if(frameMode&&kidRoute){try{kidRoute.postMessage({lq:1,t:'exit'},'*');}catch(e){}}
 frameMode=false;kidRoute=null;
 retVid=vid;retPlay=!!(vid&&vid.isConnected&&!userPaused&&!vid.paused&&!vid.ended);
 retUntil=retPlay?Date.now()+3500:0;
 pipOn=false;window.__lqPipOn=false;offX=0;offY=0;
 /* Quality range is restored late and only if PiP was not re-opened: changing it at the moment of
    the return made YouTube flush its buffer (spinner / pause) exactly while the page re-lays out. */
 setTimeout(function(){if(!pipOn)capQuality(false);},2500);
 try{if(vid){vid.removeEventListener('pause',onNativePause);vid.removeEventListener('play',onNativePlay);}}catch(e){}
 stopWatchdog();
 try{
  var n=document.querySelectorAll('['+ATTR+'],['+ANC+']');
  for(var i=0;i<n.length;i++){n[i].removeAttribute(ATTR);n[i].removeAttribute(ANC);}
  /* ancestors inside shadow roots are invisible to querySelectorAll: clear the recorded list too */
  for(var j=0;j<marked.length;j++){try{marked[j].removeAttribute(ANC);marked[j].removeAttribute(ATTR);}catch(e){}}
  marked=[];
  try{if(vid){vid.removeAttribute(ATTR);}}catch(e){}
  restoreImp();   // the site's own inline styles come back exactly as they were
  fitMode='contain';zoomV=1;
  if(st&&st.parentNode)st.parentNode.removeChild(st);
  delete document.hidden;delete document.visibilityState;
 }catch(e){}
 st=null;vid=null;
 /* ONE resize, two animation frames after the PiP stylesheet is gone, so the site measures the
    real, final layout (not the half-restored one). */
 try{requestAnimationFrame(function(){requestAnimationFrame(function(){try{window.dispatchEvent(new Event('resize'));}catch(e){}});});}catch(e){try{window.dispatchEvent(new Event('resize'));}catch(e2){}}

 /* Fast, escalating re-sync: the spinner used to stay for ~3 s until the single late pause+play ran.
    Now the first nudge comes 120 ms after the return and the poll runs every ~300 ms for 4 s, stopping
    as soon as YouTube reports playing again. */
 ytTry=0;ytStop=Date.now()+4200;
 if(ytTimer)clearTimeout(ytTimer);
 try{ytResync(0);}catch(e){}   // right now, before the page paints the restored layout
 ytTimer=setTimeout(ytPoll,90);
}
var ytTry=0,ytStop=0,ytTimer=0;
function ytPoll(){
 ytTimer=0;
 try{
  if(pipOn||Date.now()>ytStop)return;
  /* watchdog: the video was playing when PiP closed, nobody touched the screen, yet it is paused now */
  if(retUntil&&Date.now()<retUntil&&retVid&&retVid.isConnected&&retVid.paused&&!retVid.ended)doPlay(retVid);
  var done=ytResync(ytTry++);
  if(done&&ytTry>2&&!(retUntil&&Date.now()<retUntil))return;
 }catch(e){}
 ytTimer=setTimeout(ytPoll,300);
}
/* After the return from PiP YouTube's player state can be stuck on "buffering" (3) / "unstarted" (-1) while the
   <video> element plays fine: during PiP the site's own pause()/play() were swallowed, so its state machine never
   saw the events it waited for. The overlay then shows a spinner instead of play/pause/next/prev. Re-sync it
   from the real element: playVideo() first; if still stuck, a same-tick pauseVideo()+playVideo() resets the UI. */
function ytResync(stage){
 /* returns true when the player state matches the element (nothing left to fix) */
 try{
  if(pipOn)return true;
  var mp=document.getElementById('movie_player');
  var v=(mp&&mp.querySelector&&mp.querySelector('video'))||document.querySelector('video');
  if(!mp||!mp.getPlayerState||!v)return true;
  var s=mp.getPlayerState();
  var playing=!v.paused&&!v.ended&&v.readyState>=3;
  if(playing&&s!==1){
   /* tell the site's state machine what the element is really doing */
   try{v.dispatchEvent(new Event('playing'));v.dispatchEvent(new Event('timeupdate'));}catch(e){}
   try{mp.classList.remove('buffering-mode','ytp-buffering','unstarted-mode');}catch(e){}
   if(stage<5&&mp.playVideo)mp.playVideo();
   else if(mp.pauseVideo&&mp.playVideo){
    /* pause and play are no longer fired in the same tick (YouTube could ignore the play and stay paused) */
    allowUntil=Date.now()+500;
    mp.pauseVideo();
    setTimeout(function(){try{mp.playVideo();}catch(e){}if(!userPaused)doPlay(v);setTimeout(sync_,100);},180);
   }
   setTimeout(sync_,100);
   return false;
  }else if(v.paused&&!v.ended&&s===1&&mp.pauseVideo){mp.pauseVideo();setTimeout(sync_,100);return false;}
  setTimeout(sync_,100);
  return true;
 }catch(e){return true;}
}
/* PiP play/pause = the site's own play/pause button. The press is sent to YouTube's button
   (.ytp-play-button, mobile control) so the site's player state and the PiP icon always agree.
   The block on site pause()/play() is opened for 1.5 s so that press is not swallowed. If the state
   did not change after 350 ms, the player API and then the <video> element are driven directly. */
function ytBtn(){return document.querySelector('.ytp-play-button,button.player-control-play-pause-icon,.ytmCustomControlsPlayPauseButton');}
function sync_(){report();try{window.__aurexReport&&window.__aurexReport();}catch(e){}}
function toggle(){
 if(fwd('toggle'))return;
 var v=cur();if(!v)return;
 swEnd(false);
 var was=!v.paused&&!v.ended;
 userPaused=was;
 /* One press = ONE change of the <video> element, made inside the allow bracket only (doPause /
    doPlay). The guard stays closed afterwards, so the site cannot answer with its own pause()/play()
    and start a play/pause/play loop. No site-button click and no repeated retries: those re-toggled
    the video and caused exactly that loop. One quiet retry only if the element did not change. */
 if(was)doPause(v);else doPlay(v);
 report();
 setTimeout(sync_,80);
 setTimeout(function(){
  var v2=cur();if(!v2)return;
  if((!v2.paused&&!v2.ended)===was){
   if(was)doPause(v2);else doPlay(v2);
  }
  sync_();
 },500);
 setTimeout(sync_,1200);
}
function pause(){if(fwd('pause'))return;swEnd(false);userPaused=true;var v=cur();if(v)doPause(v);setTimeout(report,120);}
/* Used right after the window opens: continue only if the user did not pause it themselves. */
function resume(){if(fwd('resume'))return;var v=cur();if(v&&v.paused&&!v.ended&&!userPaused)doPlay(v);setTimeout(report,120);}
/* Picture watchdog: sound keeps playing but no new video frames are produced (black / frozen PiP
   window). Happens when the renderer treats the page as hidden or a stale compositor layer survives
   the window resize. If the frame counter does not move for 500 ms while the video plays, the page
   is re-laid-out and the decoder is nudged with a same-position seek, which repaints the picture. */
function frames(v){try{var q=v.getVideoPlaybackQuality&&v.getVideoPlaybackQuality();return q?q.totalVideoFrames:-1;}catch(e){return -1;}}
function revive(){
 /* Only inside the PiP window, only for the real symptom (sound runs, picture does not) and never
    by seeking first: a seek on a playing stream makes YouTube rebuffer, which is what showed up
    as buffering / pauses. Stage 1 only repaints (resize + stylesheet re-attach). A seek is the
    last resort, after a second look 700 ms later. */
 if(!pipOn)return;
 if(fwd('revive'))return;
 var v=cur();if(!v||v.paused||v.ended)return;
 var f0=frames(v),t0=v.currentTime;if(f0<0)return;
 setTimeout(function(){
  if(!pipOn)return;
  var v2=cur();if(!v2||v2.paused||v2.ended)return;
  if(frames(v2)>f0)return;                       // picture is being drawn: nothing to fix
  if(v2.readyState<3||v2.currentTime-t0<0.25)return;   // stalled / buffering = network, not a black picture
  var f1=frames(v2),t1=v2.currentTime;
  try{window.dispatchEvent(new Event('resize'));}catch(e){}
  try{if(st&&st.parentNode){var par=st.parentNode;par.removeChild(st);par.appendChild(st);}}catch(e){}
  setTimeout(function(){
   if(!pipOn)return;
   var v3=cur();if(!v3||v3.paused||v3.ended)return;
   if(frames(v3)>f1)return;
   if(v3.readyState<3||v3.currentTime-t1<0.25)return;
   try{var t=v3.currentTime;allow=true;v3.currentTime=t;}catch(e){}
   allow=false;
  },700);
 },500);
}
function seek(d){
 if(fwd('seek',[d]))return;
 var v=cur();if(!v)return;
 var t=v.currentTime+d;
 if(isFinite(v.duration))t=Math.min(t,Math.max(0,v.duration-0.25));
 v.currentTime=Math.max(0,t);
}
['play','playing','pause','ended','emptied','loadedmetadata','resize'].forEach(function(ev){
 document.addEventListener(ev,function(e){
  var t=e.target;
  if(!t||t.tagName!=='VIDEO')return;
  if(ev==='pause'||ev==='ended'||ev==='emptied')setTimeout(report,160);else report();
 },true);
});
setInterval(function(){if(!pipOn)report();},2500);
/* a frame that holds the video feeds its progress to the top frame (in-app player seek bar) */
setInterval(function(){if(!isTop&&pipOn&&!frameMode){try{window.parent.postMessage({lq:1,t:'info',v:info()},'*');}catch(e){}}},500);
/* ---- Built-in player <-> page sync -------------------------------------------------------------
   syncOut(keep): called when the user opens the page video in the built-in player. Returns the video's real
   position (YouTube: player API, content time - not the ad). keep=true only reads it (the page keeps
   playing until the player is ready: seamless hand-over); keep=false also pauses the page video and locks
   it so the site cannot restart it while the player is open (no double sound).
   syncIn(t,play): called when the player closes. Puts the page video at the player's position and
   resumes it only if the player was playing (X in the PiP window = stays paused). */
function ytp(){return document.getElementById('movie_player')||document.getElementById('shorts-player')||document.querySelector('.html5-video-player');}
function isAd(p){try{return !!(p&&p.classList&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));}catch(e){return false;}}
function pickSync(){
 var v=(vid&&vid.isConnected)?vid:null;
 if(!v)v=playingVideo();
 if(!v&&last&&last.isConnected)v=last;
 if(!v){
  var vs=document.getElementsByTagName('video'),big=-1;
  for(var i=0;i<vs.length;i++){var a=area(vs[i]);if(vs[i].videoWidth>0&&a>big){v=vs[i];big=a;}}
 }
 return v;
}
/* The picture the video shows right now, as a small JPEG data URL ('' when the page does not allow reading it).
   The full player opens ON this picture instead of on black (no 'player opens again' flash on a 4K switch). */
function grabFrame(v){
 try{
  var w=v.videoWidth,h=v.videoHeight;
  if(!(w>0&&h>0)||v.readyState<2)return '';
  var k=Math.min(1,960/Math.max(w,h)),c=document.createElement('canvas');
  c.width=Math.max(1,Math.round(w*k));c.height=Math.max(1,Math.round(h*k));
  c.getContext('2d').drawImage(v,0,0,c.width,c.height);
  return c.toDataURL('image/jpeg',0.7)||'';
 }catch(e){return '';}
}
function syncOut(keep,fr){
 try{
  if(frameMode&&pipOn)return JSON.stringify({ok:false});
  var v=pickSync();
  if(!v)return JSON.stringify({ok:false});
  var shot=fr?grabFrame(v):'';
  var p=ytp(),t=v.currentTime,ad=isAd(p);
  if(p&&p.getCurrentTime&&!ad){var c=p.getCurrentTime();if(isFinite(c)&&c>=0)t=c;}
  var live=!isFinite(v.duration)||v.duration<=0;
  var playing=!v.paused&&!v.ended;
  if(syncIv){clearInterval(syncIv);syncIv=0;}
  syncVid=v;
  if(!keep){
   holdVid=v;hold=true;
   allow=true;
   try{if(p&&p.pauseVideo)p.pauseVideo();}catch(e){}
   try{v.pause();}catch(e){}
   allow=false;
   retUntil=0;
   setTimeout(report,160);
  }
  return JSON.stringify({ok:true,t:(ad||live)?-1:t,playing:playing,live:live,ad:ad,f:shot,d:(ad||live)?-1:v.duration});
 }catch(e){return JSON.stringify({ok:false});}
}
function syncIn(t,play){
 try{
  if(frameMode&&pipOn)return false;
  hold=false;
  var v=(syncVid&&syncVid.isConnected)?syncVid:pickSync();
  syncVid=null;holdVid=null;
  if(!v)return false;
  if(syncIv){clearInterval(syncIv);syncIv=0;}
  var p=ytp(),ad=isAd(p),ytOk=!!(p&&p.seekTo&&p.getCurrentTime&&!ad);
  allow=true;
  if(isFinite(t)&&t>=0&&!ad){
   try{if(ytOk)p.seekTo(t,true);else v.currentTime=t;}catch(e){try{v.currentTime=t;}catch(e2){}}
  }
  allow=false;
  if(play){
   retVid=v;retPlay=true;retUntil=Date.now()+3000;
   allow=true;try{if(ytOk&&p.playVideo)p.playVideo();}catch(e){}allow=false;
   doPlay(v);
   var n=0,iv=setInterval(function(){
    n++;
    try{
     if(!v.isConnected||n>8||hold){clearInterval(iv);if(syncIv===iv)syncIv=0;return;}
     if(isFinite(t)&&t>=0&&!isAd(p)&&v.currentTime<t-1.5&&!v.seeking){allow=true;try{if(ytOk)p.seekTo(t,true);else v.currentTime=t;}catch(e){}allow=false;}
     if(v.paused&&!v.ended){allow=true;try{if(ytOk&&p.playVideo)p.playVideo();}catch(e){}allow=false;doPlay(v);}
    }catch(e){clearInterval(iv);}
   },350);
   syncIv=iv;
  }
  setTimeout(sync_,150);setTimeout(sync_,900);
  return true;
 }catch(e){return false;}
}
/* ---- In-app player controls (InlinePlayer.kt) drive the page's own <video> through these ---- */
function info(){
 try{
  if(frameMode&&pipOn)return (Date.now()-kidInfoAt<3000)?kidInfo:'';
  var v=cur();if(!v)return '';
  var d=v.duration;if(!isFinite(d)||d<=0)d=0;
  /* during a quality switch: report what the video WILL do (play on), and 'buffering' until it really does */
  var swing=(swHold||window.__lqSrc==='pending')?1:0;
  var pl=swHold?(swHold.was?1:0):((v.paused&&!v.ended)?0:1);
  var bf=swHold?(swHold.was?1:0):(window.__lqSrc==='pending'?1:((!v.paused&&v.readyState<3)?1:0));
  return JSON.stringify({t:v.currentTime,d:d,p:pl,e:v.ended?1:0,r:v.playbackRate||1,
   b:bf,s:swing,ad:isAd(ytp())?1:0});
 }catch(e){return '';}
}
function seekAbs(t){
 if(fwd('seekAbs',[t]))return;
 var v=cur();if(!v)return;var p=ytp();
 try{if(p&&p.seekTo&&!isAd(p))p.seekTo(t,true);else v.currentTime=t;}catch(e){try{v.currentTime=t;}catch(e2){}}
 setTimeout(report,200);
}
function rate(r){
 if(fwd('rate',[r]))return;
 var v=cur();if(!v)return;var p=ytp();
 try{if(p&&p.setPlaybackRate&&!isAd(p))p.setPlaybackRate(r);}catch(e){}
 try{v.playbackRate=r;}catch(e){}
}
function setPlay(b){if(fwd('setPlay',[b]))return;var v=cur();if(!v)return;swEnd(false);userPaused=!b;if(b)doPlay(v);else doPause(v);setTimeout(report,120);}
function mute(m){if(fwd('mute',[m]))return;var v=cur();if(v)try{v.muted=!!m;}catch(e){}}
var fitMode='contain',zoomV=1;
function fit(m,z){
 if(fwd('fit',[m,z]))return;
 fitMode=m||'contain';zoomV=z||1;
 var v=cur();if(!v)return;
 try{imp(v,'object-fit',fitMode);imp(v,'transform',zoomV===1?'none':'scale('+zoomV+')');}catch(e){}
}
/* ---- Quality of the page's own player (quality chip of the in-app player) -------------------------
   qualities() lists what the page's player offers, setQuality(id) switches it. Sources: YouTube's player
   API, JW Player and hls.js (window.hls). Any other page returns an empty list and the app falls back to
   the engine's list. ids: YouTube level names, or the level index for JW Player / hls.js; 'auto' = automatic. */
var YQH={hd2160:2160,hd1440:1440,hd1080:1080,hd720:720,large:480,medium:360,small:240,tiny:144};
var qForced='';
function ytQ(){var p=ytp();return(p&&p.getAvailableQualityLevels&&p.setPlaybackQuality)?p:null;}
function jwQ(){try{var j=window.jwplayer&&window.jwplayer();return(j&&j.getQualityLevels&&j.setCurrentQuality&&j.getQualityLevels().length>1)?j:null;}catch(e){return null;}}
function hlsQ(){try{var h=window.hls;return(h&&h.levels&&h.levels.length>1&&('currentLevel' in h))?h:null;}catch(e){return null;}}
function qualities(){
 try{
  if(frameMode&&pipOn)return '';
  var v=cur(),out={k:'',l:[],c:'',a:1,w:v?v.videoWidth:0,h:v?v.videoHeight:0};
  var y=ytQ();
  if(y){
   var lv=y.getAvailableQualityLevels()||[],seen={},i;
   for(i=0;i<lv.length;i++){var id=lv[i],hh=YQH[id];if(!hh||seen[id])continue;seen[id]=1;out.l.push({i:id,h:hh});}
   out.l.sort(function(a,b){return b.h-a.h;});
   if(out.l.length>0){
    out.k='yt';
    /* 1440p / 4K / 8K that exist in the video's own stream list but are not offered by the mobile player: known at once,
       without waiting for the engine look-up. */
    try{
     var pr=(y.getPlayerResponse&&y.getPlayerResponse())||window.ytInitialPlayerResponse,af=pr&&pr.streamingData&&pr.streamingData.adaptiveFormats,sx={},xs=[];
     if(af)for(var q=0;q<af.length;q++){var fh=af[q]&&af[q].height|0,fw=af[q]&&af[q].width|0,sh=(fw>0&&fh>0)?Math.min(fw,fh):fh;if(fh>=1440&&sh<=2160&&!sx[fh]){sx[fh]=1;xs.push(fh);}}
     xs.sort(function(a,b){return b-a;});out.x=xs;
    }catch(e){}
    var ok=false;for(i=0;i<out.l.length;i++)if(out.l[i].i===qForced)ok=true;
    if(ok){out.a=0;out.c=qForced;}else qForced='';
    return JSON.stringify(out);
   }
  }
  var jw=jwQ();
  if(jw){
   var jl=jw.getQualityLevels(),jc=jw.getCurrentQuality();
   out.k='jw';
   for(var n=0;n<jl.length;n++){
    var lb=String(jl[n]&&jl[n].label||'');
    if(/auto/i.test(lb)){if(n===jc)out.a=1;continue;}
    var jh=parseInt(lb,10);if(!(jh>0))continue;
    out.l.push({i:String(n),h:jh});
    if(n===jc){out.a=0;out.c=String(n);}
   }
   out.l.sort(function(a,b){return b.h-a.h;});
   if(out.l.length>1)return JSON.stringify(out);
   out.l=[];
  }
  var hl=hlsQ();
  if(hl){
   out.k='hls';
   var seenH={};
   for(var m=0;m<hl.levels.length;m++){
    var lh=hl.levels[m]&&hl.levels[m].height;
    if(!(lh>0)||seenH[lh])continue;seenH[lh]=1;
    out.l.push({i:String(m),h:lh});
   }
   out.l.sort(function(a,b){return b.h-a.h;});
   var man=(typeof hl.manualLevel==='number')?hl.manualLevel:-1;
   if(man>=0){out.a=0;out.c=String(man);}
   if(out.l.length>1)return JSON.stringify(out);
  }
  return JSON.stringify({k:'',l:[],c:'',a:1,w:out.w,h:out.h});
 }catch(e){return '';}
}
/* ---- Quality switch = HOLD, LOAD, RESUME ------------------------------------------------------------------------
   The page keeps playing the old quality (its sound too) while the new one loads, and afterwards the site left the video
   paused. So a hand-picked quality works like this: the video is paused at once (old sound stops, the guard keeps the site
   from restarting it), the new quality loads in the background, and when it has data the video plays on - from the same
   second, with the new quality's sound. info() reports 'buffering' for the whole time (s:1), so the app's buffer screen
   stays up until playback really runs. Never longer than 12 s; a play/pause press by the person cancels the hold. */
var swHold=null,swTok=0;
function swEnd(resume){
 var h=swHold;if(!h)return;
 swHold=null;swTok++;
 if(!resume)return;
 var v=h.v;if(!v||!v.isConnected)return;
 userPaused=false;
 var p=ytp();
 allow=true;try{if(p&&p.playVideo&&!isAd(p))p.playVideo();}catch(e){}allow=false;
 doPlay(v);
 /* the site may still answer with a pause of its own: look again and play on */
 var again=function(){try{if(!swHold&&v.isConnected&&v.paused&&!v.ended&&!userPaused){allow=true;try{if(p&&p.playVideo&&!isAd(p))p.playVideo();}catch(e){}allow=false;doPlay(v);}}catch(e){}};
 setTimeout(again,350);setTimeout(again,1100);setTimeout(again,2200);
 setTimeout(sync_,250);setTimeout(sync_,1200);
}
function swBegin(v){
 var was=swHold?swHold.was:(!v.paused&&!v.ended);   /* a second pick during a switch keeps the first intent */
 var tok=++swTok;
 swHold={v:v,was:was,t0:Date.now(),h0:v.videoHeight|0,saw:false};
 if(was){userPaused=true;doPause(v);}                /* old sound stops NOW */
 var iv=setInterval(function(){
  var want=false;
  try{
   if(!swHold||swTok!==tok){clearInterval(iv);return;}
   want=swHold.was;
   var el=Date.now()-swHold.t0;
   if(!v.isConnected){clearInterval(iv);swEnd(false);return;}
   if(v.seeking||v.readyState<3)swHold.saw=true;
   if((v.videoHeight|0)>0&&(v.videoHeight|0)!==swHold.h0)swHold.saw=true;
   var ready=!v.seeking&&v.readyState>=3;
   /* data is there for the new quality (seen the reload, or 1.5 s passed without one): play on. 12 s = give up and play. */
   if(el>=12000||(ready&&el>=350&&(swHold.saw||el>=1500))){clearInterval(iv);swEnd(want);}
  }catch(e){clearInterval(iv);swEnd(want);}
 },80);
}
function setQuality(id){
 try{
  id=String(id);
  /* a hand-picked quality is held (see above); only 'auto' keeps the old rule: the site may pause / replay briefly */
  var hv=(id!=='auto')?cur():null;
  if(hv)swBegin(hv);else allowUntil=Date.now()+3000;
  var y=ytQ();
  if(y&&(id==='auto'||YQH[id])){
   if(id==='auto'){
    qForced='';
    try{y.setPlaybackQualityRange('tiny','hd2160');}catch(e){}   /* automatic never climbs above 4K (8K cannot be decoded) */
    try{y.setPlaybackQuality('auto');}catch(e){}
   }else{
    qForced=id;
    try{y.setPlaybackQualityRange(id,id);}catch(e){}
    try{y.setPlaybackQuality(id);}catch(e){}
   }
   /* Apply NOW. YouTube only changes the quality of data it has not loaded yet and keeps playing ~30 s of the old one.
      Seeking to the very same second drops that buffer: the new quality starts at exactly this moment (and the video
      reports 'waiting', so the app's buffer screen and the real state agree). */
   if(id!=='auto'){try{var ct=y.getCurrentTime();if(isFinite(ct)&&ct>=0&&!isAd(y))y.seekTo(ct,true);}catch(e){}}
   setTimeout(sync_,400);setTimeout(sync_,1500);
   return;
  }
  var n=parseInt(id,10);
  var jw=jwQ();
  if(jw){
   if(id==='auto'){var jl=jw.getQualityLevels();for(var i=0;i<jl.length;i++)if(/auto/i.test(String(jl[i]&&jl[i].label||''))){jw.setCurrentQuality(i);break;}}
   else if(isFinite(n))jw.setCurrentQuality(n);
   return;
  }
  var hl=hlsQ();
  if(hl){hl.currentLevel=(id==='auto')?-1:(isFinite(n)?n:-1);}
 }catch(e){}
}
/* ---- In-place quality switch for pages whose own player has no quality API (xHamster & co) ---------------------
   setSource(url) swaps the source of the page's own <video> and continues at the same position / speed / play
   state, so choosing a quality changes ONLY the quality: no second player opens. Result: 'start' (switching, the
   outcome is published in window.__lqSrc = pending | ok | fail), 'unsupported' (this WebView cannot play that
   kind of URL inside a <video>: nothing was touched), 'nov' (no video), 'err'. A switch that errors or takes too
   long puts the old source and position back. */
var srcTok=0;
function setSource(url){
 try{
  var v=cur();if(!v||!url)return 'nov';
  url=String(url);
  if(url.indexOf('.m3u8')>=0){
   var ct='';
   try{ct=v.canPlayType('application/vnd.apple.mpegurl')||v.canPlayType('application/x-mpegURL')||'';}catch(e){}
   if(!ct)return 'unsupported';
  }
  var tok=++srcTok;window.__lqSrc='pending';
  var oldAttr=v.getAttribute('src'),t=v.currentTime||0,was=!v.paused&&!v.ended,rate=v.playbackRate||1,muted=v.muted;
  var finished=false,timer=0;
  allowUntil=Date.now()+14000;   /* the load below pauses the element: let our own play() through */
  function off(){
   try{v.removeEventListener('loadedmetadata',ok);v.removeEventListener('error',bad);}catch(e){}
   if(timer){clearTimeout(timer);timer=0;}
  }
  function restore(){
   try{v.playbackRate=rate;}catch(e){}
   try{v.muted=muted;}catch(e){}
   if(t>0){try{v.currentTime=t;}catch(e){}}
   if(was){try{doPlay(v);}catch(e){}}
  }
  function ok(){
   if(finished||tok!==srcTok)return;
   finished=true;off();restore();window.__lqSrc='ok';
   setTimeout(sync_,300);setTimeout(sync_,1200);
  }
  function bad(){
   if(finished||tok!==srcTok)return;
   finished=true;off();
   try{if(oldAttr!==null)v.setAttribute('src',oldAttr);else v.removeAttribute('src');}catch(e){}
   try{
    var back=function(){try{v.removeEventListener('loadedmetadata',back);}catch(e){}restore();};
    v.addEventListener('loadedmetadata',back);
    v.load();
   }catch(e){}
   window.__lqSrc='fail';
  }
  v.addEventListener('loadedmetadata',ok);
  v.addEventListener('error',bad);
  timer=setTimeout(bad,12000);
  v.src=url;
  return 'start';
 }catch(e){return 'err';}
}
window.__lqPip={qualities:qualities,setQuality:setQuality,setSource:setSource,setPlay:setPlay,mute:mute,fit:fit,info:info,seekAbs:seekAbs,rate:rate,syncOut:syncOut,syncIn:syncIn,enter:enter,exit:exit,toggle:toggle,pause:pause,resume:resume,seek:seek,report:report,revive:revive};
report();
})()"""

/** Keeps YouTube media playing when the page becomes hidden / browser enters PiP. */
private const val YT_BACKGROUND_JS = """(function(){
if(window.__lqBg)return;window.__lqBg=1;
function keep(){
  try{
    if(window.__lqNoBg)return;
    var v=document.querySelector('video.html5-main-video,video');
    if(!v)return;
    if(document.hidden&&v.paused){v.play().catch(function(){});}
    if(v.dataset.lqbg==='1'&&document.hidden){v.play().catch(function(){});}
  }catch(e){}
}
document.addEventListener('visibilitychange',function(){if(document.hidden){keep();setTimeout(keep,400);setTimeout(keep,1200);}},{passive:true});
var bgT=0;try{new MutationObserver(function(){if(!document.hidden||bgT)return;bgT=setTimeout(function(){bgT=0;keep();},600);}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})()"""

/**
 * Fix 3: Full YouTube visibility-spoofing + AurexMediaBridge video-state reporter.
 *
 * What this does:
 *  1. Overrides document.hidden / visibilityState so YouTube always sees the page as
 *     visible, preventing its "tab hidden → pause" logic from firing.
 *  2. Wraps Document.prototype.addEventListener and Window.prototype.addEventListener
 *     to silently swallow any handler registered for the blocked visibility / focus
 *     events AFTER this script runs.
 *  3. Adds capture-phase listeners to stopImmediatePropagation on those same events
 *     for handlers that were registered BEFORE this script (e.g. by the page itself).
 *  4. Runs setInterval every 4 s to keep window._lact fresh, preventing YouTube's
 *     idle-detection from showing the "Still watching?" popup.
 *  5. Uses a MutationObserver to attach play/pause/ended/loadedmetadata listeners to
 *     every <video> element in the page and reports the playing state back to Kotlin
 *     via AurexMediaBridge.onVideoStateChange(isPlaying, videoWidth, videoHeight).
 *
 * Injected on YouTube pages in onPageStarted (early, before page scripts run) AND
 * onPageFinished (re-applied in case the SPA re-mounts its player).
 */
private const val AUREX_YT_VISIBILITY_JS = """(function(){
if(window.__aurexVis)return;window.__aurexVis=1;

/* 1. Override page-visibility APIs so YouTube never sees the page as hidden. */
try{
  Object.defineProperty(Document.prototype,'hidden',
    {get:function(){return false;},configurable:true,enumerable:true});
  Object.defineProperty(Document.prototype,'webkitHidden',
    {get:function(){return false;},configurable:true,enumerable:true});
  Object.defineProperty(Document.prototype,'visibilityState',
    {get:function(){return 'visible';},configurable:true,enumerable:true});
  Object.defineProperty(Document.prototype,'webkitVisibilityState',
    {get:function(){return 'visible';},configurable:true,enumerable:true});
}catch(e){}

/* 2. Swallow future addEventListener registrations for blocked events. */
var BLOCKED=['visibilitychange','webkitvisibilitychange','mozvisibilitychange','blur','pagehide'];
function wrapAddListener(proto){
  var orig=proto.addEventListener;
  if(!orig||orig.__aurexWrapped)return;
  var wrapped=function(type,fn,opts){
    if(BLOCKED.indexOf(type)>=0)return;
    return orig.call(this,type,fn,opts);
  };
  wrapped.__aurexWrapped=true;
  proto.addEventListener=wrapped;
}
try{wrapAddListener(Document.prototype);}catch(e){}
try{wrapAddListener(Window.prototype);}catch(e){}

/* 3. Capture-phase blockers for handlers already registered. */
function captureBlock(e){
  try{e.stopImmediatePropagation();e.preventDefault();}catch(ex){}
}
var origDA=EventTarget.prototype.addEventListener;
function addCapture(target,type){
  try{origDA.call(target,type,captureBlock,{capture:true,passive:false});}catch(e){}
}
addCapture(document,'visibilitychange');
addCapture(document,'webkitvisibilitychange');
addCapture(document,'mozvisibilitychange');
addCapture(window,'blur');
addCapture(window,'pagehide');

/* 4. Anti-idle: keep YouTube's last-activity timestamp fresh. */
setInterval(function(){try{window._lact=Date.now();}catch(e){}},4000);

/* 5. MutationObserver video bridge → AurexMediaBridge.onVideoStateChange. */
function reportVideoState(video){
  try{
    if(!window.AurexMediaBridge)return;
    var playing=video&&!video.paused&&!video.ended&&video.readyState>2;
    var w=video?video.videoWidth:0;
    var h=video?video.videoHeight:0;
    window.AurexMediaBridge.onVideoStateChange(!!playing,w||0,h||0);
  }catch(e){}
}

function attachVideoListeners(video){
  if(!video||video.__aurexAttached)return;
  video.__aurexAttached=true;
  var onPlay=function(){setTimeout(function(){reportVideoState(video);},100);};
  var onStop=function(){reportVideoState(video);};
  ['play','playing','loadedmetadata'].forEach(function(ev){video.addEventListener(ev,onPlay,{passive:true});});
  ['pause','ended'].forEach(function(ev){video.addEventListener(ev,onStop,{passive:true});});
}

function scanVideos(){
  try{document.querySelectorAll('video').forEach(attachVideoListeners);}catch(e){}
}

scanVideos();
try{
  new MutationObserver(function(){scanVideos();})
    .observe(document.documentElement,{childList:true,subtree:true});
}catch(e){}

})()"""

// ── Browser state ────────────────────────────────────────────────────────────

class BrowserState(private val act: Activity) {
    private val prefs = act.getSharedPreferences("liquid", Context.MODE_PRIVATE)
    private val comp = act as? ComponentActivity

    val tabs = mutableStateListOf<Tab>()
    var current by mutableIntStateOf(0)
    private var nextTabId = 0

    val downloads: DownloadManager =
        (act.application as? AurexApp)?.downloads ?: DownloadManager(act)

    /** Quality sheet shown while a download is being prepared (null = hidden). */
    var qualityPrompt by mutableStateOf<QualityPrompt?>(null)
    private var lastPrefetch = ""
    val blocker = AdBlocker(act)

    /** Smart popup / redirect guard (PopupGuard.kt). Declared before the first tab is created. */
    val guard = PopupGuard()
    var smartGuard by mutableStateOf(true)   // always on: no user switch
    var redirectGuard by mutableStateOf(true) // always on: no user switch
    var guardStrict by mutableStateOf(false)  // removed: smart scoring replaces strict mode
    var popupsBlocked by mutableIntStateOf(prefs.getInt("pgPopups", 0))
    var redirectsBlocked by mutableIntStateOf(prefs.getInt("pgRedirects", 0))
    /** Sites (registrable domains) whose popups and redirects the user allows. */
    val trustedSites = mutableStateListOf<String>().apply {
        addAll(prefs.getStringSet("pgTrusted", emptySet()) ?: emptySet())
    }.also { guard.setUserTrusted(it) }

    /** Page host used for $third-party / $domain filter options (set at the main-frame request, so no stale value). */
    private val topHosts = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun pageHostOf(tab: Tab): String {
        topHosts[tab.id]?.let { if (it.isNotEmpty()) return it }
        val u = runCatching { tab.url }.getOrNull().orEmpty()
        return runCatching { android.net.Uri.parse(u).host }.getOrNull().orEmpty().lowercase()
    }

    init {
        // The filter lists load / update in the background; tabs pick up the new cosmetic rules on their next page.
        blocker.onEngineReady = { tabs.forEach { t -> runCatching { installEarlyScript(t) } } }
    }

    /** What websites are told changed (website language, or the app language it follows): tabs get the new page script. */
    private val webLangHook: () -> Unit = {
        act.runOnUiThread { tabs.forEach { t -> runCatching { installEarlyScript(t) } } }
    }

    // Declared after webLangHook on purpose (a property read before its initializer would be null).
    init { AppLang.setWebListener(webLangHook) }

    /** Called when the activity goes away, so a later one is not talked to through this (dead) state. */
    fun release() { AppLang.clearWebListener(webLangHook) }

    // Privacy
    var adblock by mutableStateOf(prefs.getBoolean("adblock", true))
    var popupBlock by mutableStateOf(prefs.getBoolean("popup", true))
    // HTTP -> HTTPS upgrade is always on (switch removed); see normalize().
    var dnt by mutableStateOf(prefs.getBoolean("dnt", true))
    var blockAdult by mutableStateOf(prefs.getBoolean("blockAdult", false))
    var thirdPartyCookies by mutableStateOf(prefs.getBoolean("thirdParty", false))
    var blocked by mutableIntStateOf(prefs.getInt("blocked", 0))
    var adultBlocked by mutableIntStateOf(prefs.getInt("adultBlocked", 0))
    /** Permanently on (the switch was removed): the adult filter always asks for the phone lock before it is turned off. */
    var adultLocked by mutableStateOf(true)
    /** Switching to a private tab asks for the phone lock (biometric, or PIN / pattern / password). On by default. */
    var incognitoLock by mutableStateOf(prefs.getBoolean("incognitoLock", true))
    /** True after the phone lock was passed for this private visit; cleared once the person is back on normal tabs. */
    var privateUnlocked by mutableStateOf(false)
    private var privateAuthBusy = false
    /** Sites the person allowed after the filter blocked them by mistake (never sites on the adult list). */
    val adultAllowed = mutableStateListOf<String>().apply { addAll(prefs.getStringSet("adultAllowed", emptySet()).orEmpty()) }

    init {
        // Declared after blockAdult on purpose (a property read before its initializer would be null).
        AdultFilter.init(act)
        AdultFilter.setUserAllowed(adultAllowed.toSet())
        if (prefs.contains("adultPin")) prefs.edit().remove("adultPin").putBoolean("adultLock", adultLocked).apply()
        if (blockAdult) AdultFilter.loadBigList()
    }

    // Appearance / feel
    var accent by mutableIntStateOf(0xFF0A84FF.toInt()) // fixed Apple system blue
    // New key: sampling site colours made the whole interface shift hue between
    // pages, so this starts off even for installs that had the old option on.
    var dynamicAccent by mutableStateOf(false) // adaptive site colour removed
    var glass by mutableFloatStateOf(prefs.getFloat("glass", 0.86f))
    var haptics by mutableStateOf(prefs.getBoolean("haptics", true))
    /** 0 light, 1 medium, 2 strong - mirrored into [Haptics.level] by [updateHapticLevel]. */
    var hapticLevel by mutableIntStateOf(prefs.getInt("hapticLevel", 1).coerceIn(0, 2))
    var glow by mutableStateOf(prefs.getBoolean("glow", true))
    // Toolbar position option removed: the bar is always at the bottom.
    var barTop by mutableStateOf(false)
    var autoHide by mutableStateOf(prefs.getBoolean("autoHide", true))
    var barVisible by mutableStateOf(true)
    var toolbarLocked by mutableStateOf(false)
    var themeMode by mutableIntStateOf(prefs.getInt("themeMode", ThemeModes.AUTO))
    var darkWeb by mutableIntStateOf(prefs.getInt("darkWeb", DarkWebModes.AUTO))
    /** Sites where the user switched the dark page filter off (menu: "Dark mode"). Host without "www.". */
    var darkOffHosts by mutableStateOf(prefs.getStringSet("darkOffHosts", emptySet()).orEmpty().toSet())
    var textZoom by mutableIntStateOf(prefs.getInt("textZoom", 100))
    var backgroundPlay by mutableStateOf(prefs.getBoolean("backgroundPlay", true))
    var autoPip by mutableStateOf(prefs.getBoolean("autoPip", true))
    var mediaFab by mutableStateOf(prefs.getBoolean("mediaFab", true))
    /** Master switch of the video downloader. Off = engine not loaded, no video scanning, no download buttons. */
    var videoDownload by mutableStateOf(prefs.getBoolean("videoDl", true))
        private set
    /** The download button inside the floating media button (independent of the play / PiP buttons). */
    /** The floating download button is always available (its switch was removed); it still needs "Video download". */
    var downloadFab by mutableStateOf(true)
    /** Detection of videos on pages: needs the built-in player AND the video downloader. */
    private val mediaDetect: Boolean get() = mediaPlayerEnabled && videoDownload
    /** YouTube plays H.264 (hardware decoded) instead of VP9/AV1: smooth on every phone. */
    var smoothVideo by mutableStateOf(prefs.getBoolean("smoothVideo", true))
    /** Document-start scripts per tab; declared before init so restored tabs can use it. */
    private val earlyHandles = HashMap<Int, ScriptHandler>()
    var pipMode by mutableStateOf(false)

    /** True from the first moment the system starts the app -> PiP animation; hides the player controls early. */
    var pipEntering by mutableStateOf(false)
    /** In-app player mode: the page's own video is full-window with native controls on top (see InlinePlayer.kt). */
    var inlinePlayer by mutableStateOf(false)
    /** Opened from the page's fullscreen button on a landscape video: rotate to landscape once. */
    var inlineWantLandscape = false
    /** Browser chrome (bars, FAB) is hidden in the PiP window and in the in-app player. */
    val bare: Boolean get() = pipMode || inlinePlayer

    /**
     * "Open in player": the SAME <video> the page is playing becomes the player (no second decoder, no reload,
     * no resolve, no buffer: nothing can pause). Falls back to the separate engine only when the page has no video.
     */
    fun openPlayer(ctx: android.content.Context, tab: Tab, url: String, accent: Int?, audio: String?) {
        val w = tab.web
        // Default: the app's own built-in player (PlayerActivity) opens directly, continuing from the page's position.
        if (w == null || inlinePlayer) { launchPlayer(ctx, tab, url, accent, audio); return }
        w.evaluateJavascript("(function(){try{var p=window.__lqPip;return !!(p&&p.enter&&p.enter());}catch(e){return false;}})()") { r ->
            if (r == "true") {
                allowMediaResume()
                if (fullscreen != null) exitFullscreen()      // a leftover system fullscreen view would cover the controls
                inlinePlayer = true
            } else launchPlayer(ctx, tab, url, accent, audio)
        }
    }

    /** Leaves the in-app player: the video simply continues in the page, at the same position. */
    fun exitInlinePlayer() {
        if (!inlinePlayer) return
        inlinePlayer = false
        if (!pipMode) pipCommand("exit()")
    }

    /** Separate engine (quality list, subtitles, download) - picks up exactly where the video is. */
    fun openAdvancedPlayer(ctx: android.content.Context, tab: Tab, height: Int = 0) {
        val url = playableMedia(tab) ?: "blob:aurex-page"
        // The in-app player stays on screen while the full player looks the stream up (4K can take a few
        // seconds); it only closes once the full player has really started, so the page never flashes in between.
        launchPlayer(ctx, tab, url, effectiveAccent, playableAudio(tab, url), height, keepLandscape = true,
            onStarted = { PlayerActivity.whenFirstFrame { exitInlinePlayer() } })
    }

    /** Quality picked from the page's own HLS ladder: the full player plays that variant, from the same position. */
    fun openVariantPlayer(ctx: android.content.Context, tab: Tab, variantUrl: String) {
        launchPlayer(ctx, tab, variantUrl, effectiveAccent, null, keepLandscape = true,
            onStarted = { PlayerActivity.whenFirstFrame { exitInlinePlayer() } })
    }

    // Swipe controls preferences
    var swipeTabClose by mutableStateOf(prefs.getBoolean("swipeTabClose", true))
    var swipeDownloadDelete by mutableStateOf(prefs.getBoolean("swipeDownloadDelete", true))
    /** Skip the quality sheet and always download the highest quality. */
    var alwaysBestQuality by mutableStateOf(prefs.getBoolean("dlAlwaysBest", false))
    var swipeLinkTabSwitch by mutableStateOf(prefs.getBoolean("swipeLinkTabSwitch", true))
    /** Downloads list: swipe a download to the right to delete it (and its file), no confirmation. */
    var swipeDlRightDelete by mutableStateOf(prefs.getBoolean("swipeDlDelete", true))
    /** Downloads list: swipe a finished download to the left to share it. */
    var swipeDlLeftShare by mutableStateOf(prefs.getBoolean("swipeDlShare", true))
    /** Press and hold the Tabs button on the toolbar: a new tab opens with the address bar and keyboard ready. */
    var holdTabsNewTab by mutableStateOf(prefs.getBoolean("holdTabsNew", true))
    /** Press and hold the three-dot menu button on the toolbar: Settings opens. */
    var holdMenuSettings by mutableStateOf(prefs.getBoolean("holdMenuSettings", true))
    /** Tab that should start in address-bar edit mode (keyboard up) the moment it is shown; -1 = none. */
    var editTabId by mutableIntStateOf(-1)

    // Toolbar layout. 0 = single row, 1 = double row. "menu" is always appended.
    // Double-row option removed: the bar is always a single row.
    var toolbarRows by mutableIntStateOf(0)
    var toolbarItems by mutableStateOf(loadToolbarItems())
    /** Items shown on the top row in double-row mode (-1 = auto: fill up to the cap). */
    var toolbarTop by mutableIntStateOf(-1)
    /** Live horizontal page-swipe offset in px (finger on the address pill). */
    var pageDrag by mutableFloatStateOf(0f)
    /** The long screenshot being captured right now (the capture HUD shows it), null when none runs. */
    var shotSession by mutableStateOf<LongShot?>(null)
    fun topCount(size: Int): Int =
        (if (toolbarTop < 0) TOOLBAR_TOP_CAP else toolbarTop).coerceIn(0, minOf(size, TOOLBAR_TOP_CAP))
    // Number of leading toolbarItems drawn to the left of the address pill.
    var toolbarLeft by mutableIntStateOf(prefs.getInt("toolbarLeft", 0))

    // Right-side menu (width locked to fixed 240dp)
    val menuPinned = mutableStateListOf<String>()

    // Debug-build test hooks (set from MainActivity intent extras only when debuggable).
    /** In-app updates from GitHub Releases (see [Updater]). */
    val update = UpdateState(act.applicationContext)
    var openPanelRequest by mutableStateOf<Panel?>(null)
    /** Bumped to close whatever covers the page (Downloads, Tabs, menus ...): see [showMediaPage]. */
    var closePanelsTick by mutableStateOf(0)
    var openMenuRequest by mutableStateOf(false)
    var settingsTab by mutableIntStateOf(0)
    /** Simulates a toolbox drag & drop onto the toolbar (debug builds only). */
    var debugTrayDrag by mutableStateOf<String?>(null)

    // Content
    var desktop by mutableStateOf(prefs.getBoolean("desktop", false))
    var jsEnabled by mutableStateOf(prefs.getBoolean("js", true))
    var blockImages by mutableStateOf(prefs.getBoolean("noimg", false))
    var engine by mutableIntStateOf(prefs.getInt("engine", 0))
    // Videos start by themselves. Older installs saved "false"; flip it once (v3.9), the user can switch it off again.
    var autoplay by mutableStateOf(run {
        if (!prefs.getBoolean("mig_autoplay_v39", false)) {
            prefs.edit().putBoolean("mig_autoplay_v39", true).putBoolean("autoplay", true).apply()
            true
        } else prefs.getBoolean("autoplay", true)
    })
    var showHomeSites by mutableStateOf(prefs.getBoolean("homeSites", true))
    var showRecent by mutableStateOf(prefs.getBoolean("homeRecent", true))

    // Media
    var mediaPlayerEnabled by mutableStateOf(prefs.getBoolean("media", true))

    // Transient UI
    var fullscreen by mutableStateOf<View?>(null)
    private var fullscreenCb: WebChromeClient.CustomViewCallback? = null
    var source by mutableStateOf<SourceData?>(null)
    var screenshot by mutableStateOf<ScreenshotData?>(null)
    var snack by mutableStateOf<Snack?>(null)
    var jsDialog by mutableStateOf<JsDialogState?>(null)

    val bookmarks = mutableStateListOf<Bookmark>()
    val history = mutableStateListOf<HistoryEntry>()

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null

    private var lastDarkTheme: Boolean? = null
    private var lastDarkWeb: Boolean? = null

    private val fileLauncher = comp?.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
    }

    private val permissionLauncher = comp?.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.all { it }
        pendingPermission?.let { req ->
            if (granted) runCatching { req.grant(req.resources) } else req.deny()
        }
        pendingPermission = null
        if (granted) {
            pendingGeoCallback?.invoke(pendingGeoOrigin ?: "", true, false)
        } else {
            pendingGeoCallback?.invoke(pendingGeoOrigin ?: "", false, false)
        }
        pendingGeoCallback = null
        pendingGeoOrigin = null
    }

    init {
        markLive(this)
        loadLibrary()
        loadMenu()
        val saved = prefs.getString("session", "").orEmpty().lines().filter { it.isNotBlank() }
        if (saved.isNotEmpty()) {
            // Only the last tab (the one on screen) loads now; the others load when first shown.
            // Eight pages starting at once is what made launch slow on low-end phones.
            val urls = saved.take(8)
            urls.forEachIndexed { i, u -> newTab(u, defer = i != urls.lastIndex) }
        }
    }

    /** Only the most recently foregrounded browser instance may write preferences. */
    companion object {
        /** A jump the page makes on its own to another site, not an ad for sure but suspicious enough (guard score) to get its own tab. */
        private const val DETOUR_SCORE = 20

        @Volatile private var live: BrowserState? = null

        fun markLive(state: BrowserState) {
            live = state
        }
    }

    // ── Toolbar + menu persistence ───────────────────────────────────────────

    private fun loadToolbarItems(): List<String> {
        val valid = TOOLBAR_ACTIONS.map { it.first }.filter { it != "menu" }.toSet()
        val stored = prefs.getString("toolbarItems", null)
            ?: return DEFAULT_TOOLBAR_ITEMS.split(',')
        // An explicitly empty value stays empty — the user removed every control.
        return stored.split(',').map { it.trim() }
            .filter { it.isNotBlank() && it != "menu" && it in valid }
            .distinct()
            .take(TOOLBAR_MAX_ITEMS)
    }

    /** Adds a control. [lower] puts it on the second row (double-row mode). */
    fun addToolbarControl(id: String, at: Int, leftOfLink: Boolean, lower: Boolean = false) {
        if (id in toolbarItems) return
        if (toolbarItems.size >= TOOLBAR_MAX_ITEMS) {
            notify("Toolbar can hold only $TOOLBAR_MAX_ITEMS icons. Remove one first.")
            return
        }
        val list = toolbarItems.toMutableList()
        val double = toolbarRows == 1
        val oldTop = topCount(list.size)
        var index = at.coerceIn(0, list.size)
        if (double) index = if (lower) index.coerceAtLeast(oldTop) else index.coerceAtMost(oldTop)
        list.add(index, id)
        toolbarItems = list
        toolbarTop = if (double) (if (lower) oldTop else oldTop + 1).coerceIn(0, minOf(list.size, TOOLBAR_TOP_CAP)) else -1
        toolbarLeft = if (leftOfLink) index + 1
        else if (index < toolbarLeft) toolbarLeft + 1 else toolbarLeft
        toolbarLeft = toolbarLeft.coerceIn(0, minOf(list.size, TOOLBAR_TOP_CAP))
        save()
    }

    fun moveToolbarControl(from: Int, to: Int) {
        val list = toolbarItems.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val oldTop = topCount(list.size)
        val wasLeft = from < toolbarLeft
        val goesLeft = to < toolbarLeft
        val moved = list.removeAt(from)
        list.add(to, moved)
        toolbarItems = list
        toolbarLeft = (toolbarLeft +
            (if (goesLeft && !wasLeft) 1 else 0) -
            (if (wasLeft && !goesLeft) 1 else 0)
            ).coerceIn(0, minOf(list.size, TOOLBAR_TOP_CAP))
        toolbarTop = if (toolbarRows == 1)
            (oldTop - (if (from < oldTop) 1 else 0) + (if (to < oldTop) 1 else 0))
                .coerceIn(0, minOf(list.size, TOOLBAR_TOP_CAP))
        else -1
        save()
    }

    fun removeToolbarControl(id: String) {
        val index = toolbarItems.indexOf(id)
        if (index < 0) return
        val oldTop = topCount(toolbarItems.size)
        toolbarItems = toolbarItems - id
        if (index < toolbarLeft) toolbarLeft = (toolbarLeft - 1).coerceAtLeast(0)
        toolbarLeft = toolbarLeft.coerceIn(0, minOf(toolbarItems.size, TOOLBAR_TOP_CAP))
        toolbarTop = if (toolbarRows == 1)
            (oldTop - (if (index < oldTop) 1 else 0)).coerceIn(0, minOf(toolbarItems.size, TOOLBAR_TOP_CAP))
        else -1
        save()
    }

    private fun loadMenu() {
        val raw = prefs.getString("menuPinned", null)
        val ids = raw?.split(',')?.map { it.trim() }?.filter { MenuAction.byId(it) != null }
            ?: MenuAction.defaultPinned
        menuPinned.clear()
        ids.distinct().forEach { menuPinned.add(it) }
    }

    fun isPinned(action: MenuAction): Boolean = action.id in menuPinned

    fun togglePinned(action: MenuAction) {
        if (action.id in menuPinned) menuPinned.remove(action.id) else menuPinned.add(action.id)
        save()
    }

    fun movePinned(action: MenuAction, delta: Int) {
        val i = menuPinned.indexOf(action.id)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, menuPinned.lastIndex)
        if (i == j) return
        val id = menuPinned.removeAt(i)
        menuPinned.add(j, id)
        save()
    }

    fun resetMenu() {
        menuPinned.clear()
        MenuAction.defaultPinned.forEach { menuPinned.add(it) }
        save()
    }

    // ── Persistence ──────────────────────────────────────────────────────────
    private fun loadLibrary() {
        prefs.getString("bookmarks", "").orEmpty().lines().forEach { line ->
            if (line.contains('\t')) {
                val p = line.split('\t', limit = 2)
                if (p[0].isNotBlank()) bookmarks.add(Bookmark(p[0], Uri.decode(p.getOrElse(1) { p[0] }), 0))
            }
        }
        prefs.getString("history", "").orEmpty().lines().forEach { line ->
            val p = line.split('\t', limit = 3)
            if (p.size == 3 && p[1].isNotBlank()) {
                history.add(HistoryEntry(p[1], Uri.decode(p[2]), p[0].toLongOrNull() ?: 0L))
            }
        }
    }

    fun save() {
        if (live !== this) return
        prefs.edit()
            .putBoolean("adblock", adblock)
            .remove("ytadblock").remove("pillWidth").remove("pillHeight")
            .putBoolean("popup", popupBlock)
            .putBoolean("dnt", dnt).putBoolean("blockAdult", blockAdult).putBoolean("thirdParty", thirdPartyCookies)
            .putInt("blocked", blocked).putInt("adultBlocked", adultBlocked)
            .putBoolean("adultLock", adultLocked).putStringSet("adultAllowed", adultAllowed.toSet())
            .putBoolean("incognitoLock", incognitoLock)
            .putBoolean("pgSmart", smartGuard).putBoolean("pgRedirect", redirectGuard).putBoolean("pgStrict", guardStrict)
            .putInt("pgPopups", popupsBlocked).putInt("pgRedirects", redirectsBlocked)
            .putStringSet("pgTrusted", trustedSites.toSet())
            .putStringSet("darkOffHosts", darkOffHosts)
            .putInt("accent", accent).putBoolean("matchSiteColor", dynamicAccent)
            .remove("dynamicAccent")
            .putFloat("glass", glass)
            .putBoolean("haptics", haptics).putInt("hapticLevel", hapticLevel).putBoolean("glow", glow)
            .putBoolean("barTop", barTop).putBoolean("autoHide", autoHide)
            .putInt("themeMode", themeMode).putInt("darkWeb", darkWeb)
            .putInt("textZoom", textZoom).putBoolean("backgroundPlay", backgroundPlay)
            .putBoolean("autoPip", autoPip).putBoolean("mediaFab", mediaFab)
            .putBoolean("videoDl", videoDownload).putBoolean("dlFab", downloadFab)
            .putInt("toolbarRows", toolbarRows)
            .putInt("toolbarTop", toolbarTop)
            .putString("toolbarItems", toolbarItems.joinToString(","))
            .putInt("toolbarLeft", toolbarLeft.coerceIn(0, minOf(toolbarItems.size, TOOLBAR_TOP_CAP)))
            .putString("menuPinned", menuPinned.joinToString(","))
            .putBoolean("desktop", desktop).putBoolean("js", jsEnabled)
            .putBoolean("noimg", blockImages).putInt("engine", engine)
            .putBoolean("autoplay", autoplay).putBoolean("smoothVideo", smoothVideo)
            .putBoolean("homeSites", showHomeSites).putBoolean("homeRecent", showRecent)
            .putBoolean("media", mediaPlayerEnabled)
            .putBoolean("swipeTabClose", swipeTabClose)
            .putBoolean("swipeDownloadDelete", swipeDownloadDelete).putBoolean("dlAlwaysBest", alwaysBestQuality)
            .putBoolean("swipeLinkTabSwitch", swipeLinkTabSwitch)
            .putBoolean("swipeDlDelete", swipeDlRightDelete).putBoolean("swipeDlShare", swipeDlLeftShare)
            .putBoolean("holdTabsNew", holdTabsNewTab).putBoolean("holdMenuSettings", holdMenuSettings)
            .putString("bookmarks", bookmarks.joinToString("\n") { it.url + "\t" + Uri.encode(it.title) })
            .putString("history", history.joinToString("\n") { "${it.time}\t${it.url}\t${Uri.encode(it.title)}" })
            .apply()
    }

    fun deleteRecentDownload() {
        val recent = downloads.items.lastOrNull()
        if (recent == null) {
            notify("No recent downloads to delete")
            return
        }
        downloads.cancel(recent)
        runCatching {
            if (recent.file.exists()) recent.file.delete()
        }
        downloads.items.remove(recent)
        notify("Recent download & local file deleted")
    }

    fun closeCurrentTab() {
        if (tabs.isNotEmpty() && current in tabs.indices) {
            closeTab(current)
            notify("Tab closed")
        }
    }

    // Silent on purpose: the address pill's dots and the card animation already
    // show where you landed, and a snackbar popping up after every swipe read as a blink.
    fun switchToNextTab() {
        if (tabs.size > 1) current = (current + 1) % tabs.size
    }

    fun switchToPrevTab() {
        if (tabs.size > 1) current = (current - 1 + tabs.size) % tabs.size
    }

    private fun saveSession() {
        val urls = tabs.filter { !it.incognito && it.url.startsWith("http") }.map { it.url }
        prefs.edit().putString("session", urls.joinToString("\n")).apply()
    }

    fun onPause() {
        saveSession()
        save()
    }

    fun initialize(intentUrl: String?) {
        if (!intentUrl.isNullOrBlank()) newTab(intentUrl)
        else if (tabs.isEmpty()) newTab()
    }

    /** Settings -> Haptic strength: applies at once, saves, and plays a sample so the new strength can be felt. */
    fun updateHapticLevel(level: Int) {
        hapticLevel = level.coerceIn(0, 2)
        Haptics.level = hapticLevel
        save()
        Haptics.fire(null, Hx.TOGGLE_ON)
    }

    fun notify(text: String) {
        snack = Snack(System.currentTimeMillis(), text)
        // small confirmation tick for every toast (link copied, download started ...)
        Haptics.fire(null, Hx.TICK)
    }

    // ── Dynamic accent ───────────────────────────────────────────────────────

    /** Accent used by the chrome: the website colour when enabled, else the chosen palette. */
    val effectiveAccent: Int
        get() = if (dynamicAccent) tabs.getOrNull(current)?.siteAccent ?: accent else accent

    private fun saneAccent(color: Int): Int? {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.18f) return null
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = hsv[2].coerceIn(0.52f, 0.92f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun parseCssColor(raw: String): Int? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        runCatching { return android.graphics.Color.parseColor(v) }
        val m = Regex("""rgba?\(\s*(\d+)\s*[, ]\s*(\d+)\s*[, ]\s*(\d+)""").find(v) ?: return null
        return runCatching {
            android.graphics.Color.rgb(
                m.groupValues[1].toIntOrNull() ?: 0,
                m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].toIntOrNull() ?: 0
            )
        }.getOrNull()
    }

    private fun accentFromBitmap(bitmap: Bitmap?): Int? {
        if (bitmap == null || bitmap.width < 2 || bitmap.height < 2) return null
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, 18, 18, false) }.getOrNull() ?: return null
        val hsv = FloatArray(3)
        var best: Int? = null
        var bestScore = 0f
        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val c = scaled.getPixel(x, y)
                if (android.graphics.Color.alpha(c) < 150) continue
                android.graphics.Color.colorToHSV(c, hsv)
                if (hsv[1] < 0.22f || hsv[2] < 0.18f) continue
                val score = hsv[1] * (0.4f + hsv[2])
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
        }
        return best?.let { saneAccent(it) }
    }

    private fun applySiteAccent(tab: Tab, color: Int?) {
        // Always store the site colour so toggling the dynamic accent applies instantly.
        tab.siteAccent = color
    }

    // ── Theme / dark websites ────────────────────────────────────────────────

    private fun isSystemDark(): Boolean =
        (act.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun effectiveDarkTheme(): Boolean = when (themeMode) {
        ThemeModes.LIGHT -> false
        ThemeModes.DARK -> true
        else -> isSystemDark()
    }

    fun darkWebEnabled(darkTheme: Boolean = effectiveDarkTheme()): Boolean = when (darkWeb) {
        DarkWebModes.ON -> true
        DarkWebModes.OFF -> false
        else -> darkTheme
    }

    private fun hostOf(url: String?): String =
        runCatching { android.net.Uri.parse(url.orEmpty()).host }.getOrNull().orEmpty()

    fun isDarkOffHost(host: String?): Boolean {
        val k = DarkEngine.hostKey(host)
        return k.isNotEmpty() && darkOffHosts.any { k == it || k.endsWith(".$it") }
    }

    /** Dark page filter active for this address (global mode on AND not switched off for the site). */
    fun darkOnFor(url: String?): Boolean = darkWebEnabled() && !isDarkOffHost(hostOf(url))

    fun toggleDarkForSite(tab: Tab) {
        val key = DarkEngine.hostKey(hostOf(tab.url))
        if (key.isEmpty()) return
        if (!darkWebEnabled()) { notify("Dark websites are off · turn them on in Settings"); return }
        darkOffHosts = if (key in darkOffHosts) darkOffHosts - key else darkOffHosts + key
        save()
        refreshDarkEngine()
        notify(if (key in darkOffHosts) "Dark mode off for $key" else "Dark mode on for $key")
    }

    /** Live re-read of the config in every page (no reload). */
    fun refreshDarkEngine() {
        tabs.forEach { t -> t.web?.evaluateJavascript(DarkEngine.REFRESH_JS, null) }
    }

    /** Page config asked by the document-start engine (runs on a WebView thread). */
    private inner class DarkBridge {
        @android.webkit.JavascriptInterface
        fun cfg(host: String?): String {
            val on = darkWebEnabled() && !isHandsOffHost(host) && !isDarkOffHost(host) && !DarkEngine.isNativeHost(host)
            return "{\"on\":$on,\"b\":${DarkEngine.BRIGHTNESS},\"c\":${DarkEngine.CONTRAST},\"s\":${DarkEngine.SEPIA}}"
        }
    }

    /**
     * Native (algorithmic) darkening is kept ONLY for video sites, where a page filter would hurt video playback.
     * Every other page is handled by [DarkEngine], which also works while the app theme is light.
     */
    private fun applyDarkWeb(web: WebView, enabled: Boolean, url: String? = null) {
        val native = enabled && DarkEngine.isNativeHost(hostOf(url ?: web.url))
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, native)
            } else {
                WebSettingsCompat.setForceDark(
                    web.settings,
                    if (native) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
        }
    }

    /**
     * Called by the theme when light/dark changes. Keeps every live WebView in
     * sync and reloads once so algorithmic darkening can take effect.
     */
    fun syncWebTheme(darkTheme: Boolean) {
        val darkEnabled = darkWebEnabled(darkTheme)
        val themeChanged = lastDarkTheme != null && lastDarkTheme != darkTheme
        val webChanged = lastDarkWeb != null && lastDarkWeb != darkEnabled
        lastDarkTheme = darkTheme
        lastDarkWeb = darkEnabled
        val bg = if (darkTheme || darkEnabled) 0xFF06070B.toInt() else 0xFFFFFFFF.toInt()
        tabs.forEach { t ->
            t.web?.setBackgroundColor(bg)
            t.web?.let { applyDarkWeb(it, darkEnabled) }
        }
        refreshDarkEngine()
        // Only video sites (native darkening) need a reload; every other page updates live.
        if (themeChanged || webChanged) {
            tabs.forEach { if (it.url.startsWith("http") && DarkEngine.isNativeHost(hostOf(it.url))) it.web?.reload() }
        }
    }

    /** Sets the tri-state dark-website mode and refreshes live pages once. */
    fun setDarkWebMode(mode: Int) {
        darkWeb = mode.coerceIn(DarkWebModes.AUTO, DarkWebModes.OFF)
        lastDarkWeb = null
        save()
        applySettings()
        syncWebTheme(effectiveDarkTheme())
        notify(
            when (darkWeb) {
                DarkWebModes.ON -> "Dark websites always on"
                DarkWebModes.OFF -> "Dark websites off"
                else -> "Dark websites follow the app"
            }
        )
    }

    // ── Tabs ─────────────────────────────────────────────────────────────────
    fun newTab(url: String = "", incognito: Boolean = false, defer: Boolean = false, select: Boolean = true): Tab {
        if (tabs.size >= 25) {
            notify("Tab limit reached · close a tab first")
            return tabs.getOrElse(current) { tabs.first() }
        }
        val t = Tab(nextTabId++, incognito)
        t.web = createWeb(t)
        installEarlyScript(t)
        tabs.add(t)
        if (select) current = tabs.lastIndex
        if (url.isNotBlank()) {
            if (defer) {
                t.pendingUrl = url
                t.url = url
                t.title = hostOf(url)
            } else load(t, url)
        }
        barVisible = true
        return t
    }

    /** Loads a restored tab the first time it is shown. */
    fun ensureLoaded(tab: Tab) {
        val u = tab.pendingUrl ?: return
        tab.pendingUrl = null
        load(tab, u)
    }

    /** (Re)installs the scripts that must run before the page's own: touch fix + H.264 for YouTube. */
    private fun installEarlyScript(tab: Tab) {
        val w = tab.web ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            earlyHandles.remove(tab.id)?.remove()
            earlyHandles[tab.id] = WebViewCompat.addDocumentStartJavaScript(
                w,
                earlyScript(smoothVideo, adEarlyScripts() + AppLang.webScript()),
                setOf("*")
            )
        }
    }

    /** The person picked a website language (the page scripts are renewed by AppLang's listener): say when it applies. */
    fun applyWebLanguage() {
        notify("Website language set · applies to pages you open next")
    }

    /** Document-start scripts for ad / popup protection (empty when both are off). */
    private fun adEarlyScripts(): String {
        val sb = StringBuilder()
        if (adblock) sb.append(AdBlocker.NET_GUARD_JS).append(AdBlocker.YT_PRUNE_JS).append(blocker.genericScript())
        if ((popupBlock || adblock) && smartGuard) sb.append("\n;").append(AdBlocker.POPUP_GUARD_JS)
        return sb.toString()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val t = tabs[index]
        val wasCurrent = index == current
        earlyHandles.remove(t.id)
        topHosts.remove(t.id)
        guard.forget(t.id)
        shotSession?.let { if (it.owns(t.web)) it.cancel() }
        runCatching { t.web?.stopLoading(); t.web?.destroy() }
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            current = 0
            newTab()
        } else {
            val opener = if (wasCurrent && t.openerId >= 0) tabs.indexOfFirst { it.id == t.openerId } else -1
            current = when {
                opener >= 0 -> opener
                current > index -> current - 1
                current >= tabs.size -> tabs.lastIndex
                else -> current
            }
        }
        saveSession()
    }

    fun closeAllTabs() {
        earlyHandles.clear()
        tabs.forEach {
            runCatching { it.web?.stopLoading(); it.web?.destroy() }
        }
        tabs.clear()
        current = 0
        newTab()
        notify("All tabs closed")
        saveSession()
    }

    fun selectTab(tab: Tab) {
        val i = tabs.indexOf(tab)
        if (i >= 0) {
            current = i
            barVisible = true
        }
    }

    // ── Navigation ───────────────────────────────────────────────────────────
    fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        val engineUrl = ENGINES[engine.coerceIn(0, ENGINES.lastIndex)].url
        val looksLocal = t.startsWith("localhost") ||
            IPV4_HOST_RE.matches(t)
        var url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("about:") || t.startsWith("file:") || t.startsWith("content:") -> t
            t.contains("://") -> t
            looksLocal -> "http://$t"
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> engineUrl + URLEncoder.encode(t, "UTF-8")
        }
        if (url.startsWith("http://") && !looksLocal && !url.contains("localhost")) {
            url = "https://" + url.removePrefix("http://")
        }
        return url
    }

    fun load(tab: Tab, input: String) {
        val typed = normalize(input)
        if (typed.isNotBlank()) {
            if (blockAdult && AdultFilter.matches(typed)) {
                adultBlockedPage(tab, typed)
                return
            }
            val url = if (blockAdult) AdultFilter.safeSearchUrl(typed) ?: typed else typed
            tab.error = null
            pushHistory(tab, url)
            // DNT is only attached to this one top-level request (never to the page's own XHR / fetch calls), which
            // makes a signed-in Instagram session look odd; Instagram gets a plain, header-for-header normal load.
            val hdr = HashMap<String, String>()
            if (dnt && !isHandsOffHost(url)) hdr["DNT"] = "1"
            // website language: the chosen language is asked for on this top-level request too
            if (!isHandsOffHost(url)) AppLang.webHeader()?.let { hdr["Accept-Language"] = it }
            tab.web?.loadUrl(url, hdr)
            barVisible = true
        }
    }

    /**
     * Shows the "page blocked" screen for [url]: the tab is blanked (so nothing keeps playing underneath),
     * its address stays visible, and Go back returns to the page before. Must run on the UI thread.
     */
    fun adultBlockedPage(tab: Tab, url: String) {
        pushHistory(tab, url)
        blocked = blocked + 1
        adultBlocked += 1
        tab.url = url
        tab.title = "Blocked"
        tab.progress = 1f
        tab.videoActive = false; tab.videoPlaying = false; tab.videoPaused = false; tab.activeMedia = null
        tab.error = "Blocked by the adult-content filter · " + guard.hostOf(url)
        barVisible = true
        runCatching { tab.web?.stopLoading(); tab.web?.loadUrl("about:blank") }
    }

    // ── Adult filter lock: the phone's own lock (biometric, or PIN / pattern / password) ────────
    private fun verifyOwner(reason: String, onOk: () -> Unit) {
        DeviceAuth.verify(act, "Aurex Browser", reason) { ok ->
            if (ok) onOk() else notify("Not verified")
        }
    }

    /** Turning the filter on never asks anything; turning it off asks for the phone lock when the lock is on. */
    fun setAdultFilter(on: Boolean) {
        if (on) {
            blockAdult = true
            AdultFilter.loadBigList()
            save()
            notify("Adult filter on")
        } else if (!adultLocked || !DeviceAuth.isAvailable(act)) {
            blockAdult = false
            save()
            notify("Adult filter off")
        } else {
            verifyOwner("Turn off the adult filter") {
                blockAdult = false
                save()
                notify("Adult filter off")
            }
        }
    }

    /** "Allow this site" on the blocked screen: a site the heuristics got wrong. Needs the phone lock when it is on. */
    fun allowAdultSite(tab: Tab) {
        val host = guard.hostOf(tab.url).removePrefix("www.")
        if (host.isBlank() || !AdultFilter.canAllow(tab.url)) {
            notify("This site can't be allowed")
            return
        }
        val go = {
            if (host !in adultAllowed) adultAllowed.add(host)
            AdultFilter.setUserAllowed(adultAllowed.toSet())
            save()
            tab.error = null
            notify("$host allowed")
            tab.web?.loadUrl(tab.url)
        }
        if (adultLocked && DeviceAuth.isAvailable(act)) verifyOwner("Allow $host") { go() } else go()
    }

    fun clearAdultAllowed() {
        val go = {
            adultAllowed.clear()
            AdultFilter.setUserAllowed(emptySet())
            save()
            notify("Allowed sites cleared")
        }
        if (adultLocked && DeviceAuth.isAvailable(act)) verifyOwner("Clear allowed sites") { go() } else go()
    }

    // ── Private tab lock: the same phone lock, asked when the person switches to a private tab ──────
    /** True while the current tab is private and the phone lock has not been passed yet (the screen is covered). */
    val privateLocked: Boolean
        get() = privateNeedsAuth() && tabs.getOrNull(current)?.incognito == true

    /** True when going from normal tabs to private tabs has to ask for the phone lock first. */
    fun privateNeedsAuth(): Boolean = incognitoLock && !privateUnlocked && DeviceAuth.isAvailable(act)

    /** Runs [onOk] straight away when no lock is needed, otherwise after the phone lock was passed. */
    fun requirePrivate(onCancel: () -> Unit = {}, onOk: () -> Unit) {
        if (!privateNeedsAuth()) {
            onOk()
            return
        }
        if (privateAuthBusy) return
        privateAuthBusy = true
        DeviceAuth.verify(act, "Aurex Browser", "Open private tabs") { ok ->
            privateAuthBusy = false
            if (ok) {
                privateUnlocked = true
                onOk()
            } else {
                notify("Not verified")
                onCancel()
            }
        }
    }

    /** Back to the normal side: the last normal tab (or a fresh one). A blank private tab opened just now is closed. */
    fun leavePrivate() {
        val cur = tabs.getOrNull(current)
        val i = tabs.indexOfLast { !it.incognito }
        if (i >= 0) current = i else newTab()
        barVisible = true
        if (cur != null && cur.incognito && cur.isHome) closeTab(tabs.indexOf(cur))
    }

    /** "Back to normal tabs" on the lock cover. */
    fun cancelPrivateUnlock() {
        privateAuthBusy = false
        leavePrivate()
    }

    /** Turning the lock on never asks anything; turning it off asks for the phone lock. */
    fun toggleIncognitoLock(on: Boolean) {
        if (on) {
            incognitoLock = true
            save()
            notify("Private tab lock on")
        } else if (!DeviceAuth.isAvailable(act)) {
            incognitoLock = false
            save()
            notify("Private tab lock off")
        } else {
            verifyOwner("Turn off the private tab lock") {
                incognitoLock = false
                save()
                notify("Private tab lock off")
            }
        }
    }

    /**
     * Kept for call-sites; the WebView's own back/forward list is the single source of truth now
     * (standard browser behaviour: redirects, scroll position and form state are restored by the engine).
     */
    @Suppress("UNUSED_PARAMETER")
    fun pushHistory(tab: Tab, url: String) { }

    fun canGoBack(tab: Tab): Boolean = tab.web?.canGoBack() == true

    /**
     * How many steps back lead to a page the user really visited. Pages that sent the tab on by themselves
     * (auto-redirect pages, see [Tab.autoRedirectUrls]) are stepped over. 0 = nothing real behind this page.
     */
    private fun realBackSteps(tab: Tab): Int {
        val w = tab.web ?: return 0
        if (!w.canGoBack()) return 0
        val list = runCatching { w.copyBackForwardList() }.getOrNull() ?: return 1
        val cur = list.currentIndex
        var i = cur - 1
        while (i >= 0) {
            val u = runCatching { list.getItemAtIndex(i)?.url }.getOrNull().orEmpty()
            if (u.isEmpty() || u !in tab.autoRedirectUrls) return cur - i
            i--
        }
        return 0
    }

    /**
     * Back, the way the big browsers do it:
     *  1. steps through the page history, over pages that only redirected (no back-loops),
     *  2. a tab a page opened (new window, redirect) is closed and the page that opened it comes back,
     *  3. the first page of an ordinary tab returns to the start screen (history wiped, so Back does not bounce back into it).
     */
    fun navigateBack(tab: Tab) {
        tab.error = null
        val w = tab.web
        val steps = realBackSteps(tab)
        if (w != null && steps > 0) {
            if (steps == 1) w.goBack() else w.goBackOrForward(-steps)
            barVisible = true
            return
        }
        val idx = tabs.indexOf(tab)
        if (idx >= 0 && tab.openerId >= 0 && tabs.any { it.id == tab.openerId }) {
            closeTab(idx)
            barVisible = true
            return
        }
        tab.wipeHistoryOnBlank = true
        w?.loadUrl("about:blank")
        tab.url = ""
        tab.canBack = false
        tab.canForward = false
        barVisible = true
    }

    /** A hidden window a page opened passed the guard: bring it up (only if the user is still on the page that opened it). */
    private fun revealPopup(t: Tab) {
        if (!t.hiddenPopup) return
        t.hiddenPopup = false
        val i = tabs.indexOf(t)
        if (i < 0) return
        if (tabs.getOrNull(current)?.id == t.openerId) {
            current = i
            barVisible = true
        }
    }

    /**
     * A page sent this tab to another site on its own. The page the user is reading stays where it is and the
     * new site opens in a tab of its own: Back / closing that tab returns to the page, nothing is lost or looped.
     */
    private fun openRedirectInNewTab(from: Tab, url: String) {
        val i = tabs.indexOf(from)
        if (i < 0) return
        if (tabs.size >= 25) { from.web?.loadUrl(url); return }
        val now = android.os.SystemClock.uptimeMillis()
        val nt = newTab(url, from.incognito)
        nt.openerId = from.id
        nt.popupGuardUntil = now + 6000
        nt.openerHost = pageHostOf(from)
        quietNotify("Redirect opened in a new tab")
    }

    fun toggleDesktop() {
        desktop = !desktop
        tabs.forEach { t ->
            // Instagram keeps the phone's own user agent whatever the switch says (see AurexWebView.setPreferredUa).
            (t.web as? AurexWebView)?.setPreferredUa(if (desktop) DESKTOP_UA else null)
            t.web?.settings?.apply {
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        }
        tabs.getOrNull(current)?.web?.reload()
        save()
        notify(if (desktop) "Desktop site on" else "Mobile site on")
    }

    fun readerMode(tab: Tab) {
        val hex = String.format(Locale.US, "#%06X", 0xFFFFFF and effectiveAccent)
        tab.web?.evaluateJavascript(READER_JS.replace("__ACCENT__", hex), null)
        notify("Reader mode applied")
    }

    /**
     * Long screenshot of the current page (see [LongShot]): the page scrolls by itself, loads what is on screen and is
     * captured piece by piece; a touch hands the scrolling to the user. The result opens in the preview.
     */
    fun captureScreenshot(tab: Tab) {
        if (shotSession != null) return
        val web = tab.web
        if (web == null || tab.isHome || web.width <= 0 || web.height <= 0) {
            notify("Open a webpage first to capture a screenshot")
            return
        }
        // Long screenshot scrolls the page by itself, fast and in steps: on Instagram that is a machine-like
        // scroll pattern on the live account, so it stays off there (the normal screenshot keys still work).
        if (isHandsOffHost(runCatching { web.url }.getOrNull() ?: tab.url)) {
            notify("Long screenshot is off on Instagram / Facebook to keep your account safe")
            return
        }
        barVisible = false
        val title = tab.title.ifBlank { hostOf(tab.url).ifBlank { "Webpage" } }
        val session = LongShot(web, if (effectiveDarkTheme()) 0xFF0A0B0F.toInt() else 0xFFFFFFFF.toInt()) { bmp, message, screens ->
            shotSession = null
            barVisible = true
            if (bmp != null) screenshot = ScreenshotData(bmp, true, title, screens)
            else if (message != null) notify(message)
        }
        shotSession = session
        session.start()
    }

    fun cancelShot() {
        shotSession?.cancel()
    }

    private fun pngName(data: ScreenshotData): String {
        val safeName = data.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(36).ifBlank { "page" }
        return "Aurex_${safeName}_${System.currentTimeMillis()}.png"
    }

    /** Saves the picture to the gallery (Pictures/AurexBrowser). Encoding a tall PNG takes a moment: off the UI thread. */
    fun saveScreenshot(data: ScreenshotData, onDone: (Boolean) -> Unit) {
        Thread {
            val fileName = pngName(data)
            val ok = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/AurexBrowser")
                    }
                    val uri = act.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri != null && act.contentResolver.openOutputStream(uri)?.use { out ->
                        data.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } == true
                } else {
                    val dir = act.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)?.apply { mkdirs() } ?: act.filesDir
                    java.io.File(dir, fileName).outputStream().use { out ->
                        data.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } catch (e: Throwable) {
                false
            }
            act.runOnUiThread {
                notify(if (ok) "Saved to Pictures/AurexBrowser" else "Save failed")
                onDone(ok)
            }
        }.start()
    }

    /** Opens the share sheet. The picture goes through the cache folder (the gallery copy is not needed for sharing). */
    fun shareScreenshot(data: ScreenshotData, onDone: () -> Unit = {}) {
        Thread {
            val file = try {
                val dir = java.io.File(act.cacheDir, "shots").apply { mkdirs() }
                dir.listFiles()?.forEach { runCatching { it.delete() } }
                java.io.File(dir, pngName(data)).also { f ->
                    f.outputStream().use { out -> data.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                }
            } catch (e: Throwable) {
                null
            }
            act.runOnUiThread {
                if (file == null) {
                    notify("Unable to share screenshot")
                } else {
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.files", file)
                        val intent = Intent(Intent.ACTION_SEND)
                            .setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        act.startActivity(Intent.createChooser(intent, "Share Screenshot"))
                    }.onFailure { notify("Unable to share screenshot") }
                }
                onDone()
            }
        }.start()
    }

    fun applySettings() {
        tabs.forEach { t ->
            t.web?.settings?.apply {
                @SuppressLint("SetJavaScriptEnabled")
                javaScriptEnabled = jsEnabled
                blockNetworkImage = blockImages
                textZoom = this@BrowserState.textZoom
                mediaPlaybackRequiresUserGesture = !autoplay
                javaScriptCanOpenWindowsAutomatically = !(popupBlock || adblock)
            }
            (t.web as? AurexWebView)?.setPreferredUa(if (desktop) DESKTOP_UA else null)
            CookieManager.getInstance().setAcceptThirdPartyCookies(t.web, !t.incognito && thirdPartyCookies)
            t.web?.let { applyDarkWeb(it, darkWebEnabled()) }
            installEarlyScript(t)
        }
    }

    fun updateFilters() {
        blocker.refresh(true)
        notify("Updating filter lists…")
    }

    // ── Library actions ──────────────────────────────────────────────────────
    fun toggleBookmark(tab: Tab) {
        if (tab.url.isBlank()) return
        val existing = bookmarks.firstOrNull { it.url == tab.url }
        if (existing != null) {
            bookmarks.remove(existing)
            notify("Bookmark removed")
        } else {
            bookmarks.add(0, Bookmark(tab.url, tab.title, System.currentTimeMillis()))
            notify("Bookmark saved")
        }
        save()
    }

    fun isBookmarked(url: String): Boolean = bookmarks.any { it.url == url }

    fun deleteHistory(entry: HistoryEntry) {
        history.remove(entry); save()
    }

    fun clearHistory() {
        history.clear(); save(); notify("History cleared")
    }

    // ── Cookies: the Meta login survives "clear" ──────────────────────────────
    // Wiping the Instagram / Facebook cookies makes the next visit a brand-new, never-seen device that has to log
    // in again from the same IP. That "new device + fresh login" event is a top trigger for checkpoints, locks and
    // bans. So clearing cookies removes everything EXCEPT the Meta sites' cookies (device id, session, csrf).
    private class KeptCookie(val url: String, val domain: String, val name: String, val value: String)

    private val META_COOKIE_SITES = listOf(
        "https://www.instagram.com/" to "instagram.com",
        "https://i.instagram.com/" to "instagram.com",
        "https://accountscenter.instagram.com/" to "instagram.com",
        "https://www.facebook.com/" to "facebook.com",
        "https://m.facebook.com/" to "facebook.com",
        "https://web.facebook.com/" to "facebook.com",
        "https://auth.meta.com/" to "meta.com",
        "https://www.meta.com/" to "meta.com",
        "https://www.threads.net/" to "threads.net",
        "https://www.threads.com/" to "threads.com",
        "https://www.messenger.com/" to "messenger.com"
    )
    /** Cookies the sites mark HttpOnly (the cookie manager hands them over without the flag, so it is put back). */
    private val META_HTTP_ONLY = setOf("sessionid", "xs", "datr", "sb", "fr")

    private fun snapshotMetaCookies(): List<KeptCookie> {
        val cm = CookieManager.getInstance()
        val out = ArrayList<KeptCookie>()
        for ((url, domain) in META_COOKIE_SITES) {
            val raw = runCatching { cm.getCookie(url) }.getOrNull() ?: continue
            for (part in raw.split(';')) {
                val kv = part.trim()
                val i = kv.indexOf('=')
                if (i > 0) out.add(KeptCookie(url, domain, kv.substring(0, i), kv.substring(i + 1)))
            }
        }
        return out.distinctBy { it.domain + "|" + it.name + "|" + it.value }
    }

    private fun restoreMetaCookies(keep: List<KeptCookie>) {
        if (keep.isEmpty()) return
        val cm = CookieManager.getInstance()
        for (c in keep) {
            val httpOnly = if (c.name in META_HTTP_ONLY) "; HttpOnly" else ""
            runCatching {
                cm.setCookie(c.url, c.name + "=" + c.value + "; Domain=." + c.domain + "; Path=/; Secure; Max-Age=31536000" + httpOnly)
            }
        }
        runCatching { cm.flush() }
    }

    /** Removes every cookie except the Meta (Instagram / Facebook / Threads) login. */
    private fun removeCookiesKeepingMeta() {
        val keep = snapshotMetaCookies()
        CookieManager.getInstance().removeAllCookies { restoreMetaCookies(keep) }
    }

    fun clearCookies() {
        removeCookiesKeepingMeta()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { it.web?.clearCache(true) }
        notify("Cookies & site data cleared · Instagram / Facebook login kept")
    }

    fun clearCache() {
        tabs.forEach { it.web?.clearCache(true) }
        notify("Cache cleared")
    }

    fun clearBrowsingData() {
        history.clear()
        removeCookiesKeepingMeta()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach {
            it.web?.clearCache(true)
            it.web?.clearHistory()
            it.web?.clearFormData()
        }
        save()
        notify("Browsing data cleared · Instagram / Facebook login kept")
    }

    /**
     * "Video download" master switch. Off: running video downloads are paused, the stream list and
     * the engine's look-up cache are dropped, page scanning stops and no download button is shown, so
     * the downloader holds no memory and does no background work. On: the engine warms up again and
     * the pages that are already open are scanned once more.
     */
    fun setVideoDownload(on: Boolean, quiet: Boolean = false) {
        if (videoDownload == on) return
        videoDownload = on
        VideoDl.enabled = on
        val app = act.applicationContext
        if (on) {
            YtDlpEngine.warmUpAsync(app)
            tabs.forEach { t ->
                (t.web as? AurexWebView)?.sniffEnabled = true
                if (mediaPlayerEnabled && !isHandsOffHost(t.web?.url)) t.web?.evaluateJavascript(MEDIA_JS, null)
            }
            if (!quiet) notify("Video download on")
        } else {
            val paused = downloads.pauseVideo()
            qualityPrompt = null
            lastPrefetch = ""
            YtDlpEngine.release()
            tabs.forEach { t ->
                (t.web as? AurexWebView)?.sniffEnabled = false
                t.media.clear(); t.videoSrcs.clear(); t.mediaRef.clear()
            }
            if (!quiet) {
                notify(
                    if (paused > 0) "Video download off · $paused paused" else "Video download off · engine stopped"
                )
            }
        }
        save()
    }

    fun resetSettings() {
        adblock = true; popupBlock = true
        dnt = true; thirdPartyCookies = false
        accent = 0xFF0A84FF.toInt(); dynamicAccent = false
        glass = 0.86f; haptics = true; glow = true
        hapticLevel = 1; Haptics.level = 1
        barTop = false; autoHide = true; textZoom = 100
        themeMode = ThemeModes.AUTO; darkWeb = DarkWebModes.AUTO; darkOffHosts = emptySet()
        backgroundPlay = true; autoPip = true; mediaFab = true; downloadFab = true
        setVideoDownload(true, quiet = true)
        desktop = false; jsEnabled = true; blockImages = false; engine = 0
        autoplay = true; smoothVideo = true; showHomeSites = true; showRecent = true
        toolbarRows = 0; toolbarItems = DEFAULT_TOOLBAR_ITEMS.split(','); toolbarLeft = 0; toolbarTop = -1
        mediaPlayerEnabled = true
        swipeDlRightDelete = true; swipeDlLeftShare = true
        holdTabsNewTab = true; holdMenuSettings = true
        resetMenu()
        lastDarkTheme = null; lastDarkWeb = null
        save(); applySettings()
        tabs.forEach { it.web?.reload() }
        notify("Settings reset")
    }

    // ── Sharing / printing / clipboard ───────────────────────────────────────
    fun share(url: String) {
        if (url.isBlank()) return
        runCatching {
            act.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                    "Share link"
                )
            )
        }
    }

    fun copy(url: String) {
        if (url.isBlank()) return
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("URL", url))
        notify("Link copied")
    }

    fun download(url: String, mime: String? = null) {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) {
            notify("This link cannot be downloaded")
            return
        }
        val tab = tabs.getOrNull(current)
        val isHlsLink = mime?.contains("mpegurl", true) == true || url.contains(".m3u8", true)
        if (!videoDownload) {
            // Engine is off: a plain link is still saved as an ordinary file, streams need the switch.
            if (isHlsLink) notify(VideoDl.OFF_HINT)
            else {
                downloads.start(url, tab?.web?.settings?.userAgentString, null, mime, tab?.url)
                notify("Download started")
            }
            return
        }
        // The page is asked which video is playing, because YouTube changes videos without a page
        // load and the stored tab address can be the home page.
        VideoPage.resolve(tab) { page, title ->
            val ua = tab?.web?.settings?.userAgentString
            val ref = page.ifBlank { tab?.url }
            val name = title.ifBlank { null }
            val cookie = if (tab?.incognito == true) null
            else siteCookie(url)

            // The embed player's own address: video hosts with hotlink protection only answer a request that names it.
            val frameRef = tab?.mediaRef?.get(url)
            // Sites without a quality choice: start the video that is playing right now.
            val direct = {
                downloads.start(url, ua, null, mime, ref, title = name, frameReferer = frameRef)
                notify("Download started")
            }
            val hlsOrDirect = {
                if (isHlsLink) askHlsQuality(url, mime, ua, frameRef ?: ref, cookie, name, direct) else direct()
            }
            when {
                YtDlpEngine.isItemUrl(page) && !isHlsLink ->
                    askEngineQuality(tab, page, title, null)
                YtDlpEngine.isYoutubeUrl(page) && !isHlsLink ->
                    notify("Open the YouTube video page, then download")
                YtDlpEngine.isQualitySite(page) ->
                    askEngineQuality(tab, page, title, hlsOrDirect)
                else -> hlsOrDirect()
            }
        }
    }

    /**
     * Download button for pages whose video is a blob: stream with no direct link (Instagram reels
     * on the home feed, the Reels tab, stories). The post that is on screen is found through the
     * page (VideoPage), then handed to the engine with the quality sheet.
     */
    fun downloadPlayingPage() {
        if (!videoDownload) { notify(VideoDl.OFF_HINT); return }
        val tab = tabs.getOrNull(current)
        VideoPage.resolve(tab) { page, title ->
            if (YtDlpEngine.isItemUrl(page)) {
                askEngineQuality(tab, page, title, null)
            } else {
                val direct = tab?.let { playableMedia(it) }
                if (direct != null) download(direct)
                // No link to hand over (blob / MSE player, protected embed): the engine looks at the page itself.
                else if (page.startsWith("http", true) && !isHandsOffHost(page)) askEngineQuality(tab, page, title, null)
                else notify("Open the reel or story first, then tap download")
            }
        }
    }

    private fun sizeDetail(bytes: Long?): String =
        if (bytes != null && bytes > 0) "≈ " + formatBytes(bytes) else ""

    /** Typical total bitrate (kbit/s, video only) of a height, used when a site gives no size at all. */
    private fun typicalKbps(h: Int): Int = when {
        h >= 2160 -> 16000
        h >= 1440 -> 9000
        h >= 1080 -> 4200
        h >= 720 -> 2300
        h >= 480 -> 1100
        h >= 360 -> 650
        h >= 240 -> 330
        else -> 160
    }

    /**
     * Size of every height: the site's own number when it has one, else scaled from the nearest known
     * height, else from a typical bitrate x duration. Second = true when the number is a prediction.
     */
    private fun predictSizes(heights: List<Int>, media: YtDlpEngine.ResolvedMedia): Map<Int, Pair<Long, Boolean>> {
        val known = media.sizeByHeight.filterValues { it > 0 }
        val out = HashMap<Int, Pair<Long, Boolean>>()
        heights.forEach { h ->
            val real = known[h]
            out[h] = when {
                real != null -> real to false
                known.isNotEmpty() -> {
                    val hk = known.keys.minByOrNull { kotlin.math.abs(it - h) }!!
                    ((known[hk]!! * Math.pow(h.toDouble() / hk, 1.5)).toLong()) to true
                }
                media.durationSec > 0 ->
                    (((typicalKbps(h) + 128) * 1000.0 / 8.0 * media.durationSec).toLong()) to true
                else -> 0L to true
            }
        }
        return out
    }

    /** Quality sheet fed by the engine (YouTube, Instagram, X, xHamster ... any site it lists heights for). */
    private fun askEngineQuality(tab: Tab?, page: String, title: String, fallback: (() -> Unit)?) {
        // "Always best quality": no sheet, the highest quality starts at once
        if (alwaysBestQuality) {
            startUniversalFor(tab, page, YtQuality.BEST, title)
            return
        }
        val prompt = QualityPrompt(title.ifBlank { hostOf(page) })
        qualityPrompt = prompt
        val cookies = if (tab?.incognito == true) null
        else siteCookie(page)
        val appCtx = act.applicationContext
        Thread {
            val media = runCatching { YtDlpEngine.resolveCached(appCtx, page, cookies) }.getOrNull()
            act.runOnUiThread {
                if (qualityPrompt !== prompt) return@runOnUiThread   // dismissed while looking
                val heights = media?.heights.orEmpty().filter { it >= 144 }
                if (media == null || heights.size < 2) {
                    qualityPrompt = null
                    if (fallback != null) fallback()
                    else startUniversalFor(tab, page, YtQuality.BEST, title)
                    return@runOnUiThread
                }
                val sizes = predictSizes(heights, media)
                val opts = ArrayList<QualityOption>()
                heights.forEach { h ->
                    val (bytes, guess) = sizes[h] ?: (0L to true)
                    opts.add(QualityOption(MediaTools.labelForHeight(h), sizeDetail(bytes), h, bytes = bytes, guess = guess))
                    if (h in media.hdrHeights) {
                        val hb = bytes + bytes / 10
                        opts.add(QualityOption(MediaTools.labelForHeight(MediaTools.hdrOf(h)), sizeDetail(hb), MediaTools.hdrOf(h), bytes = hb, guess = guess))
                    }
                }
                val dur = media.durationSec
                val m4a = if (media.audioBytes > 0) media.audioBytes else (dur * 128_000.0 / 8.0).toLong()
                val mp3 = (dur * 192_000.0 / 8.0).toLong()
                opts.add(QualityOption("MP3", "audio only", audioQuality = YtQuality.MP3, bytes = mp3, guess = true))
                opts.add(QualityOption("M4A", "audio only", audioQuality = YtQuality.M4A, bytes = m4a, guess = media.audioBytes <= 0))
                prompt.options = opts
                prompt.loading = false
                prompt.onPick = { o ->
                    qualityPrompt = null
                    val aq = o.audioQuality
                    if (aq != null) startUniversalFor(tab, page, aq, title)
                    else startUniversalFor(tab, page, YtQuality.BEST, title, o.height)
                }
            }
        }.start()
    }

    /** Quality sheet for an HLS master playlist; starts at once when it only has one quality. */
    private fun askHlsQuality(
        url: String, mime: String?, ua: String?, ref: String?, cookie: String?,
        name: String?, direct: () -> Unit
    ) {
        Thread {
            val variants = runCatching { downloads.listHlsVariants(url, ua, ref, cookie) }.getOrDefault(emptyList())
            act.runOnUiThread {
                if (variants.size < 2) {
                    direct()
                    return@runOnUiThread
                }
                if (alwaysBestQuality) {
                    val best = variants.first()   // sorted: highest quality first
                    downloads.start(
                        best.url, ua, null, "application/vnd.apple.mpegurl", ref,
                        title = name, qualityLabel = MediaTools.qualityLabel(best.width, best.height)
                    )
                    notify("Download started · best quality")
                    return@runOnUiThread
                }
                val prompt = QualityPrompt(name ?: hostOf(url))
                prompt.loading = false
                prompt.options = variants.map { v ->
                    val label = MediaTools.qualityLabel(v.width, v.height)
                    QualityOption(label, sizeDetail(v.estBytes), v.height, hlsUrl = v.url, bytes = v.estBytes, guess = true)
                }
                prompt.onPick = { o ->
                    qualityPrompt = null
                    downloads.start(
                        o.hlsUrl ?: url, ua, null, "application/vnd.apple.mpegurl", ref,
                        title = name, qualityLabel = o.label
                    )
                    notify("Download started · ${o.label}")
                }
                qualityPrompt = prompt
            }
        }.start()
    }

    /** Looks the playing video up in the background, so Play and Download are instant later. */
    private fun prefetchPage(tab: Tab, url: String) {
        // The look-up starts the engine for every video page: never while "Video download" is off.
        if (!videoDownload || tab.incognito || !url.startsWith("http", true) || !YtDlpEngine.isItemUrl(url)) return
        if (isHandsOffHost(url)) return   // Instagram: only ever looked up when the user asks
        val id = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)
        val key = if (id != null && YtDlpEngine.isYoutubeUrl(url)) "https://www.youtube.com/watch?v=$id" else url
        if (key == lastPrefetch) return
        lastPrefetch = key
        val cookies = siteCookie(key)
        val appCtx = act.applicationContext
        // Starts almost at once: the lookup runs on its own thread and the engine is already warm, so
        // waiting for the page only delayed the quality list / Play / Download that come after it.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            YtDlpEngine.prefetch(appCtx, key, cookies)
        }, 300L)
    }

    /**
     * Universal video/audio download of the page [pageUrl] (any site the engine understands).
     * [title] names the file; when it is null and [pageUrl] is the open page, the page's own
     * video title is used.
     */
    fun downloadUniversal(pageUrl: String, quality: YtQuality, title: String? = null) {
        if (!videoDownload) { notify(VideoDl.OFF_HINT); return }
        if (!YtDlpEngine.canTry(pageUrl)) {
            notify("Open a page with a video first")
            return
        }
        val tab = tabs.getOrNull(current)
        val live = runCatching { tab?.web?.url }.getOrNull()
        val samePage = tab != null && (tab.url == pageUrl || live == pageUrl)
        if (samePage && (YtDlpEngine.isYoutubeUrl(pageUrl) || YtDlpEngine.isInstagramUrl(pageUrl) || YtDlpEngine.isOkUrl(pageUrl))) {
            // Ask the page for the real video id + title (see VideoPage).
            VideoPage.resolve(tab) { page, t -> startUniversalFor(tab, page, quality, title ?: t) }
        } else {
            val t = title ?: if (samePage) VideoPage.clean(tab?.title) else null
            startUniversalFor(tab, pageUrl, quality, t)
        }
    }

    private fun startUniversalFor(
        tab: Tab?, pageUrl: String, quality: YtQuality, title: String?, height: Int = 0
    ) {
        if (!YtDlpEngine.canTry(pageUrl)) {
            notify("Open a page with a video first")
            return
        }
        // YouTube home / feeds / channels are not one video: nothing to download from them.
        if (YtDlpEngine.isYoutubeUrl(pageUrl) && !YtDlpEngine.isItemUrl(pageUrl)) {
            notify("Open a YouTube video first (not the home page), then download")
            return
        }
        // Media links the page exposed are kept as backup sources for sites WITHOUT their own
        // extractor (HLS / DASH / MP4 behind the player).
        val onThisPage = tab != null && (tab.url == pageUrl || runCatching { tab.web?.url }.getOrNull() == pageUrl)
        val backup = if (onThisPage) universalCandidates(tab) else emptyList()
        downloads.startUniversal(
            pageUrl,
            quality,
            tab?.web?.settings?.userAgentString,
            useCookies = tab?.incognito != true,
            title = title?.takeIf { it.isNotBlank() },
            altUrls = backup,
            height = height,
            altRefs = backup.mapNotNull { u -> tab?.mediaRef?.get(u)?.let { r -> u to r } }.toMap()
        )
        notify("Download started" + if (height > 0) " · " + MediaTools.labelForHeight(height) else "")
    }

    private fun universalCandidates(tab: Tab?): List<String> {
        if (tab == null) return emptyList()
        // YouTube / Instagram / X ... have their own extractor: the page is the source, and their
        // sniffed stream links (googlevideo "videoplayback") are signed halves of a video that only
        // produce junk files. Backup links are for sites WITHOUT an extractor.
        if (YtDlpEngine.isItemUrl(tab.url)) return emptyList()
        return (listOfNotNull(tab.activeMedia) + tab.media.toList())
            .filter { it.startsWith("http", true) && (isMediaUrl(it) || it in tab.videoSrcs) && mediaScore(it) > 0 }
            .filter { YtDlpEngine.isBackupSource(tab.url, it) }
            .distinct()
            .sortedByDescending { mediaScore(it) }
            .take(4)
    }

    /**
     * Best direct URL for the floating media button. Direct video wins over
     * audio, and the currently playing source wins over stale discovered links.
     */
    fun playableMedia(tab: Tab): String? {
        val active = tab.activeMedia
            ?.takeIf { it.isNotBlank() && !it.startsWith("blob:") && !it.startsWith("data:") && (isMediaUrl(it) || it in tab.videoSrcs) }
        val discovered = tab.media.asSequence()
            .filter { !it.startsWith("blob:") && !it.startsWith("data:") && (isMediaUrl(it) || it in tab.videoSrcs) }
            .maxByOrNull { mediaScore(it) + if (it in tab.videoSrcs) 15 else 0 }
        return when {
            active != null && (discovered == null || mediaScore(active) >= mediaScore(discovered)) -> active
            discovered != null && mediaScore(discovered) > 0 -> discovered
            !active.isNullOrBlank() -> active
            else -> null
        }
    }

    /** Audio track that matches an adaptive video stream, if the page exposed one. */
    fun playableAudio(tab: Tab?, videoUrl: String?): String? {
        if (tab == null || videoUrl == null) return null
        val videoKind = mediaKind(videoUrl)
        // Only adaptive streams are split into separate video/audio tracks.
        // Pairing an arbitrary MP3 with a normal MP4 usually breaks playback.
        if (videoKind != "HLS" && videoKind != "DASH") return null
        val videoHost = runCatching { URI(videoUrl).host }.getOrNull()
        return tab.media.asSequence()
            .filter { it != videoUrl && !it.startsWith("blob:") && !it.startsWith("data:") }
            .filter { isMediaUrl(it) }
            .filter { runCatching { URI(it).host }.getOrNull() == videoHost }
            .filter { candidate ->
                val kind = mediaKind(candidate)
                if (kind == "AUDIO") return@filter true
                if (kind != "HLS" && kind != "DASH") return@filter false
                val lower = candidate.lowercase()
                lower.contains("audio") || lower.contains("/aud") ||
                    lower.contains("aac") || lower.contains("aud_")
            }
            .maxByOrNull { mediaScore(it) }
    }

    /** Asks MainActivity to enter browser PiP (used by the YouTube background option). */
    fun enterBrowserPip(): Boolean = (act as? MainActivity)?.enterBrowserPip() == true

    /** Runs a snippet against the page's PiP helper (window.__lqPip) in the current tab. */
    /** A PiP video must be able to resume itself without a fresh tap. */
    fun allowMediaResume() {
        runCatching { tabs.getOrNull(current)?.web?.settings?.mediaPlaybackRequiresUserGesture = false }
    }

    /** Until this time (epoch-ms) a video that was playing in PiP counts as trusted content. */
    @Volatile var pipTrustUntil = 0L

    /**
     * Quality change without leaving the player: the page's own <video> switches to [url] in place and continues
     * at the same position. [done] gets: start | unsupported | nov | err (see PIP_JS setSource).
     */
    fun switchPageSource(url: String, done: (String) -> Unit) {
        val w = tabs.getOrNull(current)?.web ?: run { done("nov"); return }
        w.evaluateJavascript(
            "(function(){try{var p=window.__lqPip;return (p&&p.setSource)?p.setSource(" +
                org.json.JSONObject.quote(url) + "):'nov';}catch(e){return 'err';}})()"
        ) { raw -> done(raw.orEmpty().trim('"')) }
    }

    /** Outcome of the last [switchPageSource]: pending | ok | fail | "" */
    fun pageSourceState(done: (String) -> Unit) {
        val w = tabs.getOrNull(current)?.web ?: run { done("fail"); return }
        w.evaluateJavascript("(function(){try{return window.__lqSrc||'';}catch(e){return '';}})()") { raw ->
            done(raw.orEmpty().trim('"'))
        }
    }

    fun pipCommand(call: String) {
        val w = tabs.getOrNull(current)?.web ?: return
        w.evaluateJavascript(
            "(function(){try{var p=window.__lqPip;if(p){p." + call + "}}catch(e){}})()", null
        )
    }

    // ── Find in page ─────────────────────────────────────────────────────────
    fun find(tab: Tab, query: String) {
        if (query.isBlank()) tab.web?.clearMatches() else tab.web?.findAllAsync(query)
    }

    fun findNext(tab: Tab, forward: Boolean) {
        runCatching { tab.web?.findNext(forward) }
    }

    fun clearFind(tab: Tab) {
        runCatching { tab.web?.clearMatches() }
    }

    // ── Fullscreen ───────────────────────────────────────────────────────────
    fun exitFullscreen() {
        fullscreen = null
        runCatching { fullscreenCb?.onCustomViewHidden() }
        fullscreenCb = null
    }

    // ── WebView factory ──────────────────────────────────────────────────────
    private fun webBackground(): Int = if (effectiveDarkTheme()) 0xFF06070B.toInt() else 0xFFFFFFFF.toInt()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWeb(tab: Tab): WebView = AurexWebView(act).apply {
        // Fix 1: AurexWebView's init already sets LAYER_TYPE_HARDWARE, RENDERER_PRIORITY_IMPORTANT,
        // mediaPlaybackRequiresUserGesture = false, javaScriptEnabled, domStorageEnabled.
        // Enable background-media throttle suppression whenever backgroundPlay is on.
        backgroundMediaActive = backgroundPlay
        sniffEnabled = videoDownload     // off = the stream sniffer never starts in this tab
        setBackgroundColor(webBackground())
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = jsEnabled
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = !(popupBlock || adblock)
            // Respect the autoplay preference; allowMediaResume() sets this to false
            // once a video is playing so the renderer can resume it after a PiP transition.
            mediaPlaybackRequiresUserGesture = !autoplay
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            offscreenPreRaster = false
            loadsImagesAutomatically = !blockImages
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            setGeolocationEnabled(true)
            cacheMode = if (tab.incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            blockNetworkImage = blockImages
            textZoom = this@BrowserState.textZoom
        }
        // Desktop-site switch; Instagram always keeps the phone's own user agent (see AurexWebView.setPreferredUa).
        setPreferredUa(if (desktop) DESKTOP_UA else null)
        // WebView adds "X-Requested-With: <this app's package name>" to every request, which tells a site
        // (Instagram included) exactly which unknown app the account is being used from. Chromium documents
        // it as a passive fingerprinting source; an empty allow-list stops it going to any site. Best effort.
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        }
        // Note: setRendererPriorityPolicy is already called in AurexWebView.init; skip duplicate.
        applyDarkWeb(this, darkWebEnabled())
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, !tab.incognito && thirdPartyCookies)
        addJavascriptInterface(WebBridge(tab), "AurexMedia")
        // Fix 3: AurexMediaBridge handles service start/stop from JS video-state callbacks.
        addJavascriptInterface(AurexMediaBridge(tab), "AurexMediaBridge")
        // Lets the document-start script ask the filter engine before a script sends a request (see AdBlocker.NET_GUARD_JS).
        addJavascriptInterface(AdGuardBridge(tab), "AurexAdGuard")
        addJavascriptInterface(AdultBridge(tab), "AurexAdult")
        addJavascriptInterface(DarkBridge(), "AurexDark")
        setFindListener { total, cur, _ ->
            tab.findTotal = total
            tab.findCurrent = if (total > 0) cur + 1 else 0
        }
        // A finger swipe on the page decides the toolbar on its own, whether or not the page can scroll:
        // swipe up always hides it, swipe down brings it back (so a short page can never trap a hidden bar).
        // The listener only watches (returns false), the page still gets every touch.
        val swipeDistance = android.view.ViewConfiguration.get(act).scaledTouchSlop * 3f
        var swipeX = 0f
        var swipeY = 0f
        var swipeDone = true
        setOnTouchListener { v, ev ->
            // A long screenshot is running: the touch only tells it that the user took over scrolling.
            val shot = shotSession
            if (shot != null && shot.owns(v)) {
                shot.onTouch(ev.actionMasked)
                return@setOnTouchListener false
            }
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    swipeX = ev.x; swipeY = ev.y
                    swipeDone = false
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> swipeDone = true   // pinch zoom: not a swipe
                android.view.MotionEvent.ACTION_MOVE -> if (!swipeDone) {
                    val dx = ev.x - swipeX
                    val dy = ev.y - swipeY
                    if (abs(dy) >= swipeDistance && abs(dy) > abs(dx) * 1.3f) {
                        swipeDone = true
                        if (autoHide && !toolbarLocked && !tab.isHome && fullscreen == null && !inlinePlayer) {
                            if (dy < 0) barVisible = false else barVisible = true
                        }
                    }
                }
            }
            false
        }
        var scrollAccum = 0
        setOnScrollChangeListener { _, _, y, _, oldY ->
            if (!autoHide || toolbarLocked || tab.isHome || shotSession != null) return@setOnScrollChangeListener
            val dy = y - oldY
            if (dy > 0) {
                // A short downward swipe hides the chrome, but never before the
                // page has actually moved away from the top.
                if (scrollAccum < 0) scrollAccum = 0
                scrollAccum = (scrollAccum + dy).coerceAtMost(260)
                if (scrollAccum > 36 && y > 120) barVisible = false
            } else if (dy < 0) {
                // Any upward movement brings the bar straight back, exactly like
                // Safari — reading upward should never feel unresponsive.
                if (scrollAccum > 0) scrollAccum = 0
                scrollAccum = (scrollAccum + dy).coerceAtLeast(-140)
                if (!barVisible || scrollAccum < -14 || y <= 10) {
                    barVisible = true
                    scrollAccum = 0
                }
            }
        }
        setDownloadListener { url, ua, cd, mime, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                notify("This download cannot be saved")
            } else {
                downloads.start(url, ua, cd, mime, tab.url, title = VideoPage.clean(tab.title).ifBlank { null })
                notify("Download started · adaptive speed")
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val reqHost = request.url.host.orEmpty()
                if (request.isForMainFrame) topHosts[tab.id] = reqHost.lowercase()
                // Instagram / Meta CDN: left completely alone. Nothing is blocked, rewritten, probed or re-requested,
                // and its CDN links (signed halves of a DASH stream) are never kept as media links: the page
                // address is what goes to the engine.
                // The same holds for every sub-request of a Meta page (Facebook, Threads ...): nothing of it is filtered.
                if (!request.isForMainFrame && (isMetaCdnHost(reqHost) || isHandsOffHost(pageHostOf(tab)))) return null
                // Video chunks are requested hundreds of times; they never go through the filters
                // (a false match there stalls playback) and only the first one is listed as media.
                if (reqHost.endsWith("googlevideo.com")) {
                    if (mediaDetect && !request.isForMainFrame &&
                        runCatching { tab.media.none { "googlevideo.com" in it } }.getOrDefault(false)
                    ) addMedia(tab, url)
                    return null
                }
                // YouTube's own player files / thumbnails / player API: pure string test, no filter
                // scan, so the first frame is not held up by ~15 regexes per unique URL.
                if (isTrustedYoutubeAsset(reqHost, request.url.path.orEmpty())) return null
                if (request.isForMainFrame && !isHandsOffHost(reqHost) && vetPopup(tab, url)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (blockAdult) {
                    // Main frame: whole address. Everything else (frames, images, scripts): cached host check only.
                    val adult = if (request.isForMainFrame) AdultFilter.matches(url) else AdultFilter.isAdultHost(reqHost)
                    if (adult) {
                        countBlocked()
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                if (adblock && !request.isForMainFrame && !isHandsOffHost(pageHostOf(tab)) &&
                    blocker.shouldBlockRequest(
                        url, reqHost, pageHostOf(tab),
                        blocker.inferType(url, request.requestHeaders?.get("Accept"))
                    )
                ) {
                    countBlocked()
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (mediaDetect && !request.isForMainFrame) {
                    // The Referer of the request is the FRAME that asked (the embed player), which is what the
                    // video host expects when the link is played or saved later.
                    val frameRef = request.requestHeaders?.get("Referer") ?: request.requestHeaders?.get("referer")
                    if (isMediaUrl(url)) addMedia(tab, url, frameRef)
                    else if (looksLikeMediaFetch(request)) addMedia(tab, url, frameRef, confirmed = true)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false
                // Instagram <-> Facebook <-> Threads sign-in and Accounts Center hop between sites with redirects.
                // The popup / redirect guards below must never cut such a hop or push it into another tab: the login
                // would restart (again and again) and that "repeated login attempts" pattern is what gets accounts locked.
                val metaNav = request.isForMainFrame && (isHandsOffHost(uri.host) || isHandsOffHost(guardPageHost(tab)))
                if (request.isForMainFrame && blockAdult && AdultFilter.matches(uri.toString())) {
                    act.runOnUiThread {
                        blocked = blocked + 1
                        notify("Adult content blocked")
                    }
                    return true
                }
                if (request.isForMainFrame && blockAdult && (scheme == "http" || scheme == "https")) {
                    val safe = AdultFilter.safeSearchUrl(uri.toString())
                    if (safe != null) {
                        pushHistory(tab, safe)
                        view.loadUrl(safe)
                        return true
                    }
                }
                if (request.isForMainFrame && !metaNav && vetPopup(tab, uri.toString(), request.isRedirect)) return true
                // Smart redirect guard: a page that sends the tab to another site on its own, or an ad-looking jump.
                if (request.isForMainFrame && !metaNav && redirectGuard && (popupBlock || adblock) &&
                    (scheme == "http" || scheme == "https")
                ) {
                    val target = uri.toString()
                    val v = guard.assess(
                        PopupGuard.Kind.NAVIGATION, tab.id, target, guardPageHost(tab),
                        request.hasGesture(), request.isRedirect,
                        blocker.shouldBlock(target) || guard.knownPopNetwork(target), guardStrict,
                        adultJump(target, guardPageHost(tab))
                    )
                    if (v.block) {
                        act.runOnUiThread {
                            blocked = blocked + 1
                            redirectsBlocked += 1
                            quietNotify("Redirect blocked · " + guard.hostOf(target))
                        }
                        return true
                    }
                    // Not an ad for sure, but the PAGE (not a tapped link) is sending this tab to another site:
                    // the new site gets its own tab, the page the user is on stays. Back returns to it.
                    val here = view.url.orEmpty()
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!request.isRedirect && v.score >= DETOUR_SCORE && here.startsWith("http", true) &&
                        !tab.hiddenPopup && now - lastDetourAt > 1200 && tabs.size < 25
                    ) {
                        lastDetourAt = now
                        act.runOnUiThread { openRedirectInNewTab(tab, target) }
                        return true
                    }
                }
                if (request.isForMainFrame && !metaNav && adblock && (request.isRedirect || !request.hasGesture()) &&
                    blocker.shouldBlock(uri.toString())
                ) {
                    act.runOnUiThread { blocked = blocked + 1; quietNotify("Blocked an ad redirect") }
                    return true
                }
                if (scheme == "http" || scheme == "https" || scheme == "about") {
                    if (request.isForMainFrame && uri.toString() != "about:blank") {
                        pushHistory(tab, uri.toString())
                        if (scheme != "about") {
                            if (request.hasGesture()) {
                                tab.autoRedirectUrls.remove(uri.toString())
                            } else if (!request.isRedirect) {
                                // The page left by itself (script / meta refresh) without any tap: Back must step over it.
                                val here = view.url.orEmpty()
                                if (here.startsWith("http", true)) tab.autoRedirectUrls.add(here)
                            }
                        }
                    }
                    return false
                }
                // A page that was not tapped must not be able to launch other apps (fake "install" ads).
                if (!request.hasGesture() && (popupBlock || adblock)) {
                    act.runOnUiThread { blocked = blocked + 1; quietNotify("Blocked an app launch") }
                    return true
                }
                return try {
                    if (scheme == "intent") {
                        val raw = uri.toString()
                        try {
                            val i = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE)
                                component = null
                                selector = null
                            }
                            act.startActivity(i)
                        } catch (e: Exception) {
                            val fb = raw.substringAfter("S.browser_fallback_url=", "").substringBefore(';')
                            val target = runCatching { java.net.URLDecoder.decode(fb, "UTF-8") }.getOrDefault("")
                            if (target.startsWith("http")) view.loadUrl(target)
                            else notify("No app can open this link")
                        }
                    } else {
                        act.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    true
                } catch (e: Exception) {
                    notify("No app can open ${uri.scheme} links"); true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                applyDarkWeb(view, darkWebEnabled(), url)
                // The blank page behind a "page blocked" screen must not clear that screen.
                if (url == "about:blank" && tab.error?.startsWith("Blocked") == true) {
                    tab.progress = 1f
                    return
                }
                // Adult filter also runs here so anything that slipped past the request check is still stopped.
                if (blockAdult && AdultFilter.matches(url)) {
                    adultBlockedPage(tab, url)
                    return
                }
                tab.url = if (url == "about:blank") "" else url
                if (url.startsWith("http", true)) guard.visit(tab.id, guard.hostOf(url))
                if (tab.hiddenPopup && url != "about:blank") revealPopup(tab)
                shotSession?.let { if (it.owns(view)) it.cancel() }
                tab.progress = 0.08f
                tab.error = null
                tab.favicon = favicon
                tab.siteAccent = null
                tab.videoActive = false
                tab.videoPlaying = false
                tab.videoPaused = false
                tab.activeMedia = null
                tab.canBack = view.canGoBack()
                tab.canForward = view.canGoForward()
                tab.media.clear(); tab.videoSrcs.clear(); tab.mediaRef.clear()
                tab.findCurrent = 0; tab.findTotal = 0
                barVisible = true

                // Fix 3: inject visibility-spoof early (document-start) for YouTube so
                // YouTube's own scripts never see the page as hidden.
                val isYt = url.contains("youtube.com") || url.contains("youtu.be") ||
                    url.contains("youtube-nocookie.com")
                if (backgroundPlay && isYt) {
                    view.evaluateJavascript(AUREX_YT_VISIBILITY_JS, null)
                }
            }

            /**
             * Single-page sites (YouTube!) change the address with history.pushState and never
             * fire onPageStarted / onPageFinished. Without this the tab kept the first address
             * (e.g. the YouTube home page) while a video played, and the downloader and the
             * built-in player were given that address instead of the video's.
             */
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (url.startsWith("http", true) && tab.url != url && !isReload) { tab.url = url }
                prefetchPage(tab, url)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                // Inject before first paint so ads and popups never flash.
                injectProtection(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == "about:blank" && tab.error?.startsWith("Blocked") == true) {
                    tab.progress = 1f
                    return
                }
                tab.progress = 1f
                tab.canBack = view.canGoBack()
                tab.canForward = view.canGoForward()
                if (url == "about:blank") {
                    tab.url = ""
                    tab.title = "New tab"
                    if (tab.wipeHistoryOnBlank) {
                        tab.wipeHistoryOnBlank = false
                        tab.autoRedirectUrls.clear()
                        runCatching { view.clearHistory() }
                        tab.canBack = false
                        tab.canForward = false
                    }
                    return
                }
                tab.url = url
                tab.title = view.title?.takeIf { it.isNotBlank() } ?: hostOf(url)
                if (!tab.incognito && url.startsWith("http")) recordHistory(url, tab.title)
                prefetchPage(tab, url)
                injectProtection(view, url)
                if (blockAdult && url.startsWith("http", true)) view.evaluateJavascript(AdultFilter.SCAN_JS, null)
                val isYt = url.contains("youtube.com") || url.contains("youtu.be") ||
                    url.contains("youtube-nocookie.com")
                if (backgroundPlay && isYt) {
                    view.evaluateJavascript(YT_BACKGROUND_JS, null)
                    // Fix 3: re-apply the full visibility spoof after the page has loaded
                    // (YouTube's SPA may replace its player on navigation).
                    view.evaluateJavascript(AUREX_YT_VISIBILITY_JS, null)
                }
                if (!isHandsOffHost(url)) {
                    if (mediaPlayerEnabled) {
                        view.evaluateJavascript(MEDIA_JS, null)
                    }
                    view.evaluateJavascript(PIP_JS, null)
                }
                view.evaluateJavascript(THEME_COLOR_JS) { raw ->
                    val css = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                    val parsed = parseCssColor(css.orEmpty())?.let { saneAccent(it) }
                    if (parsed != null) applySiteAccent(tab, parsed)
                    else if (tab.siteAccent == null) applySiteAccent(tab, accentFromBitmap(tab.favicon))
                }
                val darkEnabled = darkWebEnabled()
                applyDarkWeb(view, darkEnabled, url)
                // WebViews without document-start scripts: the engine is injected here instead (idempotent).
                if (!isHandsOffHost(url)) view.evaluateJavascript(DarkEngine.JS + "\n;" + DarkEngine.REFRESH_JS, null)
                saveSession()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    tab.progress = 1f
                    tab.error = error.description?.toString() ?: "Page could not be loaded"
                }
            }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                tab.progress = 1f
                tab.error = "Secure connection failed"
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress / 100f
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                if (title.isNotBlank()) {
                    tab.title = title
                    refreshMediaInfo(tab)
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                tab.favicon = icon
                if (tab.siteAccent == null) applySiteAccent(tab, accentFromBitmap(icon))
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val now = android.os.SystemClock.uptimeMillis()
                if ((popupBlock || adblock) && !guard.isUserTrusted(pageHostOf(tab))) {
                    // Allowed only for a real tap, and at most one window per tap burst.
                    val tooSoon = now - lastPopupAt < 1500
                    if (!isUserGesture || tooSoon || tabs.size >= 25) {
                        lastPopupAt = now
                        act.runOnUiThread { blocked = blocked + 1; popupsBlocked += 1; quietNotify("Popup blocked") }
                        return false
                    }
                }
                lastPopupAt = now
                // The window starts hidden and comes up when its first address passed the guard: an ad window
                // is closed unseen instead of flashing up and snapping back to the page.
                val nt = newTab("", tab.incognito, select = false)
                nt.hiddenPopup = true
                nt.openerId = tab.id
                nt.popupGuardUntil = now + 6000
                nt.openerHost = pageHostOf(tab)
                (resultMsg.obj as WebView.WebViewTransport).webView = nt.web
                resultMsg.sendToTarget()
                // A window that never loads anything is shown anyway after a moment (scripted pages that fill it later).
                act.window.decorView.postDelayed({ revealPopup(nt) }, 2500)
                return true
            }

            override fun onCloseWindow(window: WebView) {
                // window.close() from a page we opened: drop the tab and go back to the opener.
                act.window.decorView.post {
                    val i = tabs.indexOfFirst { it.web === window }
                    if (i >= 0) closeTab(i)
                }
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                val intent = fileChooserParams.createIntent()
                return try {
                    fileLauncher?.launch(intent); true
                } catch (e: Exception) {
                    pendingFileCallback = null; false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val perms = mutableListOf<String>()
                request.resources.forEach { r ->
                    when (r) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> perms.add(Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> perms.add(Manifest.permission.RECORD_AUDIO)
                    }
                }
                if (perms.isEmpty()) {
                    runCatching { request.grant(request.resources) }
                    return
                }
                val missing = perms.filter {
                    ContextCompat.checkSelfPermission(act, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    runCatching { request.grant(request.resources) }
                } else {
                    pendingPermission = request
                    permissionLauncher?.launch(missing.toTypedArray())
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                // Instagram never gets the phone's GPS position: a precise location that disagrees with the
                // connection's IP (VPN, mobile data, travel) is a classic "account used from somewhere odd" signal.
                if (isHandsOffHost(origin)) {
                    callback.invoke(origin, false, false)
                    return
                }
                val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                val missing = perms.filter {
                    ContextCompat.checkSelfPermission(act, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoCallback = callback
                    pendingGeoOrigin = origin
                    permissionLauncher?.launch(missing.toTypedArray())
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // The page's own fullscreen / player button must open OUR player: the page's <video> goes full-window
                // with the in-app controls (same element, nothing reloads). The system fullscreen view is only the
                // fallback for pages without a usable video.
                val web = tab.web
                // Our player (or the PiP window) already owns the screen: the page may NOT put its own fullscreen view on
                // top of it (that view covered the in-app controls: touch dead in landscape, picture jumping).
                if (web != null && (inlinePlayer || pipMode)) {
                    runCatching { callback.onCustomViewHidden() }
                    return
                }
                if (web == null) { fullscreen = view; fullscreenCb = callback; return }
                web.evaluateJavascript(
                    "(function(){try{var p=window.__lqPip;return !!(p&&p.enter&&p.enter());}catch(e){return false;}})()"
                ) { r ->
                    if (r == "true") {
                        allowMediaResume()
                        if (fullscreen != null) exitFullscreen()
                        inlineWantLandscape = tab.videoWidth >= tab.videoHeight
                        inlinePlayer = true
                        runCatching { callback.onCustomViewHidden() }   // let the page leave its own fullscreen
                    } else {
                        fullscreen = view
                        fullscreenCb = callback
                    }
                }
            }

            override fun onHideCustomView() {
                fullscreen = null
                fullscreenCb = null
            }

            /** "Leave this page?" traps: leaving is never blocked, and a flood of prompts is answered with "leave". */
            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                if (!(popupBlock || adblock)) return false
                if (!dialogAllowed(view)) { result.confirm(); return true }
                jsDialog = JsDialogState("Leave this page?", message.ifBlank { "Changes you made may not be saved." }, "", false) { ok, _ ->
                    if (ok) result.confirm() else result.cancel()
                }
                return true
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                if (!dialogAllowed(view)) { result.cancel(); return true }
                jsDialog = JsDialogState("Alert", message, "", false) { _, _ -> result.confirm() }
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                if (!dialogAllowed(view)) { result.cancel(); return true }
                jsDialog = JsDialogState("Confirm", message, "", false) { ok, _ ->
                    if (ok) result.confirm() else result.cancel()
                }
                return true
            }

            override fun onJsPrompt(
                view: WebView, url: String, message: String,
                defaultValue: String?, result: android.webkit.JsPromptResult
            ): Boolean {
                if (!dialogAllowed(view)) { result.cancel(); return true }
                jsDialog = JsDialogState("Input", message, defaultValue ?: "", true) { ok, text ->
                    if (ok) result.confirm(text ?: "") else result.cancel()
                }
                return true
            }
        }
    }

    /** Cosmetic, popup and YouTube ad protection, safe to run more than once. */
    private fun injectProtection(view: WebView, url: String) {
        if (isHandsOffHost(url)) return
        val isYt = url.contains("youtube.com") || url.contains("youtu.be") ||
            url.contains("youtube-nocookie.com")
        if (adblock && !isYt) view.evaluateJavascript(AdBlocker.HIDE_JS, null)
        if (popupBlock || adblock) view.evaluateJavascript(AdBlocker.POPUP_JS, null)
        if (adblock && isYt) view.evaluateJavascript(AdBlocker.YT_JS, null)
        if (adblock) {
            val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
            if (host.isNotEmpty()) blocker.hostCosmeticJs(host)?.let { view.evaluateJavascript(it, null) }
        }
    }

    // ── Popup / dialog guards ────────────────────────────────────────────────
    private val pendingBlocked = java.util.concurrent.atomic.AtomicInteger()
    private val blockedFlush = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Called from network threads; the counter state is touched once per ~400 ms burst. */
    private fun countBlocked() {
        pendingBlocked.incrementAndGet()
        if (blockedFlush.compareAndSet(false, true)) {
            act.window.decorView.postDelayed({
                blockedFlush.set(false)
                val n = pendingBlocked.getAndSet(0)
                if (n > 0) blocked = blocked + n
            }, 400)
        }
    }

    @Volatile private var lastPopupAt = 0L
    @Volatile private var lastDetourAt = 0L
    private var lastQuietAt = 0L
    private var dialogBurst = 0
    private var dialogBurstAt = 0L

    /** Same message as notify(), but a burst of blocks shows one toast instead of a pile of them. */
    private fun quietNotify(text: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastQuietAt < 2500) return
        lastQuietAt = now
        notify(text)
    }

    /**
     * A window a page just opened is closed again if its first address is an ad/tracker one, or (smart guard)
     * if the jump behaves like an ad: script-opened window to another site with ad-style address.
     * Returns true when the load must be stopped.
     */
    private fun vetPopup(tab: Tab, url: String, redirect: Boolean = false): Boolean {
        if (!(popupBlock || adblock)) return false
        if (tab.popupGuardUntil == 0L || android.os.SystemClock.uptimeMillis() > tab.popupGuardUntil) return false
        if (url == "about:blank") return false
        if (guard.isUserTrusted(tab.openerHost)) return false
        val listHit = blocker.shouldBlock(url) || guard.knownPopNetwork(url)
        val bad = if (smartGuard) {
            // The tap that opened this window was recorded on the OPENER tab.
            val src = if (tab.openerId >= 0) tab.openerId else tab.id
            guard.assess(PopupGuard.Kind.WINDOW, src, url, tab.openerHost, true, redirect, listHit, guardStrict, adultJump(url, tab.openerHost)).block
        } else listHit
        if (!bad) return false
        act.window.decorView.post {
            val i = tabs.indexOf(tab)
            if (i >= 0) {
                blocked = blocked + 1
                popupsBlocked += 1
                quietNotify("Popup blocked")
                closeTab(i)
            }
        }
        return true
    }

    /** A normal page jumping to an adult site: an adult ad (an adult page linking to adult pages is not). */
    private fun adultJump(target: String, pageHost: String): Boolean =
        AdultFilter.isAdultHost(guard.hostOf(target)) && !AdultFilter.isAdultHost(pageHost)

    /** Where a jump comes from: the opener for a window that was just opened, else the page the tab is on. */
    private fun guardPageHost(tab: Tab): String {
        if (tab.openerHost.isNotEmpty() && tab.popupGuardUntil != 0L &&
            android.os.SystemClock.uptimeMillis() <= tab.popupGuardUntil
        ) return tab.openerHost
        return pageHostOf(tab)
    }

    // Sites the user trusts: the guard stays quiet there (popups, redirects, click-hijack removal).
    fun currentSiteHost(): String {
        val t = tabs.getOrNull(current) ?: return ""
        return guard.registrable(guard.hostOf(t.url))
    }

    fun isSiteTrusted(host: String): Boolean = guard.registrable(host) in trustedSites

    fun setSiteTrusted(host: String, on: Boolean) {
        val r = guard.registrable(host)
        if (r.isEmpty()) return
        if (on) { if (r !in trustedSites) trustedSites.add(r) } else trustedSites.remove(r)
        guard.setUserTrusted(trustedSites)
        applySettings(); save()
        notify(if (on) "Popups and redirects allowed on $r · reload the page" else "Popup guard is active on $r again")
    }

    fun clearTrustedSites() {
        trustedSites.clear()
        guard.setUserTrusted(trustedSites)
        applySettings(); save()
    }

    /** Only the tab you are looking at may show a dialog, and not more than three in a row. */
    private fun dialogAllowed(view: WebView): Boolean {
        if (tabs.getOrNull(current)?.web !== view) return false
        if (jsDialog != null) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now - dialogBurstAt > 6000) dialogBurst = 0
        dialogBurstAt = now
        dialogBurst++
        if (dialogBurst == 4) quietNotify("Blocked repeated page dialogs")
        return dialogBurst <= 3
    }

    private fun isTrustedYoutubeAsset(host: String, path: String): Boolean {
        if (host.endsWith("ytimg.com") || host.endsWith("ggpht.com") || host.endsWith("gstatic.com")) return true
        if (host.endsWith("youtube.com")) {
            return path.startsWith("/s/") ||
                path.startsWith("/youtubei/v1/player") || path.startsWith("/youtubei/v1/next") ||
                path.startsWith("/youtubei/v1/browse") || path.startsWith("/youtubei/v1/search")
        }
        return false
    }

    private fun addMedia(tab: Tab, url: String, ref: String? = null, confirmed: Boolean = false) {
        if (url.length > 2048) return
        act.runOnUiThread {
            if (!mediaDetect) return@runOnUiThread
            if (confirmed) tab.videoSrcs.add(url)
            if (!ref.isNullOrBlank() && ref.startsWith("http", true) && ref != tab.mediaRef[url]) tab.mediaRef[url] = ref
            if (url !in tab.media && tab.media.size < 40) tab.media.add(url)
        }
    }

    private val STATIC_EXT = setOf(
        "js", "mjs", "css", "json", "html", "htm", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico",
        "woff", "woff2", "ttf", "otf", "eot", "map", "txt", "xml", "pdf", "zip", "apk", "rar", "7z", "wasm"
    )

    /**
     * A link with no file extension and no mime hint that the page loads like a video: the browser asks media
     * elements for it in ranges ("Range: bytes=0-"). Sites that serve their video from ".../get?token=..." were
     * never found before because only the address was looked at.
     */
    private fun looksLikeMediaFetch(request: WebResourceRequest): Boolean {
        if (!request.method.equals("GET", true)) return false
        val h = request.requestHeaders ?: return false
        val range = h["Range"] ?: h["range"] ?: return false
        if (!range.startsWith("bytes=", true)) return false
        val accept = (h["Accept"] ?: h["accept"]).orEmpty()
        if (accept.startsWith("image/", true) || accept.startsWith("text/", true)) return false
        val ext = request.url.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext !in STATIC_EXT
    }

    private fun recordHistory(url: String, title: String) {
        history.removeAll { it.url == url }
        history.add(0, HistoryEntry(url, title, System.currentTimeMillis()))
        while (history.size > 400) history.removeAt(history.lastIndex)
        if (history.size % 5 == 0) save()
    }

    /**
     * Answers [AdBlocker.NET_GUARD_JS]: should this request (sent from the page's JavaScript) be blocked?
     * Uses the same rules as the network layer, so a request is blocked here exactly when it would be
     * emptied there - but the page now sees a real failure instead of a successful empty reply.
     */
    private inner class AdGuardBridge(private val tab: Tab) {
        @JavascriptInterface
        fun blocked(url: String?, kind: Int): Boolean {
            if (!adblock || url.isNullOrEmpty() || url.length > 4096) return false
            return try {
                val uri = android.net.Uri.parse(url)
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return false
                val host = uri.host?.lowercase().orEmpty()
                if (host.isEmpty() || host.endsWith("googlevideo.com")) return false
                if (isTrustedYoutubeAsset(host, uri.path.orEmpty())) return false
                val hit = blocker.shouldBlockRequest(url, host, pageHostOf(tab), kind)
                if (hit) countBlocked()
                hit
            } catch (_: Throwable) {
                false
            }
        }

        /** Smart popup guard (AdBlocker.POPUP_GUARD_JS): may this window / link jump happen? true = block it. */
        @JavascriptInterface
        fun popup(url: String?, flags: Int): Boolean {
            if (!(popupBlock || adblock) || url == null || url.length > 4096) return false
            return try {
                val listHit = (adblock && blocker.shouldBlock(url)) || guard.knownPopNetwork(url)
                val v = guard.assessOpen(url, pageHostOf(tab), flags, listHit, guardStrict, adultJump(url, pageHostOf(tab)))
                if (v.block) {
                    countBlocked()
                    act.runOnUiThread { popupsBlocked += 1; quietNotify("Popup blocked") }
                }
                v.block
            } catch (_: Throwable) {
                false
            }
        }

        /** A real tap landed on a link: the navigation it causes is then recognised as intended. */
        @JavascriptInterface
        fun clicked(href: String?) {
            if (!href.isNullOrEmpty() && href.length <= 4096) guard.click(tab.id, href)
        }

        /** The user trusts this site: the guard script stays out of it. */
        @JavascriptInterface
        fun exempt(): Boolean = guard.isUserTrusted(pageHostOf(tab))

        /** kind 2 = an invisible click-hijack layer was removed (kind 1, a blocked window, is counted in popup()). */
        @JavascriptInterface
        fun note(kind: Int) {
            if (kind == 2) {
                countBlocked()
                act.runOnUiThread { popupsBlocked += 1; quietNotify("Invisible ad layer removed") }
            }
        }
    }

    /** Answers [AdultFilter.SCAN_JS]: the page says it is adult content (label, rating or terms). */
    private inner class AdultBridge(private val tab: Tab) {
        @JavascriptInterface
        fun flag(reason: String?) {
            if (!blockAdult) return
            act.runOnUiThread {
                val live = runCatching { tab.web?.url }.getOrNull().orEmpty()
                val host = guard.hostOf(live)
                if (live.startsWith("http", true) && host.isNotEmpty() && !AdultFilter.skipsPageScan(host)) {
                    adultBlockedPage(tab, live)
                }
            }
        }
    }

    private inner class WebBridge(private val tab: Tab) {
        @JavascriptInterface
        fun found(url: String) {
            if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return
            addMedia(tab, url)
        }

        /** A <video> in any frame (SRC_JS) loaded this link: media, whatever its address looks like. [frame] = that frame's page. */
        @JavascriptInterface
        fun src(url: String?, frame: String?) {
            if (url.isNullOrBlank() || !url.startsWith("http", true)) return
            addMedia(tab, url, frame, confirmed = true)
        }

        @JavascriptInterface
        fun playing(
            url: String,
            title: String,
            width: Int,
            height: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float
        ) {
            act.runOnUiThread {
                tab.videoActive = true
                if (!url.isNullOrBlank() && !url.startsWith("blob:") && !url.startsWith("data:")) {
                    tab.activeMedia = url
                }
                if (title.isNotBlank() && tab.title.isBlank()) tab.title = title
                if (width > 0 && height > 0) {
                    tab.videoWidth = width
                    tab.videoHeight = height
                }
                if (right > left && bottom > top) {
                    tab.videoRectLeft = left.coerceIn(0f, 1f)
                    tab.videoRectTop = top.coerceIn(0f, 1f)
                    tab.videoRectRight = right.coerceIn(0f, 1f)
                    tab.videoRectBottom = bottom.coerceIn(0f, 1f)
                }
            }
        }

        @JavascriptInterface
        fun stopped() {
            act.runOnUiThread { tab.videoActive = false }
        }

        /** The page asked for fullscreen while its video plays and PIP_JS already took the video over: show OUR player. */
        @JavascriptInterface
        fun fullscreen() {
            act.runOnUiThread {
                if (inlinePlayer || pipMode) return@runOnUiThread
                allowMediaResume()
                if (fullscreen != null) exitFullscreen()
                inlineWantLandscape = tab.videoWidth >= tab.videoHeight
                inlinePlayer = true
            }
        }

        /** state: 0 = no video, 1 = a video is playing, 2 = that video is paused. */
        @JavascriptInterface
        fun vstate(state: Int, width: Int, height: Int) {
            act.runOnUiThread {
                val wasPlaying = tab.videoPlaying
                val now = System.currentTimeMillis()
                // Returning from PiP the page re-lays out and briefly reports "no video" (state 0)
                // although the video never stopped. Ignore that blip, otherwise the stable clock,
                // the keep-alive service and the PiP button all reset and Home / the PiP button
                // only work again after a fresh 3 s.
                if (state == 0 && wasPlaying && now < pipTrustUntil) return@runOnUiThread
                tab.videoPlaying = state == 1
                tab.videoPaused = state == 2
                if (state == 1) {
                    if (!wasPlaying) {
                        // Brief stop/start (buffering, seek, PiP hand-over) keeps the running clock;
                        // just after a PiP return the video is already trusted (not an ad).
                        tab.videoPlayingStableSince = when {
                            now < pipTrustUntil -> now - MainActivity.AUTO_PIP_STABLE_MS
                            tab.videoPlayingStableSince > 0L && now - tab.videoStoppedAt < 2_500L ->
                                tab.videoPlayingStableSince
                            else -> now
                        }
                    }

                    // Fix 5: keep the AurexWebView renderer alive and start the foreground service.
                    (tab.web as? AurexWebView)?.backgroundMediaActive = true
                    if (!wasPlaying) startMediaService(tab)
                } else {
                    // Video stopped or paused: remember when, the clock itself is only reused when
                    // playback resumes within 2.5 s (see above); a longer pause re-measures from zero.
                    if (wasPlaying) tab.videoStoppedAt = now

                    // Fix 5: stop the foreground service when no video is playing.
                    // Not while the PiP window is open: a pause there is a button press, and
                    // dropping the renderer / service on every pause is what let the system
                    // throttle the page and flip play <-> pause repeatedly.
                    if (wasPlaying && !pipMode) {
                        (tab.web as? AurexWebView)?.backgroundMediaActive = false
                        // A paused video keeps its player card (play button, progress); only a vanished one removes it.
                        if (state == 2) pauseMediaService(tab) else stopMediaService()
                    } else if (state == 0 && !pipMode && now >= pipTrustUntil && mediaTab === tab) {
                        stopMediaService()
                    }
                }
                if (width > 0 && height > 0) {
                    tab.videoWidth = width
                    tab.videoHeight = height
                }
            }
        }
    }

    // ── Fix 3 + Fix 5: AurexMediaBridge ─────────────────────────────────────
    /**
     * JavaScript interface exposed as [window.AurexMediaBridge].
     *
     * Called from [AUREX_YT_VISIBILITY_JS] whenever a <video> element's play/pause
     * state changes. Bridges video-state events back to Kotlin for:
     *  - Starting / stopping [BrowserMediaService] (Fix 5).
     *  - Keeping [AurexWebView.backgroundMediaActive] in sync (Fix 1).
     *
     * Tab-level state (videoPlaying, videoWidth, videoHeight) continues to be
     * managed by [WebBridge.vstate] via PIP_JS, so there is no duplication.
     */
    private inner class AurexMediaBridge(private val tab: Tab) {
        /**
         * Called from JS with the current playing state and intrinsic video dimensions.
         *
         * @param isPlaying true if the video element is actively playing.
         * @param videoWidth  intrinsic pixel width  of the video track (0 if unknown).
         * @param videoHeight intrinsic pixel height of the video track (0 if unknown).
         */
        @JavascriptInterface
        fun onVideoStateChange(isPlaying: Boolean, videoWidth: Int, videoHeight: Int) {
            act.runOnUiThread {
                // Two scripts report the same flag with different rules (readyState > 2 here,
                // the PiP helper's own check there). In PiP they disagreed on every buffering dip
                // and the button flickered, so the PiP helper is the only source while it is open.
                if (pipMode) return@runOnUiThread
                val wasActive = tab.videoPlaying
                // Mirror state so the bridge's callbacks are reflected in tab UI too.
                if (isPlaying) {
                    if (!wasActive) {
                        val now = System.currentTimeMillis()
                        tab.videoPlaying = true
                        tab.videoPlayingStableSince = when {
                            now < pipTrustUntil -> now - MainActivity.AUTO_PIP_STABLE_MS
                            tab.videoPlayingStableSince > 0L && now - tab.videoStoppedAt < 2_500L ->
                                tab.videoPlayingStableSince
                            else -> now
                        }
                    }
                    if (videoWidth > 0 && videoHeight > 0) {
                        tab.videoWidth  = videoWidth
                        tab.videoHeight = videoHeight
                    }
                    (tab.web as? AurexWebView)?.backgroundMediaActive = true
                    if (!wasActive) startMediaService(tab)
                } else {
                    if (wasActive) {
                        if (System.currentTimeMillis() < pipTrustUntil) return@runOnUiThread
                        tab.videoPlaying           = false
                        tab.videoStoppedAt         = System.currentTimeMillis()
                        (tab.web as? AurexWebView)?.backgroundMediaActive = false
                        pauseMediaService(tab)
                    }
                }
            }
        }
    }

    // ── Fix 5: foreground media service lifecycle ────────────────────────────

    private fun mediaIntent(tab: Tab?, playing: Boolean): Intent =
        Intent(act, BrowserMediaService::class.java)
            .putExtra(BrowserMediaService.EXTRA_PLAYING, playing)
            // A private tab keeps its title and address out of the notification (lock screen).
            .putExtra(BrowserMediaService.EXTRA_TITLE, if (tab?.incognito == true) "Private video" else tab?.title.orEmpty())
            .putExtra(BrowserMediaService.EXTRA_HOST, if (tab?.incognito == true) "" else hostOf(tab?.url))
            .putExtra(BrowserMediaService.EXTRA_URL, if (tab?.incognito == true) "" else tab?.url.orEmpty())
            .putExtra(BrowserMediaService.EXTRA_ACCENT, effectiveAccent)

    /** The tab whose video the player card controls (the one that last started playing). */
    private var mediaTab: Tab? = null

    /** Tap on the player card: close what covers the page and show the tab whose video plays. */
    fun showMediaPage() {
        val t = mediaTab?.takeIf { it in tabs } ?: tabs.getOrNull(current) ?: return
        openPanelRequest = null
        closePanelsTick++
        if (tabs.indexOf(t) != current) selectTab(t)
    }

    /** The page the notification's buttons talk to. */
    fun mediaWeb(): AurexWebView? =
        ((mediaTab?.takeIf { it in tabs } ?: tabs.getOrNull(current))?.web) as? AurexWebView

    /** Video paused: keep the card (so it can be resumed from there); the service frees its locks after a few seconds. */
    fun pauseMediaService(tab: Tab? = null) {
        runCatching {
            act.startService(
                mediaIntent(tab ?: mediaTab ?: tabs.getOrNull(current), true)
                    .putExtra(BrowserMediaService.EXTRA_PAUSED, true)
            )
        }
    }

    /** The page title arrived / changed while its video plays: refresh the notification text. */
    fun refreshMediaInfo(tab: Tab) {
        if (!tab.videoPlaying) return
        runCatching { act.startService(mediaIntent(tab, true)) }
    }

    fun startMediaService(tab: Tab? = null) {
        runCatching {
            mediaTab = tab ?: tabs.getOrNull(current)
            MediaNotif.start(act.applicationContext) { mediaWeb() }
            val intent = mediaIntent(tab ?: tabs.getOrNull(current), true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                act.startForegroundService(intent)
            } else {
                act.startService(intent)
            }
        }
    }

    /** After the PiP window closes: if the video ended up paused, release the keep-alive locks. */
    fun releaseMediaIfIdle() {
        val tab = tabs.getOrNull(current) ?: return
        if (tab.videoPlaying) return
        (tab.web as? AurexWebView)?.backgroundMediaActive = false
        stopMediaService()
    }

    /**
     * The page's video is gone. Between two videos (autoplay / Next) the page briefly has none: the card stays
     * and is simply updated when the next one starts, instead of vanishing and popping back. If nothing follows
     * within a few seconds the service removes it.
     */
    private fun stopMediaService() {
        runCatching {
            act.startService(
                Intent(act, BrowserMediaService::class.java)
                    .putExtra(BrowserMediaService.EXTRA_PLAYING, true)
                    .putExtra(BrowserMediaService.EXTRA_HOLD, true)
            )
        }
    }

    // ── Fix 4: PiP CSS injection ─────────────────────────────────────────────

    /**
     * Called from [MainActivity.onPictureInPictureModeChanged].
     *
     * When [isInPipMode] is true, injects a <style> that:
     *  - Hides every element on the page (body * { visibility: hidden }).
     *  - Forces the <video>, YouTube player container, and #player-container-id
     *    to be visible and to fill the entire viewport so only the video shows
     *    inside the small PiP window.
     *
     * When [isInPipMode] is false, removes that style so the page returns to its
     * normal layout (the existing PIP_JS / __lqPip.exit() CSS is separate and
     * managed by the in-page helper; this CSS is an extra safety layer for the
     * system PiP transition flash).
     */
    fun togglePipVideoFullscreenCss(isInPipMode: Boolean) {
        val w = tabs.getOrNull(current)?.web ?: return
        // Layout is handled entirely by PIP_JS now. This only cleans up the old stylesheet if a
        // page still carries it, so nothing can fight the helper for the player's size.
        if (!isInPipMode) {
            w.evaluateJavascript(
                "(function(){var s=document.getElementById('__aurexPipCss');if(s)s.remove();})()",
                null
            )
        }
    }
}
