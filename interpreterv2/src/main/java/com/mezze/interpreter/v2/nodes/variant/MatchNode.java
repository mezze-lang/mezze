package com.mezze.interpreter.v2.nodes.variant;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Pattern match node for variants.
 *
 * Uses integer tag dispatch instead of string comparison.
 * Pattern matching becomes a simple switch on the tag value.
 */
@NodeInfo(shortName = "match")
public final class MatchNode extends MezzeNode {

    @Child
    private MezzeNode valueNode;

    @Children
    private final MatchBranchNode[] branches;

    // Index of the wildcard branch (if any), or -1
    @CompilationFinal
    private final int wildcardBranchIndex;

    public MatchNode(MezzeNode valueNode, MatchBranchNode[] branches, int wildcardBranchIndex) {
        this.valueNode = valueNode;
        this.branches = branches;
        this.wildcardBranchIndex = wildcardBranchIndex;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object value = valueNode.execute(frame);

        if (value instanceof MezzeVariant variant) {
            int tag = variant.getTag();

            // Try each branch in order
            for (int i = 0; i < branches.length; i++) {
                MatchBranchNode branch = branches[i];

                if (branch.matches(tag)) {
                    // Bind payload variables and execute branch body
                    branch.bindPayload(frame, variant);
                    return branch.execute(frame);
                }
            }

            // Try wildcard branch
            if (wildcardBranchIndex >= 0) {
                MatchBranchNode wildcard = branches[wildcardBranchIndex];
                wildcard.bindValue(frame, variant);
                return wildcard.execute(frame);
            }

            // No match - this should be caught by typechecker
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("No matching pattern for variant: " + variant);
        }

        // Non-variant matching (e.g., matching on Int, Bool)
        // Try each branch for literal pattern matching
        for (int i = 0; i < branches.length; i++) {
            MatchBranchNode branch = branches[i];
            if (branch.matchesLiteral(value)) {
                return branch.execute(frame);
            }
        }

        // Try wildcard
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, value);
            return wildcard.execute(frame);
        }

        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching pattern for value: " + value);
    }
}
