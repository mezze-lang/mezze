package com.mezze.language.parsedast.diagnostics

/**
 * "Did you mean …?" helper. Given a misspelled identifier and a list of
 * valid candidates, returns the closest match by edit distance.
 *
 * Threshold is tuned so single-character typos always suggest, and longer
 * candidates allow slightly more slack:
 * - distance 1 always passes
 * - distance 2 passes if the candidate is ≥ 4 chars
 * - distance 3 passes if the candidate is ≥ 8 chars
 *
 * Returns null if nothing close enough exists. Empty candidate list also
 * returns null — never throws.
 */
object LevenshteinSuggest {

    /** Find the single closest candidate, or null if nothing close enough. */
    fun closest(typed: String, candidates: Collection<String>): String? {
        if (typed.isEmpty() || candidates.isEmpty()) return null
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in candidates) {
            if (candidate == typed) continue  // exact match isn't a "did you mean"
            val d = distance(typed, candidate)
            if (d < bestDistance && acceptable(d, candidate.length)) {
                best = candidate
                bestDistance = d
            }
        }
        return best
    }

    /** Find the top N closest candidates, ordered by distance. */
    fun closestN(typed: String, candidates: Collection<String>, n: Int = 3): List<String> {
        if (typed.isEmpty() || candidates.isEmpty() || n <= 0) return emptyList()
        val scored = candidates.asSequence()
            .filter { it != typed }
            .map { it to distance(typed, it) }
            .filter { (cand, d) -> acceptable(d, cand.length) }
            .sortedBy { it.second }
            .take(n)
            .map { it.first }
            .toList()
        return scored
    }

    private fun acceptable(d: Int, candidateLen: Int): Boolean = when {
        d <= 1 -> true
        d == 2 && candidateLen >= 4 -> true
        d == 3 && candidateLen >= 8 -> true
        else -> false
    }

    /**
     * Classic Levenshtein edit distance. O(m*n) time, O(min(m,n)) space.
     * Not case-insensitive — identifiers are case-sensitive in Mezze.
     */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Two-row rolling buffer
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,        // insertion
                    prev[j] + 1,            // deletion
                    prev[j - 1] + cost      // substitution
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
