package com.mezze.interpreter.v2

import com.mezze.interpreter.v2.values.MezzeRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Integration tests for combining string interpolation patterns with if-let guards.
 *
 * Tests that both features work correctly together in realistic scenarios:
 * - String pattern matching with conditional if-let extraction
 * - Chained lookups using captured string values
 * - Complex parsing scenarios with optional data
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class StringPatternIfLetIntegrationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ==========================================================================
    // SECTION 1: String Pattern with If-Let Guard
    // ==========================================================================

    @Test
    fun `string pattern captures value used in if-let lookup`() {
        val code = """
            let lookup: {name: Str} -> | Some {val: Int}, None | = {name} ->
                match name is
                    "alice" -> Some {val = 100}
                    "bob" -> Some {val = 200}
                    _ -> None

            let process = {input: Str} ->
                match input is
                    "user:$(name)" if let Some {val = score} = lookup {name = name} -> score
                    _ -> 0

            process {input = "user:alice"}
        """.trimIndent()

        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun `string pattern with if-let falls through on None`() {
        val code = """
            let lookup: {name: Str} -> | Some {val: Int}, None | = {name} ->
                match name is
                    "alice" -> Some {val = 100}
                    _ -> None

            let process = {input: Str} ->
                match input is
                    "user:$(name)" if let Some {val = score} = lookup {name = name} -> score
                    "user:$(name)" -> -1
                    _ -> -2

            process {input = "user:unknown"}
        """.trimIndent()

        val result = eval(code)
        assertEquals(-1L, result)
    }

    @Test
    fun `string pattern with if-let checking payload value in body`() {
        // Note: "if let ... if condition" syntax is not supported.
        // Use nested match or check condition in the body instead.
        val code = """
            let lookup: {name: Str} -> | Some {val: Int}, None | = {name} ->
                match name is
                    "admin" -> Some {val = 999}
                    "user" -> Some {val = 100}
                    _ -> None

            let process = {input: Str} ->
                match input is
                    "role:$(name)" if let Some {val = level} = lookup {name = name} ->
                        match level > 500 is
                            True -> "admin access"
                            False -> "user access"
                    _ -> "no access"

            process {input = "role:admin"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("admin access", result)
    }

    // ==========================================================================
    // SECTION 2: Multiple Captures with If-Let
    // ==========================================================================

    @Test
    fun `multiple string captures with if-let lookup on one`() {
        val code = """
            let validate: {domain: Str} -> | Some {val: Str}, None | = {domain} ->
                match domain is
                    "example.com" -> Some {val = "trusted"}
                    "test.org" -> Some {val = "testing"}
                    _ -> None

            let parse_email = {email: Str} ->
                match email is
                    "$(user)@$(domain)" if let Some {val = status} = validate {domain = domain} ->
                        "$(user) on $(status) domain"
                    "$(user)@$(domain)" -> "$(user) on unknown domain"
                    _ -> "invalid email"

            parse_email {email = "alice@example.com"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("alice on trusted domain", result)
    }

    @Test
    fun `both string captures used in if-let expression`() {
        val code = """
            let lookup_pair: {a: Str, b: Str} -> | Some {val: Int}, None | = {a, b} ->
                match a is
                    "foo" ->
                        match b is
                            "bar" -> Some {val = 42}
                            _ -> None
                    _ -> None

            let process = {input: Str} ->
                match input is
                    "$(first)-$(second)" if let Some {val = result} = lookup_pair {a = first, b = second} -> result
                    _ -> 0

            process {input = "foo-bar"}
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, result)
    }

    // ==========================================================================
    // SECTION 3: Nested If-Let with String Patterns
    // ==========================================================================

    @Test
    fun `string pattern result used in nested option extraction`() {
        val code = """
            let get_config: {key: Str} -> | Some {val: {enabled: Bool, value: Int}}, None | = {key} ->
                match key is
                    "debug" -> Some {val = {enabled = True, value = 1}}
                    "release" -> Some {val = {enabled = False, value = 0}}
                    _ -> None

            let process = {input: Str} ->
                match input is
                    "config:$(key)" if let Some {val = {enabled, value}} = get_config {key = key} ->
                        match enabled is
                            True -> value + 100
                            False -> value
                    _ -> -1

            process {input = "config:debug"}
        """.trimIndent()

        val result = eval(code)
        assertEquals(101L, result)
    }

    // ==========================================================================
    // SECTION 4: Real-World Scenarios
    // ==========================================================================

    @Test
    fun `URL routing with optional user lookup`() {
        val code = """
            let find_user: {id: Str} -> | Some {val: {name: Str, role: Str}}, None | = {id} ->
                match id is
                    "123" -> Some {val = {name = "Alice", role = "admin"}}
                    "456" -> Some {val = {name = "Bob", role = "user"}}
                    _ -> None

            let route = {path: Str} ->
                match path is
                    "/users/$(id)/profile" if let Some {val = {name, role}} = find_user {id = id} ->
                        "Profile: $(name) ($(role))"
                    "/users/$(id)/profile" -> "User not found"
                    "/$(resource)" -> "Resource: $(resource)"
                    _ -> "404"

            route {path = "/users/123/profile"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("Profile: Alice (admin)", result)
    }

    @Test
    fun `URL routing - user not found falls through`() {
        val code = """
            let find_user: {id: Str} -> | Some {val: {name: Str}}, None | = {id} ->
                match id is
                    "123" -> Some {val = {name = "Alice"}}
                    _ -> None

            let route = {path: Str} ->
                match path is
                    "/users/$(id)/profile" if let Some {val = {name}} = find_user {id = id} ->
                        "Profile: $(name)"
                    "/users/$(id)/profile" -> "User $(id) not found"
                    _ -> "404"

            route {path = "/users/999/profile"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("User 999 not found", result)
    }

    @Test
    fun `log parsing with level configuration lookup`() {
        // Note: "if let ... if condition" syntax is not supported.
        // Use nested match to check additional conditions.
        val code = """
            let get_level_config: {level: Str} -> | Some {val: Int}, None | = {level} ->
                match level is
                    "DEBUG" -> Some {val = 0}
                    "INFO" -> Some {val = 1}
                    "WARN" -> Some {val = 2}
                    "ERROR" -> Some {val = 3}
                    _ -> None

            let parse_log = {line: Str, minLevel: Int} ->
                match line is
                    "[$(level)] $(message)" if let Some {val = levelNum} = get_level_config {level = level} ->
                        match levelNum >= minLevel is
                            True -> "SHOW: $(message)"
                            False -> "SKIP"
                    "[$(level)] $(message)" ->
                        "SKIP"
                    _ -> "INVALID"

            let r1 = parse_log {line = "[DEBUG] test message", minLevel = 2}
            let r2 = parse_log {line = "[ERROR] critical failure", minLevel = 2}

            "$(r1) | $(r2)"
        """.trimIndent()

        val result = eval(code)
        assertEquals("SKIP | SHOW: critical failure", result)
    }

    @Test
    fun `environment variable parsing with validation`() {
        val code = """
            let valid_keys: {key: Str} -> | Some {val: Str}, None | = {key} ->
                match key is
                    "HOME" -> Some {val = "path"}
                    "USER" -> Some {val = "string"}
                    "PORT" -> Some {val = "number"}
                    _ -> None

            let parse_env = {line: Str} ->
                match line is
                    "$(key)=$(value)" if let Some {val = expectedKind} = valid_keys {key = key} ->
                        { valid = True, key = key, value = value, kind = expectedKind }
                    "$(key)=$(value)" ->
                        { valid = False, key = key, value = value, kind = "unknown" }
                    _ ->
                        { valid = False, key = "", value = "", kind = "invalid" }

            parse_env {line = "PORT=8080"}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals(true, result.getField("valid"))
        assertEquals("PORT", result.getField("key"))
        assertEquals("8080", result.getField("value"))
        assertEquals("number", result.getField("kind"))
    }

    // ==========================================================================
    // SECTION 5: Edge Cases
    // ==========================================================================

    @Test
    fun `string pattern with empty capture and if-let`() {
        val code = """
            let check: {prefix: Str} -> | Some {val: Int}, None | = {prefix} ->
                match prefix is
                    "" -> Some {val = 0}
                    "test" -> Some {val = 1}
                    _ -> None

            let process = {input: Str} ->
                match input is
                    "$(prefix)-suffix" if let Some {val = n} = check {prefix = prefix} -> n
                    _ -> -1

            process {input = "-suffix"}
        """.trimIndent()

        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `if-let with string pattern inside the option payload`() {
        val code = """
            let get_data: {id: Int} -> | Some {val: Str}, None | = {id} ->
                match id is
                    1 -> Some {val = "name=Alice"}
                    2 -> Some {val = "name=Bob"}
                    _ -> None

            let process = {id: Int} ->
                match id is
                    n if let Some {val = data} = get_data {id = n} ->
                        match data is
                            "name=$(person)" -> "Found: $(person)"
                            _ -> "Bad format"
                    _ -> "Not found"

            process {id = 1}
        """.trimIndent()

        val result = eval(code)
        assertEquals("Found: Alice", result)
    }

    @Test
    fun `chained option lookups with string pattern using &&`() {
        // Multiple if-let bindings are chained with &&
        val code = """
            let getA: {x: Str} -> | Some {val: Int}, None | = {x} ->
                match x is
                    "a" -> Some {val = 10}
                    _ -> None

            let getB: {x: Str} -> | Some {val: Int}, None | = {x} ->
                match x is
                    "b" -> Some {val = 20}
                    _ -> None

            let process = {input: Str} ->
                match input is
                    "$(first)-$(second)" if let Some {val = a} = getA {x = first} && let Some {val = b} = getB {x = second} ->
                        a + b
                    _ -> 0

            process {input = "a-b"}
        """.trimIndent()

        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `chained option lookups - second fails`() {
        val code = """
            let getA: {x: Str} -> | Some {val: Int}, None | = {x} ->
                match x is
                    "a" -> Some {val = 10}
                    _ -> None

            let getB: {x: Str} -> | Some {val: Int}, None | = {x} ->
                match x is
                    "b" -> Some {val = 20}
                    _ -> None

            let process = {input: Str} ->
                match input is
                    "$(first)-$(second)" if let Some {val = a} = getA {x = first} && let Some {val = b} = getB {x = second} ->
                        a + b
                    "$(first)-$(second)" -> -1
                    _ -> -2

            process {input = "a-c"}
        """.trimIndent()

        val result = eval(code)
        assertEquals(-1L, result)
    }

    // ==========================================================================
    // SECTION 6: Complex Parsing Pipelines
    // ==========================================================================

    @Test
    fun `API request parsing with auth validation`() {
        val code = """
            let validate_token: {token: Str} -> | Some {val: {userId: Str, role: Str}}, None | = {token} ->
                match token is
                    "valid-admin" -> Some {val = {userId = "1", role = "admin"}}
                    "valid-user" -> Some {val = {userId = "2", role = "user"}}
                    _ -> None

            let handle_request = {request: Str} ->
                match request is
                    "auth:$(token) $(method) $(path)" if let Some {val = {userId, role}} = validate_token {token = token} ->
                        "$(method) $(path) by user $(userId) ($(role))"
                    "auth:$(token) $(rest)" -> "Invalid token"
                    _ -> "Malformed request"

            handle_request {request = "auth:valid-admin GET /admin/users"}
        """.trimIndent()

        val result = eval(code)
        assertEquals("GET /admin/users by user 1 (admin)", result)
    }

    @Test
    fun `cascading fallbacks with string patterns and if-let`() {
        val code = """
            let cache: {key: Str} -> | Some {val: Str}, None | = {key} ->
                match key is
                    "cached-key" -> Some {val = "from cache"}
                    _ -> None

            let database: {key: Str} -> | Some {val: Str}, None | = {key} ->
                match key is
                    "db-key" -> Some {val = "from database"}
                    _ -> None

            let fetch = {query: Str} ->
                match query is
                    "get:$(key)" if let Some {val = result} = cache {key = key} -> result
                    "get:$(key)" if let Some {val = result} = database {key = key} -> result
                    "get:$(key)" -> "not found: $(key)"
                    _ -> "invalid query"

            let r1 = fetch {query = "get:cached-key"}
            let r2 = fetch {query = "get:db-key"}
            let r3 = fetch {query = "get:missing"}

            "$(r1) | $(r2) | $(r3)"
        """.trimIndent()

        val result = eval(code)
        assertEquals("from cache | from database | not found: missing", result)
    }
}
