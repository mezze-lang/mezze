package com.mezze.interpreter.v2.nodes.polyglot;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Invoke a method on a polyglot object - FULLY JIT-COMPILABLE.
 *
 * Uses polymorphic inline caching for up to 3 receiver types.
 * Beyond 3 types, falls back to uncached library dispatch.
 *
 * Example Mezze code:
 *   let np = Python.import { module = "numpy" }
 *   let arr = np.array { data = [1, 2, 3] }
 *   let mean = np.mean { a = arr }
 */
@NodeInfo(shortName = "polyglot-invoke")
public final class PolyglotInvokeNode extends MezzeNode {

    @CompilationFinal
    private final String methodName;

    @Child
    private MezzeNode receiverNode;

    @Children
    private final MezzeNode[] argumentNodes;

    // Polymorphic library caching - up to 3 receiver types
    private static final int LIBRARY_LIMIT = 3;

    @Child
    private InteropLibrary cachedLibrary1;
    @CompilationFinal
    private Class<?> cachedClass1;

    @Child
    private InteropLibrary cachedLibrary2;
    @CompilationFinal
    private Class<?> cachedClass2;

    @Child
    private InteropLibrary cachedLibrary3;
    @CompilationFinal
    private Class<?> cachedClass3;

    @CompilationFinal
    private int cacheCount = 0;

    @CompilationFinal
    private boolean megamorphic = false;

    // Error path profiles - marks these paths as unlikely for JIT optimization
    private final InlinedBranchProfile unsupportedMessageProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile unknownIdentifierProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile arityErrorProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile unsupportedTypeProfile = InlinedBranchProfile.getUncached();

    public PolyglotInvokeNode(String methodName, MezzeNode receiverNode, MezzeNode[] argumentNodes) {
        this.methodName = methodName;
        this.receiverNode = receiverNode;
        this.argumentNodes = argumentNodes;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object receiver = receiverNode.execute(frame);
        Class<?> receiverClass = receiver.getClass();

        // Evaluate arguments - @ExplodeLoop unrolls this
        Object[] arguments = new Object[argumentNodes.length];
        for (int i = 0; i < argumentNodes.length; i++) {
            arguments[i] = argumentNodes[i].execute(frame);
        }

        // Try cached libraries first (polymorphic inline caching)
        InteropLibrary lib = getCachedLibrary(receiver, receiverClass);

        return doInvoke(receiver, lib, arguments);
    }

    private InteropLibrary getCachedLibrary(Object receiver, Class<?> receiverClass) {
        // Fast path: check cached libraries
        if (cachedClass1 == receiverClass && cachedLibrary1 != null) {
            return cachedLibrary1;
        }
        if (cachedClass2 == receiverClass && cachedLibrary2 != null) {
            return cachedLibrary2;
        }
        if (cachedClass3 == receiverClass && cachedLibrary3 != null) {
            return cachedLibrary3;
        }

        // Slow path: add to cache or use uncached
        return addToCache(receiver, receiverClass);
    }

    private InteropLibrary addToCache(Object receiver, Class<?> receiverClass) {
        CompilerDirectives.transferToInterpreterAndInvalidate();

        if (megamorphic) {
            // Too many types, use uncached
            return InteropLibrary.getUncached(receiver);
        }

        // Try to add to cache
        if (cacheCount == 0) {
            cachedClass1 = receiverClass;
            cachedLibrary1 = insert(InteropLibrary.getFactory().create(receiver));
            cacheCount = 1;
            return cachedLibrary1;
        } else if (cacheCount == 1) {
            cachedClass2 = receiverClass;
            cachedLibrary2 = insert(InteropLibrary.getFactory().create(receiver));
            cacheCount = 2;
            return cachedLibrary2;
        } else if (cacheCount == 2) {
            cachedClass3 = receiverClass;
            cachedLibrary3 = insert(InteropLibrary.getFactory().create(receiver));
            cacheCount = 3;
            return cachedLibrary3;
        } else {
            // Cache full, go megamorphic
            megamorphic = true;
            return InteropLibrary.getUncached(receiver);
        }
    }

    private Object doInvoke(Object receiver, InteropLibrary lib, Object[] arguments) {
        try {
            return lib.invokeMember(receiver, methodName, arguments);
        } catch (UnsupportedMessageException e) {
            unsupportedMessageProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "Cannot invoke method on this object: " + receiver, e
            );
        } catch (UnknownIdentifierException e) {
            unknownIdentifierProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "Unknown method: " + methodName, e
            );
        } catch (ArityException e) {
            arityErrorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "Wrong number of arguments for method " + methodName +
                ": expected " + e.getExpectedMinArity() + ", got " + e.getActualArity(), e
            );
        } catch (UnsupportedTypeException e) {
            unsupportedTypeProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "Unsupported argument types for method " + methodName, e
            );
        }
    }
}
