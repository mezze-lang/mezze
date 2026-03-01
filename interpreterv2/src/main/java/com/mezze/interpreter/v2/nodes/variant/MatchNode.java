package com.mezze.interpreter.v2.nodes.variant;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeList;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Pattern match node for variants - AGGRESSIVELY OPTIMIZED.
 *
 * Key optimizations:
 * 1. JUMP TABLE DISPATCH for dense variant tags (O(1) vs O(N))
 * 2. Uses integer tag dispatch instead of string comparison
 * 3. CountingConditionProfile per branch for branch prediction
 * 4. Pre-computed branch count for @ExplodeLoop
 * 5. Separate variant vs literal matching paths
 * 6. Direct array indexing when tags are 0..N contiguous
 *
 * Jump table architecture:
 * - If all variant branches have tags 0..maxTag with no gaps, use array indexing
 * - tagToIndex[tag] = branch index (or -1 for wildcard)
 * - Eliminates the linear search completely
 */
@NodeInfo(shortName = "match")
public final class MatchNode extends MezzeNode {

    @Child
    private MezzeNode valueNode;

    @Children
    private final MatchBranchNode[] branches;

    // Branch profiles for JIT optimization - one per branch
    @CompilationFinal(dimensions = 1)
    private final CountingConditionProfile[] branchProfiles;

    // Index of the wildcard branch (if any), or -1
    @CompilationFinal
    private final int wildcardBranchIndex;

    // Pre-computed branch count for @ExplodeLoop
    @CompilationFinal
    private final int branchCount;

    // ===== JUMP TABLE OPTIMIZATION =====
    // If true, use O(1) array indexing instead of O(N) linear search
    @CompilationFinal
    private final boolean useJumpTable;

    // Jump table: tag → branch index (only used if useJumpTable is true)
    // Value of -1 means use wildcard, -2 means no match
    @CompilationFinal(dimensions = 1)
    private final int[] tagToIndex;

    // Tag bounds (for tighter bounds checking)
    @CompilationFinal
    private final int minTag;

    @CompilationFinal
    private final int maxTag;

    // Error branch profile - tell JIT this path is cold
    private final InlinedBranchProfile noMatchProfile = InlinedBranchProfile.getUncached();

    public MatchNode(MezzeNode valueNode, MatchBranchNode[] branches, int wildcardBranchIndex) {
        this.valueNode = valueNode;
        this.branches = branches;
        this.wildcardBranchIndex = wildcardBranchIndex;
        this.branchCount = branches.length;

        // Create a profile for each branch
        this.branchProfiles = new CountingConditionProfile[branches.length];
        for (int i = 0; i < branches.length; i++) {
            this.branchProfiles[i] = CountingConditionProfile.create();
        }

        // Build jump table if possible
        JumpTableInfo info = buildJumpTable(branches, wildcardBranchIndex);
        this.useJumpTable = info.useJumpTable;
        this.tagToIndex = info.tagToIndex;
        this.minTag = info.minTag;
        this.maxTag = info.maxTag;
    }

    /**
     * Analyze branches and build jump table if beneficial.
     */
    private static JumpTableInfo buildJumpTable(MatchBranchNode[] branches, int wildcardBranchIndex) {
        // Find min/max tags and check for density
        int minTag = Integer.MAX_VALUE;
        int maxTag = -1;
        int variantBranchCount = 0;

        for (int i = 0; i < branches.length; i++) {
            int tag = branches[i].getTag();
            if (tag >= 0) { // Skip wildcards and literals
                variantBranchCount++;
                minTag = Math.min(minTag, tag);
                maxTag = Math.max(maxTag, tag);
            }
        }

        // Don't use jump table for:
        // - No variant branches (all literals/wildcards)
        // - Sparse tags (table would be too large)
        // - Very few branches (linear search is fine)
        if (variantBranchCount == 0 || maxTag < 0) {
            return new JumpTableInfo(false, null, 0, -1);
        }

        // Check density: use jump table if span is reasonable
        // (at most 2x the number of branches to avoid wasting memory)
        int span = maxTag - minTag + 1;
        if (span > variantBranchCount * 2 || span > 64) {
            return new JumpTableInfo(false, null, minTag, maxTag);
        }

        // Build the jump table
        int[] tagToIndex = new int[maxTag + 1];

        // Initialize with -2 (no match) or -1 (use wildcard)
        int defaultValue = wildcardBranchIndex >= 0 ? -1 : -2;
        for (int i = 0; i <= maxTag; i++) {
            tagToIndex[i] = defaultValue;
        }

        // Fill in branch indices
        for (int i = 0; i < branches.length; i++) {
            int tag = branches[i].getTag();
            if (tag >= 0) {
                tagToIndex[tag] = i;
            }
        }

        return new JumpTableInfo(true, tagToIndex, minTag, maxTag);
    }

    private static final class JumpTableInfo {
        final boolean useJumpTable;
        final int[] tagToIndex;
        final int minTag;
        final int maxTag;

        JumpTableInfo(boolean useJumpTable, int[] tagToIndex, int minTag, int maxTag) {
            this.useJumpTable = useJumpTable;
            this.tagToIndex = tagToIndex;
            this.minTag = minTag;
            this.maxTag = maxTag;
        }
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = valueNode.execute(frame);

        if (value instanceof MezzeVariant variant) {
            // Use jump table dispatch if available
            if (useJumpTable) {
                return executeJumpTableMatch(frame, variant);
            }
            return executeVariantMatch(frame, variant);
        }

        // Check for array pattern matching (V2 uses Object[] for arrays)
        if (value instanceof Object[] array) {
            return executeObjectArrayMatch(frame, array);
        }

        // Check for array pattern matching (MezzeList for immutable lists)
        if (value instanceof MezzeList list) {
            return executeArrayMatch(frame, list);
        }

        // Check for string pattern matching
        if (value instanceof String str) {
            return executeStringMatch(frame, str);
        }

        // Check for record pattern matching
        if (value instanceof MezzeRecord record) {
            return executeRecordMatch(frame, record);
        }

        return executeLiteralMatch(frame, value);
    }

    /**
     * O(1) JUMP TABLE DISPATCH - FASTEST PATH.
     * Direct array indexing from tag to branch.
     */
    private Object executeJumpTableMatch(VirtualFrame frame, MezzeVariant variant) {
        int tag = variant.getTag();

        // Tight bounds check using both minTag and maxTag
        if (tag < minTag || tag > maxTag) {
            // Out of bounds - use wildcard or error
            if (wildcardBranchIndex >= 0) {
                MatchBranchNode wildcard = branches[wildcardBranchIndex];
                wildcard.bindValue(frame, variant);
                return wildcard.execute(frame);
            }
            noMatchProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("No matching pattern for variant tag: " + tag);
        }

        // O(1) lookup
        int branchIndex = tagToIndex[tag];

        if (branchIndex >= 0) {
            // Direct branch hit
            MatchBranchNode branch = branches[branchIndex];
            branch.bindPayload(frame, variant);
            return branch.execute(frame);
        } else if (branchIndex == -1) {
            // Use wildcard
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, variant);
            return wildcard.execute(frame);
        }

        // -2 = no match
        noMatchProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching pattern for variant tag: " + tag);
    }

    @ExplodeLoop
    private Object executeVariantMatch(VirtualFrame frame, MezzeVariant variant) {
        int tag = variant.getTag();

        // Try each branch in order - @ExplodeLoop unrolls this
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];

            // Profile the branch condition for JIT optimization
            if (branchProfiles[i].profile(branch.matches(tag))) {
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
        noMatchProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching pattern for variant tag: " + tag);
    }

    @ExplodeLoop
    private Object executeLiteralMatch(VirtualFrame frame, Object value) {
        // Try each branch for literal pattern matching
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];
            if (branchProfiles[i].profile(branch.matchesLiteral(value))) {
                return branch.execute(frame);
            }
        }

        // Try wildcard
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, value);
            return wildcard.execute(frame);
        }

        noMatchProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching pattern for value: " + value);
    }

    /**
     * Execute array pattern matching for Object[] - V2 OPTIMIZED.
     * Uses @ExplodeLoop for JIT unrolling.
     */
    @ExplodeLoop
    private Object executeObjectArrayMatch(VirtualFrame frame, Object[] array) {
        // Try each array pattern branch
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];

            if (branch.isArrayPattern()) {
                if (branchProfiles[i].profile(branch.matchesObjectArray(array))) {
                    branch.bindObjectArrayElements(frame, array);
                    return branch.execute(frame);
                }
            }
        }

        // Try wildcard
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, array);
            return wildcard.execute(frame);
        }

        noMatchProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching array pattern for array of size: " + array.length);
    }

    /**
     * Execute array pattern matching - OPTIMIZED.
     * Uses @ExplodeLoop for JIT unrolling.
     */
    @ExplodeLoop
    private Object executeArrayMatch(VirtualFrame frame, MezzeList list) {
        // Try each array pattern branch
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];

            if (branch.isArrayPattern()) {
                if (branchProfiles[i].profile(branch.matchesArray(list))) {
                    branch.bindArrayElements(frame, list);
                    return branch.execute(frame);
                }
            }
        }

        // Try wildcard
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, list);
            return wildcard.execute(frame);
        }

        noMatchProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching array pattern for list of size: " + list.size());
    }

    /**
     * Execute string interpolation pattern matching - OPTIMIZED.
     */
    @ExplodeLoop
    private Object executeStringMatch(VirtualFrame frame, String str) {
        // Try each string pattern branch
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];

            if (branch.isStringPattern()) {
                if (branchProfiles[i].profile(branch.matchesString(str))) {
                    branch.bindStringCaptures(frame, str);
                    return branch.execute(frame);
                }
            }
        }

        // Fall back to literal matching for exact string matches
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];
            if (branchProfiles[i].profile(branch.matchesLiteral(str))) {
                return branch.execute(frame);
            }
        }

        // Try wildcard
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, str);
            return wildcard.execute(frame);
        }

        noMatchProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching string pattern for: " + str);
    }

    /**
     * Execute record pattern matching - OPTIMIZED.
     */
    @ExplodeLoop
    private Object executeRecordMatch(VirtualFrame frame, MezzeRecord record) {
        // Try each record pattern branch
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];

            if (branch.isRecordPattern()) {
                if (branchProfiles[i].profile(branch.matchesRecord(record))) {
                    branch.bindRecordFields(frame, record);
                    return branch.execute(frame);
                }
            }
        }

        // Try wildcard
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, record);
            return wildcard.execute(frame);
        }

        noMatchProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching record pattern");
    }
}
