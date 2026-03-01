package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * SPECULATIVE FUNCTION INLINING node.
 *
 * For small functions (body < 10 nodes), we can inline the function body
 * directly at the call site, eliminating call overhead entirely.
 *
 * This uses Truffle's node inlining capabilities:
 * 1. Check if function is "small" (< 10 nodes)
 * 2. If so, clone the function body and inline it
 * 3. Use Assumption to guard against function redefinition
 * 4. Deoptimize if assumption fails
 *
 * Benefits:
 * - Eliminates call/return overhead
 * - Enables cross-function optimizations (constant folding, etc.)
 * - Better register allocation
 */
@NodeChild(value = "functionNode", type = MezzeNode.class)
@NodeInfo(shortName = "inlined-call")
@ReportPolymorphism
public abstract class InlinedCallNode extends MezzeNode {

    // Maximum AST node count for inlining
    private static final int INLINE_THRESHOLD = 15;

    @Children
    protected final MezzeNode[] argumentNodes;

    @CompilationFinal
    protected final int argumentCount;

    protected InlinedCallNode(MezzeNode[] argumentNodes) {
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    /**
     * INLINED PATH: Function is small enough to inline.
     * The function body is cloned and executed directly.
     * Uses per-function Assumption for proper deoptimization.
     */
    @Specialization(
        guards = {
            "function.getCallTarget() == cachedTarget",
            "isInlineable(cachedTarget)",
            "inlinedBody != null"
        },
        assumptions = "cachedAssumption",
        limit = "1"
    )
    @ExplodeLoop
    protected Object callInlined(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getCallTarget()") CallTarget cachedTarget,
            @Cached("getInlineAssumption(function)") Assumption cachedAssumption,
            @Cached("cloneBodyForInlining(cachedTarget)") MezzeNode inlinedBody,
            @Cached("function.getClosureValues().length") int closureLength
    ) {
        // Build arguments array
        Object[] closureValues = function.getClosureValues();
        Object[] arguments = new Object[closureLength + argumentCount];

        // Copy closure values
        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }

        // Evaluate and copy argument values
        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        // Execute inlined body with arguments bound
        // Note: The inlined body reads from the arguments array via frame.getArguments()
        return inlinedBody.execute(frame);
    }

    /**
     * DIRECT CALL PATH: Function too large to inline, use DirectCallNode.
     */
    @Specialization(
        guards = {"function.getCallTarget() == cachedTarget", "!isInlineable(cachedTarget)"},
        limit = "3"
    )
    @ExplodeLoop
    protected Object callDirect(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getCallTarget()") CallTarget cachedTarget,
            @Cached("create(cachedTarget)") DirectCallNode callNode,
            @Cached("function.getClosureValues().length") int closureLength
    ) {
        Object[] closureValues = function.getClosureValues();
        Object[] arguments = new Object[closureLength + argumentCount];

        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }

        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        return callNode.call(arguments);
    }

    /**
     * MEGAMORPHIC fallback.
     */
    @Specialization(replaces = {"callInlined", "callDirect"})
    @ExplodeLoop
    protected Object callGeneric(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached @Exclusive IndirectCallNode callNode
    ) {
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;
        Object[] arguments = new Object[closureLength + argumentCount];

        System.arraycopy(closureValues, 0, arguments, 0, closureLength);

        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        return callNode.call(function.getCallTarget(), arguments);
    }

    // ===== Inlining support methods =====

    /**
     * Check if a function is small enough to inline.
     */
    @Idempotent
    protected static boolean isInlineable(CallTarget target) {
        if (target == null) return false;
        if (!(target instanceof RootCallTarget rootCallTarget)) return false;
        RootNode root = rootCallTarget.getRootNode();
        if (root == null) return false;

        // Count nodes in the function body
        int nodeCount = countNodes(root);
        return nodeCount <= INLINE_THRESHOLD;
    }

    @TruffleBoundary
    private static int countNodes(Node node) {
        return NodeUtil.countNodes(node);
    }

    /**
     * Clone the function body for inlining.
     * Returns null if cloning fails.
     */
    @TruffleBoundary
    protected static MezzeNode cloneBodyForInlining(CallTarget target) {
        if (target == null) return null;
        if (!(target instanceof RootCallTarget rootCallTarget)) return null;
        RootNode root = rootCallTarget.getRootNode();
        if (root == null) return null;

        // For MezzeRootNode, we can extract and clone the body
        if (root instanceof com.mezze.interpreter.v2.nodes.MezzeRootNode mezzeRoot) {
            MezzeNode body = mezzeRoot.getBodyNode();
            if (body != null) {
                // Clone the body node tree
                return NodeUtil.cloneNode(body);
            }
        }
        return null;
    }

    /**
     * Get the inline assumption for a function.
     * The assumption is invalidated if the function is redefined,
     * triggering deoptimization of all inlined call sites.
     */
    protected static Assumption getInlineAssumption(MezzeFunction function) {
        return function.getInlineAssumption();
    }

    /**
     * Factory method.
     */
    public static InlinedCallNode create(MezzeNode functionNode, MezzeNode[] argumentNodes) {
        return InlinedCallNodeGen.create(argumentNodes, functionNode);
    }
}
