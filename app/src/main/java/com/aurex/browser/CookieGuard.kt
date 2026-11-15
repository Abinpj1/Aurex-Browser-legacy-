package com.aurex.browser

import okhttp3.Interceptor

/** Tag on a request whose manually attached Cookie header belongs to one site only. */
internal class CookieHome(val host: String) {
    companion object {
        fun of(url: String): CookieHome =
            CookieHome(runCatching { java.net.URI(url).host }.getOrNull().orEmpty().lowercase())
    }
}

/**
 * OkHttp keeps a hand-written `Cookie` header when it follows a redirect, so a session cookie
 * meant for site A would be sent to whatever host A redirects to (CDN, tracker, shortener).
 * This network interceptor drops the header as soon as a hop leaves the site it was meant for.
 */
internal object CookieGuard {
    private fun sameSite(a: String, b: String): Boolean =
        a == b || a.endsWith(".$b") || b.endsWith(".$a")

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val home = req.tag(CookieHome::class.java)
        if (home != null && req.header("Cookie") != null &&
            !sameSite(req.url.host.lowercase(), home.host)
        ) {
            chain.proceed(req.newBuilder().removeHeader("Cookie").build())
        } else {
            chain.proceed(req)
        }
    }
}
