package com.mezze.language.ast

/**
 * Constants for effect-handler scope hooks and runtime-bound names.
 *
 * Single source of truth — every site that reads/writes these names (the
 * typechecker, the constraint generator, the runtime driver, anywhere
 * that walks impl bodies) should reference these constants rather than
 * inlining the strings.
 *
 * Lives in :ast (rather than :typechecker) so the runtime can read
 * them without dragging :typechecker onto its classpath.
 */
object ScopedEffectConstants {
    /** Hook that creates the scope value, invoked once before body runs. */
    const val INITIALIZE_SCOPE = "initialize_scope"

    /** Hook that cleans up the scope value, invoked once after body completes. */
    const val FINALIZE_SCOPE = "finalize_scope"

    /** Name under which the scope value is bound inside op handler bodies. */
    const val SCOPE = "scope"

    /**
     * Runtime-bound name available inside op handler bodies. Calling
     * `abort {val = X}` ends the perform with X as its result, discarding
     * the body. See EffectHandlerBindings for the type shape.
     */
    const val ABORT = "abort"

    /**
     * Runtime-bound name available inside op handler bodies. Calling
     * `resume {val = X}` drives the body forward with X as the op's return.
     * Falling off the op body normally is treated as `resume {val = <last expr>}`.
     */
    const val RESUME = "resume"

    /** Field name carried by `abort` and `resume` records. */
    const val VAL_FIELD = "val"

    /**
     * True for the scope lifecycle hooks (`initialize_scope`/`finalize_scope`).
     * These are not effect operations a caller invokes — they run automatically
     * around a scoped handler — so LSP display (hover, completion) should hide
     * them from a handler's op list, showing only the real operations.
     */
    fun isScopeHook(name: String): Boolean =
        name == INITIALIZE_SCOPE || name == FINALIZE_SCOPE
}

/**
 * The three runtime-bound names an effect handler body can reference.
 * Enum instead of string keeps EffectBindingIR dispatch off the hot
 * name-lookup path — the runtime picks a branch on int tag, no
 * string equality checks (see project_no_name_based_dispatch).
 */
enum class EffectBindingKind {
    RESUME,
    ABORT,
    SCOPE;

    /**
     * The user-visible name (matches `ScopedEffectConstants.RESUME` etc.).
     * Kept for error messages and LSP display only — not for dispatch.
     */
    fun displayName(): String = when (this) {
        RESUME -> ScopedEffectConstants.RESUME
        ABORT -> ScopedEffectConstants.ABORT
        SCOPE -> ScopedEffectConstants.SCOPE
    }

    companion object {
        fun fromName(name: String): EffectBindingKind? = when (name) {
            ScopedEffectConstants.RESUME -> RESUME
            ScopedEffectConstants.ABORT -> ABORT
            ScopedEffectConstants.SCOPE -> SCOPE
            else -> null
        }
    }
}
