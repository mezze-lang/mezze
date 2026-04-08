package com.mezze.language.resolvedast

import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.Hash
import kotlinx.serialization.Serializable

/**
 * ResolvedAST — output of the resolver.
 *
 * TYPES ARE GONE (only SlotKind). Names are hashes.
 * Frame slots assigned for all locals, params, captures.
 */

// ============================================================================
// MODULE
// ============================================================================

@Serializable
data class ResolvedModule(
    val path: String,
    val rootHash: Hash,
    val functions: List<ResolvedFunction>,
    val effectHandlers: List<ResolvedEffectHandler>
)

@Serializable
data class ResolvedFunction(
    val hash: Hash,
    val name: String,
    val qualifiedName: String,
    val params: List<ResolvedSlot>,
    val locals: List<ResolvedSlot>,
    val captures: List<ResolvedCaptureSlot>,
    val body: ResolvedExpr,
    val isPublic: Boolean,
    val loc: Loc
)

@Serializable
data class ResolvedSlot(
    val index: Int,
    val kind: SlotKind,
    val name: String
)

@Serializable
data class ResolvedCaptureSlot(
    val sourceIndex: Int,
    val targetIndex: Int,
    val kind: SlotKind,
    val name: String
)

@Serializable
enum class SlotKind {
    LONG, DOUBLE, BOOLEAN, OBJECT
}

@Serializable
data class ResolvedEffectHandler(
    val hash: Hash,
    val effectHash: Hash,                       // NO string name
    val performerHash: Hash,                    // NO string name
    val params: List<ResolvedSlot>,
    val operations: List<ResolvedFunction>,     // Indexed by operationIndex, not name
    val loc: Loc
)

// ============================================================================
// EXPRESSIONS — no types, just hashes and slots
// ============================================================================

@Serializable
sealed interface ResolvedExpr {
    val hash: Hash
    val loc: Loc
}

// Variables
@Serializable data class ResolvedReadLocal(val slot: Int, val kind: SlotKind, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedWriteLocal(val slot: Int, val kind: SlotKind, val value: ResolvedExpr, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedReadParam(val index: Int, val kind: SlotKind, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedReadContext(val name: String, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedReadCapture(val index: Int, val kind: SlotKind, override val hash: Hash, override val loc: Loc) : ResolvedExpr

// Function lookup — returns MezzeFunction as a value (for passing as argument, storing in local)
@Serializable data class ResolvedFunctionRef(val targetHash: Hash, override val hash: Hash, override val loc: Loc) : ResolvedExpr

// Calls
@Serializable data class ResolvedCallDirect(val targetHash: Hash, val arg: ResolvedExpr, val isTailCall: Boolean, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedCallLocal(val slot: Int, val arg: ResolvedExpr, val isTailCall: Boolean, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedCallNative(val name: String, val arg: ResolvedExpr, override val hash: Hash, override val loc: Loc) : ResolvedExpr
/** Indirect call — evaluate function expression, then invoke with arg */
@Serializable data class ResolvedCallIndirect(val function: ResolvedExpr, val arg: ResolvedExpr, val isTailCall: Boolean, override val hash: Hash, override val loc: Loc) : ResolvedExpr

// Literals
@Serializable data class ResolvedIntLit(val value: Long, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedNatLit(val value: Long, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedFloatLit(val value: Double, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedFracLit(val numerator: Long, val denominator: Long, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedBoolLit(val value: Boolean, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedCharLit(val value: Char, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedStringLit(val parts: List<ResolvedStringPart>, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedUnitLit(override val hash: Hash, override val loc: Loc) : ResolvedExpr

@Serializable
sealed interface ResolvedStringPart {
    @Serializable data class Literal(val value: String) : ResolvedStringPart
    @Serializable data class Interpolation(val expr: ResolvedExpr) : ResolvedStringPart
}

// Records
@Serializable data class ResolvedMakeRecord(val fields: List<ResolvedFieldEntry>, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedFieldEntry(val name: String, val value: ResolvedExpr)
@Serializable data class ResolvedFieldAccess(val record: ResolvedExpr, val fieldName: String, val fieldIndex: Int, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedRecordUpdate(val record: ResolvedExpr, val updates: List<ResolvedFieldEntry>, override val hash: Hash, override val loc: Loc) : ResolvedExpr

// Variants
@Serializable data class ResolvedMakeVariant(val tag: Int, val tagName: String, val payload: ResolvedExpr?, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedMatch(val scrutinee: ResolvedExpr, val cases: List<ResolvedCase>, override val hash: Hash, override val loc: Loc) : ResolvedExpr

@Serializable
data class ResolvedCase(
    val pattern: ResolvedPattern,
    val slotBindings: List<ResolvedSlotBinding>,
    val guard: ResolvedExpr?,
    val body: ResolvedExpr,
    val loc: Loc
)

@Serializable data class ResolvedSlotBinding(val slot: Int, val kind: SlotKind, val name: String)

// Control flow
@Serializable data class ResolvedIf(val cond: ResolvedExpr, val thenBr: ResolvedExpr, val elseBr: ResolvedExpr, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedBlock(val stmts: List<ResolvedStmt>, val result: ResolvedExpr, override val hash: Hash, override val loc: Loc) : ResolvedExpr

@Serializable
sealed interface ResolvedStmt {
    @Serializable data class Let(val slot: Int, val kind: SlotKind, val name: String, val contentHash: Hash, val value: ResolvedExpr, val loc: Loc) : ResolvedStmt
    @Serializable data class Expr(val expr: ResolvedExpr, val loc: Loc) : ResolvedStmt
}

// Closures
@Serializable data class ResolvedMakeLambda(val functionHash: Hash, val captures: List<ResolvedCaptureRead>, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedCaptureRead(val slot: Int, val kind: SlotKind)

// Sequences
@Serializable data class ResolvedMakeSeq(val elements: List<ResolvedSeqElement>, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable sealed interface ResolvedSeqElement {
    @Serializable data class Regular(val expr: ResolvedExpr) : ResolvedSeqElement
    @Serializable data class Spread(val expr: ResolvedExpr) : ResolvedSeqElement
}
@Serializable data class ResolvedSeqGet(val seq: ResolvedExpr, val index: ResolvedExpr, override val hash: Hash, override val loc: Loc) : ResolvedExpr

// Effects
@Serializable data class ResolvedPerform(val handlerBindings: List<ResolvedHandlerBinding>, val body: ResolvedExpr, override val hash: Hash, override val loc: Loc) : ResolvedExpr
@Serializable data class ResolvedHandlerBinding(val handlerHash: Hash, val effectHash: Hash, val performerName: String, val params: ResolvedExpr?)
/** Effect operation — runtime dispatch via handler stack */
@Serializable data class ResolvedPerformOp(val effectHash: Hash, val operationName: String, val arg: ResolvedExpr, override val hash: Hash, override val loc: Loc) : ResolvedExpr

// ============================================================================
// PATTERNS
// ============================================================================

@Serializable
sealed interface ResolvedPattern {
    val loc: Loc
}

@Serializable data class ResolvedVarPattern(val slot: Int, val kind: SlotKind, override val loc: Loc) : ResolvedPattern
@Serializable data class ResolvedWildcardPattern(override val loc: Loc) : ResolvedPattern
@Serializable data class ResolvedLiteralPattern(val value: ResolvedExpr, override val loc: Loc) : ResolvedPattern
@Serializable data class ResolvedVariantPattern(val tag: Int, val payload: ResolvedPattern?, override val loc: Loc) : ResolvedPattern
@Serializable data class ResolvedRecordPattern(val fields: List<ResolvedPatternField>, val isOpen: Boolean, override val loc: Loc) : ResolvedPattern
@Serializable data class ResolvedPatternField(val fieldName: String, val fieldIndex: Int, val pattern: ResolvedPattern)
@Serializable data class ResolvedSeqPattern(val elements: List<ResolvedPattern>, val restSlot: Int?, override val loc: Loc) : ResolvedPattern
