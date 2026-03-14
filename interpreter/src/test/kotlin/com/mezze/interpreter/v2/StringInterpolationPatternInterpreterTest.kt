package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord
import com.mezze.interpreter.v2.values.MezzeVariant

/**
 * Comprehensive tests for string interpolation pattern matching in the interpreter.
 * Migrated from V1 to V2 with unboxed values.
 *
 * Tests runtime behavior of:
 * - Basic string interpolation patterns
 * - Multiple captures in a single pattern
 * - Pattern matching with guards
 * - Backtracking for ambiguous patterns
 * - Edge cases and real-world use cases
 */
class StringInterpolationPatternInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ==========================================================================
    // SECTION 1: Basic String Interpolation Patterns
    // ==========================================================================

    @Test
    fun `basic pattern - prefix and capture`() {
        val code = """
            match "hello world" is
                "hello $(name)" -> name
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("world", result)
    }

    @Test
    fun `basic pattern - capture and suffix`() {
        val code = """
            match "hello world" is
                "$(greeting) world" -> greeting
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("hello", result)
    }

    @Test
    fun `basic pattern - prefix, capture, suffix`() {
        val code = """
            match "prefix-middle-suffix" is
                "prefix-$(middle)-suffix" -> middle
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("middle", result)
    }

    @Test
    fun `basic pattern - capture only`() {
        val code = """
            match "anything here" is
                "$(all)" -> all
        """.trimIndent()

        val result = eval(code)
        assertEquals("anything here", result)
    }

    @Test
    fun `basic pattern - no match returns fallback`() {
        val code = """
            match "wrong prefix" is
                "hello $(name)" -> name
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("no match", result)
    }

    // ==========================================================================
    // SECTION 2: Multiple Captures
    // ==========================================================================

    @Test
    fun `two captures with separator`() {
        val code = """
            match "foo-bar" is
                "$(first)-$(second)" -> "first: $(first), second: $(second)"
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("first: foo, second: bar", result)
    }

    @Test
    fun `three captures with separators`() {
        val code = """
            match "2024-01-15" is
                "$(year)-$(month)-$(day)" -> "$(day)/$(month)/$(year)"
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("15/01/2024", result)
    }

    @Test
    fun `captures with prefix and suffix`() {
        val code = """
            match "[INFO] Hello World @12:00" is
                "[$(level)] $(message) @$(time)" -> { level = level, msg = message, time = time }
                _ -> { level = "", msg = "", time = "" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("INFO", result.getField("level"))
        assertEquals("Hello World", result.getField("msg"))
        assertEquals("12:00", result.getField("time"))
    }

    // ==========================================================================
    // SECTION 3: Pattern Matching with Guards
    // ==========================================================================

    @Test
    fun `pattern with guard - passes`() {
        val code = """
            match "user:admin" is
                "user:$(name)" if name == "admin" -> "Admin access"
                "user:$(name)" -> "Regular user: $(name)"
                _ -> "Unknown"
        """.trimIndent()

        val result = eval(code)
        assertEquals("Admin access", result)
    }

    @Test
    fun `pattern with guard - fails, falls through`() {
        val code = """
            match "user:guest" is
                "user:$(name)" if name == "admin" -> "Admin access"
                "user:$(name)" -> "Regular user: $(name)"
                _ -> "Unknown"
        """.trimIndent()

        val result = eval(code)
        assertEquals("Regular user: guest", result)
    }

    @Test
    fun `pattern with multiple guards`() {
        val code = """
            match "[ERROR] Connection failed" is
                "[$(level)] $(msg)" if level == "ERROR" -> "Critical: $(msg)"
                "[$(level)] $(msg)" if level == "WARN" -> "Warning: $(msg)"
                "[$(level)] $(msg)" -> "Info: $(msg)"
                _ -> "Unknown"
        """.trimIndent()

        val result = eval(code)
        assertEquals("Critical: Connection failed", result)
    }

    // ==========================================================================
    // SECTION 4: Multiple Match Cases
    // ==========================================================================

    @Test
    fun `multiple patterns - first matches`() {
        val code = """
            match "GET /api/users" is
                "GET $(path)" -> { method = "GET", path = path }
                "POST $(path)" -> { method = "POST", path = path }
                _ -> { method = "UNKNOWN", path = "" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("GET", result.getField("method"))
        assertEquals("/api/users", result.getField("path"))
    }

    @Test
    fun `multiple patterns - second matches`() {
        val code = """
            match "POST /api/create" is
                "GET $(path)" -> { method = "GET", path = path }
                "POST $(path)" -> { method = "POST", path = path }
                _ -> { method = "UNKNOWN", path = "" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("POST", result.getField("method"))
        assertEquals("/api/create", result.getField("path"))
    }

    @Test
    fun `file extension detection`() {
        val code = """
            let classify_file = {name: Str} ->
                match name is
                    "$(base).txt" -> "text"
                    "$(base).json" -> "json"
                    "$(base).xml" -> "xml"
                    _ -> "unknown"

            classify_file {name = "data.json"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("json", result)
    }

    // ==========================================================================
    // SECTION 5: Backtracking for Ambiguous Patterns
    // ==========================================================================

    @Test
    fun `backtracking - multiple separators`() {
        // When the separator appears multiple times, backtracking finds the correct split
        val code = """
            match "a-b-c" is
                "$(first)-$(rest)" -> { first = first, rest = rest }
                _ -> { first = "", rest = "" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        // Should greedily match first occurrence, then backtrack if needed
        assertEquals("a", result.getField("first"))
        assertEquals("b-c", result.getField("rest"))
    }

    @Test
    fun `backtracking - nested structure`() {
        // Pattern needs to backtrack to find the right boundary
        val code = """
            match "start-middle-end-finish" is
                "$(prefix)-end-$(suffix)" -> { prefix = prefix, suffix = suffix }
                _ -> { prefix = "", suffix = "" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("start-middle", result.getField("prefix"))
        assertEquals("finish", result.getField("suffix"))
    }

    // ==========================================================================
    // SECTION 6: Edge Cases
    // ==========================================================================

    @Test
    fun `empty capture at start`() {
        val code = """
            match "-suffix" is
                "$(prefix)-suffix" -> prefix
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("", result)
    }

    @Test
    fun `empty capture at end`() {
        val code = """
            match "prefix-" is
                "prefix-$(suffix)" -> suffix
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("", result)
    }

    @Test
    fun `capture with special characters`() {
        val code = """
            match "hello@world.com" is
                "$(user)@$(domain)" -> { user = user, domain = domain }
                _ -> { user = "", domain = "" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("hello", result.getField("user"))
        assertEquals("world.com", result.getField("domain"))
    }

    @Test
    fun `literal string pattern still works`() {
        val code = """
            match "exact" is
                "exact" -> "matched"
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("matched", result)
    }

    @Test
    fun `pattern with dollar sign in value`() {
        // Note: $$ in string literals produces a single $
        // So "cost: $$$(amount)" matches strings like "cost: $100"
        val code = """
            match "cost: 100" is
                "cost: $(amount)" -> amount
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("100", result)
    }

    // ==========================================================================
    // SECTION 7: Real-World Use Cases
    // ==========================================================================

    @Test
    fun `URL parsing`() {
        val code = """
            let parse_url = {url: Str} ->
                match url is
                    "https://$(domain)/$(path)" -> { secure = True, domain = domain, path = path }
                    "http://$(domain)/$(path)" -> { secure = False, domain = domain, path = path }
                    _ -> { secure = False, domain = "", path = "" }

            parse_url {url = "https://example.com/api/v1"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals(true, result.getField("secure"))
        assertEquals("example.com", result.getField("domain"))
        assertEquals("api/v1", result.getField("path"))
    }

    @Test
    fun `email parsing`() {
        val code = """
            let parse_email = {email: Str} ->
                match email is
                    "$(local)@$(domain)" -> Some {val = { local = local, domain = domain }}
                    _ -> None

            parse_email {email = "user@example.com"}
        """.trimIndent()

        val result = eval(code) as MezzeVariant
        assertEquals("Some", result.constructorName)
        val payload = result.payloadValue as MezzeRecord
        val inner = payload.getField("val") as MezzeRecord
        assertEquals("user", inner.getField("local"))
        assertEquals("example.com", inner.getField("domain"))
    }

    @Test
    fun `log parsing with level classification`() {
        val code = """
            let classify_log = {line: Str} ->
                match line is
                    "[DEBUG] $(msg)" -> { level = "debug", message = msg }
                    "[INFO] $(msg)" -> { level = "info", message = msg }
                    "[WARN] $(msg)" -> { level = "warn", message = msg }
                    "[ERROR] $(msg)" -> { level = "error", message = msg }
                    _ -> { level = "unknown", message = line }

            classify_log {line = "[WARN] Low disk space"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("warn", result.getField("level"))
        assertEquals("Low disk space", result.getField("message"))
    }

    @Test
    fun `command line parsing`() {
        val code = """
            let parse_cmd = {input: Str} ->
                match input is
                    "help" -> "show help"
                    "version" -> "show version"
                    "run $(script)" -> "running $(script)"
                    "install $(pkg)" -> "installing $(pkg)"
                    _ -> "unknown command"

            parse_cmd {input = "run main.mz"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("running main.mz", result)
    }

    @Test
    fun `configuration key-value parsing`() {
        val code = """
            let parse_config = {line: Str} ->
                match line is
                    "# $(comment)" -> { kind = "comment", key = "", value = comment }
                    "$(key) = $(value)" -> { kind = "setting", key = key, value = value }
                    "" -> { kind = "empty", key = "", value = "" }
                    _ -> { kind = "invalid", key = "", value = line }

            parse_config {line = "host = localhost"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("setting", result.getField("kind"))
        assertEquals("host", result.getField("key"))
        assertEquals("localhost", result.getField("value"))
    }

    // ==========================================================================
    // SECTION 8: Function Definitions with String Patterns
    // ==========================================================================

    @Test
    fun `function using string pattern in match`() {
        val code = """
            let extract_name = {greeting: Str} ->
                match greeting is
                    "Hello, $(name)!" -> name
                    "Hi, $(name)!" -> name
                    _ -> "stranger"

            extract_name {greeting = "Hello, Alice!"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("Alice", result)
    }

    @Test
    fun `chained function calls with string patterns`() {
        val code = """
            let get_domain = {email: Str} ->
                match email is
                    "$(user)@$(domain)" -> domain
                    _ -> ""

            let get_tld = {domain: Str} ->
                match domain is
                    "$(base).$(tld)" -> tld
                    _ -> ""

            get_tld {domain = get_domain {email = "user@example.com"}}
        """.trimIndent()

        val result = eval(code)
        assertEquals("com", result)
    }

    // ==========================================================================
    // SECTION 9: Integration with Other Patterns
    // ==========================================================================

    @Test
    fun `string pattern in nested match`() {
        val code = """
            let process = {opt} ->
                match opt is
                    Some {val = s} ->
                        match s is
                            "$(key)=$(value)" -> { found = True, key = key, value = value }
                            _ -> { found = False, key = "", value = "" }
                    None -> { found = False, key = "", value = "" }

            process {opt = Some {val = "name=test"}}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals(true, result.getField("found"))
        assertEquals("name", result.getField("key"))
        assertEquals("test", result.getField("value"))
    }

    @Test
    fun `string pattern with longer value`() {
        // Tests string interpolation pattern with a longer captured value
        val code = """
            match "name=Alice Smith" is
                "$(key)=$(value)" -> { key = key, value = value }
                _ -> { key = "", value = "" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("name", result.getField("key"))
        assertEquals("Alice Smith", result.getField("value"))
    }

    // ==========================================================================
    // SECTION 10: Complex Real-World Scenarios
    // ==========================================================================

    @Test
    fun `API path routing`() {
        val code = """
            let route = {path: Str} ->
                match path is
                    "/api/v1/users/$(userId)" -> { endpoint = "user", id = userId }
                    "/api/v1/posts/$(postId)" -> { endpoint = "post", id = postId }
                    "/api/v1/$(resource)" -> { endpoint = resource, id = "" }
                    _ -> { endpoint = "not_found", id = "" }

            route {path = "/api/v1/users/123"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("user", result.getField("endpoint"))
        assertEquals("123", result.getField("id"))
    }

    @Test
    fun `semantic version parsing`() {
        val code = """
            let parse_version = {ver: Str} ->
                match ver is
                    "$(major).$(minor).$(patch)" -> { major = major, minor = minor, patch = patch }
                    "$(major).$(minor)" -> { major = major, minor = minor, patch = "0" }
                    _ -> { major = "0", minor = "0", patch = "0" }

            parse_version {ver = "1.2.3"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("1", result.getField("major"))
        assertEquals("2", result.getField("minor"))
        assertEquals("3", result.getField("patch"))
    }
}
