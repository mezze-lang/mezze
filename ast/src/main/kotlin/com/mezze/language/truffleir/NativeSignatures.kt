package com.mezze.language.truffleir

/**
 * Static metadata about a native operation's runtime ABI — the part the
 * typechecker / IR-builder needs to transpose a user record into
 * positional `CallNativeIR.args`.
 *
 * The interpreter's `NativeRegistry` holds the matching handler lambda;
 * the two are kept in sync by declaring both at the same site (the DSL
 * `NativeOp(...)` factory in the interpreter module). The handler-side
 * registry is the source of truth — it calls `NativeSignatures.register`
 * here to publish its signature for the IR-builder to find.
 *
 * Why split into two registries: the typechecker and stdlib modules
 * compile before the interpreter, so they can't touch interpreter classes
 * without a cyclic dependency. The signature registry lives in `ast`,
 * which both layers depend on, and only carries IR-shape info (name,
 * param order, slot kinds) — no executable code.
 */
data class NativeParam(val name: String, val kind: SlotKind)

data class NativeSignature(
    val name: String,
    val params: List<NativeParam>,
    val returns: SlotKind,
    /** True when the runtime handler has no observable side effects and no
     *  MezzeSuspend production — the compiler can duplicate or elide the
     *  call. Declared at the NativeOp registration site; default false
     *  (conservative). */
    val pure: Boolean = false,
    /** If set, this native has a same-shape [InlineOp] equivalent that
     *  BDSL emits as a Truffle DSL DirectXxx node. The compiler's
     *  post-substitution peephole collapses a CallNativeIR to
     *  InlineBinaryOpIR when this is present, unlocking the JIT fast
     *  path. Declared at the NativeOp registration site. */
    val inlineOp: InlineOp? = null,
) {
    /** Returns the positional index of [fieldName], or -1 if absent. */
    fun indexOf(fieldName: String): Int {
        for (i in params.indices) {
            if (params[i].name == fieldName) return i
        }
        return -1
    }

    val argKinds: List<SlotKind> get() = params.map { it.kind }
}

/**
 * Global registry of native signatures. Populated by the interpreter at
 * class-load time when `NativeOp(...)` DSL declarations fire their init
 * blocks.
 *
 * Both [register] and [signatureOf] are thread-safe for the realistic
 * pattern: all writes happen at static-init (single-threaded), reads
 * happen during typechecking/lowering (massively concurrent but never
 * racing a write).
 */
object NativeSignatures {
    private val signatures = HashMap<String, NativeSignature>()

    /**
     * Optional bootstrap hook. The interpreter sets this so the typechecker
     * can force DSL declarations to load before the first signature query.
     * Without the hook, the registry simply appears empty and the
     * IR-builder emits the legacy record-ABI shape — safe but slower.
     */
    @Volatile private var bootstrap: Runnable? = null
    @Volatile private var bootstrapped = false

    fun setBootstrap(hook: Runnable) {
        // Synchronized to keep the hook + the "ran it yet?" flag in lockstep;
        // the hook itself is allowed to be a no-op on second call.
        synchronized(this) {
            bootstrap = hook
            bootstrapped = false
        }
    }

    private fun ensureBootstrapped() {
        if (bootstrapped) return
        synchronized(this) {
            if (bootstrapped) return
            bootstrap?.run()
            bootstrapped = true
        }
    }

    fun register(sig: NativeSignature) {
        val prior = signatures.put(sig.name, sig)
        if (prior != null) {
            throw IllegalStateException(
                "duplicate native signature for ${sig.name}; second declaration site"
            )
        }
    }

    /** Returns the signature, or null when the op uses the legacy record path. */
    fun signatureOf(name: String): NativeSignature? {
        ensureBootstrapped()
        return signatures[name]
    }

    /** Snapshot of all registered names — useful for diagnostics. */
    fun knownNames(): Set<String> {
        ensureBootstrapped()
        return signatures.keys.toSet()
    }

    fun size(): Int {
        ensureBootstrapped()
        return signatures.size
    }
}
