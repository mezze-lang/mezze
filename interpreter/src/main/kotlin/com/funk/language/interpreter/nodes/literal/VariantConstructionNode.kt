package com.funk.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkVariant
import com.funk.language.interpreter.FunkRecord
import com.funk.language.interpreter.FunkList
import com.funk.language.interpreter.FunkCons
import com.funk.language.interpreter.FunkNil
import com.funk.language.interpreter.FunkBool

/**
 * Variant construction node - creates a variant value with a constructor and optional data.
 *
 * This is used for all variant constructions including:
 * - Newtype constructors: Email {value = "test@example.com"}
 * - Regular variants: Some {val = 42}, None, Ok {val = "success"}
 * - Specialized variants: Cons {head = 1, tail = Nil}, Nil, True, False
 *
 * Specialized variants are recognized and converted to optimized runtime representations:
 * - List: Cons/Nil → FunkCons/FunkNil for efficient linked list operations
 * - Bool: True/False → FunkBool for efficient boolean operations
 *
 * This allows user-facing variant syntax while using efficient internal representations.
 *
 * Pattern: User writes `Cons { head = x, tail = xs }` → Runtime gets `FunkCons(x, xs)`
 * Pattern: User writes `True` → Runtime gets `FunkBool(true)`
 */
@NodeInfo(shortName = "variant-construction")
class VariantConstructionNode(
    private val constructor: String,
    @field:Child private var valueNode: FunkNode?
) : FunkNode() {

    override fun execute(frame: VirtualFrame): FunkValue {
        val value = valueNode?.execute(frame)

        // Handle specialized variants with optimized representations
        return when (constructor) {
            // Boolean constructors -> FunkBool
            "True" -> FunkBool(true)
            "False" -> FunkBool(false)

            // List constructors -> FunkCons/FunkNil
            "Nil" -> FunkNil
            "Cons" -> {
                val record = value as? FunkRecord
                    ?: throw RuntimeException("Cons constructor requires a record with 'head' and 'tail' fields")
                val head = record.fields["head"]
                    ?: throw RuntimeException("Cons constructor requires 'head' field")
                val tail = record.fields["tail"] as? FunkList
                    ?: throw RuntimeException("Cons constructor requires 'tail' field of type List")
                FunkCons(head, tail)
            }

            // Default: standard variant
            else -> FunkVariant(constructor, value)
        }
    }

    override fun toString(): String {
        return if (valueNode != null) {
            "VariantConstruction($constructor, ...)"
        } else {
            "VariantConstruction($constructor)"
        }
    }
}
