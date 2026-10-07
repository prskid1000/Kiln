package app.kiln.llm

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Network policy for model endpoints (SPEC §8.3):
 *  - HTTPS everywhere, except plain HTTP to loopback and the tailnet range
 *    100.64.0.0/10 (On Device AI and Telecode proxies over Tailscale);
 *  - a self-signed certificate is trusted only when its SHA-256 is pinned.
 */
object Http {

    fun cleartextAllowed(host: String): Boolean {
        if (host == "localhost") return true
        val a = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
        if (a.size == 4) {
            val b0 = a[0].toInt() and 0xff; val b1 = a[1].toInt() and 0xff
            return b0 == 127 || (b0 == 100 && b1 in 64..127)
        }
        return InetAddress.getByAddress(a).isLoopbackAddress
    }

    fun checkUrl(url: String) {
        val u = java.net.URI(url)
        require(u.scheme == "https" || (u.scheme == "http" && cleartextAllowed(u.host ?: ""))) {
            "plain HTTP is only allowed to localhost or the tailnet (100.64.0.0/10): $url"
        }
    }

    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** Trust manager that accepts exactly one certificate (by SHA-256). */
    class Pinned(private val sha: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("client auth unsupported")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || !sha256(chain[0]).equals(sha, ignoreCase = true))
                throw java.security.cert.CertificateException("certificate does not match the pinned SHA-256")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    data class Tls(val factory: SSLSocketFactory, val trust: X509TrustManager, val hostnames: HostnameVerifier)

    fun pinnedTls(sha: String): Tls {
        val tm = Pinned(sha)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        // The pin identifies the server; the cert's SAN may not name the tailnet IP.
        return Tls(ctx.socketFactory, tm, HostnameVerifier { _, _ -> true })
    }

    fun client(profile: Profile): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)      // long thinking turns stream slowly at first
            .writeTimeout(2, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps) require(cleartextAllowed(url.host)) { "cleartext refused for ${url.host}" }
                chain.proceed(chain.request())
            })
        profile.pinnedCertSha256?.let { val t = pinnedTls(it); b.sslSocketFactory(t.factory, t.trust).hostnameVerifier(t.hostnames) }
        return b.build()
    }

    /** "https://x/v1/" | "https://x" → "https://x/v1" (SPEC §8.3: accept either form). */
    fun v1Base(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/v1")) b else "$b/v1"
    }

    /** Root without /v1 (the Anthropic SDK appends /v1 itself). */
    fun rootBase(baseUrl: String): String = baseUrl.trim().trimEnd('/').removeSuffix("/v1")
}
