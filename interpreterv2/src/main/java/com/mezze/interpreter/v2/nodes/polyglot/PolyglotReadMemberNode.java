package com.mezze.interpreter.v2.nodes.polyglot;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Read a member/property from a polyglot object - FULLY JIT-COMPILABLE.
 *
 * Uses @CachedLibrary for polymorphic inline caching:
 * - Monomorphic (1 type): Fully inlined, O(1)
 * - Polymorphic (2-3 types): Inline cache with type guards
 * - Megamorphic (4+ types): Falls back to uncached library
 *
 * Example Mezze code:
 *   let np = Python.import { module = "numpy" }
 *   let version = np.version   // Read the 'version' property
 */
@NodeChild("receiverNode")
@NodeInfo(shortName = "polyglot-read")
public abstract class PolyglotReadMemberNode extends MezzeNode {

    @CompilationFinal
    protected final String memberName;

    protected PolyglotReadMemberNode(String memberName) {
        this.memberName = memberName;
    }

    /**
     * Cached path: inline cache for up to 3 receiver types.
     * The library is specialized per receiver, enabling full JIT inlining.
     */
    @Specialization(guards = "interop.hasMembers(receiver)", limit = "3")
    protected Object readCached(Object receiver,
            @CachedLibrary("receiver") InteropLibrary interop) {
        try {
            return interop.readMember(receiver, memberName);
        } catch (UnsupportedMessageException | UnknownIdentifierException e) {
            throw handleError(receiver, e);
        }
    }

    /**
     * Megamorphic fallback: uses uncached library.
     * This path is slower but handles any receiver type.
     */
    @Specialization(replaces = "readCached")
    @ReportPolymorphism.Megamorphic
    protected Object readUncached(Object receiver) {
        InteropLibrary interop = InteropLibrary.getUncached(receiver);
        try {
            return interop.readMember(receiver, memberName);
        } catch (UnsupportedMessageException | UnknownIdentifierException e) {
            throw handleError(receiver, e);
        }
    }

    @TruffleBoundary
    private RuntimeException handleError(Object receiver, Exception e) {
        if (e instanceof UnknownIdentifierException) {
            return new RuntimeException(
                "Member '" + memberName + "' not found on object: " + receiver, e
            );
        }
        return new RuntimeException(
            "Cannot read members from this object: " + receiver, e
        );
    }

    public static PolyglotReadMemberNode create(String memberName, MezzeNode receiverNode) {
        return PolyglotReadMemberNodeGen.create(memberName, receiverNode);
    }
}
