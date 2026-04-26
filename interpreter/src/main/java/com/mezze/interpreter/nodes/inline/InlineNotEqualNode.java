package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
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
 * INLINABLE not-equal comparison - embeds directly into parent nodes.
 * Uses InlinedConditionProfile for identity fast-path.
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineNotEqualNode extends Node {

    public abstract boolean execute(Node node, Object left, Object right);

    @Specialization
    protected static boolean neLongs(long left, long right) {
        return left != right;
    }

    @Specialization
    protected static boolean neDoubles(double left, double right) {
        return left != right;
    }

    @Specialization
    protected static boolean neBooleans(boolean left, boolean right) {
        return left != right;
    }

    @Specialization
    protected static boolean neStrings(Node node, String left, String right,
            @Cached @Exclusive InlinedConditionProfile identityProfile) {
        if (identityProfile.profile(node, left == right)) {
            return false;
        }
        return !deepStringEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepStringEquals(String left, String right) {
        return left.equals(right);
    }

    @Specialization
    protected static boolean neRecords(Node node, MezzeRecord left, MezzeRecord right,
            @Cached @Exclusive InlinedConditionProfile identityProfile) {
        if (identityProfile.profile(node, left == right)) {
            return false;
        }
        return !deepRecordEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepRecordEquals(MezzeRecord left, MezzeRecord right) {
        return left.equals(right);
    }

    @Specialization
    protected static boolean neVariants(Node node, MezzeVariant left, MezzeVariant right,
            @Cached @Exclusive InlinedConditionProfile identityProfile,
            @Cached @Exclusive InlinedConditionProfile tagProfile) {
        if (identityProfile.profile(node, left == right)) {
            return false;
        }
        if (tagProfile.profile(node, left.getTag() != right.getTag())) {
            return true;
        }
        return !deepVariantEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepVariantEquals(MezzeVariant left, MezzeVariant right) {
        return left.equals(right);
    }

    @Fallback
    protected static boolean neObjects(Node node, Object left, Object right,
            @Cached @Exclusive InlinedConditionProfile identityProfile) {
        if (identityProfile.profile(node, left == right)) {
            return false;
        }
        if (left == null || right == null) {
            return true;
        }
        return !deepObjectEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepObjectEquals(Object left, Object right) {
        return left.equals(right);
    }
}
