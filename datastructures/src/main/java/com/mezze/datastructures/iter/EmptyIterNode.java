package com.mezze.datastructures.iter;

/**
 * Descriptor node for an empty iterator ({@code iter::empty}).
 * Singleton — all empty iterators share one instance.
 */
public final class EmptyIterNode extends IterNode {

    public static final EmptyIterNode INSTANCE = new EmptyIterNode();

    private EmptyIterNode() {}
}
