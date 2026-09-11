package com.mezze.language.typechecker.cache

import com.mezze.language.store.MezzeStore
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category

object TypeCheckCache {

    private var store: MezzeStore? = null

    fun initialize(store: MezzeStore) {
        this.store = store
        DebugLogger.debug(Category.TypeChecker) {
            "TypeCheckCache initialized with store persistence"
        }
    }

    fun initializeInMemory() {
        store = null
        DebugLogger.debug(Category.TypeChecker) {
            "TypeCheckCache initialized in-memory only"
        }
    }

    fun hasPersistence(): Boolean = store != null

    // L-04 (Move-5): a name-keyed ability-impl cache used to live here — its
    // `computeAbilityKey`/`typeToString` folded types into a NAME-based key (one of
    // the ≥5 duplicate structural deciders the audit flagged, violating
    // no_name_dispatch: two same-named-but-hash-distinct types collided). It had
    // ZERO external callers — real ability-impl selection routes through
    // TypingContext.getAbilityImpl(contentHash), keyed on hash — so it was dead.
    // Removed entirely (not revived): reviving would require TypeIdentity/hash
    // keying, a load-bearing change nothing needs. This retires the L-04 decider at
    // zero behavior cost. `clearAll` is now a no-op kept for its call sites.

    fun clearAll() {
        // Nothing to clear — the in-memory ability-impl cache was removed (L-04).
    }
}
