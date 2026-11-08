package com.gozar

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** API-24-safe base64 for the encrypted device identity. */
internal object WarpBase64 {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(3, bytes.size - i)
            val value = ((bytes[i].toInt() and 255) shl 16) or
                (if (n > 1) (bytes[i + 1].toInt() and 255) shl 8 else 0) or
                (if (n > 2) bytes[i + 2].toInt() and 255 else 0)
            out.append(alphabet[(value ushr 18) and 63]); out.append(alphabet[(value ushr 12) and 63])
            out.append(if (n > 1) alphabet[(value ushr 6) and 63] else '=')
            out.append(if (n > 2) alphabet[value and 63] else '=')
            i += n
        }
        return out.toString()
    }
    fun decode(text: String): ByteArray {
        val input = text.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }
        require(input.isNotEmpty() && input.length % 4 == 0)
        val padding = input.count { it == '=' }
        require(padding <= 2 && (padding == 0 || input.endsWith("=".repeat(padding))))
        val out = ByteArray(input.length / 4 * 3 - padding)
        var bits = 0; var buffer = 0; var index = 0
        for (ch in input) {
            if (ch == '=') continue
            val value = alphabet.indexOf(ch); require(value >= 0)
            buffer = (buffer shl 6) or value; bits += 6
            if (bits >= 8) { bits -= 8; out[index++] = ((buffer ushr bits) and 255).toByte() }
        }
        require(index == out.size && (bits == 0 || buffer and ((1 shl bits) - 1) == 0))
        return out
    }
}

internal data class WarpIdentity(
    val id: String, val token: String, val privateKey: String, val publicKey: String,
    val serverKey: String = "", val ipv4: String = ""
) {
    fun json(): String = JSONObject().put("id", id).put("token", token).put("private_key", privateKey)
        .put("public_key", publicKey).put("server_key", serverKey).put("ipv4", ipv4).toString()
    fun baseValid(): Boolean = try {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}")) && token.isNotEmpty() && !token.contains('\r') && !token.contains('\n'))
        val factory = KeyFactory.getInstance("EC")
        factory.generatePrivate(PKCS8EncodedKeySpec(WarpBase64.decode(privateKey)))
        val public = factory.generatePublic(X509EncodedKeySpec(WarpBase64.decode(publicKey))) as ECPublicKey
        require(public.params.curve.field.fieldSize == 256)
        true
    } catch (_: Exception) { false }
    fun ready(): Boolean = try {
        require(baseValid())
        val address = FoxWarpPackets.ipv4(ipv4) ?: throw IllegalArgumentException("invalid address")
        require(FoxWarpPackets.privateIPv4(address))
        val pin = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(WarpBase64.decode(serverKey))) as ECPublicKey
        require(pin.params.curve.field.fieldSize == 256)
        true
    } catch (_: Exception) { false }
    companion object {
        fun parse(raw: String): WarpIdentity {
            val j = JSONObject(raw)
            return WarpIdentity(j.optString("id"), j.optString("token"), j.optString("private_key"),
                j.optString("public_key"), j.optString("server_key"), j.optString("ipv4"))
        }
    }
}

/** Tracks every loopback socket, so stop interrupts enrollment and endpoint probes. */
internal class WarpSockets(private val proxyPort: Int, private val current: () -> Boolean) {
    private val lock = Any()
    private val open = HashSet<Socket>()
    @Volatile private var stopped = false
    fun valid(): Boolean = !stopped && try { current() } catch (_: Exception) { false }
    fun connect(host: String, port: Int): Socket {
        if (!valid() || proxyPort !in 1..65535 || !host.matches(Regex("[A-Za-z0-9.-]+")) || port !in 1..65535) {
            throw IOException("WARP upstream unavailable")
        }
        val socket = Socket()
        synchronized(lock) {
            if (stopped) throw IOException("WARP stopped")
            open.add(socket)
        }
        try {
            if (!valid()) throw IOException("WARP stopped")
            socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 12000)
            socket.soTimeout = 12000
            socket.tcpNoDelay = true
            val request = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val header = readHeader(socket.getInputStream())
            if (!header.startsWith("HTTP/1.1 200 ") && !header.startsWith("HTTP/1.0 200 ")) {
                throw IOException("Mozilla CONNECT refused WARP upstream")
            }
            if (!valid()) throw IOException("WARP stopped")
            return socket
        } catch (error: Exception) {
            release(socket)
            throw error
        }
    }
    fun release(socket: Socket) {
        synchronized(lock) { open.remove(socket) }
        try { socket.close() } catch (_: Exception) { }
    }
    fun close() {
        val old = synchronized(lock) { stopped = true; open.toList().also { open.clear() } }
        old.forEach { try { it.close() } catch (_: Exception) { } }
    }
    companion object {
        fun readHeader(input: java.io.InputStream): String {
            val b = ByteArrayOutputStream(); var matched = 0
            while (b.size() < 32768) {
                val next = input.read(); if (next < 0) throw IOException("truncated HTTP header")
                b.write(next)
                matched = if (next == byteArrayOf(13, 10, 13, 10)[matched].toInt()) matched + 1 else if (next == 13) 1 else 0
                if (matched == 4) return b.toString("ISO-8859-1")
            }
            throw IOException("HTTP header too large")
        }
    }
}

/** Enrollment uses a Web-PKI-verified TLS connection through Mozilla only. */
internal class WarpEnrollment(private val sockets: WarpSockets, private val save: (String) -> Boolean) {
    private val random = SecureRandom()
    private fun request(method: String, path: String, token: String, body: JSONObject): JSONObject {
        val raw = sockets.connect("api.cloudflareclient.com", 443)
        try {
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, "api.cloudflareclient.com", 443, true) as SSLSocket
            val params = tls.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            params.serverNames = listOf(SNIHostName("api.cloudflareclient.com"))
            tls.sslParameters = params
            tls.startHandshake()
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            val authorization = if (token.isEmpty()) "" else "Authorization: Bearer $token\r\n"
            val header = "$method /v0a5641$path HTTP/1.1\r\nHost: api.cloudflareclient.com\r\n" +
                "User-Agent: 1.1.1.1/6.38.9-5641 (Android 16.0.0)\r\nCF-Client-Version: a-6.38.9-5641\r\n" +
                "Content-Type: application/json\r\nAccept: application/json\r\nAccept-Encoding: identity\r\n" +
                authorization + "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
            tls.outputStream.write(header.toByteArray(Charsets.US_ASCII)); tls.outputStream.write(payload); tls.outputStream.flush()
            val input = tls.inputStream
            val response = WarpSockets.readHeader(input)
            val lines = response.split("\r\n")
            val code = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            if (code !in 200..299) throw IOException("WARP registration HTTP $code")
            val fields = lines.drop(1).mapNotNull {
                val at = it.indexOf(':'); if (at <= 0) null else it.substring(0, at).trim().lowercase(Locale.US) to it.substring(at + 1).trim()
            }.toMap()
            val bytes = ByteArrayOutputStream()
            fun copy(count: Int) {
                if (count < 0 || bytes.size().toLong() + count > 1_048_576L) throw IOException("WARP response too large")
                val buffer = ByteArray(4096); var left = count
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(left, buffer.size)); if (n <= 0) throw IOException("truncated WARP response")
                    bytes.write(buffer, 0, n); left -= n
                }
            }
            if (fields["transfer-encoding"]?.equals("chunked", true) == true) {
                while (true) {
                    val line = ByteArrayOutputStream()
                    while (line.size() < 128) { val ch = input.read(); if (ch < 0) throw IOException("truncated WARP chunk"); if (ch == 10) break; line.write(ch) }
                    val size = line.toString("US-ASCII").trim().substringBefore(';').toIntOrNull(16) ?: throw IOException("invalid WARP chunk")
                    if (size == 0) break
                    copy(size)
                    if (input.read() != 13 || input.read() != 10) throw IOException("invalid WARP chunk ending")
                }
            } else {
                val length = fields["content-length"]?.toIntOrNull()
                if (length != null) copy(length) else {
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        if (bytes.size().toLong() + n > 1_048_576L) throw IOException("WARP response too large")
                        bytes.write(buffer, 0, n)
                    }
                }
            }
            val result = JSONObject(bytes.toString("UTF-8"))
            return result.optJSONObject("result") ?: result
        } finally { sockets.release(raw) }
    }
    fun obtain(saved: String?): WarpIdentity {
        var identity: WarpIdentity
        if (saved == null) {
            val generator = KeyPairGenerator.getInstance("EC"); generator.initialize(ECGenParameterSpec("secp256r1"))
            val pair = generator.generateKeyPair()
            val nonce = ByteArray(32).also { random.nextBytes(it) }
            val serial = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val body = JSONObject().put("key", WarpBase64.encode(nonce)).put("install_id", "").put("fcm_token", "")
                .put("tos", date).put("model", "Android").put("serial_number", serial).put("os_version", "")
                .put("key_type", "curve25519").put("tunnel_type", "wireguard").put("locale", "en_US")
            val reply = request("POST", "/reg", "", body)
            identity = WarpIdentity(reply.optString("id"), reply.optString("token"), WarpBase64.encode(pair.private.encoded), WarpBase64.encode(pair.public.encoded))
            if (!identity.baseValid() || !save(identity.json())) throw IOException("could not securely save WARP identity")
        } else {
            identity = try { WarpIdentity.parse(saved) } catch (_: Exception) { throw IOException("invalid WARP identity") }
            if (!identity.baseValid()) throw IOException("invalid WARP identity")
        }
        if (!identity.ready()) {
            val body = JSONObject().put("key", identity.publicKey).put("key_type", "secp256r1").put("tunnel_type", "masque")
            val reply = request("PATCH", "/reg/${identity.id}", identity.token, body)
            val config = reply.getJSONObject("config")
            val candidate = identity.copy(serverKey = config.getJSONArray("peers").getJSONObject(0).getString("public_key"),
                ipv4 = config.getJSONObject("interface").getJSONObject("addresses").getString("v4").substringBefore('/'))
            if (!candidate.ready() || !save(candidate.json())) throw IOException("could not securely save WARP enrollment")
            identity = candidate
        }
        return identity
    }
}

/** P-256 self-signed client certificate and mandatory enrollment SPKI pin. */
internal object WarpTLS {
    private fun der(tag: Int, body: ByteArray): ByteArray {
        val size = body.size
        val length = when { size < 128 -> byteArrayOf(size.toByte()); size < 256 -> byteArrayOf(0x81.toByte(), size.toByte()); else -> byteArrayOf(0x82.toByte(), (size ushr 8).toByte(), size.toByte()) }
        return byteArrayOf(tag.toByte()) + length + body
    }
    private fun seq(vararg items: ByteArray) = der(0x30, items.fold(ByteArray(0)) { a, b -> a + b })
    fun context(identity: WarpIdentity): SSLContext {
        if (!identity.ready()) throw IOException("incomplete WARP identity")
        val factory = KeyFactory.getInstance("EC")
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(WarpBase64.decode(identity.privateKey)))
        val pub = factory.generatePublic(X509EncodedKeySpec(WarpBase64.decode(identity.publicKey)))
        val algorithm = seq(byteArrayOf(0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02))
        val time = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val validity = seq(der(0x17, time.format(Date(now - 3_600_000)).toByteArray(Charsets.US_ASCII)),
            der(0x17, time.format(Date(now + 86_400_000)).toByteArray(Charsets.US_ASCII)))
        val serial = BigInteger(128, SecureRandom()).add(BigInteger.ONE).toByteArray()
        // Java rejects an empty issuer DN; both names are local-only labels,
        // while WARP authenticates the device by its enrolled P-256 key.
        val name = seq(der(0x31, seq(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03),
            der(0x0c, "Gozar WARP".toByteArray(Charsets.UTF_8)))))
        val tbs = seq(der(0xa0, der(0x02, byteArrayOf(2))), der(0x02, serial), algorithm, name, validity, name, pub.encoded)
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(private); update(tbs) }.sign()
        val encoded = seq(tbs, algorithm, der(0x03, byteArrayOf(0) + signature))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
        val password = charArrayOf('w', 'a', 'r', 'p')
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setKeyEntry("warp", private, password, arrayOf(cert)) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val expected = WarpBase64.decode(identity.serverKey)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw java.security.cert.CertificateException("unexpected client verification") }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty() || !MessageDigest.isEqual(chain[0].publicKey.encoded, expected)) {
                    throw java.security.cert.CertificateException("WARP server pin mismatch")
                }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(trust), SecureRandom()) }
    }
    fun connect(sockets: WarpSockets, ssl: SSLContext, host: String, port: Int): Pair<Socket, SSLSocket> {
        val raw = sockets.connect(host, port)
        try {
            val secured = ssl.socketFactory.createSocket(raw, "consumer-masque.cloudflareclient.com", port, true) as SSLSocket
            val parameters = secured.sslParameters
            parameters.serverNames = listOf(SNIHostName("consumer-masque.cloudflareclient.com"))
            try { parameters.applicationProtocols = arrayOf("h2") }
            catch (_: NoSuchMethodError) { throw IOException("WARP requires HTTP/2 ALPN") }
            secured.sslParameters = parameters
            secured.startHandshake()
            if (try { secured.applicationProtocol != "h2" } catch (_: NoSuchMethodError) { true }) throw IOException("WARP did not negotiate HTTP/2")
            secured.soTimeout = 0
            if (!sockets.valid()) throw IOException("WARP stopped")
            return raw to secured
        } catch (error: Exception) { sockets.release(raw); throw error }
    }
}
