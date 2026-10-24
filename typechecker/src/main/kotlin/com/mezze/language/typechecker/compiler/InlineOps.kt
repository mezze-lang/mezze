package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.Keywords
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.SourceInfo

/**
 * Compile-time fast path for primitive method calls.
 *
 * Methods like `Int.+`, `Float.<`, `Bool.and`, `Str.++` always resolve
 * to a single Truffle node — no impl table lookup, no closure capture,
 * no dispatch overhead. The compiler folds them at IR-emit time.
 *
 * Two predicates: receiver is a known primitive type, AND method name
 * is a known op. Both must be present; otherwise the call falls
 * through to the regular ability-op resolution path.
 *
 * Functions are file-private — only [TypedASTCompiler.compileCall]
 * uses them.
 */

/**
 * The machine op each desugared operation name lowers to.
 *
 * These names come from the OPERATOR DESUGARER — `+` becomes `add`, `<` becomes
 * `lt` — so recognising them here is the compiler reading back its own output,
 * not dispatching on a user-facing name. The tie from a name to a machine
 * instruction cannot be derived from anything, so it is written once, here.
 *
 * Anywhere the question is "which ABILITY is this operation from", ask
 * [com.mezze.language.typechecker.WellKnownHashConstants.isOperatorAbility]
 * instead: a user ability may declare its own `add`, and this map must not
 * speak for it.
 */
private val inlineOpByName = mapOf(
    "add" to InlineOp.ADD,
    "sub" to InlineOp.SUB,
    "mul" to InlineOp.MUL,
    "div" to InlineOp.DIV,
    "mod" to InlineOp.MOD,
    "eq" to InlineOp.EQ,
    "neq" to InlineOp.NEQ,
    "lt" to InlineOp.LT,
    "le" to InlineOp.LE,
    "gt" to InlineOp.GT,
    "ge" to InlineOp.GE,
    "and" to InlineOp.AND,
    "or" to InlineOp.OR,
    "neg" to InlineOp.NEG,
    "not" to InlineOp.NOT,
)

/** The desugared names of [inlineOpByName], for callers that only ask whether
 *  a name is one of them. */
internal val inlineableOps: Set<String> = inlineOpByName.keys

private val primitiveTypes = setOf(
    IntType::class, FloatType::class,
    StringType::class, CharType::class, FracType::class
)

/**
 * Bool inlines like a primitive even though it is a VARIANT in the type system —
 * `and`/`or`/`not`/`eq` on it are answered by an inline node over a machine
 * boolean. It cannot be listed in [primitiveTypes] because keying on the Kotlin
 * class would catch every variant, so it is asked for by shape.
 */
private fun inlinesAsPrimitive(type: MezzeType): Boolean =
    type::class in primitiveTypes || PrimitiveTypes.isBoolVariant(type)

internal fun tryInlineOp(methodName: String, receiverType: MezzeType): InlineOp? {
    if (!inlinesAsPrimitive(receiverType)) return null
    return inlineOpByName[methodName]
}

/** Build the IR node for an inlined binary op, extracting the right
 *  operand from the wrapped argument record (typically `{r = value}`
 *  or `{it = receiver, r = value}`). */
internal fun compileInlineOp(op: InlineOp, receiverIR: NodeIR, argIR: NodeIR, src: SourceInfo): NodeIR {
    // Extract the right operand from the wrapped argument record.
    // TypedAstBuilder desugars `x.add {r = y}` to a call whose arg is
    // a record like {it = x, r = y} (or just {r = y}); the right
    // operand always lives at field `Keywords.R`.
    val rightIR = if (argIR is MakeRecordIR) {
        val rField = argIR.fields.find { it.name == Keywords.R }
        rField?.value ?: argIR
    } else {
        argIR
    }
    return InlineBinaryOpIR(op, receiverIR, rightIR, src)
}
