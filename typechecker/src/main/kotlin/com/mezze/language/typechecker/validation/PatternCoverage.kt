package com.mezze.language.typechecker.validation

import com.mezze.language.parsedast.ParsedConstructorPattern
import com.mezze.language.parsedast.ParsedLiteralPattern
import com.mezze.language.parsedast.ParsedLiteralValue
import com.mezze.language.parsedast.ParsedNewtypePattern
import com.mezze.language.parsedast.ParsedPattern
import com.mezze.language.parsedast.ParsedRecordPattern
import com.mezze.language.parsedast.ParsedSeqPattern
import com.mezze.language.parsedast.ParsedStringInterpPattern
import com.mezze.language.parsedast.ParsedStringPatternPart
import com.mezze.language.parsedast.ParsedVariablePattern
import com.mezze.language.parsedast.ParsedWildcardPattern

/**
 * "Pattern A covers pattern B": every value B matches, A matches too. Used for
 * V0603 — an arm whose every alternative an earlier unconditional arm covers can
 * never run. Exact for wildcards, names, literals, constructors, records, newtypes
 * and lists (including a middle `...`); conservative for string patterns (it only
 * claims coverage it can prove from literal prefixes/suffixes), so it never calls a
 * live arm dead. Both patterns are matched against the same scrutinee, so a
 * constructor is identified by its tag.
 */
object PatternCoverage {

    fun covers(a: ParsedPattern, b: ParsedPattern): Boolean = when (a) {
        is ParsedWildcardPattern, is ParsedVariablePattern -> true
        is ParsedLiteralPattern -> b is ParsedLiteralPattern && a.value == b.value
        is ParsedConstructorPattern -> b is ParsedConstructorPattern && a.constructor == b.constructor && run {
            val pa = a.payload; val pb = b.payload
            when {
                pa == null -> true
                pb == null -> irrefutable(pa)
                else -> covers(pa, pb)
            }
        }
        is ParsedNewtypePattern -> b is ParsedNewtypePattern && a.typeName == b.typeName && covers(a.inner, b.inner)
        is ParsedRecordPattern -> b is ParsedRecordPattern && a.entries.all { ea ->
            val pa = ea.pattern ?: WILDCARD
            when (val eb = b.entries.firstOrNull { it.name == ea.name }) {
                null -> irrefutable(pa)                       // b leaves the field unconstrained
                else -> covers(pa, eb.pattern ?: WILDCARD)     // a punned `{ f }` field matches anything
            }
        }
        is ParsedSeqPattern -> b is ParsedSeqPattern && seqCovers(a, b)
        is ParsedStringInterpPattern -> stringCovers(a, b)
        else -> false
    }

    /** Matches every value of its type: a name, `_`, or a record of those. */
    fun irrefutable(p: ParsedPattern): Boolean = when (p) {
        is ParsedWildcardPattern, is ParsedVariablePattern -> true
        is ParsedRecordPattern -> p.entries.all { e -> e.pattern.let { it == null || irrefutable(it) } }
        is ParsedSeqPattern -> p.restPattern != null && p.elements.isEmpty()   // `[...]`, `[...rest]`
        is ParsedStringInterpPattern -> p.parts.size == 1 && p.parts[0] is ParsedStringPatternPart.Capture
        else -> false
    }

    private val WILDCARD = ParsedWildcardPattern(com.mezze.language.parsedast.Loc("", 0, 0, 0, 0))

    private fun seqCovers(a: ParsedSeqPattern, b: ParsedSeqPattern): Boolean {
        val aOpen = a.restPattern != null; val bOpen = b.restPattern != null
        val aMin = a.elements.size; val bMin = b.elements.size
        val aPre = aMin - a.suffixCount; val aSuf = a.suffixCount
        val bPre = bMin - b.suffixCount; val bSuf = b.suffixCount
        if (!aOpen) return !bOpen && bMin == aMin && a.elements.indices.all { covers(a.elements[it], b.elements[it]) }
        if (bMin < aMin) return false                       // b matches lists too short for a
        if (!bOpen) {
            // b has a fixed length m: a's prefix reads b[i], a's suffix reads b[m - aSuf + j].
            return (0 until aPre).all { covers(a.elements[it], b.elements[it]) } &&
                (0 until aSuf).all { j -> covers(a.elements[aPre + j], b.elements[bMin - aSuf + j]) }
        }
        // Both open: a position a constrains is only known in b when it falls in b's own
        // prefix (from the start) or suffix (from the end); anywhere else b matches anything.
        val prefixOk = (0 until aPre).all { i ->
            if (i < bPre) covers(a.elements[i], b.elements[i]) else irrefutable(a.elements[i])
        }
        val suffixOk = (0 until aSuf).all { j ->
            val fromEnd = aSuf - j                          // 1 = last
            if (fromEnd <= bSuf) covers(a.elements[aPre + j], b.elements[bMin - fromEnd]) else irrefutable(a.elements[aPre + j])
        }
        return prefixOk && suffixOk
    }

    private fun stringOf(p: ParsedPattern): String? =
        ((p as? ParsedLiteralPattern)?.value as? ParsedLiteralValue.StringValue)?.value

    private fun stringCovers(a: ParsedStringInterpPattern, b: ParsedPattern): Boolean {
        val parts = a.parts
        val lit = { i: Int -> (parts.getOrNull(i) as? ParsedStringPatternPart.Literal)?.value }
        val isHole = { i: Int -> parts.getOrNull(i) is ParsedStringPatternPart.Capture }
        // Only the shapes whose coverage is provable from literals: "$(x)", "p$(x)", "$(x)s", "p$(x)s".
        val (prefix, suffix) = when {
            parts.size == 1 && isHole(0) -> "" to ""
            parts.size == 2 && lit(0) != null && isHole(1) -> lit(0)!! to ""
            parts.size == 2 && isHole(0) && lit(1) != null -> "" to lit(1)!!
            parts.size == 3 && lit(0) != null && isHole(1) && lit(2) != null -> lit(0)!! to lit(2)!!
            else -> return false
        }
        stringOf(b)?.let { s -> return s.length >= prefix.length + suffix.length && s.startsWith(prefix) && s.endsWith(suffix) }
        if (b !is ParsedStringInterpPattern) return false
        val first = (b.parts.firstOrNull() as? ParsedStringPatternPart.Literal)?.value ?: ""
        val last = (b.parts.lastOrNull() as? ParsedStringPatternPart.Literal)?.value ?: ""
        // b's own fixed start/end must already carry a's prefix/suffix; with a hole in
        // between, b's matches are at least first+last long, so they fit a's shape.
        val bHasHole = b.parts.any { it is ParsedStringPatternPart.Capture }
        return bHasHole && first.startsWith(prefix) && last.endsWith(suffix) &&
            (b.parts.size > 1 || (prefix.isEmpty() && suffix.isEmpty()))
    }
}
