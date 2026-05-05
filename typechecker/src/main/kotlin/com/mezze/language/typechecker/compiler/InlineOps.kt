package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
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

/** Method names that resolve to a single Truffle node when invoked on
 *  a primitive receiver. Public to the compiler package — used by
 *  call-site dispatch to gate the dict-passing fallback (no need to
 *  emit a dict-method call for an op we'll inline anyway). */
internal val inlineableOps = setOf(
    "add", "sub", "mul", "div", "mod",
    "eq", "neq", "lt", "le", "gt", "ge",
    "and", "or", "neg", "not"
)

private val primitiveTypes = setOf(
    IntType::class, FloatType::class, NatType::class, BoolType::class,
    StringType::class, CharType::class, FracType::class
)

internal fun tryInlineOp(methodName: String, receiverType: MezzeType): InlineOp? {
    if (methodName !in inlineableOps) return null
    if (receiverType::class !in primitiveTypes) return null
    return when (methodName) {
        "add" -> InlineOp.ADD
        "sub" -> InlineOp.SUB
        "mul" -> InlineOp.MUL
        "div" -> InlineOp.DIV
        "mod" -> InlineOp.MOD
        "eq" -> InlineOp.EQ
        "neq" -> InlineOp.NEQ
        "lt" -> InlineOp.LT
        "le" -> InlineOp.LE
        "gt" -> InlineOp.GT
        "ge" -> InlineOp.GE
        "and" -> InlineOp.AND
        "or" -> InlineOp.OR
        "neg" -> InlineOp.NEG
        "not" -> InlineOp.NOT
        else -> null
    }
}

/** Build the IR node for an inlined binary op, extracting the right
 *  operand from the wrapped argument record (typically `{r = value}`
 *  or `{it = receiver, r = value}`). */
internal fun compileInlineOp(op: InlineOp, receiverIR: NodeIR, argIR: NodeIR, src: SourceInfo): NodeIR {
    // Extract the 'r' value from the argument record
    // arg is typically MakeRecordIR({it=receiver, r=value}) or MakeRecordIR({r=value})
    val rightIR = if (argIR is MakeRecordIR) {
        val rField = argIR.fields.find { it.name == "r" }
        rField?.value ?: argIR
    } else {
        argIR
    }
    return InlineBinaryOpIR(op, receiverIR, rightIR, src)
}
