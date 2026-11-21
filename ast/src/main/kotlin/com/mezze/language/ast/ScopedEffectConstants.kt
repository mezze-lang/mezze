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
     * IR names of the two control handles, reached in source ONLY as fields of
     * `cont` (`cont.abort { val }`, `cont.resume { val }`). The bare names are
     * NOT bound in a handler body and NOT reserved: `abort` and `resume` are
     * ordinary identifiers. The compiler lowers `cont.abort`/`cont.resume` to
     * `EffectBindingIR("abort"/"resume")`, so the handles keep their
     * constant-folded fast path (see `MezzeBytecodeRootNode.EffectBinding`).
     */
    const val ABORT = "abort"
    const val RESUME = "resume"

    /**
     * THE runtime-bound name in a performer op body — the one record through
     * which a handler touches its continuation (one mechanism, one spelling):
     *
     *     cont.resume { val }      hand `val` back as the op's result (tail only;
     *                              falling off the body does the same, and is
     *                              what the 99% handler writes)
     *     cont.abort  { val }      end the whole `perform` with `val` (tail only)
     *     cont.park   { on, set }  suspend this task until the condition holds
     *
     * Reserved at binding sites like `scope`. Userland never holds a
     * continuation — the runtime does; `cont` itself is not a value.
     */
    const val CONT = "cont"
    const val CONT_PARK = "park"
    const val CONT_ABORT = ABORT
    const val CONT_RESUME = RESUME

    /** `"abort"`/`"resume"` when [e] is `cont.abort`/`cont.resume` (unqualified `cont`), else null. */
    fun contHandle(e: com.mezze.language.parsedast.ParsedExpr): String? {
        if (e !is com.mezze.language.parsedast.ParsedMemberAccess) return null
        val r = e.receiver
        if (r !is com.mezze.language.parsedast.ParsedIdentifier || r.qualifier != null || r.localName != CONT) return null
        return if (e.member == ABORT || e.member == RESUME) e.member else null
    }
    /**
     * The third impl-only field, after `initialize_scope`/`finalize_scope`: a
     * SELECTOR the runtime calls while the perform body is parked —
     * `drive = { runnable } -> runnable.first {}` — never a bookkeeping loop.
     * `runnable` is an opaque O(1) view of the ready set (`RunQueue`).
     */
    const val DRIVE = "drive"
    const val DRIVE_RUNNABLE = "runnable"

    /** `CoopAsyncWith { on_stall: Option { after: Duration, f: { waiting } -> {} <...> } }`: the
     *  stall report, handed to the task machine by the performer's `initialize_scope`.
     *  `None` (and a performer without the field) means the machine reports nothing, ever —
     *  the runtime has no output of its own; the report is a Mezze function whose effects are
     *  the perform site's. */
    /** Impl-only fields ANY cont-capable performer may declare, read by the machine like `drive`:
     *  `stall_after = {} -> Duration` and `on_stall = { waiting } -> {}` (its body's effects are the
     *  performer's added effects, so the perform site must handle them). `CoopAsyncWith` carries
     *  the same report as CONFIG (`on_stall: Option {...}`) and registers it from `initialize_scope`. */
    const val ON_STALL = "on_stall"
    const val STALL_AFTER_HOOK = "stall_after"
    const val STALL_AFTER = "after"
    const val STALL_F = "f"
    const val STALL_WAITING = "waiting"
    const val STALL_TASK_ID = "id"
    const val STALL_TASK_ON = "on"
    /** Field name carried by `abort` and `resume` records. */
    const val VAL_FIELD = "val"

    /**
     * True for the scope lifecycle hooks (`initialize_scope`/`finalize_scope`).
     * These are not effect operations a caller invokes — they run automatically
     * around a scoped handler — so LSP display (hover, completion) should hide
     * them from a handler's op list, showing only the real operations.
     */
    fun isScopeHook(name: String): Boolean =
        name == INITIALIZE_SCOPE || name == FINALIZE_SCOPE || name == DRIVE || name == ON_STALL || name == STALL_AFTER_HOOK
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
    SCOPE,
    CONT;

    /**
     * The user-visible name (matches `ScopedEffectConstants.RESUME` etc.).
     * Kept for error messages and LSP display only — not for dispatch.
     */
    fun displayName(): String = when (this) {
        RESUME -> ScopedEffectConstants.RESUME
        ABORT -> ScopedEffectConstants.ABORT
        SCOPE -> ScopedEffectConstants.SCOPE
        CONT -> ScopedEffectConstants.CONT
    }

    companion object {
        /**
         * Resolve a binding name to its kind, or null for anything else.
         *
         * The parameter is deliberately nullable: a non-null `String` would
         * make the Kotlin compiler emit a `checkNotNullParameter` guard whose
         * failure path calls `throwParameterIsNullNPE` → `Throwable.getStackTrace()`.
         * This method is reachable from the bytecode interpreter's PE'd execute
         * path (the `isResumeOrAbortKind` specialization guard), and under
         * native-image that stack-trace-building path reaches SVM's
         * `Optional.map`-over-lambda machinery — tripping the runtime-compilation
         * blocklist with hundreds of phantom violations. A nullable param emits
         * no intrinsic, so the PE'd `when` stays clean. `null` simply falls
         * through to `else -> null`, identical to any unknown name.
         */
        fun fromName(name: String?): EffectBindingKind? = when (name) {
            ScopedEffectConstants.RESUME -> RESUME
            ScopedEffectConstants.ABORT -> ABORT
            ScopedEffectConstants.SCOPE -> SCOPE
            ScopedEffectConstants.CONT -> CONT
            else -> null
        }
    }
}
