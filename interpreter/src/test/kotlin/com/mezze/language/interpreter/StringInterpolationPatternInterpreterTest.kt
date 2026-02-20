package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.language.typechecker.ModuleResolver

/**
 * Comprehensive tests for string interpolation pattern matching in the interpreter
 *
 * Tests runtime behavior of:
 * - Basic string interpolation patterns
 * - Multiple captures in a single pattern
 * - Pattern matching with guards
 * - Backtracking for ambiguous patterns
 * - Edge cases and real-world use cases
 */
class StringInterpolationPatternInterpreterTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        )
        return result
    }

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
        assertEquals("world", (result as MezzeString).value)
    }

    @Test
    fun `basic pattern - capture and suffix`() {
        val code = """
            match "hello world" is
                "$(greeting) world" -> greeting
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("hello", (result as MezzeString).value)
    }

    @Test
    fun `basic pattern - prefix, capture, suffix`() {
        val code = """
            match "prefix-middle-suffix" is
                "prefix-$(middle)-suffix" -> middle
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("middle", (result as MezzeString).value)
    }

    @Test
    fun `basic pattern - capture only`() {
        val code = """
            match "anything here" is
                "$(all)" -> all
        """.trimIndent()

        val result = eval(code)
        assertEquals("anything here", (result as MezzeString).value)
    }

    @Test
    fun `basic pattern - no match returns fallback`() {
        val code = """
            match "wrong prefix" is
                "hello $(name)" -> name
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("no match", (result as MezzeString).value)
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
        assertEquals("first: foo, second: bar", (result as MezzeString).value)
    }

    @Test
    fun `three captures with separators`() {
        val code = """
            match "2024-01-15" is
                "$(year)-$(month)-$(day)" -> "$(day)/$(month)/$(year)"
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("15/01/2024", (result as MezzeString).value)
    }

    @Test
    fun `captures with prefix and suffix`() {
        val code = """
            match "[INFO] Hello World @12:00" is
                "[$(level)] $(message) @$(time)" -> { level = level, msg = message, time = time }
                _ -> { level = "", msg = "", time = "" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("INFO", (result.fields["level"] as MezzeString).value)
        assertEquals("Hello World", (result.fields["msg"] as MezzeString).value)
        assertEquals("12:00", (result.fields["time"] as MezzeString).value)
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
        assertEquals("Admin access", (result as MezzeString).value)
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
        assertEquals("Regular user: guest", (result as MezzeString).value)
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
        assertEquals("Critical: Connection failed", (result as MezzeString).value)
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
        assertEquals("GET", (result.fields["method"] as MezzeString).value)
        assertEquals("/api/users", (result.fields["path"] as MezzeString).value)
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
        assertEquals("POST", (result.fields["method"] as MezzeString).value)
        assertEquals("/api/create", (result.fields["path"] as MezzeString).value)
    }

    @Test
    fun `file extension detection`() {
        val code = """
            let classify_file = {name: String} ->
                match name is
                    "$(base).txt" -> "text"
                    "$(base).json" -> "json"
                    "$(base).xml" -> "xml"
                    _ -> "unknown"

            classify_file {name = "data.json"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("json", (result as MezzeString).value)
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
        assertEquals("a", (result.fields["first"] as MezzeString).value)
        assertEquals("b-c", (result.fields["rest"] as MezzeString).value)
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
        assertEquals("start-middle", (result.fields["prefix"] as MezzeString).value)
        assertEquals("finish", (result.fields["suffix"] as MezzeString).value)
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
        assertEquals("", (result as MezzeString).value)
    }

    @Test
    fun `empty capture at end`() {
        val code = """
            match "prefix-" is
                "prefix-$(suffix)" -> suffix
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("", (result as MezzeString).value)
    }

    @Test
    fun `capture with special characters`() {
        val code = """
            match "hello@world.com" is
                "$(user)@$(domain)" -> { user = user, domain = domain }
                _ -> { user = "", domain = "" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("hello", (result.fields["user"] as MezzeString).value)
        assertEquals("world.com", (result.fields["domain"] as MezzeString).value)
    }

    @Test
    fun `literal string pattern still works`() {
        val code = """
            match "exact" is
                "exact" -> "matched"
                _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("matched", (result as MezzeString).value)
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
        assertEquals("100", (result as MezzeString).value)
    }

    // ==========================================================================
    // SECTION 7: Real-World Use Cases
    // ==========================================================================

    @Test
    fun `URL parsing`() {
        val code = """
            let parse_url = {url: String} ->
                match url is
                    "https://$(domain)/$(path)" -> { secure = True, domain = domain, path = path }
                    "http://$(domain)/$(path)" -> { secure = False, domain = domain, path = path }
                    _ -> { secure = False, domain = "", path = "" }

            parse_url {url = "https://example.com/api/v1"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals(true, (result.fields["secure"] as MezzeBool).value)
        assertEquals("example.com", (result.fields["domain"] as MezzeString).value)
        assertEquals("api/v1", (result.fields["path"] as MezzeString).value)
    }

    @Test
    fun `email parsing`() {
        val code = """
            let parse_email = {email: String} ->
                match email is
                    "$(local)@$(domain)" -> Some {val = { local = local, domain = domain }}
                    _ -> None

            parse_email {email = "user@example.com"}
        """.trimIndent()

        val result = eval(code) as MezzeVariant
        assertEquals("Some", result.constructor)
        val payload = result.value as MezzeRecord
        val inner = payload.fields["val"] as MezzeRecord
        assertEquals("user", (inner.fields["local"] as MezzeString).value)
        assertEquals("example.com", (inner.fields["domain"] as MezzeString).value)
    }

    @Test
    fun `log parsing with level classification`() {
        val code = """
            let classify_log = {line: String} ->
                match line is
                    "[DEBUG] $(msg)" -> { level = "debug", message = msg }
                    "[INFO] $(msg)" -> { level = "info", message = msg }
                    "[WARN] $(msg)" -> { level = "warn", message = msg }
                    "[ERROR] $(msg)" -> { level = "error", message = msg }
                    _ -> { level = "unknown", message = line }

            classify_log {line = "[WARN] Low disk space"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("warn", (result.fields["level"] as MezzeString).value)
        assertEquals("Low disk space", (result.fields["message"] as MezzeString).value)
    }

    @Test
    fun `command line parsing`() {
        val code = """
            let parse_cmd = {input: String} ->
                match input is
                    "help" -> "show help"
                    "version" -> "show version"
                    "run $(script)" -> "running $(script)"
                    "install $(pkg)" -> "installing $(pkg)"
                    _ -> "unknown command"

            parse_cmd {input = "run main.mz"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("running main.mz", (result as MezzeString).value)
    }

    @Test
    fun `configuration key-value parsing`() {
        val code = """
            let parse_config = {line: String} ->
                match line is
                    "# $(comment)" -> { kind = "comment", key = "", value = comment }
                    "$(key) = $(value)" -> { kind = "setting", key = key, value = value }
                    "" -> { kind = "empty", key = "", value = "" }
                    _ -> { kind = "invalid", key = "", value = line }

            parse_config {line = "host = localhost"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("setting", (result.fields["kind"] as MezzeString).value)
        assertEquals("host", (result.fields["key"] as MezzeString).value)
        assertEquals("localhost", (result.fields["value"] as MezzeString).value)
    }

    // ==========================================================================
    // SECTION 8: Function Definitions with String Patterns
    // ==========================================================================

    @Test
    fun `function using string pattern in match`() {
        val code = """
            let extract_name = {greeting: String} ->
                match greeting is
                    "Hello, $(name)!" -> name
                    "Hi, $(name)!" -> name
                    _ -> "stranger"

            extract_name {greeting = "Hello, Alice!"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("Alice", (result as MezzeString).value)
    }

    @Test
    fun `chained function calls with string patterns`() {
        val code = """
            let get_domain = {email: String} ->
                match email is
                    "$(user)@$(domain)" -> domain
                    _ -> ""

            let get_tld = {domain: String} ->
                match domain is
                    "$(base).$(tld)" -> tld
                    _ -> ""

            get_tld {domain = get_domain {email = "user@example.com"}}
        """.trimIndent()

        val result = eval(code)
        assertEquals("com", (result as MezzeString).value)
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
        assertEquals(true, (result.fields["found"] as MezzeBool).value)
        assertEquals("name", (result.fields["key"] as MezzeString).value)
        assertEquals("test", (result.fields["value"] as MezzeString).value)
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
        assertEquals("name", (result.fields["key"] as MezzeString).value)
        assertEquals("Alice Smith", (result.fields["value"] as MezzeString).value)
    }

    // ==========================================================================
    // SECTION 10: Complex Real-World Scenarios
    // ==========================================================================

    @Test
    fun `API path routing`() {
        val code = """
            let route = {path: String} ->
                match path is
                    "/api/v1/users/$(userId)" -> { endpoint = "user", id = userId }
                    "/api/v1/posts/$(postId)" -> { endpoint = "post", id = postId }
                    "/api/v1/$(resource)" -> { endpoint = resource, id = "" }
                    _ -> { endpoint = "not_found", id = "" }

            route {path = "/api/v1/users/123"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("user", (result.fields["endpoint"] as MezzeString).value)
        assertEquals("123", (result.fields["id"] as MezzeString).value)
    }

    @Test
    fun `semantic version parsing`() {
        val code = """
            let parse_version = {ver: String} ->
                match ver is
                    "$(major).$(minor).$(patch)" -> { major = major, minor = minor, patch = patch }
                    "$(major).$(minor)" -> { major = major, minor = minor, patch = "0" }
                    _ -> { major = "0", minor = "0", patch = "0" }

            parse_version {ver = "1.2.3"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("1", (result.fields["major"] as MezzeString).value)
        assertEquals("2", (result.fields["minor"] as MezzeString).value)
        assertEquals("3", (result.fields["patch"] as MezzeString).value)
    }
}
