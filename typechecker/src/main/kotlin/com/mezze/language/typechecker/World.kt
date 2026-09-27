package com.mezze.language.typechecker

import java.util.concurrent.atomic.AtomicLong

/**
 * One isolated view of the codebase.
 *
 * The typechecker's module caches were process-global and keyed by module PATH
 * alone, which quietly assumed a single view of the world. Two consequences, both
 * blocking:
 *
 *  - **Collision.** Two variants of a project that both contain `app::handler`
 *    share one cache entry, so the second to be checked sees the first's result.
 *    That makes a speculative or dry-run change impossible: there is nowhere to put
 *    the hypothetical version.
 *  - **False cycles.** The cycle-detection set conflated "this PROCESS is checking
 *    X" with "this REQUEST is checking X". Two concurrent checks that both reach X
 *    make each other look like an import cycle — and the failure is a reported
 *    error, not a slow path.
 *
 * A [World] is the missing half of the cache key. Nothing about it is heavyweight:
 * it is an identity, and the caches key on `(world, module)`.
 *
 * ## The stdlib is shared, not copied
 *
 * A world does NOT own a copy of the standard library. Stdlib typed modules are
 * immutable and identical in every world, and there are a great many of them — a
 * copy per world would multiply the largest thing in memory by the number of
 * worlds to no purpose. So lookups fall through to one shared base layer, which
 * makes creating a world nearly free. That is also what lets a speculative
 * changeset be a thin overlay over a shared base rather than a fork of everything.
 *
 * ## Default world
 *
 * Every existing entry point defaults to [DEFAULT], so code that has no opinion
 * about worlds keeps the single-world behaviour it had. A caller that needs
 * isolation asks for [fresh].
 */
@JvmInline
value class World(val id: String) {

    override fun toString(): String = "world($id)"

    companion object {
        /**
         * The world every caller shares unless it asks otherwise.
         *
         * Named rather than nullable: "no world" and "the default world" are the
         * same thing here, and a null would invite each call site to decide what
         * absence means.
         */
        @JvmStatic
        val DEFAULT: World = World("default")

        private val counter = AtomicLong(0)

        /**
         * A world nothing else can be using.
         *
         * [label] is for diagnostics only — the counter is what makes it unique, so
         * two `fresh("eval")` calls are still distinct worlds.
         */
        @JvmStatic
        @JvmOverloads
        fun fresh(label: String = "world"): World = World("$label-${counter.incrementAndGet()}")
    }
}
