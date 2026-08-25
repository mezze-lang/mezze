package com.mezze.datastructures.iter;

/**
 * Descriptor tree node for a lazy Iter pipeline. Every stage in an
 * {@code iter::range(...).map(f).filter(p)…} chain is one immutable
 * {@code IterNode} — the source at the leaf, unary transforms above it.
 *
 * <p>Kept as an abstract base (not a sealed interface) because native-image
 * SVM's TruffleFeature pattern-matching prefers explicit subclass lists and
 * the descriptor tree is a closed set anyway (Range, UnaryOp for now;
 * Zip/Concat/Take/Drop can be added later without breaking existing
 * pattern-matching sites).
 *
 * <p>Instances are effectively final: all fields on subclasses are
 * {@code @CompilationFinal} so PE can constant-fold the descriptor when
 * fold's specializations pattern-match on it.
 */
public abstract class IterNode {
    /** Fresh descriptor construction — no state to inherit. */
    protected IterNode() {}
}
