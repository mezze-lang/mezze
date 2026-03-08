package com.mezze.interpreter.v2.nodes.inline;

import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;

/**
 * INLINABLE equality comparison - embeds directly into parent nodes.
 * Uses InlinedConditionProfile for identity fast-path.
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineEqualNode extends Node {

    public abstract boolean execute(Node node, Object left, Object right);

    @Specialization
    protected static boolean eqLongs(long left, long right) {
        return left == right;
    }

    @Specialization
    protected static boolean eqDoubles(double left, double right) {
        return left == right;
    }

    @Specialization
    protected static boolean eqBooleans(boolean left, boolean right) {
        return left == right;
    }

    @Specialization
    protected static boolean eqStrings(Node node, String left, String right,
            @Cached @Exclusive InlinedConditionProfile identityProfile) {
        if (identityProfile.profile(node, left == right)) {
            return true;
        }
        return deepStringEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepStringEquals(String left, String right) {
        return left.equals(right);
    }

    @Specialization
    protected static boolean eqRecords(Node node, MezzeRecord left, MezzeRecord right,
            @Cached @Exclusive InlinedConditionProfile identityProfile) {
        if (identityProfile.profile(node, left == right)) {
            return true;
        }
        return deepRecordEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepRecordEquals(MezzeRecord left, MezzeRecord right) {
        return left.equals(right);
    }

    @Specialization
    protected static boolean eqVariants(Node node, MezzeVariant left, MezzeVariant right,
            @Cached @Exclusive InlinedConditionProfile identityProfile,
            @Cached @Exclusive InlinedConditionProfile tagProfile) {
        if (identityProfile.profile(node, left == right)) {
            return true;
        }
        if (tagProfile.profile(node, left.getTag() != right.getTag())) {
            return false;
        }
        return deepVariantEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepVariantEquals(MezzeVariant left, MezzeVariant right) {
        return left.equals(right);
    }

    @Fallback
    protected static boolean eqObjects(Node node, Object left, Object right,
            @Cached @Exclusive InlinedConditionProfile identityProfile) {
        if (identityProfile.profile(node, left == right)) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return deepObjectEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepObjectEquals(Object left, Object right) {
        return left.equals(right);
    }
}
