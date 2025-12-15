package com.funk.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkVariant

/**
 * Variant construction node - creates a variant value with a constructor and optional data.
 *
 * This is used for all variant constructions including:
 * - Newtype constructors: Email {value = "test@example.com"}
 * - Regular variants: Some {val = 42}, None, Ok {val = "success"}
 *
 * Newtypes are represented as single-constructor variants at runtime,
 * making them zero-cost after construction (just variant construction overhead).
 */
@NodeInfo(shortName = "variant-construction")
class VariantConstructionNode(
    private val constructor: String,
    @field:Child private var valueNode: FunkNode?
) : FunkNode() {

    override fun execute(frame: VirtualFrame): FunkValue {
        val value = valueNode?.execute(frame)
        return FunkVariant(constructor, value)
    }

    override fun toString(): String {
        return if (valueNode != null) {
            "VariantConstruction($constructor, ...)"
        } else {
            "VariantConstruction($constructor)"
        }
    }
}
