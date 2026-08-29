import com.oracle.svm.core.annotate.*;
import com.oracle.truffle.api.instrumentation.EventContext;

/**
 * Web Image generates INVALID WebAssembly for SandboxInstrument.checkAstDepth:
 *
 *   [wasm-validator error in function
 *    _SandboxInstrument_checkAstDepth___EventContext_SandboxContext_V]
 *   local.set's value type must be correct, on (local.set $14 (local.get $13))
 *
 * It is the ONLY invalid function in a module with 43,014 reachable methods.
 * checkAstDepth enforces a max-AST-depth resource limit that a browser
 * playground does not use, so no-op it to sidestep the backend codegen bug.
 *
 * SandboxContext is package-private, so it is reached via @TargetClass alias.
 */
@TargetClass(className = "com.oracle.truffle.sandbox.SandboxContext")
final class Target_com_oracle_truffle_sandbox_SandboxContext {
}

@TargetClass(className = "com.oracle.truffle.sandbox.SandboxInstrument")
final class Target_com_oracle_truffle_sandbox_SandboxInstrument {
    @Substitute
    private static void checkAstDepth(EventContext eventContext,
                                      Target_com_oracle_truffle_sandbox_SandboxContext ctx) {
        // no-op: AST-depth resource limit not enforced under Web Image
    }
}
