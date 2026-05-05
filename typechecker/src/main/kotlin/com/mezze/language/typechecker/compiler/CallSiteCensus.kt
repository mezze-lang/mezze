package com.mezze.language.typechecker.compiler

/**
 * Static call-site census — populated as [TypedASTCompiler.compileCall]
 * emits IR. Dumped at the end of `compile()` when
 * `MEZZE_DISPATCH_CENSUS` is set. Mono "owns"
 * `inlineOp`/`methodConcrete`/`methodSingleVar`/`byHashMono`/`native`;
 * dict shows up in `methodDictCallee`/`byHashDictInject`.
 *
 * Diagnostic-only — never read by emitted IR. Use a dedicated env var
 * instead of `MEZZE_MONO_STATS` so per-module census output doesn't
 * conflate with mono driver tracing during normal stats runs.
 */
internal class CallSiteCensus {
    var inlineOp = 0          // Int.+, Str.size, etc. — inlined at compile time
    var methodConcrete = 0    // x.show {} where x has concrete type → CallDirect(opHash)
    var methodSingleVar = 0   // x.show {} VarType receiver but only one impl exists
    var methodDictCallee = 0  // DictMethodCallIR — read impl from dict slot
    var byHashMono = 0        // CallDirect(specializedHash) via monoCallSiteRewrites
    var byHashDictInject = 0  // CallDirect(genericHash) + injected __dict_* fields
    var byHashPlain = 0       // CallDirect(genericHash) — no dict, no specialization
    var native = 0            // CallNativeIR
    var indirect = 0          // CallIndirect — local/param/capture/lambda
    var globalThunk = 0       // CallDirect(hash, unit) — auto-call top-level thunk

    fun total() = inlineOp + methodConcrete + methodSingleVar +
        methodDictCallee + byHashMono + byHashDictInject + byHashPlain + native +
        indirect + globalThunk
}
