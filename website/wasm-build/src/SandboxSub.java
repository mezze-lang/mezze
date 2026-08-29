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
 *
 * Guarded by onlyWith: the sandbox instrument ships in some GraalVM builds and
 * not others, and an unconditional substitution fails the build outright when
 * its target is absent.
 */
final class SandboxPresent implements java.util.function.BooleanSupplier {
    @Override
    public boolean getAsBoolean() {
        try {
            Class.forName("com.oracle.truffle.sandbox.SandboxInstrument", false,
                          SandboxPresent.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

@TargetClass(className = "com.oracle.truffle.sandbox.SandboxContext",
             onlyWith = SandboxPresent.class)
final class Target_com_oracle_truffle_sandbox_SandboxContext {
}

@TargetClass(className = "com.oracle.truffle.sandbox.SandboxInstrument",
             onlyWith = SandboxPresent.class)
final class Target_com_oracle_truffle_sandbox_SandboxInstrument {
    @Substitute
    private static void checkAstDepth(EventContext eventContext,
                                      Target_com_oracle_truffle_sandbox_SandboxContext ctx) {
        // no-op: AST-depth resource limit not enforced under Web Image
    }
}
