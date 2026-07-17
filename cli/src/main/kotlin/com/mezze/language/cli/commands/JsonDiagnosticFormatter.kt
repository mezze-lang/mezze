package com.mezze.language.cli.commands

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.parser.ParseException
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.module.ModuleError
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON output for `mez check --json`.
 *
 * Stable schema for tooling/CI consumers: one outer [CheckResultJson]
 * envelope, a flat list of [DiagnosticJson] entries that cover parse
 * errors, type errors, module errors, and warnings — all in the same
 * shape with optional fields for source-specific data.
 *
 * Field-level translation:
 *   - `ParseException` → `code`, `message`, `location` from `line/col/length`,
 *     `hints`, no `fixHints`/`metadata`.
 *   - `TypeCheckError` → all fields. `metadata: Map<String, Any>` is coerced
 *     to `JsonElement` per known value type (String, List<String>, Number,
 *     MezzeType, Set<String>). Unknown types fall through to `toString()`.
 *   - `ModuleError` → `code`, `message`, `hints`, `fixHints`. `loc` only on
 *     subclasses that carry one (`ExportNotFound`, `PrivateExport`); others
 *     get `location = null`.
 *   - `TypeChecker.TypeCheckWarning` → `severity = WARNING`, `code = null`.
 *
 * Exit codes preserved: 0 on OK, non-zero on errors. The JSON only
 * affects stdout formatting.
 */
object JsonDiagnosticFormatter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false   // omit nulls — consumers can default
    }

    fun render(result: CheckResultJson): String =
        json.encodeToString(CheckResultJson.serializer(), result)

    // ====================================================================
    // Conversion helpers — each error source → DiagnosticJson
    // ====================================================================

    fun fromParseException(e: ParseException): DiagnosticJson = DiagnosticJson(
        severity = Severity.ERROR,
        code = e.code.takeIf { it != ErrorCode.UNCATEGORIZED }?.display(),
        message = e.errorMessage,
        location = SourceLocationJson(
            file = e.file,
            startLine = e.line,
            startCol = e.column,
            endLine = e.line,
            endCol = e.column + e.length.coerceAtLeast(1),
        ),
        hints = e.hints,
        fixHints = emptyList(),
        metadata = null,
    )

    fun fromTypeCheckError(e: TypeCheckError): DiagnosticJson = DiagnosticJson(
        severity = Severity.ERROR,
        code = e.code.takeIf { it != ErrorCode.UNCATEGORIZED }?.display(),
        message = e.message,
        location = e.loc?.toJson(),
        hints = e.hints,
        fixHints = e.fixHints.map { it.toJson() },
        metadata = if (e.metadata.isEmpty()) null else coerceMetadata(e.metadata),
    )

    /**
     * `TypeChecker.TypeCheckResult.Failure.errors` carries the result-style
     * inner class (`TypeChecker.TypeCheckError`), NOT the standalone
     * exception. Same field shapes, different declared type. Separate
     * converter avoids ambiguity at call sites.
     */
    fun fromTypeCheckError(e: TypeChecker.TypeCheckError): DiagnosticJson = DiagnosticJson(
        severity = Severity.ERROR,
        code = e.code.takeIf { it != ErrorCode.UNCATEGORIZED }?.display(),
        message = e.message,
        location = e.loc?.toJson(),
        hints = e.hints,
        fixHints = e.fixHints.map { it.toJson() },
        metadata = if (e.metadata.isEmpty()) null else coerceMetadata(e.metadata),
    )

    fun fromModuleError(e: ModuleError): DiagnosticJson = DiagnosticJson(
        severity = Severity.ERROR,
        code = e.code.takeIf { it != ErrorCode.UNCATEGORIZED }?.display(),
        message = e.message ?: e.javaClass.simpleName,
        location = extractModuleErrorLoc(e)?.toJson(),
        hints = e.hints,
        fixHints = e.fixHints.map { it.toJson() },
        metadata = null,
    )

    fun fromWarning(w: TypeChecker.TypeCheckWarning): DiagnosticJson = DiagnosticJson(
        severity = Severity.WARNING,
        code = null,
        message = w.message,
        location = w.loc?.toJson(),
        hints = emptyList(),
        fixHints = emptyList(),
        metadata = null,
    )

    /**
     * Extract a [Loc] from a [ModuleError] subclass that carries one. Most
     * subclasses don't — ModuleNotFound, CircularDependency, etc. are
     * file-level by nature.
     */
    private fun extractModuleErrorLoc(e: ModuleError): Loc? = when (e) {
        is ModuleError.ExportNotFound -> e.loc
        is ModuleError.PrivateExport -> e.loc
        else -> null
    }

    // ====================================================================
    // Top-level builders — wrap a list of diagnostics in a CheckResultJson
    // ====================================================================

    fun ok(file: String?, app: String?, typedModule: TypedModule?, warnings: List<TypeChecker.TypeCheckWarning>): CheckResultJson {
        val bindings = typedModule?.decls?.filterIsInstance<TypedLetDecl>()?.map { it.toBindingJson() }
            ?: emptyList()
        val warningDiags = warnings.map { fromWarning(it) }
        return CheckResultJson(
            status = if (warningDiags.isEmpty()) Status.OK else Status.ERRORS,
            file = file,
            app = app,
            summary = SummaryJson(errors = 0, warnings = warningDiags.size),
            diagnostics = warningDiags,
            bindings = bindings,
        )
    }

    fun errors(file: String?, app: String?, diagnostics: List<DiagnosticJson>): CheckResultJson {
        val errorCount = diagnostics.count { it.severity == Severity.ERROR }
        val warningCount = diagnostics.count { it.severity == Severity.WARNING }
        return CheckResultJson(
            status = if (errorCount == 0) Status.OK else Status.ERRORS,
            file = file,
            app = app,
            summary = SummaryJson(errors = errorCount, warnings = warningCount),
            diagnostics = diagnostics,
            bindings = emptyList(),
        )
    }

    // ====================================================================
    // Loc / FixHint / metadata → JSON
    // ====================================================================

    private fun Loc.toJson(): SourceLocationJson = SourceLocationJson(
        file = file,
        startLine = startLine,
        startCol = startCol,
        endLine = endLine,
        endCol = endCol,
    )

    private fun FixHint.toJson(): FixHintJson = when (this) {
        is FixHint.Rename -> FixHintJson.Rename(loc.toJson(), candidates)
        is FixHint.Import -> FixHintJson.Import(loc.toJson(), name, candidateModules)
        is FixHint.WrapInPerform -> FixHintJson.WrapInPerform(loc.toJson(), effectName, candidatePerformers)
        is FixHint.AddEffectToAnnotation -> FixHintJson.AddEffectToAnnotation(loc.toJson(), effectName)
        is FixHint.AddPub -> FixHintJson.AddPub(loc.toJson(), declModulePath, declName)
        is FixHint.FillMatchArms -> FixHintJson.FillMatchArms(loc.toJson(), missingConstructors)
        is FixHint.RemoveRange -> FixHintJson.RemoveRange(loc.toJson(), label)
        is FixHint.WrapInConstructor -> FixHintJson.WrapInConstructor(loc.toJson(), constructor)
    }

    /**
     * Coerce `TypeCheckError.metadata: Map<String, Any>` values to
     * [JsonElement] based on observed shapes (String, Int, Boolean,
     * List<String>, Map keyed by String, MezzeType). Unknown types are
     * stringified via `toString()` rather than dropped — better to leak
     * a stringified value than to silently lose information.
     */
    private fun coerceMetadata(metadata: Map<String, Any>): Map<String, JsonElement> =
        metadata.mapValues { (_, v) -> coerceValue(v) }

    private fun coerceValue(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is com.mezze.language.ast.MezzeType -> JsonPrimitive(prettyPrintType(v))
        is List<*> -> JsonArray(v.map { coerceValue(it) })
        is Set<*> -> JsonArray(v.map { coerceValue(it) })
        is Map<*, *> -> kotlinx.serialization.json.JsonObject(
            v.entries.associate { (k, vv) -> k.toString() to coerceValue(vv) }
        )
        else -> JsonPrimitive(v.toString())
    }

    private fun TypedLetDecl.toBindingJson(): BindingJson = BindingJson(
        name = name,
        type = prettyPrintType(scheme.type),
        isPublic = isPublic,
        location = loc.toJson(),
    )
}

// ============================================================================
// DATA MODELS — the JSON schema for `mez check --json`
// ============================================================================

@Serializable
enum class Status { @SerialName("ok") OK, @SerialName("errors") ERRORS }

@Serializable
enum class Severity { @SerialName("error") ERROR, @SerialName("warning") WARNING }

@Serializable
data class CheckResultJson(
    val status: Status,
    /** Set in single-file mode (`mez check file.mz`). */
    val file: String? = null,
    /** Set in project mode (`mez check` or `mez check --app NAME`). */
    val app: String? = null,
    val summary: SummaryJson,
    val diagnostics: List<DiagnosticJson>,
    /** Inferred top-level let bindings — only populated on success. */
    val bindings: List<BindingJson> = emptyList(),
)

@Serializable
data class SummaryJson(val errors: Int, val warnings: Int)

@Serializable
data class DiagnosticJson(
    val severity: Severity,
    /** Stable error code (e.g. "T0201", "M0801"). Null for warnings and
     *  for errors that haven't yet been migrated off `UNCATEGORIZED`. */
    val code: String? = null,
    val message: String,
    val location: SourceLocationJson? = null,
    val hints: List<String> = emptyList(),
    val fixHints: List<FixHintJson> = emptyList(),
    /** Source-specific structured data (e.g. `field`, `availableFields`).
     *  Only populated for `TypeCheckError`s that attach metadata. */
    val metadata: Map<String, JsonElement>? = null,
)

@Serializable
data class SourceLocationJson(
    /** Absolute or project-relative path; may be null when the parser had
     *  no file context (rare). */
    val file: String? = null,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
)

/**
 * Polymorphic fix-hint shape. Each variant carries the data needed to
 * apply the fix without re-deriving it from the message. `kind`
 * discriminator on the wire so consumers dispatch by string.
 */
@Serializable
sealed class FixHintJson {
    abstract val loc: SourceLocationJson

    @Serializable
    @SerialName("Rename")
    data class Rename(
        override val loc: SourceLocationJson,
        val candidates: List<String>,
    ) : FixHintJson()

    @Serializable
    @SerialName("Import")
    data class Import(
        override val loc: SourceLocationJson,
        val name: String,
        val candidateModules: List<String>,
    ) : FixHintJson()

    @Serializable
    @SerialName("WrapInPerform")
    data class WrapInPerform(
        override val loc: SourceLocationJson,
        val effectName: String,
        val candidatePerformers: List<String>,
    ) : FixHintJson()

    @Serializable
    @SerialName("AddEffectToAnnotation")
    data class AddEffectToAnnotation(
        override val loc: SourceLocationJson,
        val effectName: String,
    ) : FixHintJson()

    @Serializable
    @SerialName("AddPub")
    data class AddPub(
        override val loc: SourceLocationJson,
        val declModulePath: String,
        val declName: String,
    ) : FixHintJson()

    @Serializable
    @SerialName("FillMatchArms")
    data class FillMatchArms(
        override val loc: SourceLocationJson,
        val missingConstructors: List<String>,
    ) : FixHintJson()

    @Serializable
    @SerialName("RemoveRange")
    data class RemoveRange(
        override val loc: SourceLocationJson,
        val label: String,
    ) : FixHintJson()

    @Serializable
    @SerialName("WrapInConstructor")
    data class WrapInConstructor(
        override val loc: SourceLocationJson,
        val constructor: String,
    ) : FixHintJson()
}

@Serializable
data class BindingJson(
    val name: String,
    val type: String,
    val isPublic: Boolean,
    val location: SourceLocationJson,
)
