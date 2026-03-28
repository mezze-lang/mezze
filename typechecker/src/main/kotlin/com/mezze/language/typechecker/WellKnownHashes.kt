package com.mezze.language.typechecker

import com.mezze.language.ast.AbilityDeclaration
import com.mezze.language.ast.hashing.ASTHasher
import com.mezze.language.parser.MezzeParser

/**
 * Singleton that computes and caches the content hashes of compiler-known
 * ability/effect declarations at first use.
 *
 * ## Why this exists
 *
 * Two constructs are universally known to the compiler:
 * - **Inspect** — universally available ability (auto-derived for all types)
 * - **Mut** — hermetic effect (cannot coexist with other effects)
 *
 * These were previously identified by name string ("Inspect", "Mut"), which breaks
 * when a user imports the declaration under an alias:
 *
 *   use std::abilities::inspect::{Inspect as Debug}
 *
 * After that import the compiler saw "Debug" in the constraint, not "Inspect", so
 * the special-case logic was silently skipped.
 *
 * The fix is to identify these by the *content hash* of the declaration, which is
 * stable across renames.
 *
 * ## Mut note
 *
 * Effects don't carry content hashes yet — `MezzeType.EffectRow` stores effect names
 * as `Set<String>`.  The Mut check therefore remains name-based for now.
 * TODO: switch Mut to hash-based once effects carry declaration hashes.
 */
object WellKnownHashes {

    /**
     * Content hash of the `Inspect` ability declaration
     * (`stdlib/std/abilities/inspect.mz`).
     *
     * Computed lazily on first access so that the parser/resource loader is not
     * invoked during static initialisation.  Returns `null` if the resource cannot
     * be found (should not happen in production; allows graceful degradation).
     */
    val inspectAbilityHash: String? by lazy { computeInspectHash() }

    // -------------------------------------------------------------------------
    // Public helpers
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when [abilityHash] identifies the Inspect ability by content.
     *
     * Use this as the primary check.  A null hash never matches (the caller should
     * fall back to the name-based check below when the hash is unavailable).
     */
    fun isInspect(abilityHash: String?): Boolean =
        abilityHash != null && abilityHash == inspectAbilityHash

    /**
     * Returns `true` when the constraint identifies the Inspect ability.
     *
     * Checks by content hash first; falls back to the legacy name "Inspect" so that
     * code paths that haven't been migrated to hash-based constraints continue to
     * work.  The name fallback also keeps the compiler working when
     * `inspectAbilityHash` could not be computed (missing resource).
     */
    fun isInspect(abilityName: String, abilityHash: String?): Boolean =
        isInspect(abilityHash) || abilityName == "Inspect"

    /**
     * Returns `true` when [effectName] names the Mut effect.
     *
     * Currently name-based only — see class-level TODO.
     */
    fun isMut(effectName: String): Boolean =
        effectName == "Mut"  // TODO: use hash once effects carry declaration hashes

    // -------------------------------------------------------------------------
    // Internal computation
    // -------------------------------------------------------------------------

    private fun computeInspectHash(): String? {
        return try {
            val source = ModuleResolver.readStdlibFile("stdlib/std/abilities/inspect.mz")
                ?: ModuleResolver.readStdlibFile("std/abilities/inspect.mz")
                ?: return null

            val parser = MezzeParser()
            val program = parser.parse(source)
            val decl = program.items.filterIsInstance<AbilityDeclaration>()
                .firstOrNull { it.name == "Inspect" }
                ?: return null

            ASTHasher.hashAbilityDeclaration(decl).toFullHexString()
        } catch (_: Exception) {
            null
        }
    }
}
