package com.mezze.interpreter.v2.nodes.literal;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Fraction literal node - returns a MezzeRecord with numerator and denominator.
 *
 * Fractions in Mezze are represented as records: {numerator: Int, denominator: Int}
 * The value is @CompilationFinal so the JIT can inline this as a constant.
 */
@NodeInfo(shortName = "frac")
public final class FracLiteralNode extends MezzeNode {

    private static final String[] FRAC_FIELDS = new String[]{"numerator", "denominator"};

    @CompilationFinal
    private final long numerator;

    @CompilationFinal
    private final long denominator;

    // Cached record instance since fractions are immutable
    @CompilationFinal
    private MezzeRecord cachedRecord;

    public FracLiteralNode(long numerator, long denominator) {
        // Do NOT reduce the fraction at literal construction time.
        // Reduction should happen during operations, not at creation.
        // This matches V1 behavior where fractions are stored as-is.
        this.numerator = numerator;
        this.denominator = denominator;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (cachedRecord == null) {
            cachedRecord = MezzeRecord.fromFields(FRAC_FIELDS, new Object[]{numerator, denominator});
        }
        return cachedRecord;
    }

    @Override
    public double executeDouble(VirtualFrame frame) {
        return (double) numerator / (double) denominator;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
