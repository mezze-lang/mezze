package com.mezze.interpreter.v2.nodes.polyglot;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.runtime.MezzePolyglotSupport;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Import a module from a guest language.
 *
 * Note: import() inherently requires @TruffleBoundary because it loads
 * and initializes modules at runtime. However, we cache the imported
 * module for repeated imports of the same constant module name.
 *
 * Most imports happen at module initialization time (not in hot loops),
 * so the @TruffleBoundary here is acceptable.
 *
 * Example Mezze code:
 *   let np = Python.import { module = "numpy" }
 */
@NodeInfo(shortName = "polyglot-import")
public final class PolyglotImportNode extends MezzeNode {

    @CompilationFinal
    private final String languageId;

    @Child
    private MezzeNode moduleNameNode;

    // Cache for constant module names
    @CompilationFinal
    private String cachedModuleName;

    @CompilationFinal
    private Object cachedModule;

    @CompilationFinal
    private boolean isCached = false;

    public PolyglotImportNode(String languageId, MezzeNode moduleNameNode) {
        this.languageId = languageId;
        this.moduleNameNode = moduleNameNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object moduleNameValue = moduleNameNode.execute(frame);
        if (!(moduleNameValue instanceof String moduleName)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Module name must be a string");
        }

        // Check if we have a cached import for this module
        if (isCached && moduleName.equals(cachedModuleName)) {
            return cachedModule;
        }

        // Import and cache
        Object module = importModule(moduleName);

        if (!isCached) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedModuleName = moduleName;
            cachedModule = module;
            isCached = true;
        }

        return module;
    }

    @TruffleBoundary
    private Object importModule(String moduleName) {
        MezzeContext context = getContext();
        MezzePolyglotSupport polyglot = context.getPolyglotSupport();
        return polyglot.importModule(languageId, moduleName);
    }
}
