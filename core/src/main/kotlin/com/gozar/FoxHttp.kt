package com.gozar

import okhttp3.OkHttpClient
import okhttp3.toMediaType
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Shared OkHttp factory for the whole core. Base rules that every caller
 * relies on:
 *
 * - Redirects are never followed automatically; each call site decides
 *   (Mozilla's API answers non-2xx for accounts we must not leak credentials to).
 * - Threads without a control-tunnel scope go out directly; threads inside
 *   [FoxNet.throughControlTunnel] go out through the loopback control proxy.
 * - Per-request TLS behavior (TLS 1.2 retry, remembered-IP dial) is layered
 *   on top of the system trust manager via [FoxTls12]/[FoxCachedTls]; trust
 *   and hostname verification are never weakened.
 *
 * Instances are built per call on purpose: HttpURLConnection pooled nothing
 * either, and a fresh client keeps the control-scope thread-local unambiguous.
 */
internal object FoxHttp {

    private val trustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    /** The system default (initialised) socket factory, ready for wrapping. */
    fun defaultSocketFactory(): SSLSocketFactory = socketFactory

    /** The system default X509TrustManager, used as the OkHttp certificate anchor. */
    fun defaultTrustManager(): X509TrustManager = trustManager

    /**
     * A redirect-free builder with timeouts applied and the control scope of
     * [control] honored. Pass the value from [FoxNet.controlPortOf] (null for
     * a direct connection).
     */
    fun builder(control: Int?, timeoutMs: Int): OkHttpClient.Builder = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(control?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it)) } ?: Proxy.NO_PROXY)
        .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)

    /** Build the client with [factory] as the TLS layer over system trust. */
    fun secureWith(builder: OkHttpClient.Builder, factory: SSLSocketFactory): OkHttpClient =
        builder.sslSocketFactory(factory, trustManager).build()

    /** A JSON body with a fixed `application/json` media type. */
    fun jsonBody(text: String): okhttp3.RequestBody {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val mediaType = "application/json".toMediaType()
        return object : okhttp3.RequestBody() {
            override fun contentType(): okhttp3.MediaType = mediaType
            override fun contentLength(): Long = bytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) = sink.write(bytes)
        }
    }

    /** Read a body fully with a hard [limit]; used for relay-grade payloads. */
    fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray {
        val sink = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            sink.write(buffer, 0, count)
            if (sink.size() > limit) throw java.io.IOException("response is too large")
        }
        return sink.toByteArray()
    }
}
