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

        fun register(opener: MezzeStoreOpener) {
            registered = opener
        }

        fun current(): MezzeStoreOpener? = registered
    }
}
