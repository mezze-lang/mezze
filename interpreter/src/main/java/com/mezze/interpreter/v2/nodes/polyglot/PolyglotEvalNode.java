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
 * Evaluate code in a guest language.
 *
 * Note: eval() inherently requires @TruffleBoundary because it parses
 * source code at runtime. However, we cache the parsed result for
 * repeated evaluations of the same constant code string.
 *
 * For hot paths, use PolyglotExecuteNode with a pre-imported function
 * instead of repeated eval() calls.
 *
 * Example Mezze code:
 *   let result = Python.eval { code = "1 + 2 * 3" }
 */
@NodeInfo(shortName = "polyglot-eval")
public final class PolyglotEvalNode extends MezzeNode {

    @CompilationFinal
    private final String languageId;

    @Child
    private MezzeNode codeNode;

    // Cache for constant code strings
    @CompilationFinal
    private String cachedCode;

    @CompilationFinal
    private Object cachedResult;

    @CompilationFinal
    private boolean isConstant = false;

    @CompilationFinal
    private boolean checkedConstant = false;

    public PolyglotEvalNode(String languageId, MezzeNode codeNode) {
        this.languageId = languageId;
        this.codeNode = codeNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object codeValue = codeNode.execute(frame);
        if (!(codeValue instanceof String code)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Code must be a string");
        }

        // On first execution, check if code is constant
        if (!checkedConstant) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            checkedConstant = true;
            cachedCode = code;
            cachedResult = evalCode(code);
            isConstant = true;
            return cachedResult;
        }

        // If code is the same constant, return cached result
        // Identity check first for interned strings (fast path), then equality
        if (isConstant && (code == cachedCode || code.equals(cachedCode))) {
            return cachedResult;
        }

        // Code changed, mark as non-constant and eval fresh
        if (isConstant) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isConstant = false;
        }

        return evalCode(code);
    }

    @TruffleBoundary
    private Object evalCode(String code) {
        MezzeContext context = getContext();
        MezzePolyglotSupport polyglot = context.getPolyglotSupport();
        return polyglot.eval(languageId, code);
    }
}
