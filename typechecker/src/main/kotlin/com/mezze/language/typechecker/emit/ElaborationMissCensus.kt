package com.mezze.language.typechecker.emit

import com.mezze.language.parsedast.Loc

/**
 * A.2 — where the builder does not know a type and mints a fresh variable
 * instead of saying so.
 *
 * The target state is that a miss is an `ElaborationError`. Getting there in one
 * step would turn every existing miss into a build failure, and nobody knows how
 * many there are — so this counts them first. The same shape as the 0.2 ratchet:
 * measure, then ratchet, then enforce.
 *
 * Why a fresh variable is not a harmless placeholder: it is INDISTINGUISHABLE
 * from a legitimately polymorphic binding. A consumer asking "is this
 * polymorphic?" gets yes either way, so a binding the compiler simply failed to
 * type takes the same path as one that is genuinely generic. For an operator
 * that means dynamic dispatch on an untyped receiver, and for an erased newtype
 * that silently uses the WRAPPED type's ordering — BUG 11, which returns a wrong
 * answer rather than failing.
 *
 * Off unless `MEZZE_ELAB_CENSUS` is set, and purely additive: recording a miss
 * never changes what is compiled.
 */
object ElaborationMissCensus {

    /** `where` → how many times the builder guessed there. */
    private val misses = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private val enabled: Boolean = System.getenv("MEZZE_ELAB_CENSUS") != null

    /** Whether counting is on. Off by default, so normal runs pay nothing. */
    val isEnabled: Boolean get() = enabled

    init {
        // Print at exit rather than from a test: the builder runs during every
        // compile, and a census that only covers whatever a single test happened
        // to compile would under-report.
        if (enabled) {
            Runtime.getRuntime().addShutdownHook(
                Thread { if (total() > 0) System.err.println(report()) },
            )
        }
    }

    /** A match-case binding whose type neither the solver nor the structural
     *  read supplied. [name] is the bound variable; [loc] localises it. */
    fun recordMatchBindingMiss(name: String, loc: Loc) {
        if (!enabled) return
        val where = "match-binding\t${loc.file.substringAfterLast('/')}:${loc.startLine}\t$name"
        misses.computeIfAbsent(where) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }

    /** Total guesses recorded so far. */
    fun total(): Int = misses.values.sumOf { it.get() }

    /** Distinct sites, most-hit first. */
    fun bySite(): List<Pair<String, Int>> =
        misses.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }

    fun reset() = misses.clear()

    /** One line per site, for a test or a build to print. */
    fun report(): String = buildString {
        appendLine("elaboration misses (fresh var minted because the type was unknown): ${total()}")
        appendLine("distinct sites: ${misses.size}")
        for ((where, n) in bySite()) appendLine("  ${n}x\t$where")
    }
}
