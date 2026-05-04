package com.mezze.interpreter.nodes;

import com.mezze.interpreter.runtime.MezzeContext;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Read a runtime-injected scope variable by name.
 *
 * Today this is only used for `scope`, which is registered as a local in the
 * MezzeContext by HandleNode (scoped effects) and read by handler operation
 * lambdas that execute in a different frame. Unlike ReadGlobalByHashNode, this
 * does NOT auto-evaluate thunks — `scope` is a runtime record value.
 *
 * The `varName` field is a real local variable name (e.g., "scope"), NOT a
 * content hash. Splitting this from ReadGlobalByHashNode disambiguates the
 * two namespaces at the IR layer.
 */
@NodeInfo(shortName = "read-scope")
public final class ReadScopeNode extends MezzeNode {

    @CompilationFinal
    private final String varName;

    public ReadScopeNode(String varName) {
        this.varName = varName;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return lookupInContext();
    }

    @TruffleBoundary
    private Object lookupInContext() {
        MezzeContext context = MezzeContext.get(this);
        Object value = context.lookupLocal(varName);
        if (value == null) {
            throw new RuntimeException("Scope variable '" + varName + "' not found");
        }
        return value;
    }
}
