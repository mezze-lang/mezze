package com.mezze.language.langservice.diagnostics

import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.parser.ParseException
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.module.ModuleError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for the JSON shape of `mez check --json` output. The
 * end-to-end CLI integration is exercised by smoke tests; here we lock
 * down the schema and the per-error-source conversions.
 */
class JsonDiagnosticFormatterTest {

    private val json = Json { prettyPrint = false; encodeDefaults = false; explicitNulls = false }

    private fun parseJson(s: String): JsonObject =
        json.parseToJsonElement(s) as JsonObject

    // ----- ParseException -----

    @Test
    fun `fromParseException maps fields including code and length`() {
        val e = ParseException(
            errorMessage = "Unexpected token",
            position = 42,
            line = 3,
            column = 5,
            sourceCode = null,
            file = "test.mz",
            length = 7,
            code = ErrorCode.P0101_EXPECTED_TOKEN,
            hints = listOf("try this"),
        )
        val d = JsonDiagnosticFormatter.fromParseException(e)
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("P0101", d.code)
        assertEquals("Unexpected token", d.message)
        assertEquals("test.mz", d.location?.file)
        assertEquals(3, d.location?.startLine)
        assertEquals(5, d.location?.startCol)
        assertEquals(3, d.location?.endLine)
        // endCol = startCol + length
        assertEquals(12, d.location?.endCol)
        assertEquals(listOf("try this"), d.hints)
        assertTrue(d.fixHints.isEmpty())
        assertNull(d.metadata)
    }

    @Test
    fun `fromParseException omits UNCATEGORIZED code`() {
        val e = ParseException("oops", 0, 1, 1, code = ErrorCode.UNCATEGORIZED)
        val d = JsonDiagnosticFormatter.fromParseException(e)
        assertNull(d.code, "UNCATEGORIZED → null code")
    }

    // ----- TypeCheckError (exception type) -----

    @Test
    fun `fromTypeCheckError carries metadata, fixHints, loc`() {
        val loc = Loc(file = "f.mz", startLine = 1, startCol = 5, endLine = 1, endCol = 6)
        val e = TypeCheckError(
            message = "Field 'z' not found",
            loc = loc,
            errorType = ErrorType.MISSING_FIELD,
            metadata = mapOf("field" to "z", "availableFields" to listOf("x", "y")),
            code = ErrorCode.T0205_FIELD_NOT_FOUND,
            hints = emptyList(),
            fixHints = listOf(FixHint.Rename(loc, listOf("x", "y"))),
        )
        val d = JsonDiagnosticFormatter.fromTypeCheckError(e)
        assertEquals("T0205", d.code)
        assertEquals("Field 'z' not found", d.message)
        assertEquals(loc.startLine, d.location?.startLine)
        assertEquals(1, d.fixHints.size)
        val rename = d.fixHints[0] as FixHintJson.Rename
        assertEquals(listOf("x", "y"), rename.candidates)
        // metadata coerced
        val meta = d.metadata!!
        assertEquals(JsonPrimitive("z"), meta["field"])
        assertEquals(JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))), meta["availableFields"])
    }

    @Test
    fun `metadata coercion stringifies unknown types instead of dropping`() {
        // An unknown value type — wrapped in a custom class — falls back to toString().
        data class Weird(val n: Int) { override fun toString() = "Weird($n)" }
        val e = TypeCheckError(
            message = "x", code = ErrorCode.UNCATEGORIZED,
            metadata = mapOf("weird" to Weird(7)),
        )
        val d = JsonDiagnosticFormatter.fromTypeCheckError(e)
        assertEquals(JsonPrimitive("Weird(7)"), d.metadata!!["weird"])
    }

    // ----- ModuleError -----

    @Test
    fun `fromModuleError handles ModuleNotFound without loc`() {
        val e = ModuleError.ModuleNotFound("std::missing", listOf("/usr/lib"))
        val d = JsonDiagnosticFormatter.fromModuleError(e)
        assertEquals("M0801", d.code)
        assertNull(d.location, "ModuleNotFound has no source loc")
        assertTrue(d.hints.isNotEmpty())
    }

    @Test
    fun `fromModuleError handles ExportNotFound with loc and fixHints`() {
        val loc = Loc("f.mz", 2, 1, 2, 10)
        val e = ModuleError.ExportNotFound(
            modulePath = "std::seq",
            exportName = "filtr",
            loc = loc,
            availableExports = listOf("filter", "map", "fold"),
        )
        val d = JsonDiagnosticFormatter.fromModuleError(e)
        assertEquals("M0803", d.code)
        assertEquals(2, d.location?.startLine)
        // fixHints include a Rename pointing at the typo loc.
        assertTrue(d.fixHints.any { it is FixHintJson.Rename }, "expected Rename fix-hint")
    }

    // ----- Warning -----

    @Test
    fun `warning has no code and severity WARNING`() {
        val w = com.mezze.language.typechecker.TypeChecker.TypeCheckWarning(
            message = "Unused binding 'x'",
            loc = Loc("f.mz", 1, 5, 1, 6),
        )
        val d = JsonDiagnosticFormatter.fromWarning(w)
        assertEquals(Severity.WARNING, d.severity)
        assertNull(d.code)
        assertEquals("Unused binding 'x'", d.message)
    }

    @Test
    fun `a coded warning keeps its code`() {
        val w = com.mezze.language.typechecker.TypeChecker.TypeCheckWarning(
            message = "`stale` is deprecated",
            loc = Loc("f.mz", 2, 3, 2, 8),
            code = com.mezze.language.parsedast.diagnostics.WarningCode.W0501_DEPRECATED,
        )
        assertEquals("W0501", JsonDiagnosticFormatter.fromWarning(w).code)
    }

    @Test
    fun `warnings do not fail a check`() {
        // `mez check --json` passed an empty list here, because a warning used to
        // turn the envelope's status into ERRORS — so no warning ever reached JSON.
        val w = com.mezze.language.typechecker.TypeChecker.TypeCheckWarning(message = "unused", loc = null)
        val r = JsonDiagnosticFormatter.ok(file = "/tmp/a.mz", app = null, typedModule = null, warnings = listOf(w))
        assertEquals(Status.OK, r.status)
        assertEquals(1, r.summary.warnings)
    }

    @Test
    fun `a warning prints like an error, with its code and place`() {
        val w = com.mezze.language.typechecker.TypeChecker.TypeCheckWarning(
            message = "`stale` is deprecated",
            loc = Loc("app.mz", 1, 9, 1, 13),
            code = com.mezze.language.parsedast.diagnostics.WarningCode.W0501_DEPRECATED,
        )
        val text = com.mezze.language.langservice.printing.ErrorFormatter.formatWarning(w, "let x = stale {}")
        assertTrue(text.startsWith("warning[W0501]: `stale` is deprecated"), text)
        assertTrue(text.contains("--> app.mz:1:9"), text)
    }

    // ----- Envelope: status and summary -----

    @Test
    fun `ok envelope reports zero errors and bindings empty when typed module is null`() {
        val r = JsonDiagnosticFormatter.ok(file = "/tmp/a.mz", app = null, typedModule = null, warnings = emptyList())
        assertEquals(Status.OK, r.status)
        assertEquals("/tmp/a.mz", r.file)
        assertEquals(0, r.summary.errors)
        assertEquals(0, r.summary.warnings)
        assertTrue(r.bindings.isEmpty())
    }

    @Test
    fun `errors envelope counts errors and warnings independently`() {
        val parseDiag = DiagnosticJson(Severity.ERROR, code = "P0001", message = "lex")
        val warnDiag = DiagnosticJson(Severity.WARNING, message = "unused")
        val r = JsonDiagnosticFormatter.errors("f.mz", null, listOf(parseDiag, warnDiag))
        assertEquals(Status.ERRORS, r.status)
        assertEquals(1, r.summary.errors)
        assertEquals(1, r.summary.warnings)
    }

    // ----- JSON round-trip -----

    @Test
    fun `render emits well-formed JSON`() {
        val loc = Loc("f.mz", 1, 5, 1, 12)
        val e = TypeCheckError(
            message = "Type mismatch",
            loc = loc,
            code = ErrorCode.T0201_TYPE_MISMATCH,
            fixHints = listOf(FixHint.Rename(loc, listOf("x"))),
        )
        val r = JsonDiagnosticFormatter.errors("f.mz", null, listOf(JsonDiagnosticFormatter.fromTypeCheckError(e)))
        val str = JsonDiagnosticFormatter.render(r)
        // Parses as JSON and has the expected shape.
        val obj = parseJson(str)
        assertEquals(JsonPrimitive("errors"), obj["status"])
        assertEquals(JsonPrimitive("f.mz"), obj["file"])
        val diags = obj["diagnostics"] as JsonArray
        assertEquals(1, diags.size)
        val diag = diags[0] as JsonObject
        assertEquals(JsonPrimitive("T0201"), diag["code"])
        assertEquals(JsonPrimitive("Type mismatch"), diag["message"])
        // FixHint discriminator
        val fixHints = diag["fixHints"] as JsonArray
        assertEquals(JsonPrimitive("Rename"), (fixHints[0] as JsonObject)["type"])
    }

    @Test
    fun `render omits null fields and empty lists per encodeDefaults policy`() {
        // A bare-minimum diagnostic: no loc, no hints, no fixHints, no metadata.
        val d = DiagnosticJson(Severity.ERROR, message = "x")
        val r = JsonDiagnosticFormatter.errors(null, null, listOf(d))
        val str = JsonDiagnosticFormatter.render(r)
        // No `code`, no `location`, no `hints`, no `fixHints`, no `metadata`,
        // no `bindings`, no `file`, no `app` keys.
        assertFalse(str.contains("\"file\""), "no file key when null")
        assertFalse(str.contains("\"app\""), "no app key when null")
        assertFalse(str.contains("\"bindings\""), "no bindings key when empty")
        // But required keys are present.
        assertTrue(str.contains("\"status\""))
        assertTrue(str.contains("\"summary\""))
        assertTrue(str.contains("\"diagnostics\""))
        assertTrue(str.contains("\"severity\""))
        assertTrue(str.contains("\"message\""))
    }

    // ----- Result-style inner TypeCheckError -----

    @Test
    fun `fromTypeCheckError on result-style inner type maps same fields`() {
        // Failure.errors carries TypeChecker.TypeCheckError (the inner result
        // type), distinct from the standalone exception. Same shape, separate
        // overload; verify it maps correctly.
        val loc = Loc("f.mz", 4, 1, 4, 8)
        val e = com.mezze.language.typechecker.TypeChecker.TypeCheckError(
            phase = "ConstraintSolver",
            message = "Cannot unify Int with Str",
            loc = loc,
            errorType = com.mezze.language.typechecker.ErrorType.TYPE_MISMATCH,
            code = ErrorCode.T0201_TYPE_MISMATCH,
            hints = listOf("check arg types"),
        )
        val d = JsonDiagnosticFormatter.fromTypeCheckError(e)
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("T0201", d.code)
        assertEquals("Cannot unify Int with Str", d.message)
        assertEquals(4, d.location?.startLine)
        assertEquals(listOf("check arg types"), d.hints)
    }

    // ----- All FixHint variants serialize -----

    @Test
    fun `all FixHint variants round-trip with correct discriminator`() {
        val loc = Loc("f.mz", 1, 1, 1, 5)
        val hints = listOf<FixHint>(
            FixHint.Rename(loc, listOf("y")),
            FixHint.Import(loc, "foo", listOf("std::foo", "myapp::foo")),
            FixHint.WrapInPerform(loc, "Log", listOf("Console")),
            FixHint.AddEffectToAnnotation(loc, "IO"),
            FixHint.AddPub(loc, "std::seq", "filter"),
        )
        val e = TypeCheckError(message = "x", code = ErrorCode.UNCATEGORIZED, fixHints = hints)
        val d = JsonDiagnosticFormatter.fromTypeCheckError(e)
        assertEquals(5, d.fixHints.size)
        assertTrue(d.fixHints[0] is FixHintJson.Rename)
        assertTrue(d.fixHints[1] is FixHintJson.Import)
        assertTrue(d.fixHints[2] is FixHintJson.WrapInPerform)
        assertTrue(d.fixHints[3] is FixHintJson.AddEffectToAnnotation)
        assertTrue(d.fixHints[4] is FixHintJson.AddPub)

        // Each emits its `type` discriminator in JSON.
        val r = JsonDiagnosticFormatter.errors(null, null, listOf(d))
        val obj = parseJson(JsonDiagnosticFormatter.render(r))
        val diags = obj["diagnostics"] as JsonArray
        val fixHints = (diags[0] as JsonObject)["fixHints"] as JsonArray
        val kinds = fixHints.map { (it as JsonObject)["type"] }
        assertEquals(
            listOf(
                JsonPrimitive("Rename"),
                JsonPrimitive("Import"),
                JsonPrimitive("WrapInPerform"),
                JsonPrimitive("AddEffectToAnnotation"),
                JsonPrimitive("AddPub"),
            ),
            kinds,
        )
    }

    // ----- App envelope -----

    @Test
    fun `package mode envelope uses app field instead of file`() {
        val d = DiagnosticJson(Severity.ERROR, message = "x")
        val r = JsonDiagnosticFormatter.errors(file = null, app = "myapp", diagnostics = listOf(d))
        val str = JsonDiagnosticFormatter.render(r)
        assertTrue(str.contains("\"app\""))
        assertFalse(str.contains("\"file\""))
        assertTrue(str.contains("\"myapp\""))
    }

    // ----- Nullable loc on ModuleError -----

    @Test
    fun `ModuleNotFound emits no location field at all`() {
        val e = ModuleError.ModuleNotFound("std::missing", listOf("/a", "/b"))
        val d = JsonDiagnosticFormatter.fromModuleError(e)
        val r = JsonDiagnosticFormatter.errors(null, null, listOf(d))
        val str = JsonDiagnosticFormatter.render(r)
        assertFalse(str.contains("\"location\""), "missing loc → no location key")
    }

    // ----- Summary tallies are independent of envelope-input ordering -----

    @Test
    fun `summary counts errors and warnings interleaved`() {
        val diags = listOf(
            DiagnosticJson(Severity.WARNING, message = "w1"),
            DiagnosticJson(Severity.ERROR, message = "e1"),
            DiagnosticJson(Severity.ERROR, message = "e2"),
            DiagnosticJson(Severity.WARNING, message = "w2"),
            DiagnosticJson(Severity.ERROR, message = "e3"),
        )
        val r = JsonDiagnosticFormatter.errors(null, null, diags)
        assertEquals(3, r.summary.errors)
        assertEquals(2, r.summary.warnings)
    }

    // ----- Metadata coercion handles deep collections -----

    @Test
    fun `metadata coercion handles nested maps and lists`() {
        val e = TypeCheckError(
            message = "x", code = ErrorCode.UNCATEGORIZED,
            metadata = mapOf(
                "nested" to mapOf("inner" to listOf(1, 2, 3)),
                "ints" to listOf(10, 20),
            ),
        )
        val d = JsonDiagnosticFormatter.fromTypeCheckError(e)
        val r = JsonDiagnosticFormatter.errors(null, null, listOf(d))
        val obj = parseJson(JsonDiagnosticFormatter.render(r))
        val diag = (obj["diagnostics"] as JsonArray)[0] as JsonObject
        val meta = diag["metadata"] as JsonObject
        // ints[0] == 10
        val ints = meta["ints"] as JsonArray
        assertEquals(JsonPrimitive(10), ints[0])
        // nested.inner[2] == 3
        val nested = meta["nested"] as JsonObject
        val inner = nested["inner"] as JsonArray
        assertEquals(JsonPrimitive(3), inner[2])
    }

    // ----- Empty top-level list -----

    @Test
    fun `errors envelope with empty diagnostics still emits diagnostics field`() {
        // Edge case: caller invokes errors() with [] — should still produce
        // valid JSON with empty array. (Not currently triggered in flow but
        // keep the invariant.)
        val r = JsonDiagnosticFormatter.errors(null, null, emptyList())
        assertEquals(Status.OK, r.status, "empty errors list = OK status")
        assertEquals(0, r.summary.errors)
        // Parse as JSON to be format-agnostic about whitespace/indentation.
        val obj = parseJson(JsonDiagnosticFormatter.render(r))
        assertEquals(JsonPrimitive("ok"), obj["status"])
    }
}
