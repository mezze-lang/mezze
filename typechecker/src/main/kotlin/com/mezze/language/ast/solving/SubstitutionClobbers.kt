package com.mezze.language.ast.solving

/**
 * Collision recording for [Substitution.compose], off by default.
 *
 * `compose` is a union in which THIS side wins on a collision: when both sides
 * bind the same variable to DIFFERENT values, the loser is discarded and the two
 * are never unified. That is a choice between two answers rather than a union of
 * information, and nothing checks the two agreed.
 *
 * This records where it happens so `SubstitutionClobberGateTest` can hold the
 * number down. Switched on only by that gate; otherwise the cost is one boolean
 * read per compose.
 *
 * Thread-local because the test suite solves on several threads and a shared
 * counter would make the gate's number depend on scheduling.
 */
object SubstitutionClobbers {
    private val recordingFlag = ThreadLocal.withInitial { false }
    private val seenSet = ThreadLocal.withInitial { LinkedHashSet<String>() }

    var recording: Boolean
        get() = recordingFlag.get()
        set(v) = recordingFlag.set(v)

    /** Variables bound twice to different values, as `name#id`. */
    val seen: MutableSet<String> get() = seenSet.get()
}
