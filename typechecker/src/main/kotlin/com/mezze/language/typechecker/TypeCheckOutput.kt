package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import kotlinx.serialization.Serializable

/**
 * Public output types from the typechecker.
 * These are the results of type checking — consumed by the interpreter, CLI, and LSP.
 */

@Serializable
data class TypeCheckResult(
    val resolvedImports: List<ResolvedImport> = emptyList(),
    val warnings: List<TypeCheckWarning> = emptyList(),
    val importedAbilities: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl> = emptyMap(),
    val importedEffects: Map<String, com.mezze.language.parsedast.ParsedEffectDecl> = emptyMap(),
    val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR> = emptyList(),
    val typedModule: com.mezze.language.typedast.TypedModule? = null,
    val performerRecords: List<Array<String>> = emptyList(),
    val loadedModules: Map<com.mezze.language.ast.ModulePath, com.mezze.language.typedast.TypedModule> = emptyMap(),
    /**
     * For each typechecked module loaded transitively (imports), the source
     * file path and content hash. Lets StorePopulator persist a typed_modules
     * row for every module, not just the entry. modulePath is keyed the same
     * way as `loadedModules` above.
     */
    val loadedModuleSources: Map<com.mezze.language.ast.ModulePath, LoadedModuleSource> = emptyMap()
) {
    fun getVariableType(name: String): MezzeType? {
        return typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == name }
            ?.scheme?.type
    }
}

@Serializable
data class LoadedModuleSource(
    val filePath: String,
    val sourceHash: com.mezze.language.typedast.Hash,
)

@Serializable
data class TypeCheckWarning(
    val message: String,
    val loc: Loc? = null
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
