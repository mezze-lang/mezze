package com.mezze.language.typedast

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.Visibility
import kotlinx.serialization.Serializable

/**
 * TypedAST — output of the typechecker.
 *
 * Every expression has a concrete MezzeType (required, not null).
 * Every declaration has a content Hash (required, not null).
 * All cross-references use Hash, never name strings.
 * Abilities and effects are still present (consumed by later stages).
 */

// ============================================================================
// MODULE
// ============================================================================

@Serializable
data class TypedModule(
    val path: String,                                // For error messages only — NOT for lookup
    val hash: Hash,                                  // Module identity (hash of all decl hashes)
    val imports: List<TypedImport>,                   // What this module imports
    val exports: Map<String, ExportEntry>,            // name → export (for import resolution by other modules)
    val decls: List<TypedDecl>,
    val abilityDecls: List<TypedAbilityDecl>,         // Consumed by monomorphizer
    val abilityImpls: List<TypedAbilityImpl>,         // Consumed by monomorphizer
    val effectDecls: List<TypedEffectDecl>,            // Passed through to interpreter
    val performerDecls: List<TypedPerformerDecl>,      // Passed through to interpreter
    val dependencies: List<ModuleDependency>           // Which modules we depend on
)

/**
 * An import — maps a local name to a hash.
 *
 * `use std::int::{add as plus}` produces:
 * TypedImport(localName="plus", originalName="add", declHash=Hash("ab12..."), ...)
 *
 * The localName is used only for scope resolution within THIS module.
 * The declHash is the definition's identity — used for all subsequent lookups.
 */
@Serializable
data class TypedImport(
    val localName: String,          // How it's referred to in THIS module
    val originalName: String,       // Name in the source module (for error messages)
    val moduleHash: Hash,           // Identity of the source module
    val declHash: Hash,             // Identity of the imported definition
    val type: MezzeType,
    val kind: ExportKind
)

/**
 * An export — what this module makes available to others.
 */
@Serializable
data class ExportEntry(
    val name: String,               // Public name
    val hash: Hash,                 // Content hash (identity)
    val type: MezzeType,
    val kind: ExportKind
)

@Serializable
enum class ExportKind {
    FUNCTION,
    TYPE,
    ABILITY,
    EFFECT,
    PERFORMER
}

/**
 * Module dependency — tracked by hash, not path.
 */
@Serializable
data class ModuleDependency(
    val moduleHash: Hash,                // Which module we depend on
    val importedHashes: Set<Hash>        // Which specific definitions we use from it
)

// ============================================================================
// DECLARATIONS — all have Hash and Loc
// ============================================================================

@Serializable
sealed interface TypedDecl {
    val hash: Hash
    val loc: Loc
    val isPublic: Boolean
}

@Serializable
data class TypedLetDecl(
    override val hash: Hash,
    val name: String,
    val typeParams: List<TypeParam>,
    val value: TypedExpr,
    val scheme: TypeScheme,
    override val isPublic: Boolean,
    override val loc: Loc
) : TypedDecl

@Serializable
data class TypedTypeDecl(
    override val hash: Hash,
    val name: String,
    val typeParams: List<TypeParam>,
    val definition: TypedTypeDef,
    override val isPublic: Boolean,
    override val loc: Loc
) : TypedDecl

// ============================================================================
// TYPE SCHEME & PARAMS
// ============================================================================

@Serializable
data class TypeScheme(
    val typeParams: List<TypeParam>,
    val abilityConstraints: List<TypedAbilityConstraint>,
    val type: MezzeType
)

@Serializable
data class TypeParam(
    val name: String,
    val kind: TypeKind
)

@Serializable
sealed interface TypeKind {
    @Serializable data object Star : TypeKind
    @Serializable data class Arrow(val from: TypeKind, val to: TypeKind) : TypeKind
}

@Serializable
data class TypedAbilityConstraint(
    val abilityHash: Hash,
    val typeArgs: List<MezzeType>
)

// ============================================================================
// ABILITY DECLARATIONS (consumed by monomorphizer)
// ============================================================================

@Serializable
data class TypedAbilityDecl(
    val hash: Hash,
    val name: String,
    val typeParams: List<TypeParam>,
    val receiverTypeParam: String?,
    val associatedTypes: List<String>,
    val dependencies: List<TypedAbilityDepRef>,
    val operations: List<TypedAbilityOp>,
    val loc: Loc
)

@Serializable
data class TypedAbilityDepRef(
    val abilityHash: Hash,
    val typeArgs: List<MezzeType>
)

@Serializable
data class TypedAbilityOp(
    val name: String,
    val operationIndex: Int,
    val paramType: MezzeType,
    val returnType: MezzeType,
    val loc: Loc
)

@Serializable
data class TypedAbilityImpl(
    val hash: Hash,
    val abilityHash: Hash,
    val targetTypes: List<MezzeType>,
    val forallVars: List<TypeParam>,
    val associatedTypeBindings: Map<String, MezzeType>,
    val operations: Map<String, TypedExpr>,
    val loc: Loc
)

// ============================================================================
// EFFECT DECLARATIONS (passed through to interpreter)
// ============================================================================

@Serializable
data class TypedEffectDecl(
    val hash: Hash,
    val name: String,
    val operations: List<TypedEffectOp>,
    val associatedTypes: List<String>,
    val loc: Loc
)

@Serializable
data class TypedEffectOp(
    val name: String,
    val operationIndex: Int,
    val paramType: MezzeType,
    val returnType: MezzeType,
    val loc: Loc
)

@Serializable
data class TypedPerformerDecl(
    val hash: Hash,
    val effectHash: Hash,
    val name: String,
    val params: List<TypedPerformerParam>,
    val associatedTypeBindings: Map<String, MezzeType>,
    val operations: Map<String, TypedExpr>,
    /** Content hashes for each operation, keyed by operation name.
     *  Computed from the operation body via TypedASTHasher. */
    val operationHashes: Map<String, Hash> = emptyMap(),
    val loc: Loc
)

@Serializable
data class TypedPerformerParam(
    val name: String,
    val type: MezzeType,
    val loc: Loc
)

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================

@Serializable
sealed interface TypedTypeDef

@Serializable
data class TypedRecordDef(
    val fields: Map<String, MezzeType>,
    val isOpen: Boolean
) : TypedTypeDef

@Serializable
data class TypedVariantDef(
    val constructors: Map<String, MezzeType?>,
    val isOpen: Boolean
) : TypedTypeDef

@Serializable
data class TypedNewtypeDef(
    val constructor: String,
    val wrappedType: MezzeType,
    val derivedAbilities: List<String>
) : TypedTypeDef
