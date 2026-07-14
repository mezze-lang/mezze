package com.mezze.language.typechecker.util

object StringDistance {
        fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        if (len1 == 0) return len2
        if (len2 == 0) return len1

        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(
                        dp[i - 1][j],
                        dp[i][j - 1],
                        dp[i - 1][j - 1]
                    )
                }
            }
        }

        return dp[len1][len2]
    }

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

        fun suggestOrList(
        target: String,
        candidates: List<String>,
        itemName: String = "options",
        maxShow: Int = 5
    ): String {
        val suggestion = suggestDidYouMean(target, candidates)
        if (suggestion != null) return suggestion

        return when {
            candidates.isEmpty() -> "No $itemName available"
            candidates.size <= maxShow -> "Available $itemName: ${candidates.joinToString(", ") { "'$it'" }}"
            else -> "Available $itemName: ${candidates.take(maxShow).joinToString(", ") { "'$it'" }}, ..."
        }
    }
}
