package com.mezze.language.typechecker.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * S7.1 (#369) — the no-name-dispatch CAPSTONE probe.
 *
 * A gated, off-by-default instrument that records EVERY time a correctness
 * decision site in the typechecker (identity / dispatch / unification / coverage)
 * reaches a point where a **type/effect/impl NAME string** — as opposed to a
 * content hash, a builtin/reserved kind, or a structural comparison — is what
 * DECIDES the outcome.
 *
 * The probe classifies each firing into one of two buckets, and the whole point
 * of the capstone is to prove the FORBIDDEN count is 0 across the corpus:
 *
 *  - [forbidden]: a name string DECIDED identity/dispatch/unification/coverage
 *    while a hash/kind/structural key WAS available and was ignored. If this is
 *    ever > 0 it is a genuine name-based-dispatch violation.
 *
 *  - [allowed]: a name string was used, but by DESIGN it is the only identity
 *    available (a hashless/legacy fallback, a reserved builtin-kind name that IS
 *    the identity, a per-impl binding-slot label, module-path resolution, or a
 *    display/error string). These are documented non-violations
 *    (see project no_name_string_dispatch_ever, f0_f11_audit_closed) and are
 *    counted separately so the proof is honest about where names legitimately
 *    appear.
 *
 * Off unless `-Dmezze.namefallback.probe=true`; the record calls short-circuit on
 * a single volatile read so the normal gate pays nothing. Never influences a
 * typing decision — it only observes and counts.
 */
object NameFallbackProbe {

    @Volatile
    private var enabled: Boolean = System.getProperty("mezze.namefallback.probe", "false") == "true"

    /** Test/CLI hook to flip the probe on within a single JVM. */
    fun setEnabled(on: Boolean) { enabled = on }

    fun isEnabled(): Boolean = enabled

    private val forbiddenCount = AtomicLong(0)
    private val allowedCount = AtomicLong(0)

    /** site -> (count, one sample string) for reporting. */
    private val forbiddenSites = ConcurrentHashMap<String, SiteRecord>()
    private val allowedSites = ConcurrentHashMap<String, SiteRecord>()

    data class SiteRecord(val count: AtomicLong = AtomicLong(0), @Volatile var sample: String? = null)

    /**
     * Record a FORBIDDEN name-decides-correctness firing. Call ONLY from a branch
     * where a name string is the deciding key AND a hash/kind/structural key was
     * available. In a correct (hash-driven) typechecker this must never fire.
     */
    fun forbidden(site: String, sample: () -> String) {
        if (!enabled) return
        forbiddenCount.incrementAndGet()
        forbiddenSites.computeIfAbsent(site) { SiteRecord() }.let {
            it.count.incrementAndGet()
            if (it.sample == null) it.sample = sample()
        }
    }

    /**
     * Record an ALLOWED name use (hashless fallback / reserved builtin kind /
     * binding-slot label / module-path). Counted for transparency; never a
     * violation.
     */
    fun allowed(site: String, sample: () -> String) {
        if (!enabled) return
        allowedCount.incrementAndGet()
        allowedSites.computeIfAbsent(site) { SiteRecord() }.let {
            it.count.incrementAndGet()
            if (it.sample == null) it.sample = sample()
        }
    }

    fun forbiddenTotal(): Long = forbiddenCount.get()
    fun allowedTotal(): Long = allowedCount.get()

    fun reset() {
        forbiddenCount.set(0)
        allowedCount.set(0)
        forbiddenSites.clear()
        allowedSites.clear()
    }

    /** Human-readable report for the corpus run. */
    fun report(): String = buildString {
        appendLine("=== NameFallbackProbe report ===")
        appendLine("FORBIDDEN name-decides-correctness firings: ${forbiddenCount.get()}")
        if (forbiddenSites.isEmpty()) {
            appendLine("  (none — no name string decided identity/dispatch/unification/coverage)")
        } else {
            forbiddenSites.toSortedMap().forEach { (site, rec) ->
                appendLine("  [$site] x${rec.count.get()}  e.g. ${rec.sample}")
            }
        }
        appendLine("ALLOWED name uses (hashless/reserved/slot-label/module-path): ${allowedCount.get()}")
        allowedSites.toSortedMap().forEach { (site, rec) ->
            appendLine("  [$site] x${rec.count.get()}  e.g. ${rec.sample}")
        }
    }
}
