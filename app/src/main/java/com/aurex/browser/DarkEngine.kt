package com.aurex.browser

/**
 * Dark websites, Dark-Reader style, independent of the app theme.
 *
 * Why: Android's native algorithmic darkening only works while the APP theme is dark (Android 13+), so
 * "Dark" on a light-system phone, or sites that ignore it, stayed white. This engine runs inside the page:
 *  - installed at document start (no white flash), reads its config from [BrowserState.DarkBridge];
 *  - Filter mode: the page is inverted + hue-rotated (colours keep their hue), photos / video / canvas / iframes /
 *    background photos are counter-inverted so they look natural, SVG icons and logos are inverted with the page;
 *  - pages that are already dark are detected from their real colours (and remembered) and left alone;
 *  - new content (infinite scroll, SPA) is handled by a throttled MutationObserver;
 *  - no reload on a settings change: [refresh] re-reads the config live.
 * Video sites (YouTube, Netflix ...) are excluded: a page filter makes every video frame go through the filter
 * pipeline (stutter), so those keep the native darkening.
 */
internal object DarkEngine {
    private val NATIVE_HOSTS = Regex("(^|\\.)(youtube|youtu|youtube-nocookie|vimeo|dailymotion|twitch|netflix|primevideo|hotstar|jiocinema)\\.")

    /** Hosts that use WebView's native darkening instead of the page filter. */
    fun isNativeHost(host: String?): Boolean = !host.isNullOrBlank() && NATIVE_HOSTS.containsMatchIn(host.lowercase())

    fun hostKey(host: String?): String = host.orEmpty().lowercase().removePrefix("www.")

    /** Look of the filter: brightness / contrast / sepia in percent (Dark Reader defaults 100 / 90 / 10). */
    const val BRIGHTNESS = 100
    const val CONTRAST = 90
    const val SEPIA = 6

    const val REFRESH_JS = "window.__aurexDark&&window.__aurexDark.refresh();"

    /** Idempotent: safe to run as document-start script AND again from onPageFinished. */
    val JS = """(function(){
if(window.__aurexDark)return;
if(!/^https?:${'$'}/.test(location.protocol))return;
var API=window.AurexDark;if(!API)return;
var H=location.hostname||'',ID='aurex-dark',MEM='__aurexDarkSeen',cfg={on:false},started=false,tm=0,mo=null,pend=new Map(),timers=[];
var INV='invert(1) hue-rotate(180deg)';
function readCfg(){try{cfg=JSON.parse(API.cfg(H));}catch(e){cfg={on:false};}return cfg;}
function mem(v){try{if(v===undefined)return localStorage.getItem(MEM)==='1';if(v)localStorage.setItem(MEM,'1');else localStorage.removeItem(MEM);}catch(e){}return false;}
function css(){
 var main=INV+' brightness('+cfg.b+'%) contrast('+cfg.c+'%) sepia('+cfg.s+'%)';
 return 'html{filter:'+main+'!important}'
 +'img:not([data-ad-k]),video,canvas,iframe,embed,object,[data-ad-bg]{filter:'+INV+'!important}'
 +'[data-ad-bg] img,[data-ad-bg] video,[data-ad-bg] canvas,[data-ad-bg] iframe,[data-ad-bg] [data-ad-bg]{filter:none!important}'
 +':fullscreen{filter:'+INV+'!important}:fullscreen video,:fullscreen img,:fullscreen canvas,:fullscreen iframe{filter:none!important}'
 +'html{color-scheme:light}';
}
function install(){
 if(document.getElementById(ID))return;
 var st=document.createElement('style');st.id=ID;st.textContent=css();
 (document.head||document.documentElement).appendChild(st);
}
function uninstall(){var s=document.getElementById(ID);if(s)s.remove();}
/* ---- already-dark detection (real colours, not the filtered picture) ---- */
function parse(c){var m=c&&c.match(/[\d.]+/g);if(!m||m.length<3)return null;var a=m.length>3?parseFloat(m[3]):1;if(a<0.5)return null;return(0.2126*m[0]+0.7152*m[1]+0.0722*m[2])/255;}
function effBg(el){while(el){var l=parse(getComputedStyle(el).backgroundColor);if(l!==null)return l;el=el.parentElement;}return 1;}
function pageIsDark(){
 try{
  var vw=innerWidth,vh=innerHeight,pts=[[.5,.5],[.5,.15],[.2,.6],[.8,.6],[.5,.88]],n=0,d=0;
  for(var i=0;i<pts.length;i++){var e=document.elementFromPoint(vw*pts[i][0],vh*pts[i][1]);if(e){n++;if(effBg(e)<0.35)d++;}}
  if(!n)return effBg(document.body||document.documentElement)<0.35;
  return d>=Math.ceil(n*0.6);
 }catch(e){return false;}
}
function detect(){
 if(!cfg.on)return;
 if(!document.body)return;
 if(pageIsDark()){mem(true);uninstall();}
 else{mem(false);install();}
}
/* ---- counter-invert photos, keep icons inverted ---- */
var SVG_RE=/\.svg(\?|#|${'$'})|^data:image\/svg|image\/svg/i;
function markImg(img){
 try{var s=img.currentSrc||img.src||'';
  if(SVG_RE.test(s))img.setAttribute('data-ad-k','');
  else if(img.hasAttribute('data-ad-k'))img.removeAttribute('data-ad-k');}catch(e){}
}
function markBg(el){
 var tn=el.tagName;
 if(!tn||tn==='HTML'||tn==='BODY'||tn==='IMG'||tn==='VIDEO'||tn==='CANVAS'||tn==='IFRAME'||tn==='SCRIPT'||tn==='STYLE'||tn==='svg'||tn==='SVG')return;
 if(el.hasAttribute('data-ad-bg'))return;
 var b=getComputedStyle(el).backgroundImage;
 if(!b||b==='none'||b.indexOf('url(')<0)return;
 if(SVG_RE.test(b))return;
 var r=el.getBoundingClientRect();
 if(r.width<2||r.height<2)return;
 if(r.width>=innerWidth*0.9&&r.height>=innerHeight*0.6&&el.children.length>6)return;
 el.setAttribute('data-ad-bg','');
}
function mark(el){if(el.nodeType!==1)return;if(el.tagName==='IMG')markImg(el);else markBg(el);}
function queue(n,deep){if(!n||n.nodeType!==1)return;pend.set(n,deep||pend.get(n)||false);if(!tm)tm=setTimeout(flush,100);}
function flush(){
 tm=0;var c=0,it=pend.entries(),x;
 while(c<250&&!(x=it.next()).done){
  var n=x.value[0],deep=x.value[1];pend.delete(n);c++;
  if(!n.isConnected)continue;
  try{mark(n);if(deep&&n.querySelectorAll){var d=n.querySelectorAll('*'),lim=Math.min(d.length,300),i;for(i=0;i<lim;i++)mark(d[i]);for(i=lim;i<d.length;i++)pend.set(d[i],false);}}catch(e){}
 }
 if(pend.size)tm=setTimeout(flush,60);
}
function observe(){
 if(mo||!document.documentElement)return;
 try{
  mo=new MutationObserver(function(ms){
   for(var i=0;i<ms.length;i++){var m=ms[i];
    if(m.type==='childList'){for(var j=0;j<m.addedNodes.length;j++)queue(m.addedNodes[j],true);}
    else queue(m.target,false);}
  });
  mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style']});
 }catch(e){}
}
document.addEventListener('load',function(e){var t=e.target;if(t&&t.tagName==='IMG'&&cfg.on)markImg(t);},true);
function clearTimers(){for(var i=0;i<timers.length;i++)clearTimeout(timers[i]);timers=[];}
function stop(){clearTimers();uninstall();if(mo){try{mo.disconnect();}catch(e){}mo=null;}pend.clear();started=false;}
function begin(){
 readCfg();
 if(!cfg.on){stop();return;}
 if(!mem())install();      /* known-dark site: no filter, re-checked below */
 observe();queue(document.documentElement,true);
 clearTimers();
 [400,1400,3500].forEach(function(t){timers.push(setTimeout(function(){detect();queue(document.documentElement,true);},t));});
 started=true;
}
function start(){
 if(!document.documentElement){
  var o=new MutationObserver(function(){if(document.documentElement){o.disconnect();begin();}});
  o.observe(document,{childList:true});return;
 }
 begin();
}
window.__aurexDark={refresh:function(){begin();detect();}};
document.addEventListener('DOMContentLoaded',function(){if(cfg.on){detect();queue(document.documentElement,true);}});
window.addEventListener('load',function(){if(cfg.on){detect();queue(document.documentElement,true);}});
start();
})();"""
}
