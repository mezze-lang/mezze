package com.mezze.language.store

import java.io.File

/**
 * SAM-style factory for opening a [MezzeStore] from a backing file
 * (typically a bundled `stdlib.sqlite` resource extracted to a tempfile).
 *
 * Lives in the `:store` module — which both `:typechecker` (consumer) and
 * `:store-sqlite` (impl) depend on — so [PrecompiledStdlib] doesn't need
 * to know about the concrete `SQLiteMezzeStore` class. This eliminates the
 * reflection dance that previously bridged the dep cycle and broke under
 * native-image (#168).
 *
 * Wiring: each entry-point (CLI Main, tests, LSP, etc.) calls
 * [register] with `SQLiteMezzeStore::readOnly` (or equivalent) before
 * touching `PrecompiledStdlib`. Without registration, the fast resource
 * path falls back to compile-from-source.
 */
fun interface MezzeStoreOpener {
    fun openReadOnly(file: File): MezzeStore

    companion object {
        @Volatile
        private var registered: MezzeStoreOpener? = null

        /**
         * Register the opener. Idempotent re-registration of the exact same
         * instance is silent (CLI Main + LSP Main both call this with the
         * same lambda reference in tests that load both modules). A different
         * instance overwriting an existing registration logs a warning to
         * stderr — usually a sign that two SPI providers are competing and
         * win-last is brittle.
         */
        fun register(opener: MezzeStoreOpener) {
            val prior = registered
            if (prior === opener) return
            if (prior != null) {
                System.err.println(
                    "[MezzeStoreOpener] WARN: overwriting an existing registration. " +
                    "Prior: ${prior.javaClass.name}, new: ${opener.javaClass.name}. " +
                    "If both providers were intended to be active, wrap them in a ChainedMezzeStore."
                )
            }
            registered = opener
        }

        /** Reset to unregistered. Test-only — exposed for tests that need to
         *  isolate registrations between cases. */
        internal fun reset() {
            registered = null
        }

        fun current(): MezzeStoreOpener? = registered
    }
}
