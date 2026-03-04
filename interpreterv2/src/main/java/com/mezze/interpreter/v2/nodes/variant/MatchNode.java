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
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.CountingConditionProfile;

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
    private final BranchProfile noMatchProfile = BranchProfile.create();

    // ===== CONS/NIL LIST PATTERN OPTIMIZATION =====
    // Pre-computed at construction time - NO runtime scanning

    // True if this match has Cons/Nil patterns (for MezzeList matching)
    @CompilationFinal
    private final boolean hasListPatterns;

    // Index of Cons branch, or -1 if none
    @CompilationFinal
    private final int consBranchIndex;

    // Index of Nil branch, or -1 if none
    @CompilationFinal
    private final int nilBranchIndex;

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

        // Pre-compute Cons/Nil pattern indices - O(N) once at construction, O(1) at runtime
        int consIdx = -1;
        int nilIdx = -1;
        for (int i = 0; i < branches.length; i++) {
            if (branches[i].isConsPattern()) {
                consIdx = i;
            } else if (branches[i].isNilPattern()) {
                nilIdx = i;
            }
        }
        this.consBranchIndex = consIdx;
        this.nilBranchIndex = nilIdx;
        this.hasListPatterns = (consIdx >= 0 || nilIdx >= 0);
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
            // Fast path: use precomputed hasListPatterns flag (O(1) check)
            if (hasListPatterns) {
                return executeListAsVariantMatchFast(frame, list);
            }
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
     * Falls back to linear search if guard/if-let fails.
     */
    private Object executeJumpTableMatch(VirtualFrame frame, MezzeVariant variant) {
        int tag = variant.getTag();

        // Tight bounds check using both minTag and maxTag
        if (tag < minTag || tag > maxTag) {
            // Out of bounds - use wildcard or error
            if (wildcardBranchIndex >= 0) {
                MatchBranchNode wildcard = branches[wildcardBranchIndex];
                wildcard.bindValue(frame, variant);
                Object result = wildcard.execute(frame);
                if (result != null || !wildcard.hasGuardsOrIfLet()) {
                    return result;
                }
            }
            noMatchProfile.enter();
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("No matching pattern for variant tag: " + tag);
        }

        // O(1) lookup
        int branchIndex = tagToIndex[tag];

        if (branchIndex >= 0) {
            // Direct branch hit
            MatchBranchNode branch = branches[branchIndex];
            branch.bindPayload(frame, variant);
            Object result = branch.execute(frame);
            // If guard/if-let failed, fall back to linear search
            if (result != null || !branch.hasGuardsOrIfLet()) {
                return result;
            }
            // Guard failed - fall through to linear search for remaining branches
            return executeVariantMatch(frame, variant);
        } else if (branchIndex == -1) {
            // Use wildcard
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, variant);
            Object result = wildcard.execute(frame);
            if (result != null || !wildcard.hasGuardsOrIfLet()) {
                return result;
            }
        }

        // -2 = no match
        noMatchProfile.enter();
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
                Object result = branch.execute(frame);
                // If branch has guards/if-let that failed, result is null - continue to next
                if (result != null || !branch.hasGuardsOrIfLet()) {
                    return result;
                }
                // Guard/if-let failed, continue to next branch
            }
        }

        // Try wildcard branch
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, variant);
            Object result = wildcard.execute(frame);
            if (result != null || !wildcard.hasGuardsOrIfLet()) {
                return result;
            }
        }

        // No match - this should be caught by typechecker
        noMatchProfile.enter();
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching pattern for variant tag: " + tag);
    }

    @ExplodeLoop
    private Object executeLiteralMatch(VirtualFrame frame, Object value) {
        // Try each branch for literal pattern matching
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];
            if (branchProfiles[i].profile(branch.matchesLiteral(value))) {
                // For wildcards, bind the value first
                if (branch.isWildcard()) {
                    branch.bindValue(frame, value);
                }
                Object result = branch.execute(frame);
                if (result != null || !branch.hasGuardsOrIfLet()) {
                    return result;
                }
                // Guard/if-let failed, continue to next branch
            }
        }

        // Try wildcard
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, value);
            Object result = wildcard.execute(frame);
            if (result != null || !wildcard.hasGuardsOrIfLet()) {
                return result;
            }
        }

        noMatchProfile.enter();
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

        noMatchProfile.enter();
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

        noMatchProfile.enter();
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching array pattern for list of size: " + list.size());
    }

    /**
     * Execute string interpolation pattern matching - OPTIMIZED.
     * Supports guards and if-let bindings on string patterns.
     */
    @ExplodeLoop
    private Object executeStringMatch(VirtualFrame frame, String str) {
        // Try each string pattern branch
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];

            if (branch.isStringPattern()) {
                if (branchProfiles[i].profile(branch.matchesString(str))) {
                    // Bind captures BEFORE checking guards/if-let
                    branch.bindStringCaptures(frame, str);
                    Object result = branch.execute(frame);
                    // If guard/if-let failed (returned null), continue to next branch
                    if (result != null || !branch.hasGuardsOrIfLet()) {
                        return result;
                    }
                    // Guard/if-let failed - continue to try next branch
                }
            }
        }

        // Fall back to literal matching for exact string matches
        for (int i = 0; i < branchCount; i++) {
            MatchBranchNode branch = branches[i];
            if (branchProfiles[i].profile(branch.matchesLiteral(str))) {
                Object result = branch.execute(frame);
                if (result != null || !branch.hasGuardsOrIfLet()) {
                    return result;
                }
            }
        }

        // Try wildcard
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, str);
            Object result = wildcard.execute(frame);
            if (result != null || !wildcard.hasGuardsOrIfLet()) {
                return result;
            }
        }

        noMatchProfile.enter();
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

        noMatchProfile.enter();
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("No matching record pattern");
    }

    // ===== CONS/NIL LIST PATTERN MATCHING - OPTIMIZED =====

    /**
     * FAST PATH: Match MezzeList against Cons/Nil patterns using precomputed branch indices.
     *
     * Optimizations:
     * 1. Uses precomputed consBranchIndex/nilBranchIndex - NO runtime scanning
     * 2. Direct array indexing O(1) instead of O(N) branch iteration
     * 3. Single isEmpty() check determines which branch to take
     * 4. Branch profiles for JIT optimization
     *
     * Cons { head, tail } matches non-empty list, binding head to first element, tail to rest.
     * Nil matches empty list.
     */
    private Object executeListAsVariantMatchFast(VirtualFrame frame, MezzeList list) {
        // Single check determines the branch - VERY fast
        if (list.isEmpty()) {
            // Empty list -> Nil pattern
            if (nilBranchIndex >= 0) {
                if (branchProfiles[nilBranchIndex].profile(true)) {
                    return branches[nilBranchIndex].execute(frame);
                }
            }
        } else {
            // Non-empty list -> Cons pattern
            if (consBranchIndex >= 0) {
                if (branchProfiles[consBranchIndex].profile(true)) {
                    branches[consBranchIndex].bindListConsPayload(frame, list);
                    return branches[consBranchIndex].execute(frame);
                }
            }
        }

        // Try wildcard
        if (wildcardBranchIndex >= 0) {
            MatchBranchNode wildcard = branches[wildcardBranchIndex];
            wildcard.bindValue(frame, list);
            return wildcard.execute(frame);
        }

        // Fall back to array pattern matching (for patterns like [a, b, ...rest])
        return executeArrayMatch(frame, list);
    }
}
