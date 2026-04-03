package com.mezze.language.truffleir

import com.mezze.language.resolvedast.SlotKind
import com.mezze.language.typedast.Hash
import kotlinx.serialization.Serializable

/**
 * TruffleIR — minimal serializable representation for execution.
 *
 * No abilities. No generics. Just execution.
 * Optional type annotations (returnType, typeTag) are carried through for
 * later compilation stages but are not required — all default to null.
 * This is what gets serialized to SQLite and read by TruffleCompiler.
 */

// ============================================================================
// SOURCE INFO (compact — file index instead of file path)
// ============================================================================

@Serializable
data class SourceInfo(
    val fileIndex: Int,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int
)

@Serializable
data class FileTable(
    val files: List<String>
)

// ============================================================================
// FUNCTION IR
// ============================================================================

@Serializable
data class FunctionIR(
    val hash: Hash,
    val name: String,
    val qualifiedName: String,
    val paramSlots: List<SlotKind>,
    val paramNames: List<String>,
    val localSlots: List<SlotKind>,
    val localNames: List<String>,
    val captureSlots: List<SlotKind>,
    val captureNames: List<String>,
    val body: NodeIR,
    val sourceFile: String,
    val sourceSection: SourceInfo,
    /** Optional return type descriptor, populated when type info is available from Resolver */
    val returnType: String? = null
)

// ============================================================================
// IR NODES — minimal, with source info
// ============================================================================

@Serializable
sealed interface NodeIR {
    val src: SourceInfo
}

// Literals
@Serializable data class LongLitIR(val value: Long, override val src: SourceInfo) : NodeIR
@Serializable data class DoubleLitIR(val value: Double, override val src: SourceInfo) : NodeIR
@Serializable data class BoolLitIR(val value: Boolean, override val src: SourceInfo) : NodeIR
@Serializable data class StringLitIR(val value: String, override val src: SourceInfo) : NodeIR
@Serializable data class InterpolatedStringIR(val parts: List<StringPartIR>, override val src: SourceInfo) : NodeIR
@Serializable data class UnitLitIR(override val src: SourceInfo) : NodeIR
@Serializable data class CharLitIR(val value: Char, override val src: SourceInfo) : NodeIR
@Serializable data class FracLitIR(val numerator: Long, val denominator: Long, override val src: SourceInfo) : NodeIR

@Serializable
sealed interface StringPartIR {
    @Serializable data class Lit(val value: String) : StringPartIR
    @Serializable data class Expr(val node: NodeIR) : StringPartIR
}

// Variables
@Serializable data class ReadLocalIR(val slot: Int, val kind: SlotKind, override val src: SourceInfo, val typeTag: String? = null) : NodeIR
@Serializable data class WriteLocalIR(val slot: Int, val kind: SlotKind, val value: NodeIR, override val src: SourceInfo) : NodeIR
@Serializable data class ReadCaptureIR(val index: Int, val kind: SlotKind, override val src: SourceInfo) : NodeIR
@Serializable data class ReadArgIR(val index: Int, val kind: SlotKind, override val src: SourceInfo, val typeTag: String? = null) : NodeIR

// Calls
@Serializable data class CallDirectIR(val targetHash: Hash, val arg: NodeIR, val isTailCall: Boolean, override val src: SourceInfo, val typeTag: String? = null) : NodeIR
@Serializable data class CallLocalIR(val slot: Int, val arg: NodeIR, val isTailCall: Boolean, override val src: SourceInfo) : NodeIR
@Serializable data class CallNativeIR(val name: String, val arg: NodeIR, override val src: SourceInfo) : NodeIR
/** Indirect call — evaluate function expression, then invoke */
@Serializable data class CallIndirectIR(val function: NodeIR, val arg: NodeIR, val isTailCall: Boolean, override val src: SourceInfo) : NodeIR

/** Inline binary operator — compiles to DirectAddNode/DirectSubtractNode etc. */
@Serializable data class InlineBinaryOpIR(val op: InlineOp, val left: NodeIR, val right: NodeIR, override val src: SourceInfo) : NodeIR

@Serializable
enum class InlineOp {
    ADD, SUB, MUL, DIV, MOD,
    EQ, NEQ, LT, LE, GT, GE,
    AND, OR
}

// Records
@Serializable data class MakeRecordIR(val fields: List<FieldIR>, override val src: SourceInfo, val typeTag: String? = null) : NodeIR
@Serializable data class FieldIR(val name: String, val value: NodeIR)
@Serializable data class ReadFieldIR(val record: NodeIR, val fieldName: String, val fieldIndex: Int, override val src: SourceInfo) : NodeIR
@Serializable data class RecordUpdateIR(val record: NodeIR, val updates: List<FieldIR>, override val src: SourceInfo) : NodeIR

// Variants
@Serializable data class MakeVariantIR(val tag: Int, val tagName: String, val payload: NodeIR?, override val src: SourceInfo, val typeTag: String? = null) : NodeIR
@Serializable data class MatchIR(val scrutinee: NodeIR, val cases: List<CaseIR>, override val src: SourceInfo) : NodeIR
@Serializable data class CaseIR(
    val pattern: PatternIR,
    val bindings: List<BindingIR>,
    val guard: NodeIR?,
    val body: NodeIR,
    val src: SourceInfo
)
@Serializable data class BindingIR(val slot: Int, val kind: SlotKind)

@Serializable
sealed interface PatternIR {
    @Serializable data class Variant(val tag: Int, val tagName: String, val fieldNames: List<String>, val fieldSlots: List<Int>) : PatternIR
    @Serializable data class Literal(val value: NodeIR) : PatternIR
    @Serializable data class Wildcard(val slot: Int = -1) : PatternIR
    @Serializable data class Record(val fieldNames: List<String>, val fieldSlots: List<Int>, val isOpen: Boolean) : PatternIR
    /** Seq pattern: matches arrays by length and binds elements to slots. restSlot >= 0 for [x, ...rest] patterns */
    @Serializable data class Seq(val elementSlots: List<Int>, val restSlot: Int = -1) : PatternIR
}

// Sequences
@Serializable data class MakeSeqIR(val elements: List<SeqElementIR>, override val src: SourceInfo) : NodeIR
@Serializable sealed interface SeqElementIR {
    @Serializable data class Regular(val node: NodeIR) : SeqElementIR
    @Serializable data class Spread(val node: NodeIR) : SeqElementIR
}
@Serializable data class SeqGetIR(val seq: NodeIR, val index: NodeIR, override val src: SourceInfo) : NodeIR

// Control flow
@Serializable data class IfIR(val cond: NodeIR, val thenBr: NodeIR, val elseBr: NodeIR, override val src: SourceInfo) : NodeIR
@Serializable data class BlockIR(val stmts: List<StmtIR>, val result: NodeIR, override val src: SourceInfo) : NodeIR
@Serializable sealed interface StmtIR {
    @Serializable data class Let(val slot: Int, val kind: SlotKind, val name: String, val contentHash: Hash, val value: NodeIR, val src: SourceInfo) : StmtIR
    @Serializable data class Expr(val node: NodeIR, val src: SourceInfo) : StmtIR
}

// Closures
@Serializable data class MakeLambdaIR(val functionHash: Hash, val captures: List<CaptureIR>, override val src: SourceInfo, val typeTag: String? = null) : NodeIR
@Serializable data class CaptureIR(val slot: Int, val kind: SlotKind)

// Effects
@Serializable data class HandleIR(val body: NodeIR, val handlers: List<HandlerBindIR>, override val src: SourceInfo) : NodeIR
@Serializable data class HandlerBindIR(val handlerHash: Hash, val effectHash: Hash, val performerName: String, val params: NodeIR?)
/** Effect operation call — runtime dispatch via handler stack.
 *  Can't be CallDirect because the performer isn't known at compile time
 *  (different perform blocks can use different performers).
 */
@Serializable data class PerformOpIR(val effectHash: Hash, val operationName: String, val arg: NodeIR, override val src: SourceInfo) : NodeIR
