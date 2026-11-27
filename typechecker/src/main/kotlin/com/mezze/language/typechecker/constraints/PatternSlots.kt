package com.mezze.language.typechecker.constraints

import com.mezze.language.parsedast.ParsedConstructorPattern
import com.mezze.language.parsedast.ParsedNewtypePattern
import com.mezze.language.parsedast.ParsedPattern
import com.mezze.language.parsedast.ParsedRecordPattern
import com.mezze.language.parsedast.ParsedSeqPattern
import com.mezze.language.parsedast.ParsedStringInterpPattern
import com.mezze.language.parsedast.ParsedStringPatternPart
import com.mezze.language.parsedast.ParsedVariablePattern

/**
 * Where a pattern binds a name — which decides how an unused one is written instead.
 *
 *   FIELD     a record field (`{ val }`, `{ val = x }`): leave the field out, end with `...`
 *   POSITION  one positional value (a list element, a `$(…)` string hole, a newtype's
 *             inside, a whole arm): write `_`
 *   REST      a list's named tail (`[first, ...rest]`): write `...` without a name
 */
enum class PatternSlot { FIELD, POSITION, REST }

/** The slot [name] is bound in within any of [patterns], or null when it is not bound there. */
fun patternSlotOf(patterns: List<ParsedPattern?>, name: String): PatternSlot? {
    fun walk(p: ParsedPattern?, inField: Boolean): PatternSlot? = when (p) {
        null -> null
        is ParsedVariablePattern -> if (p.name == name) (if (inField) PatternSlot.FIELD else PatternSlot.POSITION) else null
        is ParsedRecordPattern -> p.entries.firstNotNullOfOrNull { e ->
            when (val sub = e.pattern) {
                null -> if (e.name == name) PatternSlot.FIELD else null   // punned `{ val }`
                else -> walk(sub, inField = true)
            }
        }
        is ParsedConstructorPattern -> walk(p.payload, inField)
        is ParsedNewtypePattern -> walk(p.inner, inField = false)
        is ParsedSeqPattern ->
            if (p.restPattern == name) PatternSlot.REST
            else p.elements.firstNotNullOfOrNull { walk(it, inField = false) }
        is ParsedStringInterpPattern -> if (p.parts.any { it is ParsedStringPatternPart.Capture && it.variableName == name })
            PatternSlot.POSITION else null
        else -> null
    }
    return patterns.firstNotNullOfOrNull { walk(it, inField = false) }
}
