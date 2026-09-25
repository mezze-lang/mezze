package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.ModulePath
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedPerformerDecl
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class TypeCheckResult(
    val resolvedImports: List<ResolvedImport> = emptyList(),
    val warnings: List<TypeCheckWarning> = emptyList(),
    val importedAbilities: Map<String, ParsedAbilityDecl> = emptyMap(),
    val importedEffects: Map<String, ParsedEffectDecl> = emptyMap(),
    // Imported effect performers, keyed by performer name (e.g. `Console`).
    // Performers aren't ResolvedImports (the builder has no PERFORMER case), so
    // the LSP resolves `perform X` handler names through this map instead.
    val importedImpls: Map<String, ParsedPerformerDecl> = emptyMap(),
    // Every transitively-loaded effect decl, keyed by name — a superset of
    // importedEffects (which only has BY-NAME imports). Lets the LSP read an
    // effect's op signatures even when only its performer was imported (e.g.
    // `use …::{ Console }` brings the performer but not the `Log` effect).
    val allEffects: Map<String, ParsedEffectDecl> = emptyMap(),
    val loweredFunctions: List<FunctionIR> = emptyList(),
    val typedModule: TypedModule? = null,
    val performerRecords: List<Array<String>> = emptyList(),
    /**
     * Content hash of stdlib `comptime`, so the reduction pass can recognise
     * call sites without depending on `:typechecker` (where the generated
     * constant lives). Travelling with the result keeps this explicit data flow
     * rather than another piece of global state whose initialisation order
     * someone has to reason about.
     */
    @Transient
    val comptimeHashHex: String? = null,
    val loadedModules: Map<ModulePath, TypedModule> = emptyMap(),
    val loadedModuleSources: Map<ModulePath, LoadedModuleSource> = emptyMap(),
    // Per-position "expected type" for LSP completion ranking. Transient — recomputed each typecheck.
    @Transient
    val expectedTypeAt: Map<Loc, MezzeType> = emptyMap(),
) {
    fun getVariableType(name: String): MezzeType? {
        return typedModule?.decls
            ?.filterIsInstance<TypedLetDecl>()
            ?.find { it.name == name }
            ?.scheme?.type
    }
}

@Serializable
data class LoadedModuleSource(
    val filePath: String,
    val sourceHash: Hash,
)

@Serializable
data class TypeCheckWarning(
    val message: String,
    val loc: Loc? = null,
    // Structured warning code (null for warnings not yet migrated to a code).
    val code: com.mezze.language.parsedast.diagnostics.WarningCode? = null
)

@Serializable
data class ResolvedImport(
    val localName: String,
    val originalName: String,
    val modulePath: String,
    val type: MezzeType,
    val kind: ImportKind
)

@Serializable
enum class ImportKind {
    FUNCTION, TYPE, EFFECT, ABILITY, PERFORMER
}

/**
 * Build the LSP/hover-facing [TypeCheckResult] from a raw checker result.
 *
 * Every position-based feature (hover, inlay hints, find-references, rename)
 * reads `typedModule` off this object, and a `Failure` still carries one — so
 * both outcomes are mapped here and hover keeps answering while the buffer is
 * mid-edit and failing.
 *
 * Shared so the LSP and the WASM playground cannot drift: the playground
 * previously did none of this and simply returned nothing on any error.
 */
fun TypeChecker.TypeCheckResult.toHoverResult(): TypeCheckResult = when (this) {
    is TypeChecker.TypeCheckResult.Success -> TypeCheckResult(
        importedAbilities = importContext?.importedAbilities ?: emptyMap(),
        importedEffects = importContext?.importedEffects ?: emptyMap(),
        importedImpls = importContext?.importedImpls ?: emptyMap(),
        allEffects = importContext?.let { ic ->
            ic.importedEffects + ic.effectsByHash.values.associateBy { it.name }
        } ?: emptyMap(),
        typedModule = typedModule,
        warnings = warnings.map { TypeCheckWarning(it.message, it.loc, it.code) },
        expectedTypeAt = expectedTypeAt,
    )
    is TypeChecker.TypeCheckResult.Failure -> TypeCheckResult(
        typedModule = typedModule,
        expectedTypeAt = expectedTypeAt,
    )
}
