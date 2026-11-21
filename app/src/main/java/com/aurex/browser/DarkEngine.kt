package com.aurex.browser

/**
 * Bidirectional dark & light website engine, independent of the Android system theme.
 *
 *  - When Dark websites is ON:
 *      * Ordinary light sites get Dark-Reader style inversion + hue-rotation (photos/videos counter-inverted).
 *      * Native dark-capable sites (YouTube, Netflix, etc.) keep native darkening without filter stutter.
 *      * `window.matchMedia('(prefers-color-scheme: dark)')` reports `true` even on light-system phones.
 *  - When Dark websites is OFF (Theme = Light, Dark websites = Off, or per-site Dark mode off):
 *      * Removes the invert filter immediately.
 *      * Overrides `window.matchMedia('(prefers-color-scheme: dark)')` -> `false` and `light` -> `true`
 *        (preventing Android system Dark Mode from keeping YouTube, Google, GitHub, Reddit, Wikipedia dark!).
 *      * Injects `color-scheme: light only !important` and neutralizes `@media (prefers-color-scheme: dark)`
 *        rules in stylesheets (restoring them cleanly if Dark mode is turned back on).
 */
internal object DarkEngine {
    private val NATIVE_HOSTS = Regex(
        """(^|\.)(youtube|youtu|youtube-nocookie|vimeo|dailymotion|twitch|netflix|primevideo|hotstar|jiocinema)\."""
    )

    /** Sites that also read `Sec-CH-Prefers-Color-Scheme` on the server or bind theme deeply at load time. */
    private val SERVER_THEME_HOSTS = Regex(
        """(^|\.)(youtube|youtu|youtube-nocookie|google|bing|duckduckgo|search\.brave|github|reddit|wikipedia|wikimedia|vimeo|dailymotion|twitch|netflix|primevideo|hotstar|jiocinema|x|twitter)\."""
    )

    /** Hosts that use WebView's native darkening instead of the page invert filter. */
    fun isNativeHost(host: String?): Boolean = !host.isNullOrBlank() && NATIVE_HOSTS.containsMatchIn(host.lowercase())

    /** Hosts that benefit from a clean no-cache reload with `Sec-CH-Prefers-Color-Scheme` on dark<->light flip. */
    fun needsReloadOnFlip(host: String?): Boolean = !host.isNullOrBlank() && SERVER_THEME_HOSTS.containsMatchIn(host.lowercase())

    fun hostKey(host: String?): String = host.orEmpty().lowercase().removePrefix("www.")

    const val BRIGHTNESS = 100
    const val CONTRAST = 90
    const val SEPIA = 6

    const val REFRESH_JS = "window.__aurexDark&&window.__aurexDark.refresh();"

    /** Idempotent: safe to run as document-start script AND again from onPageFinished. */
    val JS = """(function(){
if(window.__aurexDark)return;
if(!/^https?:${'$'}/.test(location.protocol))return;
var API=window.AurexDark;if(!API)return;
var H=location.hostname||'',ID='aurex-dark',LID='aurex-light',MEM='__aurexDarkSeen';
var cfg={on:false,light:false,nativeDark:false,b:100,c:90,s:6};
var tm=0,mo=null,lmo=null,pend=new Map(),timers=[];
var INV='invert(1) hue-rotate(180deg)';
var PCS_DARK=/prefers-color-scheme\s*:\s*dark/i;
var PCS_LIGHT=/prefers-color-scheme\s*:\s*light/i;
var savedMedia=typeof WeakMap==='function'?new WeakMap():null;
var patchedRules=[],disabledLinks=[],mqls=[];
var origMetaCs=null;

function readCfg(){
 try{cfg=JSON.parse(API.cfg(H));}catch(e){cfg={on:false,light:false,nativeDark:false,b:100,c:90,s:6};}
 return cfg;
}
function mem(v){
 try{
  if(v===undefined)return localStorage.getItem(MEM)==='1';
  if(v)localStorage.setItem(MEM,'1');else localStorage.removeItem(MEM);
 }catch(e){}
 return false;
}

/* ---- Live window.matchMedia override for prefers-color-scheme ---- */
var origMM=window.matchMedia&&window.matchMedia.bind(window);
function wantedScheme(){
 if(cfg.light)return 'light';
 if(cfg.on||cfg.nativeDark)return 'dark';
 return '';
}
function evalScheme(q){
 var ws=wantedScheme();
 if(!ws)return null;
 if(PCS_DARK.test(q))return ws==='dark';
 if(PCS_LIGHT.test(q))return ws==='light';
 return null;
}
if(origMM&&!window.__aurexMmHook){
 window.__aurexMmHook=1;
 window.matchMedia=function(query){
  var q=String(query||'');
  var real=origMM(q);
  if(!PCS_DARK.test(q)&&!PCS_LIGHT.test(q))return real;
  var ls=[],onch=null;
  var cur=evalScheme(q);
  var lastM=(cur!==null)?cur:!!real.matches;
  var proxy={
   get media(){return q;},
   get matches(){var v=evalScheme(q);return v!==null?v:!!real.matches;},
   get onchange(){return onch;},
   set onchange(fn){onch=(typeof fn==='function')?fn:null;},
   addListener:function(fn){if(typeof fn==='function'&&ls.indexOf(fn)<0)ls.push(fn);},
   removeListener:function(fn){var i=ls.indexOf(fn);if(i>=0)ls.splice(i,1);},
   addEventListener:function(t,fn){if(t==='change'&&typeof fn==='function'&&ls.indexOf(fn)<0)ls.push(fn);},
   removeEventListener:function(t,fn){if(t==='change'){var i=ls.indexOf(fn);if(i>=0)ls.splice(i,1);}},
   dispatchEvent:function(ev){
    if(onch)try{onch.call(proxy,ev);}catch(e){}
    for(var i=0;i<ls.length;i++)try{ls[i].call(proxy,ev);}catch(e){}
    return true;
   },
   __sync:function(){
    var now=proxy.matches;
    if(now===lastM)return;
    lastM=now;
    var ev={type:'change',media:q,matches:now,target:proxy,currentTarget:proxy};
    proxy.dispatchEvent(ev);
   }
  };
  if(mqls.length<120)mqls.push(proxy);
  return proxy;
 };
}
function notifyMqls(){
 for(var i=0;i<mqls.length;i++)try{mqls[i].__sync();}catch(e){}
}

/* ---- Force Light Mode (neutralizes system-dark prefers-color-scheme: dark!) ---- */
function patchRuleList(rules){
 if(!rules)return;
 for(var i=0;i<rules.length;i++){
  var r=rules[i];
  if(!r)continue;
  try{
   if(r.media&&r.media.mediaText&&PCS_DARK.test(r.media.mediaText)){
    if(savedMedia&&!savedMedia.has(r)){
     savedMedia.set(r,r.media.mediaText);
     patchedRules.push(r);
    }
    r.media.mediaText=r.media.mediaText.replace(/\(?\s*prefers-color-scheme\s*:\s*dark\s*\)?/gi,'not all');
   }
   if(r.cssRules&&r.cssRules.length)patchRuleList(r.cssRules);
  }catch(e){}
 }
}
function stripDarkCss(){
 if(!cfg.light)return;
 try{
  var links=document.querySelectorAll('link[rel~="stylesheet" i][media]');
  for(var i=0;i<links.length;i++){
   var lk=links[i];
   if(PCS_DARK.test(lk.getAttribute('media')||'')&&!lk.disabled){
    lk.disabled=true;lk.setAttribute('data-al-dis','1');disabledLinks.push(lk);
   }
  }
 }catch(e){}
 try{
  var ss=document.styleSheets;
  for(var j=0;j<ss.length;j++){
   try{
    var s=ss[j];
    if(s.ownerNode&&s.ownerNode.id===LID)continue;
    if(s.media&&s.media.mediaText&&PCS_DARK.test(s.media.mediaText)){
     if(savedMedia&&!savedMedia.has(s)){savedMedia.set(s,s.media.mediaText);patchedRules.push(s);}
     s.media.mediaText='not all';
     continue;
    }
    if(s.cssRules)patchRuleList(s.cssRules);
   }catch(e){}
  }
 }catch(e){}
 try{
  var html=document.documentElement;
  if(html){
   if(H.indexOf('youtube')>=0&&html.hasAttribute('dark'))html.removeAttribute('dark');
   if(H.indexOf('wikipedia')>=0&&html.classList){
    html.classList.remove('skin-theme-clientpref-night','skin-theme-clientpref-os');
    html.classList.add('skin-theme-clientpref-day');
   }
  }
 }catch(e){}
}
function applyLight(){
 var h=document.head||document.documentElement;
 if(h&&!document.getElementById(LID)){
  var st=document.createElement('style');
  st.id=LID;
  st.textContent=':root,html,body{color-scheme:light only!important;}';
  h.appendChild(st);
 }
 try{
  var ms=document.querySelectorAll('meta[name="color-scheme" i]');
  if(ms.length>0){
   if(origMetaCs===null)origMetaCs=ms[0].getAttribute('content')||'';
   for(var i=0;i<ms.length;i++)ms[i].setAttribute('content','light');
  }else if(h){
   var m=document.createElement('meta');
   m.name='color-scheme';m.content='light';m.setAttribute('data-al-meta','1');
   h.appendChild(m);
  }
 }catch(e){}
 stripDarkCss();
 if(!lmo&&document.documentElement){
  try{
   lmo=new MutationObserver(function(ms){
    if(!cfg.light)return;
    var need=false;
    for(var i=0;i<ms.length;i++){
     var a=ms[i].addedNodes;
     for(var j=0;a&&j<a.length;j++){
      var n=a[j];
      if(n&&n.nodeType===1&&(n.tagName==='STYLE'||n.tagName==='LINK'||n.tagName==='META'))need=true;
     }
     if(ms[i].type==='attributes'&&ms[i].attributeName==='dark')need=true;
    }
    if(need)stripDarkCss();
   });
   lmo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['dark']});
  }catch(e){}
 }
}
function clearLight(){
 if(lmo){try{lmo.disconnect();}catch(e){}lmo=null;}
 var st=document.getElementById(LID);if(st)st.remove();
 try{
  var gen=document.querySelectorAll('meta[data-al-meta="1"]');
  for(var i=0;i<gen.length;i++)gen[i].remove();
  if(origMetaCs!==null){
   var ms=document.querySelectorAll('meta[name="color-scheme" i]');
   for(var j=0;j<ms.length;j++)ms[j].setAttribute('content',origMetaCs);
   origMetaCs=null;
  }
 }catch(e){}
 if(savedMedia){
  for(var k=0;k<patchedRules.length;k++){
   try{
    var r=patchedRules[k],orig=savedMedia.get(r);
    if(orig&&r.media)r.media.mediaText=orig;
   }catch(e){}
  }
  patchedRules=[];
 }
 for(var d=0;d<disabledLinks.length;d++){
  try{disabledLinks[d].disabled=false;disabledLinks[d].removeAttribute('data-al-dis');}catch(e){}
 }
 disabledLinks=[];
}

/* ---- Dark-Reader filter mode ---- */
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
function stopDark(){clearTimers();uninstall();if(mo){try{mo.disconnect();}catch(e){}mo=null;}pend.clear();}
function begin(){
 readCfg();
 notifyMqls();
 if(cfg.light){
  stopDark();
  mem(false);
  applyLight();
  clearTimers();
  [250,900,2200].forEach(function(t){timers.push(setTimeout(stripDarkCss,t));});
  return;
 }
 clearLight();
 if(!cfg.on){stopDark();return;}
 if(!mem())install();
 observe();queue(document.documentElement,true);
 clearTimers();
 [400,1400,3500].forEach(function(t){timers.push(setTimeout(function(){detect();queue(document.documentElement,true);},t));});
}
function start(){
 readCfg();
 if(!document.documentElement){
  var o=new MutationObserver(function(){if(document.documentElement){o.disconnect();begin();}});
  o.observe(document,{childList:true});return;
 }
 begin();
}
window.__aurexDark={refresh:function(){begin();if(cfg.on)detect();}};
document.addEventListener('DOMContentLoaded',function(){if(cfg.light)stripDarkCss();else if(cfg.on){detect();queue(document.documentElement,true);}});
window.addEventListener('load',function(){if(cfg.light)stripDarkCss();else if(cfg.on){detect();queue(document.documentElement,true);}});
start();
})();"""
}
