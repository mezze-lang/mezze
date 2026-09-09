package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Descriptor node for user-defined iteration via {@code iter::unfold}.
 *
 * Stores the initial state and the step function. The step function has
 * Mezze signature {@code { s: 'state } -> Step 'elem 'state <...>} and
 * returns a variant: Yield { val, next } | Skip { next } | Done.
 *
 * The sink walker calls the step function in a loop, matching on the
 * variant tag to determine Yield/Skip/Done.
 */
public final class UnfoldIterNode extends IterNode {

    @CompilationFinal
    public final Object initialState;

    @CompilationFinal
    public final Object stepFunction;

    public UnfoldIterNode(Object initialState, Object stepFunction) {
        this.initialState = initialState;
        this.stepFunction = stepFunction;
    }
}
