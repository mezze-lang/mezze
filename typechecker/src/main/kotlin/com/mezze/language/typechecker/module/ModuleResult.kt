package com.mezze.language.typechecker.module

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.parsedast.diagnostics.LevenshteinSuggest
import com.mezze.language.typechecker.ProgramIndex
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DependencyGraph
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedModule as V2TypedModule

sealed class ModuleLoadResult {
    data class Success(
        val module: LoadedModule
    ) : ModuleLoadResult()

    data class Failure(
        val errors: List<ModuleError>
    ) : ModuleLoadResult()
}

data class LoadedModule(
    val path: ModulePath,
    val filePath: String,
    val exports: Map<String, ExportedItem>,
    val dependencies: List<ModulePath>,
    val index: ProgramIndex = ProgramIndex.EMPTY,
    val sourceHash: Hash? = null,
    val parsedModule: ParsedModule? = null
) {
    val modulePath: String get() = path.fullPath
}

data class ExportedItem(
    val name: String,
    val type: ExportedItemType,
    val mezzeType: MezzeType,
    val originalName: String,
    val visibility: Visibility = Visibility.PUBLIC,
    val contentHash: Hash
)

enum class ExportedItemType {
    FUNCTION, TYPE, CONSTRUCTOR, ABILITY, EFFECT
}

sealed class ModuleError(override val message: String) : Exception(message) {
    open val code: ErrorCode = ErrorCode.UNCATEGORIZED
    open val hints: List<String> = emptyList()
    open val fixHints: List<FixHint> = emptyList()

    data class ModuleNotFound(
        val modulePath: String,
        val searchPaths: List<String>
    ) : ModuleError("Module not found: $modulePath. Searched in: ${searchPaths.joinToString(", ")}") {
        override val code = ErrorCode.M0801_MODULE_NOT_FOUND
        override val hints: List<String> = listOf(
            "check the module path spelling and ensure the file exists"
        )
    }

    data class CircularDependency(
        val cycle: List<String>
    ) : ModuleError("Circular dependency detected: ${cycle.joinToString(" -> ")}") {
        override val code = ErrorCode.M0802_CIRCULAR_DEPENDENCY
        override val hints: List<String> = listOf(
            "break the cycle by removing one of the imports or extracting shared code to a third module"
        )
    }

    data class ParseError(
        val modulePath: String,
        val error: String
    ) : ModuleError("Failed to parse module $modulePath: $error") {
        override val code = ErrorCode.M0804_MODULE_HAS_ERRORS
        override val hints: List<String> = listOf(
            "fix the parse errors in $modulePath first"
        )
    }

    data class ImportError(
        val modulePath: String,
        val itemName: String,
        val reason: String
    ) : ModuleError("Cannot import '$itemName' from '$modulePath': $reason") {
        override val code = ErrorCode.M0804_MODULE_HAS_ERRORS
        override val hints: List<String> = listOf(
            "fix the underlying error in $modulePath, then re-import"
        )
    }

    data class ExportNotFound(
        val modulePath: String,
        val exportName: String,
        val loc: Loc? = null,
        val availableExports: List<String> = emptyList()
    ) : ModuleError("Export '$exportName' not found in module '$modulePath'") {
        override val code = ErrorCode.M0803_NOT_EXPORTED
        override val hints: List<String>
            get() = buildList {
                val closest = LevenshteinSuggest
                    .closest(exportName, availableExports)
                if (closest != null) {
                    add("did you mean `$closest`?")
                }
                add("check that the symbol is declared `pub` in $modulePath")
            }
        override val fixHints: List<FixHint>
            get() {
                if (loc == null || availableExports.isEmpty()) return emptyList()
                val close = LevenshteinSuggest.closestN(exportName, availableExports, 3)
                return if (close.isNotEmpty())
                    listOf(FixHint.Rename(loc, close))
                else emptyList()
            }
    }

    data class PrivateExport(
        val modulePath: String,
        val exportName: String,
        val loc: Loc? = null
    ) : ModuleError("'$exportName' exists in module '$modulePath' but is not public. Add 'pub' to export it.") {
        override val code = ErrorCode.M0803_NOT_EXPORTED
        override val hints: List<String> = listOf(
            "add `pub` before the declaration in the source module"
        )
        override val fixHints: List<FixHint>
            get() = if (loc != null)
                listOf(FixHint.AddPub(loc, modulePath, exportName))
            else emptyList()
    }

    data class InternalModuleAccess(
        val fromModule: String,
        val toModule: String,
        val loc: Loc? = null
    ) : ModuleError(
        if (fromModule.isEmpty())
            "Cannot access internal module '$toModule' from outside its package"
        else
            "Module '$fromModule' cannot access internal module '$toModule'"
    ) {
        override val hints: List<String> = listOf(
            "internal modules can only be used by sibling modules inside the same package"
        )
    }
}
sealed class ModuleTypeCheckResult {
    data class Success(
        val module: TypedModule
    ) : ModuleTypeCheckResult()

    data class Failure(
        val errors: List<TypeCheckError>
    ) : ModuleTypeCheckResult()

    data class TypeCheckError(
        val modulePath: String,
        val phase: String,
        val message: String,
        val loc: Loc? = null
    )
}

data class TypedModule(
    val modulePath: String,
    val exports: Map<String, ExportedItem>,
    val context: TypingContext,
    val substitution: Substitution,
    val dependencyGraph: DependencyGraph = DependencyGraph.EMPTY,
    val typedModule: V2TypedModule? = null,
    val parsedModule: ParsedModule? = null,
    val typeContext: Map<String, MezzeType> = emptyMap(),
    val filePath: String? = null,
    val sourceHash: Hash? = null,
)
sealed class ImportResolutionResult {
    data class Success(
        val importContext: ModuleImportContext,
        val loadedModules: Map<ModulePath, TypedModule>
    ) : ImportResolutionResult()

    data class Failure(
        val errors: List<ModuleError>
    ) : ImportResolutionResult()
}

@kotlinx.serialization.Serializable
data class ModuleImportContext(
    val importedBindings: Map<String, ImportedBinding>,
    val importedTypes: Map<String, ParsedTypeDecl>,
    // #148: only names from explicit `use foo::{TypeName}` — alias acts as nominal wall
    // only if explicitly imported or locally declared; auto-imported types don't gate unification.
    val explicitlyImportedTypeNames: Set<String> = emptySet(),
    val importedAbilities: Map<String, ParsedAbilityDecl>,
    val importedEffects: Map<String, ParsedEffectDecl>,
    val importedImpls: Map<String, ParsedPerformerDecl>,
    val importedAbilityImpls: Map<Hash, ParsedImplAbilityDecl>,
    val effectsByHash: Map<Hash, ParsedEffectDecl> = emptyMap(),
    val abilitiesByHash: Map<Hash, ParsedAbilityDecl> = emptyMap(),
    val inherentImplOpReturnTypes: Map<Hash, Map<String, MezzeType>> = emptyMap(),
    // #230: without pre-inferred param types, imported impl ops get fresh TypeVars
    // instead of solver-discovered record types, causing spurious type matches.
    val inherentImplOpParamTypes: Map<Hash, Map<String, MezzeType>> = emptyMap(),
    // #181: without pre-inferred effect rows, imported impl methods that perform
    // effects appear pure across module boundaries.
    val inherentImplOpEffects: Map<Hash, Map<String, MezzeType.EffectRow>> = emptyMap()
) {
    companion object {
        fun empty() = ModuleImportContext(
            importedBindings = emptyMap(),
            importedTypes = emptyMap(),
            importedAbilities = emptyMap(),
            importedEffects = emptyMap(),
            importedImpls = emptyMap(),
            importedAbilityImpls = emptyMap(),
        )
    }

    fun merge(other: ModuleImportContext): ModuleImportContext {
        return ModuleImportContext(
            importedBindings = importedBindings + other.importedBindings,
            importedTypes = importedTypes + other.importedTypes,
            explicitlyImportedTypeNames = explicitlyImportedTypeNames + other.explicitlyImportedTypeNames,
            importedAbilities = importedAbilities + other.importedAbilities,
            importedEffects = importedEffects + other.importedEffects,
            importedImpls = importedImpls + other.importedImpls,
            importedAbilityImpls = importedAbilityImpls + other.importedAbilityImpls,
            effectsByHash = effectsByHash + other.effectsByHash,
            abilitiesByHash = abilitiesByHash + other.abilitiesByHash,
            inherentImplOpReturnTypes = inherentImplOpReturnTypes + other.inherentImplOpReturnTypes,
            inherentImplOpParamTypes = inherentImplOpParamTypes + other.inherentImplOpParamTypes,
            inherentImplOpEffects = inherentImplOpEffects + other.inherentImplOpEffects
        )
    }

}

@kotlinx.serialization.Serializable
data class ImportedBinding(
    val name: String,
    val originalName: String,
    val modulePath: String,
    val type: MezzeType,
    val isQualified: Boolean = false,
    val loc: Loc? = null,
    val contentHash: Hash
)

