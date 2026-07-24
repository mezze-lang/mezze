package com.mezze.interpreter.nodes;

import com.mezze.interpreter.MezzeTypes;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Base class for all Mezze AST nodes.
 *
 * Uses Truffle DSL TypeSystemReference for automatic type coercion
 * and specialization support.
 *
 * Carries an optional {@link SourceSection} for instrumentation:
 * Chrome Inspector breakpoints, CPU sampler line attribution, coverage,
 * JFR frame names, debugger line breakpoints + stepping. Set by
 * {@code TruffleCompiler} from each NodeIR's {@code SourceInfo}. Marked
 * {@code @CompilationFinal} so PE folds it as a constant — zero runtime cost.
 *
 * Implements {@link InstrumentableNode} so the GraalVM debugger (and tracer,
 * coverage, etc.) can attach probes. A node is instrumentable only when it has
 * a source section AND is tagged (see {@link #hasTag}); untagged nodes are
 * invisible to instrumentation and pay no wrapper cost. {@code @GenerateWrapper}
 * synthesizes the delegating wrapper for all four execute methods.
 */
@TypeSystemReference(MezzeTypes.class)
@NodeInfo(language = "mezze", description = "Base Mezze node")
@GenerateWrapper
public abstract class MezzeNode extends Node implements InstrumentableNode {

    @CompilationFinal
    private SourceSection sourceSection;

    /**
     * When true, this node carries the {@link StandardTags.StatementTag} —
     * a debugger step / line-breakpoint stop point. Set once at compile time
     * for the statement-level nodes (do-block statements, let values, call
     * results). Kept off by default so most expression nodes stay
     * non-instrumentable (no wrapper cost).
     */
    @CompilationFinal
    private boolean statementTag;

    /** Override Truffle's default null-returning getSourceSection. */
    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    /**
     * Set the source section for this node. Called once during compilation;
     * the field is {@code @CompilationFinal} so further changes invalidate
     * compiled code (which we never do — set-once after construction).
     */
    public void setSourceSection(SourceSection section) {
        this.sourceSection = section;
    }

    /** Mark this node as a statement stop-point for the debugger. */
    public void markStatement() {
        this.statementTag = true;
    }

    // ---- InstrumentableNode ----

    /**
     * Instrumentable only when tagged AND source-attributed. Untagged nodes
     * return false so Truffle never wraps them — the instrumentation surface
     * is exactly the statement-level nodes we opt in via {@link #markStatement}.
     */
    @Override
    public boolean isInstrumentable() {
        return statementTag && sourceSection != null;
    }

    @Override
    public boolean hasTag(Class<? extends com.oracle.truffle.api.instrumentation.Tag> tag) {
        return tag == StandardTags.StatementTag.class && statementTag;
    }

    @Override
    public WrapperNode createWrapper(ProbeNode probe) {
        return new MezzeNodeWrapper(this, probe);
    }

    /**
     * Execute this node and return the result.
     * This is the primary execution method.
     */
    public abstract Object execute(VirtualFrame frame);

    /**
     * Execute expecting a long result.
     * Throws UnexpectedResultException if result is not a long.
     */
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(result);
    }

    /**
     * Execute expecting a double result.
     * Throws UnexpectedResultException if result is not a double.
     */
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Double d) {
            return d;
        }
        // Implicit cast from long to double
        if (result instanceof Long l) {
            return l.doubleValue();
        }
        throw new UnexpectedResultException(result);
    }

    /**
     * Execute expecting a boolean result.
     * Throws UnexpectedResultException if result is not a boolean.
     */
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Boolean b) {
            return b;
        }
        throw new UnexpectedResultException(result);
    }

    /**
     * Get the Mezze language instance.
     */
    protected MezzeLanguage getLanguage() {
        return MezzeLanguage.get(this);
    }

    /**
     * Get the current Mezze context.
     */
    protected MezzeContext getContext() {
        return MezzeContext.get(this);
    }
}
