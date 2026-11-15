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
            val hook = bootstrap
            // THE HOOK, OR THE TABLE THE BUILD BAKED. A process that has the
            // interpreter gets the declarations themselves; one that does not —
            // `mez` itself, which keeps Truffle out of its own JVM on purpose —
            // reads the same facts from a resource. Without the second path the
            // registry stays EMPTY for every user-code compile: see
            // [loadFromResource].
            if (hook != null) hook.run() else loadFromResource()
            bootstrapped = true
        }
    }

    /**
     * Registers a signature.
     *
     * An IDENTICAL re-registration is accepted, because the two paths above can
     * both run in one process: a test JVM, or the interpreter itself, has the
     * resource on its classpath AND loads the declarations. A CONFLICTING one
     * still throws — that is a genuine second declaration site, and two
     * disagreeing descriptions of one native's ABI would mis-order its
     * arguments.
     */
    fun register(sig: NativeSignature) {
        val prior = signatures.put(sig.name, sig)
        if (prior != null && prior != sig) {
            throw IllegalStateException(
                "conflicting native signature for ${sig.name}: $prior vs $sig"
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

    /** Where the build writes the table, on the classpath. */
    private const val RESOURCE = "/META-INF/mezze/native-signatures.tsv"

    /**
     * The table, as the build bakes it.
     *
     * WHY A RESOURCE AT ALL. These facts are declared in the interpreter, at
     * the same site as each handler, and published into this registry when
     * those classes load. `mez` never loads them: `:cli` keeps `:interpreter`
     * off its compile classpath (#220) and the interpreter ships in a separate
     * runtime jar, loaded lazily — because keeping Truffle out of the CLI's own
     * process is what keeps it starting quickly. `mez dev` goes further and
     * runs the program in a SUBPROCESS, so the JVM that lowers the code and the
     * JVM that has the natives are never the same one.
     *
     * The consequence was that every `CallNativeIR` emitted for USER code was
     * missing all three things the declaration provides — `pure`, so nothing
     * downstream could move or elide it; `inlineOp`, so the peephole could not
     * collapse `__native_int_subtract` into arithmetic; and `params`, so the
     * call went out on the legacy record ABI, building a record per call. The
     * stdlib never had the problem: `StdlibPrecompiler` loads the declarations
     * for its own run, and its comment says why.
     *
     * So the build writes the facts down as DATA, which crosses that boundary
     * without bringing Truffle with it. One format, defined here with its
     * reader, written by whoever has the declarations.
     */
    fun export(): String {
        ensureBootstrapped()
        return signatures.values
            .sortedBy { it.name }
            .joinToString("\n", postfix = "\n") { sig ->
                listOf(
                    sig.name,
                    sig.pure.toString(),
                    sig.inlineOp?.name ?: "-",
                    sig.returns.name,
                    sig.params.joinToString(",") { "${it.name}:${it.kind.name}" },
                ).joinToString("\t")
            }
    }

    /**
     * Read [export]'s output off the classpath. Absent is not an error: a build
     * that has not produced it yet, or a slim artifact that omits it, simply
     * gets the behaviour that was there before — the record ABI, which is slower
     * and correct. A MALFORMED line is skipped for the same reason; a
     * half-understood ABI description is worse than none, and the signature it
     * would have provided is exactly what makes a call positional.
     */
    private fun loadFromResource() {
        // Kill switch, so the difference this makes can be A/B'd on one build
        // the way every other compile-time pass here can.
        val stream = NativeSignatures::class.java.getResourceAsStream(RESOURCE) ?: return
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                if (parts.size != 5) continue
                val kind = runCatching { SlotKind.valueOf(parts[3]) }.getOrNull() ?: continue
                val op = if (parts[2] == "-") null else runCatching { InlineOp.valueOf(parts[2]) }.getOrNull()
                val params = if (parts[4].isEmpty()) emptyList() else parts[4].split(',').mapNotNull { p ->
                    val at = p.lastIndexOf(':')
                    if (at <= 0) return@mapNotNull null
                    val k = runCatching { SlotKind.valueOf(p.substring(at + 1)) }.getOrNull()
                        ?: return@mapNotNull null
                    NativeParam(p.substring(0, at), k)
                }
                if (parts[4].isNotEmpty() && params.size != parts[4].split(',').size) continue
                register(NativeSignature(parts[0], params, kind, parts[1] == "true", op))
            }
        }
    }
}
