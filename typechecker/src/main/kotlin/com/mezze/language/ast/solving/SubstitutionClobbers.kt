package com.mezze.language.ast.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Bookkeeping for [Substitution.compose]'s collision handling.
 *
 * `compose` is a union in which THIS side wins on a collision. Winning alone was
 * the whole of it once: when both sides bound the same variable to DIFFERENT
 * values the loser was discarded and the two were never unified, so a solve could
 * finish holding one of two answers with nothing having checked they agreed.
 * Collisions now unify, and this records what happens.
 *
 * Two things are kept, for two different reasons:
 *
 *  - [seen] / [pairs], off by default, so `SubstitutionClobberGateTest` can hold
 *    the collision COUNT down. A collision that unifies is no longer a wrong
 *    answer, but it is still two passes disagreeing about one variable, and the
 *    number is worth watching.
 *  - [conflicts], always on, because a collision whose two values do NOT unify is
 *    a contradiction the solve is about to swallow. It costs nothing when empty.
 *
 * Thread-local throughout: the suite solves on several threads and a shared
 * counter would make the numbers depend on scheduling.
 */
object SubstitutionClobbers {
    private val recordingFlag = ThreadLocal.withInitial { false }
    private val seenSet = ThreadLocal.withInitial { LinkedHashSet<String>() }
    private val pairsList = ThreadLocal.withInitial { mutableListOf<Triple<String, String, String>>() }
    private val conflictList = ThreadLocal.withInitial { mutableListOf<String>() }
    private val resolvingFlag = ThreadLocal.withInitial { false }

    var recording: Boolean
        get() = recordingFlag.get()
        set(v) = recordingFlag.set(v)

    /**
     * True while a collision is being unified. `unify` composes internally, so
     * without this the merge would re-enter itself through its own resolution.
     */
    var resolving: Boolean
        get() = resolvingFlag.get()
        set(v) = resolvingFlag.set(v)

    /** Variables bound twice to different values, as `name#id`. */
    val seen: MutableSet<String> get() = seenSet.get()

    /** (variable, first value, second value) for each collision, while recording. */
    val pairs: MutableList<Triple<String, String, String>> get() = pairsList.get()

    /** Collisions whose two values do not unify — a contradiction, always recorded. */
    val conflicts: MutableList<String> get() = conflictList.get()

    fun record(v: TypeVar, previous: MezzeType, current: MezzeType) {
        if (!recording) return
        seen.add(v.name + "#" + v.id)
        pairs.add(Triple(v.name + "#" + v.id, previous.toString(), current.toString()))
    }

    fun recordConflict(v: TypeVar, message: String) {
        conflicts.add("${v.name}#${v.id}: $message")
    }
}
