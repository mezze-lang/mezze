package com.mezze.language.interpreter.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

import com.mezze.language.interpreter.MezzeContext;
import com.mezze.language.interpreter.MezzeLanguage;
import com.mezze.language.interpreter.MezzeValue;

/**
 * Base class for all Mezze Truffle nodes.
 * Provides common functionality and access to language context.
 *
 * Written in Java to avoid Kotlin intrinsics that trigger JIT compilation blockers.
 */
public abstract class MezzeNode extends Node {

    /**
     * Execute this node and return a MezzeValue
     */
    public abstract MezzeValue execute(VirtualFrame frame);

    /**
     * Get the current Mezze language instance
     */
    protected MezzeLanguage getLanguage() {
        return MezzeLanguage.Companion.getCurrentLanguage(this);
    }

    /**
     * Get the current Mezze context
     */
    protected MezzeContext getContext() {
        return MezzeLanguage.Companion.getCurrentContext(this);
    }

    /**
     * Execute a child node safely.
     * Note: If child is null, this delegates to a @TruffleBoundary method to throw.
     */
    protected MezzeValue executeChild(MezzeNode child, VirtualFrame frame) {
        if (child != null) {
            return child.execute(frame);
        } else {
            return throwNullChildNode();
        }
    }

    @TruffleBoundary
    private MezzeValue throwNullChildNode() {
        throw new RuntimeException("Null child node");
    }

    /**
     * Execute multiple child nodes.
     * Uses explicit loop to avoid any potential library calls in JIT path.
     */
    protected MezzeValue[] executeChildren(MezzeNode[] children, VirtualFrame frame) {
        MezzeValue[] results = new MezzeValue[children.length];
        for (int i = 0; i < children.length; i++) {
            results[i] = children[i].execute(frame);
        }
        return results;
    }
}
