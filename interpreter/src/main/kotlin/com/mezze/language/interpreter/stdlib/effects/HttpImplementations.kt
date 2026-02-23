package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.stdlib.basic.StringImplementations
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.client.HttpResponse
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * HTTP effect implementations using Vert.x WebClient.
 *
 * The Http effect has a single operation: request
 * All convenience methods (get, post, etc.) are helper functions in Mezze
 * that build Request records and call Http.request.
 *
 * Implementations:
 * - HttpClient: Production implementation using Vert.x WebClient
 * - MockHttpClient: Testing implementation with configurable responses
 */
object HttpImplementations {

    // Shared Vert.x instance (lazily initialized, thread-safe)
    private val vertx: Vertx by lazy { Vertx.vertx() }

    // Default WebClient with sensible defaults
    private val defaultClient: WebClient by lazy {
        val options = WebClientOptions()
            .setConnectTimeout(30000)
            .setIdleTimeout(60)
            .setFollowRedirects(true)
            .setMaxRedirects(10)
            .setTrustAll(false)
            .setVerifyHost(true)
        WebClient.create(vertx, options)
    }

    /**
     * Register all HTTP effect implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Core request operation with config
        context["__native_http_request"] = NativeMezzeFunction("__native_http_request") { args ->
            val record = args[0] as MezzeRecord
            val req = record.fields["req"] as MezzeRecord
            val config = record.fields["config"] as MezzeRecord
            executeFromRequestWithConfig(req, config)
        }

        // Mock request operation with glob pattern matching
        context["__native_http_mock_request"] = NativeMezzeFunction("__native_http_mock_request") { args ->
            val record = args[0] as MezzeRecord
            val req = record.fields["req"] as MezzeRecord
            val rules = record.fields["rules"] as MezzeArrayList
            val url = (req.fields["url"] as MezzeString).value
            val method = req.fields["method"] as MezzeVariant
            findMockResponse(url, method, rules)
        }

        // Utility functions
        context["__native_base64_encode"] = NativeMezzeFunction("__native_base64_encode") { args ->
            val record = args[0] as MezzeRecord
            val s = (record.fields["s"] as MezzeString).value
            val encoded = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
            MezzeString(encoded)
        }

        context["__native_base64_decode"] = NativeMezzeFunction("__native_base64_decode") { args ->
            val record = args[0] as MezzeRecord
            val s = (record.fields["s"] as MezzeString).value
            try {
                val decoded = java.util.Base64.getDecoder().decode(s)
                mezzeResultOk(MezzeString(String(decoded)))
            } catch (e: IllegalArgumentException) {
                mezzeResultErr(MezzeString("Invalid base64 string"))
            }
        }
    }

    // =========================================================================
    // Request Execution
    // =========================================================================

    private fun executeFromRequestWithConfig(req: MezzeRecord, config: MezzeRecord): MezzeVariant {
        val method = parseMethod(req.fields["method"] as MezzeVariant)
        val url = (req.fields["url"] as MezzeString).value
        val headers = parseHeaders(req.fields["headers"] as MezzeMap)
        val body = parseOptionalBody(req.fields["body"])

        // Parse config
        val timeoutMs = (config.fields["timeout_ms"] as MezzeInt).value.toInt()
        val connectTimeoutMs = (config.fields["connect_timeout_ms"] as MezzeInt).value.toInt()
        val followRedirects = (config.fields["follow_redirects"] as MezzeBool).value
        val maxRedirects = (config.fields["max_redirects"] as MezzeInt).value.toInt()
        val verifySsl = (config.fields["verify_ssl"] as MezzeBool).value
        val autoDecompress = (config.fields["auto_decompress"] as MezzeBool).value
        val keepAlive = (config.fields["keep_alive"] as MezzeBool).value

        // Override timeout from request if specified
        val effectiveTimeout = parseOptionalInt(req.fields["timeout_ms"]) ?: timeoutMs

        return executeRequest(
            method = method,
            urlString = url,
            body = body,
            headers = headers,
            timeoutMs = effectiveTimeout,
            connectTimeoutMs = connectTimeoutMs,
            followRedirects = followRedirects,
            maxRedirects = maxRedirects,
            verifySsl = verifySsl,
            autoDecompress = autoDecompress,
            keepAlive = keepAlive
        )
    }

    private fun executeRequest(
        method: HttpMethod,
        urlString: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30000,
        connectTimeoutMs: Int = 10000,
        followRedirects: Boolean = true,
        maxRedirects: Int = 10,
        verifySsl: Boolean = true,
        autoDecompress: Boolean = true,
        keepAlive: Boolean = true
    ): MezzeVariant {
        val parsed = parseUrl(urlString)
        if (parsed == null) {
            return invalidUrl(urlString, "Invalid URL format")
        }

        val future = CompletableFuture<MezzeVariant>()

        try {
            // Create client with configuration
            val options = WebClientOptions()
                .setConnectTimeout(connectTimeoutMs)
                .setIdleTimeout(60)
                .setFollowRedirects(followRedirects)
                .setMaxRedirects(maxRedirects)
                .setTrustAll(!verifySsl)
                .setVerifyHost(verifySsl)
                .setKeepAlive(keepAlive)
                .setDecompressionSupported(autoDecompress)

            val client = WebClient.create(vertx, options)

            val request = if (parsed.ssl) {
                client.request(method, parsed.port, parsed.host, parsed.path).ssl(true)
            } else {
                client.request(method, parsed.port, parsed.host, parsed.path)
            }

            // Add headers
            headers.forEach { (key, value) ->
                request.putHeader(key, value)
            }

            // Set timeout
            request.timeout(timeoutMs.toLong())

            // Send request
            val sendFuture = if (body != null) {
                request.sendBuffer(Buffer.buffer(body))
            } else {
                request.send()
            }

            sendFuture
                .onSuccess { response ->
                    future.complete(createOk(responseToMezze(response)))
                }
                .onFailure { throwable ->
                    val error = when {
                        throwable is TimeoutException ||
                        throwable.message?.contains("timeout", ignoreCase = true) == true ->
                            timeout(urlString, timeoutMs)

                        throwable is SSLException ||
                        throwable.message?.contains("ssl", ignoreCase = true) == true ||
                        throwable.message?.contains("tls", ignoreCase = true) == true ||
                        throwable.message?.contains("certificate", ignoreCase = true) == true ->
                            tlsError(urlString, throwable.message ?: "TLS error")

                        throwable.message?.contains("connection refused", ignoreCase = true) == true ||
                        throwable.message?.contains("no route to host", ignoreCase = true) == true ||
                        throwable.message?.contains("network unreachable", ignoreCase = true) == true ->
                            connectionFailed(urlString, throwable.message ?: "Connection failed")

                        else ->
                            connectionFailed(urlString, throwable.message ?: "Unknown error")
                    }
                    future.complete(error)
                }

            // Wait for result with timeout
            return future.get(timeoutMs.toLong() + 5000, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            return timeout(urlString, timeoutMs)
        } catch (e: InterruptedException) {
            return cancelled()
        } catch (e: Exception) {
            return connectionFailed(urlString, e.message ?: "Unknown error")
        }
    }

    // =========================================================================
    // Mock Response Lookup with Glob Pattern Matching
    // =========================================================================

    private fun findMockResponse(url: String, method: MezzeVariant, rules: MezzeArrayList): MezzeVariant {
        // Extract just the path from the URL for matching
        val path = extractPath(url)

        for (item in rules.elements) {
            val rule = item as MezzeRecord
            val methods = rule.fields["methods"] as MezzeArrayList
            val urlPattern = rule.fields["url"] as MezzeVariant

            // Check method match (empty array = match any method)
            if (!methods.elements.isEmpty) {
                val methodMatches = methods.elements.any { m ->
                    (m as MezzeVariant).constructor == method.constructor
                }
                if (!methodMatches) continue
            }

            // Check URL pattern match
            if (matchUrlPattern(path, urlPattern)) {
                return rule.fields["response"] as MezzeVariant
            }
        }

        // No matching rule found
        return connectionFailed(url, "No mock rule matched for ${method.constructor} $url")
    }

    private fun extractPath(url: String): String {
        return try {
            val parsed = URL(url)
            val path = parsed.path ?: "/"
            val query = parsed.query
            if (query != null) "$path?$query" else path
        } catch (e: Exception) {
            url // If not a valid URL, use as-is (might be just a path)
        }
    }

    private fun matchUrlPattern(path: String, urlPattern: MezzeVariant): Boolean {
        return when (urlPattern.constructor) {
            "Glob" -> {
                val payload = urlPattern.value as MezzeRecord
                val pattern = (payload.fields["pattern"] as MezzeString).value
                StringImplementations.globMatch(pattern, path)
            }
            else -> false
        }
    }

    // =========================================================================
    // Result Helpers
    // =========================================================================

    private fun createOk(value: MezzeValue): MezzeVariant = mezzeResultOk(value)
    private fun createErr(errorVariant: MezzeVariant): MezzeVariant = mezzeResultErr(errorVariant)

    private fun connectionFailed(url: String, reason: String) = createErr(
        MezzeVariant("ConnectionFailed", MezzeRecord(mapOf(
            "url" to MezzeString(url),
            "reason" to MezzeString(reason)
        )))
    )

    private fun timeout(url: String, ms: Int) = createErr(
        MezzeVariant("Timeout", MezzeRecord(mapOf(
            "url" to MezzeString(url),
            "ms" to MezzeInt(ms.toLong())
        )))
    )

    private fun invalidUrl(url: String, reason: String) = createErr(
        MezzeVariant("InvalidUrl", MezzeRecord(mapOf(
            "url" to MezzeString(url),
            "reason" to MezzeString(reason)
        )))
    )

    private fun tlsError(url: String, reason: String) = createErr(
        MezzeVariant("TlsError", MezzeRecord(mapOf(
            "url" to MezzeString(url),
            "reason" to MezzeString(reason)
        )))
    )

    private fun cancelled() = createErr(MezzeVariant("Cancelled", null))

    // =========================================================================
    // Response Conversion
    // =========================================================================

    private fun responseToMezze(response: HttpResponse<Buffer>): MezzeRecord {
        val headers = mutableMapOf<MezzeValue, MezzeValue>()
        response.headers().forEach { entry ->
            headers[MezzeString(entry.key.lowercase())] = MezzeString(entry.value)
        }

        val bodyBytes = response.body()?.bytes ?: ByteArray(0)

        return MezzeRecord(mapOf(
            "status" to MezzeInt(response.statusCode().toLong()),
            "headers" to MezzeMap(headers),
            "body" to MezzeBytes(bodyBytes)
        ))
    }

    // =========================================================================
    // URL Parsing
    // =========================================================================

    private data class ParsedUrl(
        val ssl: Boolean,
        val host: String,
        val port: Int,
        val path: String
    )

    private fun parseUrl(urlString: String): ParsedUrl? {
        return try {
            val url = URL(urlString)
            val ssl = url.protocol.equals("https", ignoreCase = true)
            val port = if (url.port == -1) {
                if (ssl) 443 else 80
            } else {
                url.port
            }
            val path = if (url.path.isNullOrEmpty()) "/" else url.path + (url.query?.let { "?$it" } ?: "")
            ParsedUrl(ssl, url.host, port, path)
        } catch (e: MalformedURLException) {
            null
        }
    }

    // =========================================================================
    // Parsing Helpers
    // =========================================================================

    private fun parseMethod(variant: MezzeVariant): HttpMethod {
        return when (variant.constructor) {
            "Get" -> HttpMethod.GET
            "Post" -> HttpMethod.POST
            "Put" -> HttpMethod.PUT
            "Delete" -> HttpMethod.DELETE
            "Patch" -> HttpMethod.PATCH
            "Head" -> HttpMethod.HEAD
            "Options" -> HttpMethod.OPTIONS
            else -> HttpMethod.GET
        }
    }

    private fun parseHeaders(headersMap: MezzeMap): Map<String, String> {
        val result = mutableMapOf<String, String>()
        headersMap.entries.forEach { tuple ->
            val key = tuple._1
            val value = tuple._2
            if (key is MezzeString && value is MezzeString) {
                result[key.value] = value.value
            }
        }
        return result
    }

    private fun parseOptionalBody(bodyOption: MezzeValue?): ByteArray? {
        if (bodyOption == null) return null
        return when (bodyOption) {
            is MezzeVariant -> {
                when (bodyOption.constructor) {
                    "Some" -> {
                        val payload = bodyOption.value as? MezzeRecord
                        val bytesVal = payload?.fields?.get("val") as? MezzeBytes
                        bytesVal?.bytes
                    }
                    "None" -> null
                    else -> null
                }
            }
            is MezzeBytes -> bodyOption.bytes
            else -> null
        }
    }

    private fun parseOptionalInt(intOption: MezzeValue?): Int? {
        if (intOption == null) return null
        return when (intOption) {
            is MezzeVariant -> {
                when (intOption.constructor) {
                    "Some" -> {
                        val payload = intOption.value as? MezzeRecord
                        val int = payload?.fields?.get("val") as? MezzeInt
                        int?.value?.toInt()
                    }
                    "None" -> null
                    else -> null
                }
            }
            is MezzeInt -> intOption.value.toInt()
            else -> null
        }
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    /**
     * Shutdown the Vert.x instance (for clean shutdown)
     */
    fun shutdown() {
        try {
            vertx.close()
        } catch (e: Exception) {
            // Ignore shutdown errors
        }
    }
}
