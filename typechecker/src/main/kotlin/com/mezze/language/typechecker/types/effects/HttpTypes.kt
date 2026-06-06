package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type signatures for HTTP effect operations
 *
 * Corresponds to:
 * - stdlib file: std/effects/http.mz
 * - interpreter: HttpImplementations.kt
 */
object HttpTypes {

    // HTTP Response type: {status: Int, headers: Map String String, body: Bytes, url: Str}
    private val responseType = rec(
        "status" to int,
        "headers" to WellKnownTypeApp.of("Map", listOf(str, str)),
        "body" to bytes,
        "url" to str
    )

    // HTTP Error variant type
    private val httpErrorType = WellKnownTypeApp.of("HttpError", emptyList())

    // Result Response HttpError
    private val httpResultType = result(responseType, httpErrorType)

    // Request type
    private val requestType = rec(
        "method" to WellKnownTypeApp.of("Method", emptyList()),
        "url" to str,
        "headers" to WellKnownTypeApp.of("Map", listOf(str, str)),
        "body" to option(bytes),
        "timeout_ms" to option(int)
    )

    // HttpConfig type
    private val httpConfigType = rec(
        "timeout_ms" to int,
        "connect_timeout_ms" to int,
        "follow_redirects" to bool,
        "max_redirects" to int,
        "verify_ssl" to bool,
        "auto_decompress" to bool,
        "keep_alive" to bool
    )

    // UrlPattern variant type
    private val urlPatternType = WellKnownTypeApp.of("UrlPattern", emptyList())

    // MockRule type
    private val mockRuleType = rec(
        "methods" to MezzeType.SeqType(WellKnownTypeApp.of("Method", emptyList())),
        "url" to urlPatternType,
        "response" to httpResultType
    )

    // HttpClient handle type (opaque)
    private val httpClientHandleType = WellKnownTypeApp.of("HttpClientHandle", emptyList())

    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_http_client_create") { returns(httpClientHandleType) }
        fn("__native_http_client_close") { param("client" to httpClientHandleType); returns(unit) }
        fn("__native_http_request") {
            param("req" to requestType, "config" to httpConfigType, "client" to httpClientHandleType)
            returns(httpResultType)
        }
        fn("__native_http_mock_request") {
            param("req" to requestType, "rules" to seq(mockRuleType))
            returns(httpResultType)
        }
        fn("__native_base64_encode") { param("s" to str); returns(str) }
        fn("__native_base64_decode") { param("s" to str); returns(result(str, str)) }
    }
}
