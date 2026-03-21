package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Comprehensive tests for str::glob_match function.
 * Migrated from V1 to V2 with unboxed values.
 *
 * Glob patterns support:
 * - * matches any characters except /
 * - ** matches any characters including /
 * - ? matches a single character (not /)
 * - [abc] matches any character in the set
 * - [!abc] matches any character not in the set
 * - [a-z] matches any character in the range
 */
class GlobMatchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun globMatch(pattern: String, text: String): Boolean {
        val source = """
            use std::str
            str::glob_match { pattern = "$pattern", s = "$text" }
        """.trimIndent()

        return MezzeTestHelper.run(source) as Boolean
    }

    // =========================================================================
    // EXACT MATCH (no wildcards)
    // =========================================================================

    @Test
    fun `exact match - identical strings`() {
        assertTrue(globMatch("/users", "/users"))
    }

    @Test
    fun `exact match - different strings`() {
        assertFalse(globMatch("/users", "/posts"))
    }

    @Test
    fun `exact match - empty pattern and text`() {
        assertTrue(globMatch("", ""))
    }

    @Test
    fun `exact match - empty pattern non-empty text`() {
        assertFalse(globMatch("", "/users"))
    }

    @Test
    fun `exact match - non-empty pattern empty text`() {
        assertFalse(globMatch("/users", ""))
    }

    @Test
    fun `exact match - pattern longer than text`() {
        assertFalse(globMatch("/users/123", "/users"))
    }

    @Test
    fun `exact match - text longer than pattern`() {
        assertFalse(globMatch("/users", "/users/123"))
    }

    @Test
    fun `exact match - case sensitive`() {
        assertFalse(globMatch("/Users", "/users"))
    }

    // =========================================================================
    // SINGLE STAR (*) - matches any characters except /
    // =========================================================================

    @Test
    fun `star - matches any segment`() {
        assertTrue(globMatch("/users/*", "/users/123"))
    }

    @Test
    fun `star - matches empty segment`() {
        assertTrue(globMatch("/users/*", "/users/"))
    }

    @Test
    fun `star - matches alphanumeric`() {
        assertTrue(globMatch("/users/*", "/users/abc123"))
    }

    @Test
    fun `star - matches special characters`() {
        assertTrue(globMatch("/users/*", "/users/a-b_c.d"))
    }

    @Test
    fun `star - does not match slash`() {
        assertFalse(globMatch("/users/*", "/users/123/posts"))
    }

    @Test
    fun `star - at beginning`() {
        assertTrue(globMatch("*.json", "config.json"))
    }

    @Test
    fun `star - at beginning does not match path`() {
        assertFalse(globMatch("*.json", "dir/config.json"))
    }

    @Test
    fun `star - in middle`() {
        assertTrue(globMatch("/api/*/health", "/api/v1/health"))
    }

    @Test
    fun `star - multiple stars`() {
        assertTrue(globMatch("/*/users/*", "/api/users/123"))
    }

    @Test
    fun `star - consecutive stars treated separately`() {
        // Two separate * patterns, not **
        assertTrue(globMatch("/*/*", "/a/b"))
        assertFalse(globMatch("/*/*", "/a/b/c"))
    }

    @Test
    fun `star - matches nothing (zero chars)`() {
        assertTrue(globMatch("/users/*x", "/users/x"))
    }

    @Test
    fun `star - at end matches rest of segment`() {
        assertTrue(globMatch("/users/test*", "/users/test123"))
    }

    @Test
    fun `star - at end matches empty`() {
        assertTrue(globMatch("/users/test*", "/users/test"))
    }

    // =========================================================================
    // DOUBLE STAR (**) - matches any characters including /
    // =========================================================================

    @Test
    fun `doublestar - matches single segment`() {
        assertTrue(globMatch("/api/**/health", "/api/v1/health"))
    }

    @Test
    fun `doublestar - matches multiple segments`() {
        assertTrue(globMatch("/api/**/health", "/api/v1/v2/v3/health"))
    }

    @Test
    fun `doublestar - matches zero segments`() {
        assertTrue(globMatch("/api/**/health", "/api/health"))
    }

    @Test
    fun `doublestar - at end matches everything`() {
        assertTrue(globMatch("/api/**", "/api/anything/goes/here"))
    }

    @Test
    fun `doublestar - at beginning`() {
        assertTrue(globMatch("**/health", "/api/v1/health"))
        assertTrue(globMatch("**/health", "health"))
    }

    @Test
    fun `doublestar - alone matches everything`() {
        assertTrue(globMatch("**", "/any/path/at/all"))
        assertTrue(globMatch("**", ""))
        assertTrue(globMatch("**", "single"))
    }

    @Test
    fun `doublestar - with file extension`() {
        assertTrue(globMatch("**/*.json", "/path/to/file.json"))
        assertTrue(globMatch("**/*.json", "file.json"))
    }

    @Test
    fun `doublestar - multiple doubletars`() {
        assertTrue(globMatch("/**/api/**/health", "/v1/api/v2/health"))
    }

    // =========================================================================
    // QUESTION MARK (?) - matches single character
    // =========================================================================

    @Test
    fun `question - matches single char`() {
        assertTrue(globMatch("/users/12?", "/users/123"))
    }

    @Test
    fun `question - does not match zero chars`() {
        assertFalse(globMatch("/users/12?", "/users/12"))
    }

    @Test
    fun `question - does not match multiple chars`() {
        assertFalse(globMatch("/users/1?", "/users/123"))
    }

    @Test
    fun `question - does not match slash`() {
        assertFalse(globMatch("/users/?", "/users//"))
    }

    @Test
    fun `question - multiple questions`() {
        assertTrue(globMatch("/users/???", "/users/abc"))
        assertFalse(globMatch("/users/???", "/users/ab"))
        assertFalse(globMatch("/users/???", "/users/abcd"))
    }

    @Test
    fun `question - mixed with star`() {
        assertTrue(globMatch("/users/?*", "/users/a"))
        assertTrue(globMatch("/users/?*", "/users/abc"))
        assertFalse(globMatch("/users/?*", "/users/"))
    }

    @Test
    fun `question - at end`() {
        assertTrue(globMatch("file?", "file1"))
        assertTrue(globMatch("file?", "fileX"))
        assertFalse(globMatch("file?", "file"))
        assertFalse(globMatch("file?", "file12"))
    }

    // =========================================================================
    // CHARACTER CLASS [...]
    // =========================================================================

    @Test
    fun `charclass - matches single char in set`() {
        assertTrue(globMatch("/users/[abc]", "/users/a"))
        assertTrue(globMatch("/users/[abc]", "/users/b"))
        assertTrue(globMatch("/users/[abc]", "/users/c"))
    }

    @Test
    fun `charclass - does not match char outside set`() {
        assertFalse(globMatch("/users/[abc]", "/users/d"))
        assertFalse(globMatch("/users/[abc]", "/users/x"))
    }

    @Test
    fun `charclass - with range`() {
        assertTrue(globMatch("/v[0-9]", "/v0"))
        assertTrue(globMatch("/v[0-9]", "/v5"))
        assertTrue(globMatch("/v[0-9]", "/v9"))
        assertFalse(globMatch("/v[0-9]", "/va"))
    }

    @Test
    fun `charclass - letter range`() {
        assertTrue(globMatch("/[a-z]", "/a"))
        assertTrue(globMatch("/[a-z]", "/m"))
        assertTrue(globMatch("/[a-z]", "/z"))
        assertFalse(globMatch("/[a-z]", "/A"))
        assertFalse(globMatch("/[a-z]", "/0"))
    }

    @Test
    fun `charclass - multiple ranges`() {
        assertTrue(globMatch("/[a-zA-Z0-9]", "/a"))
        assertTrue(globMatch("/[a-zA-Z0-9]", "/Z"))
        assertTrue(globMatch("/[a-zA-Z0-9]", "/5"))
        assertFalse(globMatch("/[a-zA-Z0-9]", "/-"))
    }

    @Test
    fun `charclass - combined with other patterns`() {
        assertTrue(globMatch("/api/v[0-9]/*", "/api/v1/users"))
        assertTrue(globMatch("/api/v[0-9]/*", "/api/v2/posts"))
        assertFalse(globMatch("/api/v[0-9]/*", "/api/vX/users"))
    }

    // =========================================================================
    // NEGATED CHARACTER CLASS [!...]
    // =========================================================================

    @Test
    fun `negated charclass - matches char not in set`() {
        assertTrue(globMatch("/users/[!abc]", "/users/d"))
        assertTrue(globMatch("/users/[!abc]", "/users/x"))
    }

    @Test
    fun `negated charclass - does not match char in set`() {
        assertFalse(globMatch("/users/[!abc]", "/users/a"))
        assertFalse(globMatch("/users/[!abc]", "/users/b"))
    }

    @Test
    fun `negated charclass - with range`() {
        assertTrue(globMatch("/[!0-9]", "/a"))
        assertTrue(globMatch("/[!0-9]", "/x"))
        assertFalse(globMatch("/[!0-9]", "/5"))
    }

    @Test
    fun `negated charclass - does not match non-digit`() {
        assertFalse(globMatch("/id[!0-9]", "/id5"))
        assertTrue(globMatch("/id[!0-9]", "/idx"))
    }

    // =========================================================================
    // URL PATH PATTERNS (realistic HTTP mock scenarios)
    // =========================================================================

    @Test
    fun `url - exact api endpoint`() {
        assertTrue(globMatch("/api/v1/users", "/api/v1/users"))
        assertFalse(globMatch("/api/v1/users", "/api/v1/posts"))
    }

    @Test
    fun `url - user by id`() {
        assertTrue(globMatch("/users/*", "/users/123"))
        assertTrue(globMatch("/users/*", "/users/abc"))
        assertFalse(globMatch("/users/*", "/users/123/posts"))
    }

    @Test
    fun `url - nested resource`() {
        assertTrue(globMatch("/users/*/posts/*", "/users/123/posts/456"))
        assertFalse(globMatch("/users/*/posts/*", "/users/123/comments/456"))
    }

    @Test
    fun `url - any depth health check`() {
        assertTrue(globMatch("/**/health", "/health"))
        assertTrue(globMatch("/**/health", "/api/health"))
        assertTrue(globMatch("/**/health", "/api/v1/health"))
        assertTrue(globMatch("/**/health", "/api/v1/internal/health"))
    }

    @Test
    fun `url - versioned api`() {
        assertTrue(globMatch("/api/v[0-9]/**", "/api/v1/anything"))
        assertTrue(globMatch("/api/v[0-9]/**", "/api/v2/deep/nested/path"))
        assertFalse(globMatch("/api/v[0-9]/**", "/api/vX/anything"))
    }

    @Test
    fun `url - file extension`() {
        assertTrue(globMatch("/**/*.json", "/data/config.json"))
        assertTrue(globMatch("/**/*.json", "/config.json"))
        assertFalse(globMatch("/**/*.json", "/data/config.xml"))
    }

    @Test
    fun `url - catch all`() {
        assertTrue(globMatch("**", "/any/path"))
        assertTrue(globMatch("**", "/"))
        assertTrue(globMatch("**", ""))
    }

    @Test
    fun `url - query string not special`() {
        // Query strings are just regular characters in glob
        assertTrue(globMatch("/search?q=*", "/search?q=test"))
        assertTrue(globMatch("/search?*", "/search?q=test&page=1"))
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    fun `edge - pattern with only star`() {
        assertTrue(globMatch("*", "anything"))
        assertTrue(globMatch("*", ""))
        assertFalse(globMatch("*", "path/with/slashes"))
    }

    @Test
    fun `edge - pattern with only doublestar`() {
        assertTrue(globMatch("**", ""))
        assertTrue(globMatch("**", "anything"))
        assertTrue(globMatch("**", "path/with/slashes"))
    }

    @Test
    fun `edge - trailing slash in pattern`() {
        assertTrue(globMatch("/users/", "/users/"))
        assertFalse(globMatch("/users/", "/users"))
    }

    @Test
    fun `edge - trailing slash in text`() {
        assertFalse(globMatch("/users", "/users/"))
        assertTrue(globMatch("/users/*", "/users/"))
    }

    @Test
    fun `edge - double slash`() {
        assertTrue(globMatch("/users//test", "/users//test"))
        // * matches zero chars, so it matches the empty segment between //
        assertTrue(globMatch("/users/*/test", "/users//test"))
    }

    @Test
    fun `edge - special regex chars are literal`() {
        // These should be treated as literal characters, not regex
        assertTrue(globMatch("/users/.+", "/users/.+"))
        assertTrue(globMatch("/path/(test)", "/path/(test)"))
        assertTrue(globMatch("/path/a|b", "/path/a|b"))
    }

    @Test
    fun `edge - very long path`() {
        val longPath = "/a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p"
        assertTrue(globMatch("/**/p", longPath))
        assertTrue(globMatch("/a/**/p", longPath))
    }

    @Test
    fun `edge - star followed by literal`() {
        assertTrue(globMatch("*test", "test"))
        assertTrue(globMatch("*test", "prefixtest"))
        assertFalse(globMatch("*test", "testpostfix"))
    }

    @Test
    fun `edge - doublestar followed by star`() {
        assertTrue(globMatch("/**/*", "/a/b/c"))
        assertTrue(globMatch("/**/*", "/a"))
    }

    // =========================================================================
    // COMPLEX PATTERNS
    // =========================================================================

    @Test
    fun `complex - rest api pattern`() {
        val pattern = "/api/v[0-9]/*/[0-9]*"
        assertTrue(globMatch(pattern, "/api/v1/users/123"))
        assertTrue(globMatch(pattern, "/api/v2/posts/456"))
        assertFalse(globMatch(pattern, "/api/v1/users/abc"))
    }

    @Test
    fun `complex - file path with extension`() {
        val pattern = "/**/src/**/*.kt"
        assertTrue(globMatch(pattern, "/project/src/main/File.kt"))
        assertTrue(globMatch(pattern, "/src/File.kt"))
        assertFalse(globMatch(pattern, "/project/src/main/File.java"))
    }

    @Test
    fun `complex - multiple wildcards`() {
        val pattern = "/*/api/*/v?/*"
        assertTrue(globMatch(pattern, "/service/api/internal/v1/users"))
        assertTrue(globMatch(pattern, "/app/api/public/v2/posts"))
        assertFalse(globMatch(pattern, "/api/internal/v1/users"))
    }
}
