package com.mezze.language.typechecker.util

/**
 * String similarity utilities for generating user-friendly error messages.
 *
 * Provides Levenshtein distance calculation and fuzzy matching for:
 * - "Did you mean...?" suggestions for typos
 * - Finding similar names in scope
 * - Suggesting fixes for common mistakes
 */
object StringDistance {
    /**
     * Calculate Levenshtein distance between two strings.
     *
     * Measures the minimum number of single-character edits (insertions,
     * deletions, or substitutions) required to change one string into another.
     *
     * @param s1 First string
     * @param s2 Second string
     * @return The edit distance between the strings
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        // Early termination for empty strings
        if (len1 == 0) return len2
        if (len2 == 0) return len1

        // Create matrix of distances
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        // Initialize base cases
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        // Fill the DP table
        for (i in 1..len1) {
            for (j in 1..len2) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1] // No operation needed
                } else {
                    1 + minOf(
                        dp[i - 1][j],     // Deletion
                        dp[i][j - 1],     // Insertion
                        dp[i - 1][j - 1]  // Substitution
                    )
                }
            }
        }

        return dp[len1][len2]
    }

    /**
     * Find similar strings from a list of candidates.
     *
     * Returns candidates sorted by similarity (closest first), filtered
     * by maximum edit distance.
     *
     * @param target The string to match against
     * @param candidates List of candidate strings
     * @param maxDistance Maximum edit distance to consider (default: 3)
     * @param maxResults Maximum number of results to return (default: 3)
     * @return List of similar strings, sorted by distance
     */
    fun findSimilar(
        target: String,
        candidates: List<String>,
        maxDistance: Int = 3,
        maxResults: Int = 3
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()

        return candidates
            .map { it to levenshteinDistance(target, it) }
            .filter { (_, distance) -> distance <= maxDistance }
            .sortedBy { (_, distance) -> distance }
            .take(maxResults)
            .map { (name, _) -> name }
    }

    /**
     * Generate a "Did you mean...?" suggestion string.
     *
     * @param target The string that wasn't found
     * @param candidates List of valid candidates
     * @param maxSuggestions Maximum number of suggestions to show
     * @return A suggestion string like "Did you mean: 'foo' or 'bar'?", or null if no good matches
     */
    fun suggestDidYouMean(
        target: String,
        candidates: List<String>,
        maxSuggestions: Int = 3
    ): String? {
        val similar = findSimilar(target, candidates, maxDistance = 3, maxResults = maxSuggestions)
        return when {
            similar.isEmpty() -> null
            similar.size == 1 -> "Did you mean '${similar[0]}'?"
            else -> "Did you mean: ${similar.joinToString(" or ") { "'$it'" }}?"
        }
    }

    /**
     * Generate suggestion with fallback to listing available options.
     *
     * @param target The string that wasn't found
     * @param candidates List of valid candidates
     * @param itemName What the items are called (e.g., "fields", "constructors")
     * @param maxShow Maximum number of items to show in fallback
     * @return A suggestion string
     */
    fun suggestOrList(
        target: String,
        candidates: List<String>,
        itemName: String = "options",
        maxShow: Int = 5
    ): String {
        // First try to find similar matches
        val suggestion = suggestDidYouMean(target, candidates)
        if (suggestion != null) return suggestion

        // Fall back to listing available options
        return when {
            candidates.isEmpty() -> "No $itemName available"
            candidates.size <= maxShow -> "Available $itemName: ${candidates.joinToString(", ") { "'$it'" }}"
            else -> "Available $itemName: ${candidates.take(maxShow).joinToString(", ") { "'$it'" }}, ..."
        }
    }
}
