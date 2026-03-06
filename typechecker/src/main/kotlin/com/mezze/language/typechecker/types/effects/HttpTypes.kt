package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType

/**
 * Type signatures for HTTP effect operations
 *
 * Corresponds to:
 * - stdlib file: std/effects/http.mz
 * - interpreter: HttpImplementations.kt
 */
object HttpTypes {

    // Helper to create Result type
    private fun resultType(valueType: MezzeType, errorType: MezzeType): MezzeType {
        return MezzeType.TypeApplication("Result", listOf(valueType, errorType))
    }

    // Helper to create Option type
    private fun optionType(elementType: MezzeType): MezzeType {
        return MezzeType.TypeApplication("Option", listOf(elementType))
    }

    // HTTP Response type: {status: Int, headers: Map String String, body: Bytes}
    private val responseType = MezzeType.RecordType(mapOf(
        "status" to MezzeType.IntType,
        "headers" to MezzeType.TypeApplication("Map", listOf(MezzeType.StringType, MezzeType.StringType)),
        "body" to MezzeType.BytesType
    ))

    // HTTP Error variant type
    private val httpErrorType = MezzeType.TypeApplication("HttpError", emptyList())

    // Result Response HttpError
    private val httpResultType = resultType(responseType, httpErrorType)

    // Request type
    private val requestType = MezzeType.RecordType(mapOf(
        "method" to MezzeType.TypeApplication("Method", emptyList()),
        "url" to MezzeType.StringType,
        "headers" to MezzeType.TypeApplication("Map", listOf(MezzeType.StringType, MezzeType.StringType)),
        "body" to optionType(MezzeType.BytesType),
        "timeout_ms" to optionType(MezzeType.IntType)
    ))

    // HttpConfig type (comprehensive configuration)
    private val httpConfigType = MezzeType.RecordType(mapOf(
        "timeout_ms" to MezzeType.IntType,
        "connect_timeout_ms" to MezzeType.IntType,
        "follow_redirects" to MezzeType.BoolType,
        "max_redirects" to MezzeType.IntType,
        "verify_ssl" to MezzeType.BoolType,
        "auto_decompress" to MezzeType.BoolType,
        "keep_alive" to MezzeType.BoolType
    ))

    // UrlPattern variant type: | Glob { pattern: String } |
    private val urlPatternType = MezzeType.TypeApplication("UrlPattern", emptyList())

    // MockRule type: { methods: [Method], url: UrlPattern, response: Result Response HttpError }
    private val mockRuleType = MezzeType.RecordType(mapOf(
        "methods" to MezzeType.ArrayType(MezzeType.TypeApplication("Method", emptyList())),
        "url" to urlPatternType,
        "response" to httpResultType
    ))

    // HttpClient handle type (opaque type for the client handle)
    // Using a TypeApplication with no args to represent an opaque external type
    private val httpClientHandleType = MezzeType.TypeApplication("HttpClientHandle", emptyList())

    // Unit type is represented as empty record
    private val unitType = MezzeType.RecordType(emptyMap())

    fun registerAll(context: MutableMap<String, MezzeType>) {
        // __native_http_client_create: {} -> HttpClientHandle
        // Creates a new HTTP client instance (WebClient wrapper)
        context["__native_http_client_create"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            httpClientHandleType
        )

        // __native_http_client_close: {client: HttpClientHandle} -> {}
        // Closes an HTTP client instance
        context["__native_http_client_close"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("client" to httpClientHandleType)),
            unitType
        )

        // __native_http_request: {req: Request, config: HttpConfig, client: HttpClientHandle} -> Result Response HttpError
        // Used by HttpClient implementation with scoped effects
        context["__native_http_request"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "req" to requestType,
                "config" to httpConfigType,
                "client" to httpClientHandleType
            )),
            httpResultType
        )

        // MockRule array type for MockHttpClient
        val mockRulesType = MezzeType.ArrayType(mockRuleType)

        // __native_http_mock_request: {req: Request, rules: [MockRule]} -> Result Response HttpError
        // Used by MockHttpClient implementation
        context["__native_http_mock_request"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "req" to requestType,
                "rules" to mockRulesType
            )),
            httpResultType
        )

        // Utility functions
        // __native_base64_encode: {s: String} -> String
        context["__native_base64_encode"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        // __native_base64_decode: {s: String} -> Result String String
        context["__native_base64_decode"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            resultType(MezzeType.StringType, MezzeType.StringType)
        )
    }
}
