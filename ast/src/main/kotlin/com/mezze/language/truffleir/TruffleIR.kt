package com.mezze.language.truffleir

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

@Serializable
enum class SlotKind {
    LONG, DOUBLE, BOOLEAN, OBJECT
}

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
/** Read a top-level definition from the global registry by content hash. Auto-evaluates thunks. */
@Serializable data class ReadGlobalByHashIR(val hash: Hash, override val src: SourceInfo) : NodeIR
/** Read a function from the global registry by content hash WITHOUT auto-evaluating thunks.
 *  Used for dictionary passing where we need the raw MezzeFunction, not its return value. */
@Serializable data class ReadGlobalFnByHashIR(val hash: Hash, override val src: SourceInfo) : NodeIR
/**
 * Reference to a name contributed by the effect-handler runtime inside an
 * op handler body. The set of names is fixed: `scope`, `resume`, `abort`.
 *
 *  - `scope`  — the value `initialize_scope` returned for the surrounding
 *    perform. One value per perform; lives on the MezzeContext.
 *  - `resume` — handle to drive body forward for *this* op invocation.
 *  - `abort`  — handle to terminate the perform with a value.
 *
 *  `resume` and `abort` are per-op-invocation: nested op calls each get
 *  fresh handles. They live on the current fiber's effect-handle stack.
 */
@Serializable data class EffectBindingIR(val name: String, override val src: SourceInfo) : NodeIR

// Calls
/** Direct call to a known function hash.
 *  Two ABIs:
 *    - record (isScalarABI = false): args has exactly 1 element — the MezzeRecord. Receiver
 *      unpacks fields by name and supports partial application detection at runtime.
 *    - scalar (isScalarABI = true): args[i] is the value for the callee's paramNames[i],
 *      in callee-param order. No record allocation. Only emitted when the typechecker
 *      can prove the call is fully-applied (no partial, no spread). */
@Serializable data class CallDirectIR(val targetHash: Hash, val args: List<NodeIR>, val isTailCall: Boolean, override val src: SourceInfo, val typeTag: String? = null, val isScalarABI: Boolean = false) : NodeIR
/** Direct call to a known function hash with INCOMPLETE args (partial application).
 *  Returns a curried closure capturing partialFields; subsequent calls fill in
 *  remaining params. Field names matter — runtime matches them against callee paramNames. */
@Serializable data class PartialApplyDirectIR(val targetHash: Hash, val partialFields: List<FieldIR>, val isTailCall: Boolean, override val src: SourceInfo, val typeTag: String? = null) : NodeIR
@Serializable data class CallLocalIR(val slot: Int, val arg: NodeIR, val isTailCall: Boolean, override val src: SourceInfo) : NodeIR
/**
 * Call into a built-in native operation.
 *
 * Two coexisting shapes during DSL migration:
 *
 *  - **Legacy record ABI** (default): `arg` is a single `MakeRecordIR` (or
 *    similar) and `args`/`argKinds` are empty. The interpreter routes to
 *    `NativeDispatch` (HashMap-keyed handler) or the DS-Wire peephole.
 *    Field names matter and are decoded at runtime by `getDouble(r, "l")`-
 *    style accessors.
 *
 *  - **Scalar ABI** (new, via `NativeRegistry`): `args` is non-empty,
 *    one entry per declared `Param`, in the registry's canonical order.
 *    `argKinds[i]` is the static kind for `args[i]`. `arg` is a dummy
 *    `UnitLitIR` slot that is never executed — kept only because the
 *    NodeIR shape is currently embedded in many places that assume one
 *    arg-tree. After full migration we'll remove `arg`.
 *
 * Use `isScalarABI`/`argKinds.isNotEmpty()` to discriminate.
 */
@Serializable data class CallNativeIR(
    val name: String,
    val arg: NodeIR,
    override val src: SourceInfo,
    val args: List<NodeIR> = emptyList(),
    val argKinds: List<SlotKind> = emptyList(),
) : NodeIR {
    val isScalarABI: Boolean get() = argKinds.isNotEmpty()
}
/** Indirect call — evaluate function expression, then invoke */
@Serializable data class CallIndirectIR(val function: NodeIR, val arg: NodeIR, val isTailCall: Boolean, override val src: SourceInfo) : NodeIR
/** Indirect call with positional scalar args — eliminates per-call MezzeRecord
 *  allocation for HOF callback invocations (e.g. `f {x = n}` inside `map`).
 *
 *  Emitted by the typechecker only when:
 *    - arg expr is a MakeRecordIR with exactly 1 field (no spread, no missing)
 *    - typecheck guaranteed the single field name matches the callee's single
 *      param name (so positional binding at index 0 is correct)
 *
 *  argFieldNames mirrors args 1:1 — needed for the receiver's fallback path
 *  (build a single-field MezzeRecord) when the cached callee turns out to want
 *  a record (e.g. partial application). The fast path doesn't read names. */
@Serializable data class CallIndirectScalarIR(val function: NodeIR, val args: List<NodeIR>, val argFieldNames: List<String>, val isTailCall: Boolean, override val src: SourceInfo) : NodeIR

/** Dictionary-or-dynamic ability method dispatch.
 *  Reads a dict param from the frame: if it holds a MezzeFunction, call it directly.
 *  No runtime dispatch — the typechecker resolved everything statically. */
@Serializable data class DictMethodCallIR(
    val dictSlot: Int,   // Frame slot holding the resolved impl function
    val arg: NodeIR,     // Argument record (with receiver injected as "it")
    override val src: SourceInfo
) : NodeIR

/** Inline binary operator — compiles to DirectAddNode/DirectSubtractNode etc. */
@Serializable data class InlineBinaryOpIR(val op: InlineOp, val left: NodeIR, val right: NodeIR, override val src: SourceInfo) : NodeIR

@Serializable
enum class InlineOp {
    ADD, SUB, MUL, DIV, MOD,
    EQ, NEQ, LT, LE, GT, GE,
    AND, OR,
    NEG, NOT  // Unary ops — rightIR is unused
}

/** Specialized call to a built-in operation (Map.get, Set.contains, Seq.map, etc.).
 *  Bypasses native dispatch + @TruffleBoundary cliff — the typechecker recognizes
 *  the call shape and emits this directly, which TruffleCompiler maps to a
 *  pre-built specialized Truffle node (MapGetNode, SetContainsNode, etc.). */
@Serializable data class SpecializedCallIR(
    val op: SpecializedOp,
    val args: List<NodeIR>,
    override val src: SourceInfo
) : NodeIR

@Serializable
enum class SpecializedOp {
    // Map (Phase 1 — proof of concept)
    MAP_GET, MAP_CONTAINS_KEY,

    // Set
    SET_CONTAINS,

    // Record field access
    RECORD_FIELD_GET, RECORD_FIELD_GET_LONG,

    // Seq access
    SEQ_GET, SEQ_GET_DOUBLE, SEQ_LENGTH, SEQ_APPEND_LONG, SEQ_APPEND_DOUBLE,
    SEQ_TAKE, SEQ_DROP, SEQ_COPY, SEQ_FILL,

    // Seq HOFs
    SEQ_MAP, SEQ_MAP_LONG, SEQ_MAP_DOUBLE,
    SEQ_FILTER, SEQ_FILTER_LONG, SEQ_FILTER_DOUBLE,
    SEQ_FOLD, SEQ_FOLD_LONG, SEQ_FOLD_DOUBLE,
    SEQ_REDUCE_LONG, SEQ_REDUCE_DOUBLE,
    SEQ_SCAN_LONG, SEQ_SCAN_DOUBLE,

    // Seq numeric
    SEQ_SUM, SEQ_SUM_LONG_SIMD, SEQ_SUM_DOUBLE, SEQ_SUM_DOUBLE_SIMD,
    SEQ_PRODUCT_LONG, SEQ_PRODUCT_DOUBLE,
    SEQ_MIN_LONG, SEQ_MIN_DOUBLE, SEQ_MAX_LONG, SEQ_MAX_DOUBLE,
    SEQ_DOT_PRODUCT, SEQ_COUNT, SEQ_COUNT_LONG,

    // Seq predicates/search
    SEQ_ANY_LONG, SEQ_ANY_DOUBLE, SEQ_ALL_LONG, SEQ_ALL_DOUBLE,
    SEQ_FIND_LONG, SEQ_FIND_DOUBLE,
    SEQ_CONTAINS_LONG, SEQ_CONTAINS_DOUBLE,
    SEQ_INDEX_OF, SEQ_INDEX_OF_DOUBLE,

    // Seq partition/group/zip/sort
    SEQ_PARTITION_LONG, SEQ_PARTITION_DOUBLE,
    SEQ_GROUP_BY,
    SEQ_ZIP, SEQ_ZIP_WITH_LONG, SEQ_ZIP_WITH_DOUBLE,
    SEQ_SORT, SEQ_REVERSE_LONG, SEQ_REVERSE_DOUBLE,
    SEQ_FLAT_MAP_LONG, SEQ_FLAT_MAP_DOUBLE,
    SEQ_RANGE_LONG, SEQ_CONCAT,

    // List
    LIST_CONS, LIST_CONS_DOUBLE, LIST_HEAD, LIST_HEAD_DOUBLE,

    // String
    STRING_CONCAT_BATCH, STRING_JOIN, STRING_SPLIT, STRING_PRIMITIVE,

    // Extra primitive ops not in InlineOp
    INT_BITWISE, INT_COMPARE_CHAIN,
    FLOAT_MATH, FLOAT_SQRT, FLOAT_FMA, FLOAT_ABS,
    BOOL_XOR,

    // Conversion + IO
    CONVERSION, FILE_READ
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
    val src: SourceInfo,
    val ifLetBindings: List<IfLetBindingIR> = emptyList()
)
@Serializable data class BindingIR(val slot: Int, val kind: SlotKind)

/** IR for if-let guard bindings in match cases */
@Serializable data class IfLetBindingIR(
    val expr: NodeIR,
    val pattern: PatternIR,
    val fieldNames: List<String>,
    val fieldSlots: List<Int>,
    val src: SourceInfo
)

@Serializable
sealed interface PatternIR {
    @Serializable data class Variant(val tag: Int, val tagName: String, val fieldNames: List<String>, val fieldSlots: List<Int>) : PatternIR
    @Serializable data class Literal(val value: NodeIR) : PatternIR
    @Serializable data class Wildcard(val slot: Int = -1) : PatternIR
    @Serializable data class Record(val fieldNames: List<String>, val fieldSlots: List<Int>, val isOpen: Boolean) : PatternIR
    /** Seq pattern: matches arrays by length and binds elements to slots. restSlot >= 0 for [x, ...rest] patterns */
    @Serializable data class Seq(val elementSlots: List<Int>, val restSlot: Int = -1) : PatternIR
    /** String interpolation pattern: partTypes 0=literal, 1=capture; literals has string for literal parts; captureSlots has frame slots for captures */
    @Serializable data class StringInterp(val partTypes: List<Int>, val literals: List<String?>, val captureSlots: List<Int>) : PatternIR
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
/**
 * `perform Handler in body`. `staticallyFullyDischarges` is set by the
 * typechecker/compiler to `true` when this Handle discharges every effect
 * present in `body`'s row — i.e. the row-after-this-Handle is empty. Used
 * by the Path E lazy runtime (#336) to choose between eager execution
 * (row empty → run body inline) and deferred capture (row non-empty →
 * build MezzeSuspend). Default false preserves legacy semantics for
 * existing serialized IR and cache blobs.
 */
@Serializable data class HandleIR(
    val body: NodeIR,
    val handlers: List<HandlerBindIR>,
    override val src: SourceInfo,
    val staticallyFullyDischarges: Boolean = false
) : NodeIR
/**
 * A single handler binding inside a [HandleIR].
 *
 * `multishot` (default false, additive for legacy serialised IR): when
 * true, downstream BDSL emit routes this handler's `PerformOpIR` sites
 * through the Yield / [com.mezze.interpreter.bytecode.MezzeContinuation]
 * driver instead of D5 direct dispatch. That's the slow-but-safe path
 * for handlers that call `resume` more than once (nondeterminism,
 * backtracking, generators). Path D6 — see
 * `docs/effects/bdsl-path-d6-multi-shot.md`.
 *
 * Typechecker only sets `multishot = true` when the surface language
 * marks the handler explicitly (Commit 2 wires the marker; Commit 3
 * consumes the flag at emit).
 */
@Serializable data class HandlerBindIR(val handlerHash: Hash, val effectHash: Hash, val performerName: String, val params: NodeIR?, val multishot: Boolean = false)
/** Effect operation call — runtime dispatch via handler stack.
 *  Can't be CallDirect because the performer isn't known at compile time
 *  (different perform blocks can use different performers).
 */
@Serializable data class PerformOpIR(val effectHash: Hash, val operationName: String, val arg: NodeIR, override val src: SourceInfo) : NodeIR
