package com.mezze.interpreter.nodes.builtin;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

/**
 * Base class for per-call-site optimized native operations.
 *
 * Each subclass wraps one or more @Cached Truffle DSL nodes (from the
 * datastructures module) and dispatches to them based on runtime types
 * (e.g., LongSeqStorage vs DoubleSeqStorage vs ObjectSeqStorage). When the
 * runtime type matches a specialized DSL node, that node's optimizations
 * (unboxed arrays, @ExplodeLoop, DirectCallNode for HOF callbacks, branch
 * profiles, etc.) apply. Otherwise it falls back to a generic path
 * (typically the existing SeqNatives / ListNatives / MapNatives helper).
 *
 * Subclasses must:
 *   1. Declare @Child fields holding the DSL nodes they dispatch to
 *      (initialized lazily via @Cached or via direct create() calls).
 *   2. Implement execute(record, callNode) — extract args from the record,
 *      dispatch to the appropriate child, return the result.
 *
 * The fallback for unsupported runtime types should delegate to the same
 * code the lambda-based handler would have called, so behavior is identical
 * to the un-optimized path; the only difference is performance.
 */
public abstract class OptimizedNativeNode extends Node {

    /**
     * Execute this native operation against the given record argument.
     *
     * @param args     the argument record (already unwrapped by NativeDispatch)
     * @param callNode an IndirectCallNode shared with the dispatch site, for
     *                 fallback paths that need it (e.g., when there's no
     *                 cached DirectCallNode available for the user function)
     * @return the operation's result
     */
    public abstract Object execute(MezzeRecord args, IndirectCallNode callNode);
}
