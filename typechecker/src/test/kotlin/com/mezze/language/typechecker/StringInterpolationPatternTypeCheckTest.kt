package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertContains

/**
 * Comprehensive tests for string interpolation pattern matching type checking
 *
 * Tests cover:
 * - Basic string interpolation patterns
 * - Multiple captures in a single pattern
 * - Capture variable type inference (all Str)
 * - Pattern matching with guards
 * - Error cases: type mismatches, invalid scrutinee types
 * - Exhaustiveness checking
 * - Edge cases: empty captures, special characters
 * - Integration with other pattern types
 */
class StringInterpolationPatternTypeCheckTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun assertTypeCheckFails(code: String, expectedMessagePart: String? = null): TypeCheckError {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        if (expectedMessagePart != null) {
            assertContains(error.message ?: "", expectedMessagePart, ignoreCase = true)
        }
        return error
    }

    // ==========================================================================
    // SECTION 1: Basic String Interpolation Patterns
    // ==========================================================================

    @Test
    fun `basic string pattern - prefix capture suffix`() {
        val code = """
            let extract_middle = {s: Str} ->
                match s is
                    "prefix-$(middle)-suffix" -> middle
                    _ -> "no match"
            let result = extract_middle {s = "prefix-hello-suffix"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `basic string pattern - capture at start`() {
        val code = """
            let extract_name = {s: Str} ->
                match s is
                    "$(name): some text" -> name
                    _ -> "unknown"
            let result = extract_name {s = "John: some text"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `basic string pattern - capture at end`() {
        val code = """
            let extract_extension = {s: Str} ->
                match s is
                    "file.$(ext)" -> ext
                    _ -> "unknown"
            let result = extract_extension {s = "file.txt"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `basic string pattern - capture only`() {
        val code = """
            let identity = {s: Str} ->
                match s is
                    "$(all)" -> all
            let result = identity {s = "anything"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 2: Multiple Captures
    // ==========================================================================

    @Test
    fun `multiple captures - two captures with separator`() {
        val code = """
            let extract_pair = {s: Str} ->
                match s is
                    "$(first)-$(second)" -> { first = first, second = second }
                    _ -> { first = "", second = "" }
            let result = extract_pair {s = "hello-world"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `multiple captures - three captures`() {
        val code = """
            let parse_date = {s: Str} ->
                match s is
                    "$(year)-$(month)-$(day)" -> { year = year, month = month, day = day }
                    _ -> { year = "", month = "", day = "" }
            let result = parse_date {s = "2024-01-15"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `multiple captures - with prefix and suffix`() {
        val code = """
            let parse_log = {s: Str} ->
                match s is
                    "[$(level)] $(message) @$(timestamp)" -> { level = level, message = message, ts = timestamp }
                    _ -> { level = "", message = "", ts = "" }
            let result = parse_log {s = "[INFO] Hello World @12:00"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 3: Capture Variable Type Inference
    // ==========================================================================

    @Test
    fun `capture variables are String type - used as String`() {
        val code = """
            let get_greeting = {s: Str} ->
                match s is
                    "Hello, $(name)!" -> "Greeting received from: $(name)"
                    _ -> "No greeting"
            let result = get_greeting {s = "Hello, Alice!"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `capture variables - return String directly`() {
        val code = """
            let extract = {s: Str} ->
                match s is
                    "$(value)" -> value
            let result: Str = extract {s = "test"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `capture variables - used in record with String field`() {
        val code = """
            let parse = {s: Str} ->
                match s is
                    "$(key)=$(value)" -> { key = key, value = value }
                    _ -> { key = "", value = "" }
            let result: { key: Str, value: Str } = parse {s = "name=test"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `capture variables - concatenation with other strings`() {
        val code = """
            let format = {s: Str} ->
                match s is
                    "$(name)@$(domain)" -> "Email: $(name) at $(domain)"
                    _ -> "Invalid"
            let result = format {s = "user@example.com"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 4: Pattern Matching with Guards
    // ==========================================================================

    @Test
    fun `string pattern with guard - simple comparison`() {
        val code = """
            let check_admin = {s: Str} ->
                match s is
                    "user:$(name)" if name == "admin" -> "Admin user"
                    "user:$(name)" -> "Regular user: $(name)"
                    _ -> "Unknown"
            let result = check_admin {s = "user:admin"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `string pattern with guard - using extracted value`() {
        val code = """
            let classify_level = {s: Str} ->
                match s is
                    "[$(level)] $(msg)" if level == "ERROR" -> "Critical: $(msg)"
                    "[$(level)] $(msg)" if level == "WARN" -> "Warning: $(msg)"
                    "[$(level)] $(msg)" -> "Info: $(msg)"
                    _ -> "Unknown format"
            let result = classify_level {s = "[ERROR] Something failed"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `string pattern with guard - multiple conditions`() {
        val code = """
            let validate = {s: Str} ->
                match s is
                    "$(user):$(pass)" if user != "" && pass != "" -> True
                    _ -> False
            let result = validate {s = "admin:secret"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 5: Multiple Match Cases
    // ==========================================================================

    @Test
    fun `multiple string patterns - different prefixes`() {
        val code = """
            let parse_command = {s: Str} ->
                match s is
                    "GET $(path)" -> { method = "GET", path = path }
                    "POST $(path)" -> { method = "POST", path = path }
                    "DELETE $(path)" -> { method = "DELETE", path = path }
                    _ -> { method = "UNKNOWN", path = "" }
            let result = parse_command {s = "GET /api/users"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `multiple string patterns - with wildcard fallback`() {
        val code = """
            let format_type = {s: Str} ->
                match s is
                    "$(name).txt" -> "text file: $(name)"
                    "$(name).json" -> "JSON file: $(name)"
                    "$(name).xml" -> "XML file: $(name)"
                    _ -> "unknown file type"
            let result = format_type {s = "data.json"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `string patterns with plain string fallback`() {
        val code = """
            let describe = {s: Str} ->
                match s is
                    "hello" -> "greeting"
                    "goodbye" -> "farewell"
                    "$(prefix)-world" -> "ends with world, prefix: $(prefix)"
                    _ -> "other"
            let result = describe {s = "hello-world"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 6: Error Cases - Type Mismatches
    // ==========================================================================

    @Test
    fun `error - string pattern on Int scrutinee`() {
        val code = """
            let bad = {n: Int} ->
                match n is
                    "$(x)" -> x
                    _ -> "default"
        """.trimIndent()

        assertTypeCheckFails(code, "Str")
    }

    @Test
    fun `error - string pattern on Bool scrutinee`() {
        val code = """
            let bad = {b: Bool} ->
                match b is
                    "$(x)" -> x
                    _ -> "default"
        """.trimIndent()

        assertTypeCheckFails(code)
    }

    @Test
    fun `error - string pattern on variant type`() {
        val code = """
            type Color = | Red, Green, Blue |
            let bad = {c: Color} ->
                match c is
                    "$(x)" -> x
                    _ -> "default"
        """.trimIndent()

        assertTypeCheckFails(code, "Color")
    }

    @Test
    fun `error - string pattern on array type`() {
        val code = """
            let bad = {xs: [Int]} ->
                match xs is
                    "$(x)" -> x
                    _ -> "default"
        """.trimIndent()

        assertTypeCheckFails(code)
    }

    @Test
    fun `error - string pattern on record type`() {
        val code = """
            let bad = {r: { x: Int, y: Int }} ->
                match r is
                    "$(x)" -> x
                    _ -> "default"
        """.trimIndent()

        assertTypeCheckFails(code)
    }

    // ==========================================================================
    // SECTION 7: Error Cases - Using Captures as Wrong Type
    // ==========================================================================

    @Test
    fun `error - capture used as Int`() {
        val code = """
            let bad = {s: Str} ->
                match s is
                    "$(n)" -> n + 1
                    _ -> 0
        """.trimIndent()

        assertTypeCheckFails(code)
    }

    @Test
    fun `error - capture used in arithmetic`() {
        val code = """
            let bad = {s: Str} ->
                match s is
                    "value: $(n)" -> n * 2
                    _ -> 0
        """.trimIndent()

        assertTypeCheckFails(code)
    }

    @Test
    fun `error - capture returned as Int`() {
        val code = """
            let bad = {s: Str} ->
                match s is
                    "$(x)" -> x
                    _ -> 42
        """.trimIndent()

        assertTypeCheckFails(code)
    }

    // ==========================================================================
    // SECTION 8: Exhaustiveness Checking
    // ==========================================================================

    @Test
    fun `string patterns are refutable - need fallback`() {
        val code = """
            let parse = {s: Str} ->
                match s is
                    "hello $(name)" -> name
                    _ -> "default"
            let result = parse {s = "hello world"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `variable pattern is exhaustive for strings`() {
        val code = """
            let identity = {s: Str} ->
                match s is
                    x -> x
            let result = identity {s = "test"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `wildcard pattern is exhaustive for strings`() {
        val code = """
            let constant = {s: Str} ->
                match s is
                    _ -> "constant"
            let result = constant {s = "anything"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 9: Integration with Other Patterns
    // ==========================================================================

    @Test
    fun `string patterns in Option type`() {
        val code = """
            let parse_opt = {opt} ->
                match opt is
                    Some {val = "$(name)@$(domain)"} -> { name = name, domain = domain }
                    Some {val = _} -> { name = "", domain = "" }
                    None -> { name = "", domain = "" }
            let result = parse_opt {opt = Some {val = "user@example.com"}}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `string patterns in arrays`() {
        val code = """
            let parse_first = {strs: [Str]} ->
                match strs is
                    ["$(first)-$(second)", ...] -> { a = first, b = second }
                    _ -> { a = "", b = "" }
            let result = parse_first {strs = ["hello-world", "foo-bar"]}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `nested match with string patterns`() {
        val code = """
            let parse_deeply = {outer} ->
                match outer is
                    Some {val = inner} ->
                        match inner is
                            "$(key)=$(value)" -> { key = key, value = value }
                            _ -> { key = "", value = "" }
                    None -> { key = "", value = "" }
            let result = parse_deeply {outer = Some {val = "name=test"}}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 10: Edge Cases
    // ==========================================================================

    @Test
    fun `edge case - literal string pattern (no interpolation)`() {
        val code = """
            let check = {s: Str} ->
                match s is
                    "exact match" -> True
                    _ -> False
            let result = check {s = "exact match"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `edge case - pattern with escaped dollar`() {
        val code = """
            let check = {s: Str} ->
                match s is
                    "cost: $$$(amount)" -> amount
                    _ -> ""
            let result = check {s = "cost: $100"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `edge case - very long pattern`() {
        val code = """
            let parse_complex = {s: Str} ->
                match s is
                    "BEGIN-$(a)-$(b)-$(c)-$(d)-$(e)-END" ->
                        { a = a, b = b, c = c, d = d, e = e }
                    _ -> { a = "", b = "", c = "", d = "", e = "" }
            let result = parse_complex {s = "BEGIN-1-2-3-4-5-END"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 11: Type Inference
    // ==========================================================================

    @Test
    fun `type inference - infer String scrutinee from pattern`() {
        val code = """
            let parse = {s} ->
                match s is
                    "hello $(name)" -> name
                    _ -> "default"
            let result: Str = parse {s = "hello world"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `type inference - infer from return type annotation`() {
        val code = """
            let extract: {s: Str} -> Str = {s} ->
                match s is
                    "$(value)" -> value
            let result = extract {s = "test"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 12: Real-World Use Cases
    // ==========================================================================

    @Test
    fun `real world - URL parsing`() {
        val code = """
            let parse_url = {url: Str} ->
                match url is
                    "https://$(domain)/$(path)" -> { protocol = "https", domain = domain, path = path }
                    "http://$(domain)/$(path)" -> { protocol = "http", domain = domain, path = path }
                    _ -> { protocol = "", domain = "", path = "" }
            let result = parse_url {url = "https://example.com/api/v1"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `real world - log parsing`() {
        val code = """
            type LogLevel = | Debug, Info, Warn, Error |

            let classify_log = {line: Str} ->
                match line is
                    "[DEBUG] $(msg)" -> { level = Debug, message = msg }
                    "[INFO] $(msg)" -> { level = Info, message = msg }
                    "[WARN] $(msg)" -> { level = Warn, message = msg }
                    "[ERROR] $(msg)" -> { level = Error, message = msg }
                    _ -> { level = Info, message = line }
            let result = classify_log {line = "[ERROR] Connection failed"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `real world - email validation structure`() {
        val code = """
            let parse_email = {email: Str} ->
                match email is
                    "$(local)@$(domain).$(tld)" ->
                        Some {val = { local = local, domain = domain, tld = tld }}
                    _ -> None
            let result = parse_email {email = "user@example.com"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `real world - command parsing`() {
        val code = """
            type Command = | Help, Version, Run { script: Str }, Install { pkg: Str } |

            let parse_cmd = {input: Str} ->
                match input is
                    "help" -> Help
                    "version" -> Version
                    "run $(script)" -> Run { script = script }
                    "install $(pkg)" -> Install { pkg = pkg }
                    _ -> Help
            let result = parse_cmd {input = "run main.mz"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `real world - configuration parsing`() {
        val code = """
            let parse_config_line = {line: Str} ->
                match line is
                    "# $(comment)" -> { kind = "comment", key = "", value = comment }
                    "$(key) = $(value)" -> { kind = "setting", key = key, value = value }
                    "" -> { kind = "empty", key = "", value = "" }
                    _ -> { kind = "invalid", key = "", value = line }
            let result = parse_config_line {line = "host = localhost"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 13: Recursive Functions with String Patterns
    // ==========================================================================

    @Test
    fun `recursive - parse key-value pairs`() {
        val code = """
            use std::collections::seq

            let parse_pairs = {lines: [Str]} ->
                match lines is
                    [] -> []
                    ["$(key)=$(value)", ...rest] ->
                        seq::of {x = { k = key, v = value }, xs = parse_pairs {lines = rest}}
                    [_, ...rest] -> parse_pairs {lines = rest}
            let result = parse_pairs {lines = ["a=1", "b=2", "invalid", "c=3"]}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 14: Or-Patterns with String Interpolation
    // ==========================================================================

    @Test
    fun `or pattern - different string patterns`() {
        val code = """
            let parse_greeting = {s: Str} ->
                match s is
                    "hello $(name)" | "hi $(name)" | "hey $(name)" -> name
                    _ -> "stranger"
            let result = parse_greeting {s = "hi Alice"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION 15: Complex Combinations
    // ==========================================================================

    @Test
    fun `complex - string pattern with record destructuring`() {
        val code = """
            let process = {data: { input: Str, prefix: Str }} ->
                match data is
                    { input = "$(start)-$(end)", prefix = p } if start == p ->
                        { matched = True, suffix = end }
                    _ -> { matched = False, suffix = "" }
            let result = process {data = { input = "foo-bar", prefix = "foo" }}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `complex - string pattern in do block`() {
        val code = """
            let analyze = {s: Str} -> do
                let parsed = match s is
                    "$(msgType): $(data)" -> { kind = msgType, payload = data }
                    _ -> { kind = "unknown", payload = s }
                let result = { kind = parsed.kind, payload = parsed.payload, length = 0 }
                result
            let output = analyze {s = "message: hello world"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `complex - chained string matching`() {
        val code = """
            let parse_path = {path: Str} ->
                match path is
                    "/api/v1/$(resource)/$(id)" -> { api = True, version = "v1", resource = resource, id = id }
                    "/api/v2/$(resource)/$(id)" -> { api = True, version = "v2", resource = resource, id = id }
                    "/$(section)/$(page)" -> { api = False, version = "", resource = section, id = page }
                    _ -> { api = False, version = "", resource = "", id = "" }
            let result = parse_path {path = "/api/v1/users/123"}
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ==========================================================================
    // SECTION: Error Messages with Source Locations
    // ==========================================================================

    @Test
    fun `error message for string pattern on variant has source location`() {
        val code = """
            type Color = | Red, Green, Blue |
            let bad = {c: Color} ->
                match c is
                    "$(x)" -> x
                    _ -> "default"
        """.trimIndent()

        val error = assertTypeCheckFails(code)
        // Error should have source location
        assertNotNull(error.sourceLocation, "Error should have source location")
        // Error message should mention the variant type
        assertTrue(error.message?.contains("Color") == true || error.message?.contains("variant") == true,
            "Error message should mention Color or variant. Actual: ${error.message}")
    }

    @Test
    fun `error message for string pattern on record has source location`() {
        val code = """
            let bad = {r: {x: Int}} ->
                match r is
                    "$(y)" -> y
                    _ -> "default"
        """.trimIndent()

        val error = assertTypeCheckFails(code)
        assertNotNull(error.sourceLocation, "Error should have source location")
    }
}
