package com.mezze.language.typedast

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedStringPart
import kotlinx.serialization.Serializable

/**
 * TypedAST expressions — every expression has a concrete type and a location.
 *
 * No nullable inferredType. No resolvedAbilities map.
 * Types are required. Ability resolution is done via Hash.
 */

@Serializable
sealed interface TypedExpr {
    val type: MezzeType
    val loc: Loc
}


@Serializable
data class TypedVar(
    val name: String,
    val binding: BindingRef,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr

@Serializable
sealed interface BindingRef {
    @Serializable data class Local(val depth: Int, val index: Int) : BindingRef
    @Serializable data class Param(val index: Int) : BindingRef
    @Serializable data class ByHash(val hash: Hash) : BindingRef
    @Serializable data class Capture(val index: Int) : BindingRef
    @Serializable data class Native(val name: String) : BindingRef
}


@Serializable data class TypedIntLit(val value: Long, override val type: MezzeType, override val loc: Loc) : TypedExpr
@Serializable data class TypedNatLit(val value: Long, override val type: MezzeType, override val loc: Loc) : TypedExpr
@Serializable data class TypedFloatLit(val value: Double, override val type: MezzeType, override val loc: Loc) : TypedExpr
@Serializable data class TypedFracLit(val numerator: Long, val denominator: Long, override val type: MezzeType, override val loc: Loc) : TypedExpr
@Serializable data class TypedBoolLit(val value: Boolean, override val type: MezzeType, override val loc: Loc) : TypedExpr
@Serializable data class TypedCharLit(val value: Char, override val type: MezzeType, override val loc: Loc) : TypedExpr

@Serializable
data class TypedStringLit(
    val parts: List<TypedStringPart>,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr

@Serializable
sealed interface TypedStringPart {
    @Serializable data class Literal(val value: String) : TypedStringPart
    @Serializable data class Interpolation(val expr: TypedExpr) : TypedStringPart
}


@Serializable
data class TypedLambda(
    val params: List<TypedParam>,
    val body: TypedExpr,
    val captures: List<TypedCapture>,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr

@Serializable
data class TypedParam(
    val name: String,
    val type: MezzeType,
    val loc: Loc
)

@Serializable
data class TypedCapture(
    val name: String,
    val type: MezzeType,
    val source: BindingRef
)

@Serializable
data class TypedCall(
    val function: TypedExpr,
    val arg: TypedExpr,
    val instantiation: TypeInstantiation?,
    val isTailCall: Boolean,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr

/** How a polymorphic function is instantiated at a call site */
@Serializable
data class TypeInstantiation(
    val typeArgs: List<MezzeType> = emptyList(),
    val abilityImpls: List<AbilityImplRef> = emptyList()
)

/** Reference to a specific ability impl — by hash, NOT by name */
@Serializable
data class AbilityImplRef(
    val abilityHash: Hash,
    val implHash: Hash,
    val targetType: MezzeType
)


@Serializable
data class TypedRecord(
    val entries: List<TypedRecordEntry>,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr

@Serializable
sealed interface TypedRecordEntry {
    @Serializable data class Field(val name: String, val value: TypedExpr, val loc: Loc) : TypedRecordEntry
    @Serializable data class Spread(val expr: TypedExpr, val loc: Loc) : TypedRecordEntry
}

@Serializable
data class TypedFieldAccess(
    val record: TypedExpr,
    val field: String,
    val fieldIndex: Int,
    override val type: MezzeType,
    override val loc: Loc,
    /**
     * BUG 9 / C.3: when this field access is the callee of an ABILITY method
     * call, the ability the typechecker resolved for it.
     *
     * The typechecker always knew — `MethodCallInfo.abilityName` is correct per
     * call site — and emit used to drop it, leaving the compiler to re-resolve
     * against an index keyed on the operation NAME. Two abilities declaring the
     * same operation then shared one answer, and one silently served both.
     *
     * Null for an ordinary record field access, and for an ability call whose
     * operation name only one ability in scope declares (where the name is
     * already unambiguous).
     */
    val abilityHash: Hash? = null,
) : TypedExpr

@Serializable
data class TypedRecordUpdate(
    val record: TypedExpr,
    val updates: List<TypedRecordEntry>,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr


@Serializable
data class TypedVariantConstruct(
    val tag: String,
    val payload: TypedExpr?,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr


@Serializable
data class TypedMatch(
    val scrutinee: TypedExpr,
    val cases: List<TypedCase>,
    override val type: MezzeType,
    override val loc: Loc,
    // S5.0 (#376): the UNERASED source-level scrutinee type, distinct from the
    // erased `scrutinee.type` used for runtime lowering. Emit erases a newtype
    // scrutinee to its underlying primitive (`match Age.(42) is Age.(x) ->` leaves
    // `scrutinee.type == Int`), so the decided node alone was a LESS-resolved
    // exhaustiveness oracle than the constraint-time matchScrutineeTypes side-table.
    // This carries the constraint-time solved (unerased) scrutinee type so a LATER
    // ticket (#364) can make the decided TypedModule a sufficient exhaustiveness
    // oracle and delete the constraint-time re-inference. Runtime lowering STILL
    // reads the erased `scrutinee.type` — this field is metadata only. Defaults to
    // `scrutinee.type` when the constraint-time type is unavailable (so it never
    // regresses below what the node already exposes).
    val scrutineeSourceType: MezzeType = scrutinee.type
) : TypedExpr

@Serializable
data class TypedCase(
    val patterns: List<TypedPattern>,
    val bindings: List<PatternBinding>,
    val guard: TypedExpr?,
    val body: TypedExpr,
    val loc: Loc,
    val ifLetBindings: List<TypedIfLetBinding> = emptyList()
)

/** If-let binding in a match guard: `if let Some {val = v} = expr` */
@Serializable
data class TypedIfLetBinding(
    val pattern: TypedPattern,
    val expr: TypedExpr,
    val bindingNames: List<String>,
    val loc: Loc
)

@Serializable
data class PatternBinding(
    val name: String,
    val type: MezzeType,
    val loc: Loc
)


@Serializable
data class TypedIf(
    val condition: TypedExpr,
    val thenBranch: TypedExpr,
    val elseBranch: TypedExpr,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr

@Serializable
data class TypedBlock(
    val statements: List<TypedStatement>,
    val result: TypedExpr,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr

@Serializable
sealed interface TypedStatement {
    /**
     * Let binding inside a do block.
     * @property isRecursive true if value is a lambda that references its own binding name.
     *   When true, the compiler emits self-referential MakeLambdaIR for recursive call sites
     *   instead of capturing the slot value (which would be null at lambda creation time).
     */
    @Serializable data class Let(
        val name: String,
        val contentHash: Hash,
        val type: MezzeType,
        val value: TypedExpr,
        val loc: Loc,
        val isRecursive: Boolean = false,
    ) : TypedStatement
    @Serializable data class Expr(val expr: TypedExpr, val loc: Loc) : TypedStatement
}


@Serializable
data class TypedSeq(
    val elements: List<TypedSeqElement>,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr

@Serializable
sealed interface TypedSeqElement {
    @Serializable data class Regular(val expr: TypedExpr) : TypedSeqElement
    @Serializable data class Spread(val expr: TypedExpr) : TypedSeqElement
}


@Serializable
data class TypedPerform(
    val handlers: List<TypedHandlerRef>,
    val body: TypedExpr,
    override val type: MezzeType,
    override val loc: Loc,
    /**
     * True iff this perform discharges every effect in `body`'s row (the
     * row-after-this-handler is empty), so the runtime can run the body
     * eagerly rather than build a MezzeSuspend. Computed at EMIT
     * (TypedAstBuilder) from the perform's result type; the BDSL compiler reads
     * THIS field instead of re-inspecting `type`, decoupling the discharge gate
     * from the value-carried effect row. See docs/effect-model.md (S0/S1).
     */
    val fullyDischarges: Boolean = true,
) : TypedExpr

@Serializable
data class TypedHandlerRef(
    val performerHash: Hash,
    val effectHash: Hash,
    val performerName: String,   // For runtime lookup (registered by EffectImplStorageNode)
    val params: TypedExpr?,
    val loc: Loc,
    val isScoped: Boolean = false
)

// TypedAbilityCall REMOVED — ability calls become TypedCall with BindingRef.ByHash.

/** Effect operation — runtime dispatch. Can't be resolved at compile time
 *  because the performer depends on which `perform` block we're inside. */
@Serializable
data class TypedPerformOp(
    val effectHash: Hash,
    val operationName: String,
    val arg: TypedExpr,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr


@Serializable
data class TypedPipe(
    val left: TypedExpr,
    val right: TypedExpr,
    override val type: MezzeType,
    override val loc: Loc
) : TypedExpr
