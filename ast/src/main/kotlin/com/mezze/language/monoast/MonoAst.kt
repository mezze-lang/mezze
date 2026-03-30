package com.mezze.language.monoast

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.Hash
import kotlinx.serialization.Serializable

/**
 * MonoAST — output of the monomorphizer.
 *
 * ABILITIES ARE GONE. GENERICS ARE GONE.
 * Every function is concrete. Ability calls are ByHash direct function refs.
 * Effects survive (runtime construct).
 */

// ============================================================================
// MODULE
// ============================================================================

@Serializable
data class MonoModule(
    val path: String,
    val hash: Hash,
    val functions: List<MonoFunction>,
    val effectDecls: List<MonoEffectDecl>,
    val performerDecls: List<MonoPerformerDecl>
)

// ============================================================================
// FUNCTIONS — all concrete, no generics
// ============================================================================

@Serializable
data class MonoFunction(
    val hash: Hash,
    val name: String,
    val originalName: String,
    val originalHash: Hash,
    val params: List<MonoParam>,
    val captures: List<MonoCapture>,
    val body: MonoExpr,
    val type: MezzeType,
    val isPublic: Boolean,
    val loc: Loc
)

@Serializable
data class MonoParam(
    val name: String,
    val type: MezzeType,
    val loc: Loc
)

@Serializable
data class MonoCapture(
    val name: String,
    val type: MezzeType,
    val source: MonoBinding
)

// ============================================================================
// EFFECTS (passed through)
// ============================================================================

@Serializable
data class MonoEffectDecl(
    val hash: Hash,
    val name: String,
    val operations: List<MonoEffectOp>,
    val loc: Loc
)

@Serializable
data class MonoEffectOp(
    val name: String,
    val operationIndex: Int,
    val paramType: MezzeType,
    val returnType: MezzeType
)

@Serializable
data class MonoPerformerDecl(
    val hash: Hash,
    val effectHash: Hash,
    val name: String,
    val params: List<MonoPerformerParam>,
    val operations: Map<String, MonoExpr>,
    val loc: Loc
)

@Serializable
data class MonoPerformerParam(
    val name: String,
    val type: MezzeType
)

// ============================================================================
// EXPRESSIONS — all monomorphic, no type variables
// ============================================================================

@Serializable
sealed interface MonoExpr {
    val type: MezzeType
    val loc: Loc
}

// Variables
@Serializable
data class MonoVar(
    val name: String,
    val binding: MonoBinding,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

@Serializable
sealed interface MonoBinding {
    @Serializable data class Local(val depth: Int, val index: Int) : MonoBinding
    @Serializable data class Param(val index: Int) : MonoBinding
    @Serializable data class Capture(val index: Int) : MonoBinding
    /** Lambda/function reference — creates closure when evaluated */
    @Serializable data class FunctionRef(val ref: MonoFunctionRef) : MonoBinding
    /** Non-lambda value reference — read directly (not callable) */
    @Serializable data class ValueRef(val hash: Hash) : MonoBinding
}

/**
 * Function reference — only ByHash or Native.
 * No Direct(module, name). No AbilityMethod(implModule, ability, targetType, method).
 * After monomorphization, everything is just a hash.
 */
@Serializable
sealed interface MonoFunctionRef {
    @Serializable data class ByHash(val hash: Hash) : MonoFunctionRef
    @Serializable data class Native(val name: String) : MonoFunctionRef
}

// Literals
@Serializable data class MonoIntLit(val value: Long, override val type: MezzeType, override val loc: Loc) : MonoExpr
@Serializable data class MonoNatLit(val value: Long, override val type: MezzeType, override val loc: Loc) : MonoExpr
@Serializable data class MonoFloatLit(val value: Double, override val type: MezzeType, override val loc: Loc) : MonoExpr
@Serializable data class MonoFracLit(val numerator: Long, val denominator: Long, override val type: MezzeType, override val loc: Loc) : MonoExpr
@Serializable data class MonoBoolLit(val value: Boolean, override val type: MezzeType, override val loc: Loc) : MonoExpr
@Serializable data class MonoCharLit(val value: Char, override val type: MezzeType, override val loc: Loc) : MonoExpr
@Serializable data class MonoStringLit(val parts: List<MonoStringPart>, override val type: MezzeType, override val loc: Loc) : MonoExpr

@Serializable
sealed interface MonoStringPart {
    @Serializable data class Literal(val value: String) : MonoStringPart
    @Serializable data class Interpolation(val expr: MonoExpr) : MonoStringPart
}

// Functions
@Serializable
data class MonoLambda(
    val params: List<MonoParam>,
    val body: MonoExpr,
    val captures: List<MonoCapture>,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

@Serializable
data class MonoCall(
    val function: MonoExpr,
    val arg: MonoExpr,
    val isTailCall: Boolean,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

// Records
@Serializable
data class MonoRecord(
    val entries: List<MonoRecordEntry>,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

@Serializable
sealed interface MonoRecordEntry {
    @Serializable data class Field(val name: String, val value: MonoExpr) : MonoRecordEntry
    @Serializable data class Spread(val expr: MonoExpr) : MonoRecordEntry
}

@Serializable
data class MonoFieldAccess(
    val record: MonoExpr,
    val field: String,
    val fieldIndex: Int,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

@Serializable
data class MonoRecordUpdate(
    val record: MonoExpr,
    val updates: List<MonoRecordEntry>,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

// Variants
@Serializable
data class MonoVariantConstruct(
    val tag: String,
    val payload: MonoExpr?,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

// Control flow
@Serializable
data class MonoIf(
    val condition: MonoExpr,
    val thenBranch: MonoExpr,
    val elseBranch: MonoExpr,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

@Serializable
data class MonoMatch(
    val scrutinee: MonoExpr,
    val cases: List<MonoCase>,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

@Serializable
data class MonoCase(
    val pattern: MonoPattern,
    val bindings: List<MonoPatternBinding>,
    val guard: MonoExpr?,
    val body: MonoExpr,
    val loc: Loc
)

@Serializable
data class MonoPatternBinding(val name: String, val type: MezzeType)

@Serializable
data class MonoBlock(
    val statements: List<MonoStatement>,
    val result: MonoExpr,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

@Serializable
sealed interface MonoStatement {
    @Serializable data class Let(val name: String, val contentHash: Hash, val type: MezzeType, val value: MonoExpr, val loc: Loc) : MonoStatement
    @Serializable data class Expr(val expr: MonoExpr, val loc: Loc) : MonoStatement
}

// Sequences
@Serializable
data class MonoSeq(
    val elements: List<MonoSeqElement>,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

@Serializable
sealed interface MonoSeqElement {
    @Serializable data class Regular(val expr: MonoExpr) : MonoSeqElement
    @Serializable data class Spread(val expr: MonoExpr) : MonoSeqElement
}

// Effects (runtime — use hashes)
@Serializable
data class MonoPerform(
    val handlers: List<MonoHandlerRef>,
    val body: MonoExpr,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

@Serializable
data class MonoHandlerRef(
    val performerHash: Hash,
    val effectHash: Hash,
    val performerName: String,
    val params: MonoExpr?,
    val loc: Loc
)

/** Effect operation — runtime dispatch via handler stack */
@Serializable
data class MonoPerformOp(
    val effectHash: Hash,
    val operationName: String,
    val arg: MonoExpr,
    override val type: MezzeType,
    override val loc: Loc
) : MonoExpr

// ============================================================================
// PATTERNS
// ============================================================================

@Serializable
sealed interface MonoPattern {
    val type: MezzeType
    val loc: Loc
}

@Serializable data class MonoVarPattern(val name: String, override val type: MezzeType, override val loc: Loc) : MonoPattern
@Serializable data class MonoWildcardPattern(override val type: MezzeType, override val loc: Loc) : MonoPattern
@Serializable data class MonoLiteralPattern(val value: MonoExpr, override val type: MezzeType, override val loc: Loc) : MonoPattern
@Serializable data class MonoVariantPattern(val tag: String, val payload: MonoPattern?, override val type: MezzeType, override val loc: Loc) : MonoPattern
@Serializable data class MonoRecordPattern(val fields: List<MonoFieldPattern>, val isOpen: Boolean, override val type: MezzeType, override val loc: Loc) : MonoPattern
@Serializable data class MonoFieldPattern(val name: String, val pattern: MonoPattern?)
@Serializable data class MonoSeqPattern(val elements: List<MonoPattern>, val rest: String?, override val type: MezzeType, override val loc: Loc) : MonoPattern
