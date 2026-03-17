package com.mezze.interpreter.v2.nodes.ability;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.language.ast.Keywords;
import com.mezze.language.ast.hashing.MezzeHash;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Node for Ability.for { it = value }.
 *
 * Returns a MezzeRecord containing all of the ability's operations as functions
 * with `it` (the receiver value) partially applied.
 *
 * Each operation is looked up by its own content hash (derived from the impl hash
 * and operation name) in the global context.
 *
 * Example:
 *   Show.for { it = 42 }
 *   → { show: <function with it=42 partially applied> }
 */
@NodeInfo(shortName = "ability-for")
public final class AbilityForNode extends MezzeNode {

    @Child private MezzeNode itValueNode;

    @CompilationFinal
    private final String implHash;

    @CompilationFinal(dimensions = 1)
    private final String[] operationNames;

    // Pre-computed content hashes for each operation
    @CompilationFinal(dimensions = 1)
    private final String[] operationHashes;

    private AbilityForNode(MezzeNode itValueNode, String implHash, String[] operationNames) {
        this.itValueNode = itValueNode;
        this.implHash = implHash;
        this.operationNames = operationNames;
        this.operationHashes = new String[operationNames.length];
        for (int i = 0; i < operationNames.length; i++) {
            this.operationHashes[i] = MezzeHash.Companion.operationHash(implHash, operationNames[i]);
        }
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object itValue = itValueNode.execute(frame);
        return buildBoundRecord(itValue);
    }

    /**
     * Build a record where each operation function has `it` partially applied.
     * Operations are looked up individually by their content hash.
     */
    @TruffleBoundary
    private MezzeRecord buildBoundRecord(Object itValue) {
        MezzeContext context = MezzeContext.get(this);
        Object[] boundFunctions = new Object[operationNames.length];

        for (int i = 0; i < operationNames.length; i++) {
            Object opFunc = context.lookupGlobal(operationHashes[i]);

            if (opFunc instanceof MezzeFunction func) {
                MezzeRecord itRecord = MezzeRecord.fromFields(
                    new String[]{Keywords.IT}, new Object[]{itValue}
                );
                boundFunctions[i] = func.withPartialArgs(itRecord);
            } else {
                boundFunctions[i] = opFunc;
            }
        }

        return MezzeRecord.fromFields(operationNames.clone(), boundFunctions);
    }

    public static AbilityForNode create(
            MezzeNode itValueNode,
            String implHash,
            String[] operationNames
    ) {
        return new AbilityForNode(itValueNode, implHash, operationNames);
    }
}
