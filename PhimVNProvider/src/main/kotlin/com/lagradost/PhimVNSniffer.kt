package com.phimvn

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.lagradost.cloudstream3.CommonActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class SniffHit(
    val url: String,
    val referer: String,
    val source: String   // "network" | "hls-hook" | "fetch-hook" | "xhr-hook" | "video-hook"
)

object PhimVNSniffer {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val AD_DOMAINS = listOf(
        "doubleclick", "googlesyndication", "googleadservices",
        "google-analytics", "googletagmanager", "amung.us",
        "histats", "waust.at", "adservice", "adskeeper",
        "disable-devtool", "acscdn", "rapidadexchange",
        "cloudflareinsights", "beacon.min.js", "gstatic.com/cv",
        "cuid", "fingerprint", "clarity.ms", "hotjar",
        "facebook.com", "recaptcha", "sentry.io"
    )

    private val STREAM_HINTS = listOf(
        Regex("""\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""\.mp4""", RegexOption.IGNORE_CASE),
        Regex("""\.m4v""", RegexOption.IGNORE_CASE),
        Regex("""/hls[/_.]""", RegexOption.IGNORE_CASE),
        Regex("""/stream[/_.]""", RegexOption.IGNORE_CASE),
        Regex("""/playlist""", RegexOption.IGNORE_CASE),
        Regex("""/manifest""", RegexOption.IGNORE_CASE),
        Regex("""master\.m3u""", RegexOption.IGNORE_CASE),
        Regex("""index\.m3u""", RegexOption.IGNORE_CASE),
        Regex("""format=m3u8""", RegexOption.IGNORE_CASE),
        Regex("""type=hls""", RegexOption.IGNORE_CASE),
        Regex("""type=m3u8""", RegexOption.IGNORE_CASE)
    )

    private val SEGMENT_BLOCK = Regex(
        """.*\.(ts|m4s|key|aac|vtt|srt|jpg|jpeg|png|gif|webp|css|woff2?)(\?.*)?$""",
        RegexOption.IGNORE_CASE
    )

    suspend fun sniff(
        url: String,
        referer: String,
        timeoutMs: Long = 60_000L,
        waitForResultsMs: Long = 8000L
    ): List<SniffHit> {
        val activity: Activity = CommonActivity.activity ?: return emptyList()

        return suspendCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val hits = ConcurrentHashMap<String, SniffHit>()
            val done = AtomicBoolean(false)
            val lastPageUrl = arrayOf(url)
            val resumed = AtomicBoolean(false)

            fun finish(value: List<SniffHit>) {
                if (!resumed.compareAndSet(false, true)) return
                cont.resume(value)
            }

            fun addHit(rawUrl: String, source: String) {
                if (rawUrl.isBlank()) return
                val cleanUrl = rawUrl.trim().trim('"', '\'')
                if (cleanUrl.startsWith("blob:") || cleanUrl.startsWith("data:")) return
                if (!cleanUrl.startsWith("http")) return
                if (AD_DOMAINS.any { cleanUrl.contains(it, ignoreCase = true) }) return
                if (SEGMENT_BLOCK.matches(cleanUrl.lowercase())) return
                if (!STREAM_HINTS.any { it.containsMatchIn(cleanUrl) }) return

                if (hits.putIfAbsent(cleanUrl, SniffHit(cleanUrl, lastPageUrl[0], source)) == null) {
                    ClipboardLogger.logAndCopy("SNIFF", "✓ [$source] ${cleanUrl.take(180)}")
                }
            }

            fun scheduleAfter(ms: Long) {
                main.postDelayed({
                    val hasM3u8 = hits.keys.any { it.contains(".m3u8", true) }
                    if (hasM3u8) cleanup() else main.postDelayed({ cleanup() }, 5000L)
                }, ms)
            }

            fun cleanup() {
                if (!done.compareAndSet(false, true)) return
                try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
                main.post {
                    try { (activity.window.decorView as? ViewGroup)?.let { root -> } } catch (_: Throwable) {}
                    finish(sortHits(hits.values.toList()))
                }
            }

            main.post {
                try {
                    val overlay = FrameLayout(activity).apply {
                        setBackgroundColor(0xE6000000.toInt())
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                    val spinner = ProgressBar(activity).apply {
                        isIndeterminate = true
                        layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                            gravity = Gravity.CENTER
                        }
                    }

                    val webView = WebView(activity).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = UA
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                            setSupportZoom(false)
                        }
                        setBackgroundColor(0xFF000000.toInt())
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    overlay.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    overlay.addView(spinner)

                    fun closeAll() {
                        if (!done.compareAndSet(false, true)) return
                        try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
                        main.post {
                            try { overlay.removeAllViews() } catch (_: Throwable) {}
                            try { (overlay.parent as? ViewGroup)?.removeView(overlay) } catch (_: Throwable) {}
                            try { webView.stopLoading() } catch (_: Throwable) {}
                            try { webView.loadUrl("about:blank") } catch (_: Throwable) {}
                            try { webView.destroy() } catch (_: Throwable) {}
                            finish(sortHits(hits.values.toList()))
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            addHit(u, "network")
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null) lastPageUrl[0] = url
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null) lastPageUrl[0] = url
                            spinner.visibility = View.GONE
                            view?.evaluateJavascript(JS_HOOKS, null)
                            view?.evaluateJavascript(AUTOPLAY_JS, null)
                            view?.evaluateJavascript(DPAD_JS, null)
                        }
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            val m = msg?.message() ?: return false
                            when {
                                m.startsWith("[HLS] ") -> {
                                    addHit(m.removePrefix("[HLS] ").trim(), "hls-hook")
                                    if (hits.size >= 1) scheduleAfter(waitForResultsMs)
                                }
                                m.startsWith("[FETCH] ") -> addHit(m.removePrefix("[FETCH] ").trim(), "fetch-hook")
                                m.startsWith("[XHR] ") -> addHit(m.removePrefix("[XHR] ").trim(), "xhr-hook")
                                m.startsWith("[VIDEO] ") -> addHit(m.removePrefix("[VIDEO] ").trim(), "video-hook")
                                m.startsWith("[HOOK-INIT]") -> ClipboardLogger.logAndCopy("JS", "Hooks đã cài đặt")
                            }
                            return true
                        }
                    }

                    webView.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> { webView.evaluateJavascript("__dpadMove(0,-25)", null); true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { webView.evaluateJavascript("__dpadMove(0, 25)", null); true }
                            KeyEvent.KEYCODE_DPAD_LEFT -> { webView.evaluateJavascript("__dpadMove(-25,0)", null); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { webView.evaluateJavascript("__dpadMove( 25,0)", null); true }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { webView.evaluateJavascript("__dpadClick()", null); true }
                            else -> false
                        }
                    }

                    try {
                        (activity.window.decorView as? ViewGroup)?.addView(overlay)
                    } catch (_: Throwable) {}

                    webView.loadUrl(url)

                    // Gọi closeAll thay vì cleanup vì đã định nghĩa đúng ở trên
                    main.postDelayed({ closeAll() }, timeoutMs)

                } catch (t: Throwable) {
                    ClipboardLogger.logAndCopy("SNIFFER", "init fail: ${t.message}")
                    finish(emptyList())
                }
            }
        }
    }

    private fun sortHits(list: List<SniffHit>): List<SniffHit> = list.sortedWith(
        compareBy(
            {
                when {
                    it.url.contains(".m3u8", true) && it.source != "network" -> 0
                    it.url.contains(".m3u8", true) -> 1
                    it.url.contains(".mp4", true) -> 2
                    else -> 3
                }
            },
            { it.url.length }
        )
    )

    private const val JS_HOOKS = """
    (function(){
      if (window.__vsHooked) return;
      window.__vsHooked = true;

      function report(tag, url) {
        try {
          if (typeof url === 'string' && url.length > 8) {
            console.log('[' + tag + '] ' + url);
          }
        } catch (e) {}
      }

      try {
        var _Hls = window.Hls;
        if (_Hls && _Hls.prototype && _Hls.prototype.loadSource) {
          var _orig = _Hls.prototype.loadSource;
          _Hls.prototype.loadSource = function(src) {
            report('HLS', src);
            return _orig.apply(this, arguments);
          };
        }
      } catch (e) {}

      try {
        ['Hls', 'hlsjs', 'HlsJs'].forEach(function(name) {
          var C = window[name];
          if (C && C.prototype && C.prototype.loadSource && !C.prototype.__hooked) {
            C.prototype.__hooked = true;
            var o = C.prototype.loadSource;
            C.prototype.loadSource = function(src) {
              report('HLS', src);
              return o.apply(this, arguments);
            };
          }
        });
      } catch (e) {}

      try {
        if (window.fetch && !window.fetch.__hooked) {
          var of = window.fetch;
          window.fetch = function(input) {
            try {
              var u = typeof input === 'string' ? input : (input && input.url) || '';
              report('FETCH', u);
            } catch (e) {}
            return of.apply(this, arguments);
          };
          window.fetch.__hooked = true;
        }
      } catch (e) {}

      try {
        if (window.XMLHttpRequest && !window.XMLHttpRequest.prototype.__hooked) {
          var ox = window.XMLHttpRequest.prototype.open;
          window.XMLHttpRequest.prototype.open = function(method, url) {
            try { report('XHR', String(url)); } catch (e) {}
            return ox.apply(this, arguments);
          };
          window.XMLHttpRequest.prototype.__hooked = true;
        }
      } catch (e) {}

      try {
        if (window.URL && window.URL.createObjectURL && !window.URL.createObjectURL.__hooked) {
          var oc = window.URL.createObjectURL;
          window.URL.createObjectURL = function(obj) {
            try {
              if (obj && obj.src) report('HLS', obj.src);
              else if (obj && obj.url) report('HLS', obj.url);
            } catch (e) {}
            return oc.apply(this, arguments);
          };
          window.URL.createObjectURL.__hooked = true;
        }
      } catch (e) {}

      try {
        var mo = new MutationObserver(function(muts) {
          muts.forEach(function(m) {
            if (m.type === 'attributes' && m.target && m.target.tagName === 'VIDEO') {
              var s = m.target.src || m.target.currentSrc || '';
              if (s && s.indexOf('blob:') !== 0) report('VIDEO', s);
            }
            if (m.addedNodes) {
              m.addedNodes.forEach(function(n) {
                if (n.tagName === 'VIDEO') {
                  var s = n.src || n.currentSrc || '';
                  if (s && s.indexOf('blob:') !== 0) report('VIDEO', s);
                }
              });
            }
          });
        });
        mo.observe(document.documentElement, {
          childList: true, subtree: true,
          attributes: true, attributeFilter: ['src']
        });
      } catch (e) {}

      console.log('[HOOK-INIT]');
    })();
    """

    private const val AUTOPLAY_JS = """
    (function(){
      var sels=['button[aria-label*="lay" i]','.jw-bigplay','.vjs-big-play-button','#bigPlay','#play','#player','video'];
      function clickAll(){
        sels.forEach(function(s){
          document.querySelectorAll(s).forEach(function(el){
            try{
              if(el.tagName==='VIDEO'){el.muted=true;el.play().catch(function(){});}
              else if(el.click) el.click();
            }catch(e){}
          });
        });
      }
      clickAll();
      setTimeout(clickAll,1000);
      setTimeout(clickAll,3000);
      setTimeout(clickAll,6000);
      setTimeout(clickAll,10000);
    })();
    """

    private const val DPAD_JS = """
    (function(){
      if(window.__dpadInit)return;window.__dpadInit=true;
      var c=document.createElement('div');c.id='__dpad_cursor';
      c.style.cssText='position:fixed;width:22px;height:22px;border-radius:50%;background:rgba(255,60,60,0.85);border:2px solid #fff;box-shadow:0 0 10px rgba(255,60,60,0.9);z-index:2147483647;pointer-events:none;display:none;transition:left .08s,top .08s;';
      document.body.appendChild(c);
      window.__dpadX=window.innerWidth/2;window.__dpadY=window.innerHeight/2;
      function render(){c.style.left=window.__dpadX+'px';c.style.top=window.__dpadY+'px';c.style.display='block';}
      window.__dpadMove=function(dx,dy){window.__dpadX=Math.max(0,Math.min(window.innerWidth-22,window.__dpadX+dx));window.__dpadY=Math.max(0,Math.min(window.innerHeight-22,window.__dpadY+dy));render();};
      window.__dpadClick=function(){
        var el=document.elementFromPoint(window.__dpadX+11,window.__dpadY+11);if(!el)return;
        if(el.focus)el.focus();if(el.click)el.click();
        ['mousedown','mouseup','click'].forEach(function(t){try{el.dispatchEvent(new MouseEvent(t,{bubbles:true}));}catch(e){}});
      };
      render();
    })();
    """
}
