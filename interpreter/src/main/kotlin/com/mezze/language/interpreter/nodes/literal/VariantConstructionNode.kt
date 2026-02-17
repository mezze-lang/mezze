package com.mezze.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeVariant
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeList
import com.mezze.language.interpreter.MezzeCons
import com.mezze.language.interpreter.MezzeNil
import com.mezze.language.interpreter.MezzeBool

/**
 * Variant construction node - creates a variant value with a constructor and optional data.
 *
 * This is used for all variant constructions including:
 * - Newtype constructors: Email {value = "test@example.com"}
 * - Regular variants: Some {val = 42}, None, Ok {val = "success"}
 * - Specialized variants: Cons {head = 1, tail = Nil}, Nil, True, False
 *
 * Specialized variants are recognized and converted to optimized runtime representations:
 * - List: Cons/Nil → MezzeCons/MezzeNil for efficient linked list operations
 * - Bool: True/False → MezzeBool for efficient boolean operations
 *
 * This allows user-facing variant syntax while using efficient internal representations.
 *
 * Pattern: User writes `Cons { head = x, tail = xs }` → Runtime gets `MezzeCons(x, xs)`
 * Pattern: User writes `True` → Runtime gets `MezzeBool(true)`
 */
@NodeInfo(shortName = "variant-construction")
class VariantConstructionNode(
    private val constructor: String,
    @field:Child private var valueNode: MezzeNode?
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = valueNode?.execute(frame)

        // Handle specialized variants with optimized representations
        return when (constructor) {
            // Boolean constructors -> MezzeBool
            "True" -> MezzeBool(true)
            "False" -> MezzeBool(false)

            // List constructors -> MezzeCons/MezzeNil
            "Nil" -> MezzeNil
            "Cons" -> {
                val record = value as? MezzeRecord
                    ?: throw RuntimeException("Cons constructor requires a record with 'head' and 'tail' fields")
                val head = record.fields["head"]
                    ?: throw RuntimeException("Cons constructor requires 'head' field")
                val tail = record.fields["tail"] as? MezzeList
                    ?: throw RuntimeException("Cons constructor requires 'tail' field of type List")
                MezzeCons(head, tail)
            }

            // Default: standard variant
            else -> MezzeVariant(constructor, value)
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
