package com.mezze.language.interpreter.nodes.arithmetic

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeFloat
import com.mezze.language.interpreter.MezzeBool

/**
 * Node for built-in unary operations.
 * Extracts the operand from a record and performs the operation.
 *
 * All record field access is done via @TruffleBoundary methods to avoid
 * Map operations in the JIT compilation path.
 */
@NodeInfo(shortName = "builtin-unary-op")
class BuiltinUnaryOpNode(
    @field:Child private var argumentsChild: MezzeNode,
    private val operation: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val argsValue = argumentsChild.execute(frame)
        // Delegate all logic to TruffleBoundary method
        return executeOperation(argsValue)
    }

    /**
     * Extract operand and execute the unary operation.
     * Marked as @TruffleBoundary because record field access involves Map operations.
     */
    @TruffleBoundary
    private fun executeOperation(argsValue: MezzeValue): MezzeValue {
        if (argsValue !is MezzeRecord) {
            throw RuntimeException("Unary operation arguments must be a record, got: ${argsValue::class.simpleName}")
        }

        // Try 'n' first (for negate), then 'val' (for not)
        val operand = argsValue.fields["n"] ?: argsValue.fields["val"]
            ?: throw RuntimeException("Missing 'n' or 'val' field in unary operation (available fields: ${argsValue.fields.keys})")

        return when (operation) {
            "negate" -> executeNegate(operand)
            "not" -> executeNot(operand)
            else -> throw RuntimeException("Unsupported unary operation: $operation")
        }
    }

    private fun executeNegate(operand: MezzeValue): MezzeValue {
        return when (operand) {
            is MezzeInt -> MezzeInt(-operand.value)
            is MezzeFloat -> MezzeFloat(-operand.value)
            else -> throw RuntimeException("Cannot negate ${operand::class.simpleName}")
        }
    }

    private fun executeNot(operand: MezzeValue): MezzeValue {
        return when (operand) {
            is MezzeBool -> MezzeBool(!operand.value)
            else -> throw RuntimeException("Cannot apply logical not to ${operand::class.simpleName}")
        }
    }
}
