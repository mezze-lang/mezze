package com.mezze.interpreter.v2.nodes.builtin;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Native function dispatch - OPTIMIZED for JIT.
 *
 * Uses a single switch statement that JIT compiles to a jump table.
 * Field extraction uses shape caching for O(1) access.
 *
 * Categories:
 * - Int operations (__native_int_*)
 * - Float operations (__native_float_*)
 * - String operations (__native_string_*)
 * - Bool operations (__native_bool_*)
 * - Console operations (__native_console_*)
 * - List operations (__native_list_*)
 * - Math operations (__native_float_sin, etc.)
 */
@NodeInfo(shortName = "native-dispatch")
public final class NativeFunctions extends MezzeNode {

    @CompilationFinal
    private final String functionName;

    @CompilationFinal
    private final int functionId;

    @Child
    private MezzeNode argumentNode;

    // Shape caching for record field access
    @CompilationFinal
    private MezzeRecord.Shape cachedShape;

    @CompilationFinal(dimensions = 1)
    private int[] cachedFieldIndices;

    // For higher-order functions (map, filter, fold, etc.)
    @Child
    private IndirectCallNode callNode;

    // Function IDs for jump table dispatch
    private static final int ID_UNKNOWN = 0;
    // Int operations (1-20)
    private static final int ID_INT_ADD = 1;
    private static final int ID_INT_SUBTRACT = 2;
    private static final int ID_INT_MULTIPLY = 3;
    private static final int ID_INT_DIVIDE = 4;
    private static final int ID_INT_MODULO = 5;
    private static final int ID_INT_TO_STRING = 6;
    private static final int ID_INT_ABS = 7;
    private static final int ID_INT_MIN = 8;
    private static final int ID_INT_MAX = 9;
    private static final int ID_INT_CLAMP = 10;
    private static final int ID_INT_SIGN = 11;
    private static final int ID_INT_NEG = 12;
    private static final int ID_INT_FROM_STRING = 13;
    private static final int ID_INT_FROM_NAT = 14;
    private static final int ID_INT_TO_FLOAT = 15;
    private static final int ID_INT_TO_NAT = 16;
    private static final int ID_INT_POW = 17;
    // Float operations (21-40)
    private static final int ID_FLOAT_TO_STRING = 21;
    private static final int ID_FLOAT_FROM_INT = 22;
    private static final int ID_FLOAT_FROM_STRING = 23;
    private static final int ID_FLOAT_ABS = 24;
    private static final int ID_FLOAT_MIN = 25;
    private static final int ID_FLOAT_MAX = 26;
    private static final int ID_FLOAT_CLAMP = 27;
    private static final int ID_FLOAT_SIGN = 28;
    private static final int ID_FLOAT_TO_INT = 29;
    private static final int ID_FLOAT_IS_NAN = 30;
    private static final int ID_FLOAT_IS_INFINITE = 31;
    private static final int ID_FLOAT_IS_FINITE = 32;
    private static final int ID_FLOAT_FLOOR = 33;
    private static final int ID_FLOAT_CEIL = 34;
    private static final int ID_FLOAT_ROUND = 35;
    private static final int ID_FLOAT_SQRT = 36;
    private static final int ID_FLOAT_POW = 37;
    private static final int ID_FLOAT_LN = 38;
    private static final int ID_FLOAT_LOG10 = 39;
    private static final int ID_FLOAT_EXP = 40;
    // Trig operations (41-50)
    private static final int ID_FLOAT_SIN = 41;
    private static final int ID_FLOAT_COS = 42;
    private static final int ID_FLOAT_TAN = 43;
    private static final int ID_FLOAT_ASIN = 44;
    private static final int ID_FLOAT_ACOS = 45;
    private static final int ID_FLOAT_ATAN = 46;
    private static final int ID_FLOAT_ATAN2 = 47;
    // String operations (51-80)
    private static final int ID_STRING_CONCAT = 51;
    private static final int ID_STRING_LENGTH = 52;
    private static final int ID_STRING_TO_UPPER = 53;
    private static final int ID_STRING_TO_LOWER = 54;
    private static final int ID_STRING_TRIM = 55;
    private static final int ID_STRING_SUBSTRING = 56;
    private static final int ID_STRING_CHAR_AT = 57;
    private static final int ID_STRING_INDEX_OF = 58;
    private static final int ID_STRING_CONTAINS = 59;
    private static final int ID_STRING_STARTS_WITH = 60;
    private static final int ID_STRING_ENDS_WITH = 61;
    private static final int ID_STRING_REPLACE = 62;
    private static final int ID_STRING_REPLACE_ALL = 63;
    private static final int ID_STRING_SPLIT = 64;
    private static final int ID_STRING_JOIN = 65;
    private static final int ID_STRING_REVERSE = 66;
    private static final int ID_STRING_LINES = 67;
    private static final int ID_STRING_WORDS = 68;
    private static final int ID_STRING_CHARS = 69;
    private static final int ID_STRING_PAD_LEFT = 70;
    private static final int ID_STRING_PAD_RIGHT = 71;
    private static final int ID_STRING_TRIM_START = 72;
    private static final int ID_STRING_TRIM_END = 73;
    private static final int ID_STRING_LAST_INDEX_OF = 74;
    private static final int ID_STRING_GLOB_MATCH = 75;
    private static final int ID_STRING_REPEAT = 76;
    // Bool operations (81-90)
    private static final int ID_BOOL_TO_STRING = 81;
    private static final int ID_BOOL_AND = 82;
    private static final int ID_BOOL_OR = 83;
    private static final int ID_BOOL_NOT = 84;
    private static final int ID_BOOL_XOR = 85;
    // Console operations (91-95)
    private static final int ID_CONSOLE_PRINT = 91;
    // List operations (101-130)
    private static final int ID_LIST_LENGTH = 101;
    private static final int ID_LIST_GET = 102;
    private static final int ID_LIST_HEAD = 103;
    private static final int ID_LIST_TAIL = 104;
    private static final int ID_LIST_CONS = 105;
    private static final int ID_LIST_CONCAT = 106;
    private static final int ID_LIST_REVERSE = 107;
    private static final int ID_LIST_IS_EMPTY = 108;
    private static final int ID_LIST_FROM_ARRAY = 109;
    private static final int ID_LIST_TO_ARRAY = 110;
    private static final int ID_LIST_MAP = 111;
    private static final int ID_LIST_FILTER = 112;
    private static final int ID_LIST_FOLD = 113;
    private static final int ID_LIST_TAKE = 114;
    private static final int ID_LIST_DROP = 115;
    private static final int ID_LIST_APPEND = 116;
    private static final int ID_LIST_LAST = 117;
    private static final int ID_LIST_INIT = 118;
    private static final int ID_LIST_ZIP = 119;
    private static final int ID_LIST_UNZIP = 120;
    private static final int ID_LIST_SORT = 121;
    private static final int ID_LIST_SORT_BY = 122;
    private static final int ID_LIST_EMPTY = 123;
    private static final int ID_LIST_UNCONS = 124;
    private static final int ID_LIST_RANGE = 125;
    private static final int ID_LIST_REPEAT = 126;
    private static final int ID_LIST_INTERSPERSE = 127;
    private static final int ID_LIST_FOLD_LEFT = 128;
    private static final int ID_LIST_FOLD_RIGHT = 129;
    private static final int ID_LIST_REDUCE = 130;
    private static final int ID_LIST_ANY = 131;
    private static final int ID_LIST_ALL = 132;
    private static final int ID_LIST_CONTAINS = 133;
    private static final int ID_LIST_FIND = 134;
    private static final int ID_LIST_FIND_INDEX = 135;
    private static final int ID_LIST_PARTITION = 136;
    private static final int ID_LIST_FLAT_MAP = 137;
    private static final int ID_LIST_EQUALS = 138;

    public NativeFunctions(String functionName, MezzeNode argumentNode) {
        this.functionName = functionName;
        this.functionId = getFunctionId(functionName);
        this.argumentNode = argumentNode;
        // Create indirect call node for higher-order functions
        this.callNode = IndirectCallNode.create();
    }

    @TruffleBoundary
    private static int getFunctionId(String name) {
        return switch (name) {
            // Int operations
            case "__native_int_add" -> ID_INT_ADD;
            case "__native_int_subtract" -> ID_INT_SUBTRACT;
            case "__native_int_multiply" -> ID_INT_MULTIPLY;
            case "__native_int_divide" -> ID_INT_DIVIDE;
            case "__native_int_modulo" -> ID_INT_MODULO;
            case "__native_int_to_string" -> ID_INT_TO_STRING;
            case "__native_int_abs" -> ID_INT_ABS;
            case "__native_int_min" -> ID_INT_MIN;
            case "__native_int_max" -> ID_INT_MAX;
            case "__native_int_clamp" -> ID_INT_CLAMP;
            case "__native_int_sign" -> ID_INT_SIGN;
            case "__native_int_neg" -> ID_INT_NEG;
            case "__native_int_from_string" -> ID_INT_FROM_STRING;
            case "__native_int_from_nat" -> ID_INT_FROM_NAT;
            case "__native_int_to_float" -> ID_INT_TO_FLOAT;
            case "__native_int_to_nat" -> ID_INT_TO_NAT;
            case "__native_int_pow" -> ID_INT_POW;
            // Float operations
            case "__native_float_to_string" -> ID_FLOAT_TO_STRING;
            case "__native_float_from_int" -> ID_FLOAT_FROM_INT;
            case "__native_float_from_string" -> ID_FLOAT_FROM_STRING;
            case "__native_float_abs" -> ID_FLOAT_ABS;
            case "__native_float_min" -> ID_FLOAT_MIN;
            case "__native_float_max" -> ID_FLOAT_MAX;
            case "__native_float_clamp" -> ID_FLOAT_CLAMP;
            case "__native_float_sign" -> ID_FLOAT_SIGN;
            case "__native_float_to_int" -> ID_FLOAT_TO_INT;
            case "__native_float_is_nan" -> ID_FLOAT_IS_NAN;
            case "__native_float_is_infinite" -> ID_FLOAT_IS_INFINITE;
            case "__native_float_is_finite" -> ID_FLOAT_IS_FINITE;
            case "__native_float_floor" -> ID_FLOAT_FLOOR;
            case "__native_float_ceil" -> ID_FLOAT_CEIL;
            case "__native_float_round" -> ID_FLOAT_ROUND;
            case "__native_float_sqrt" -> ID_FLOAT_SQRT;
            case "__native_float_pow" -> ID_FLOAT_POW;
            case "__native_float_ln" -> ID_FLOAT_LN;
            case "__native_float_log10" -> ID_FLOAT_LOG10;
            case "__native_float_exp" -> ID_FLOAT_EXP;
            // Trig operations
            case "__native_float_sin" -> ID_FLOAT_SIN;
            case "__native_float_cos" -> ID_FLOAT_COS;
            case "__native_float_tan" -> ID_FLOAT_TAN;
            case "__native_float_asin" -> ID_FLOAT_ASIN;
            case "__native_float_acos" -> ID_FLOAT_ACOS;
            case "__native_float_atan" -> ID_FLOAT_ATAN;
            case "__native_float_atan2" -> ID_FLOAT_ATAN2;
            // String operations
            case "__native_string_concat" -> ID_STRING_CONCAT;
            case "__native_string_length" -> ID_STRING_LENGTH;
            case "__native_string_to_upper" -> ID_STRING_TO_UPPER;
            case "__native_string_to_lower" -> ID_STRING_TO_LOWER;
            case "__native_string_trim" -> ID_STRING_TRIM;
            case "__native_string_substring" -> ID_STRING_SUBSTRING;
            case "__native_string_char_at" -> ID_STRING_CHAR_AT;
            case "__native_string_index_of" -> ID_STRING_INDEX_OF;
            case "__native_string_contains" -> ID_STRING_CONTAINS;
            case "__native_string_starts_with" -> ID_STRING_STARTS_WITH;
            case "__native_string_ends_with" -> ID_STRING_ENDS_WITH;
            case "__native_string_replace" -> ID_STRING_REPLACE;
            case "__native_string_replace_all" -> ID_STRING_REPLACE_ALL;
            case "__native_string_split" -> ID_STRING_SPLIT;
            case "__native_string_join" -> ID_STRING_JOIN;
            case "__native_string_reverse" -> ID_STRING_REVERSE;
            case "__native_string_lines" -> ID_STRING_LINES;
            case "__native_string_words" -> ID_STRING_WORDS;
            case "__native_string_chars" -> ID_STRING_CHARS;
            case "__native_string_pad_left" -> ID_STRING_PAD_LEFT;
            case "__native_string_pad_right" -> ID_STRING_PAD_RIGHT;
            case "__native_string_trim_start" -> ID_STRING_TRIM_START;
            case "__native_string_trim_end" -> ID_STRING_TRIM_END;
            case "__native_string_last_index_of" -> ID_STRING_LAST_INDEX_OF;
            case "__native_string_glob_match" -> ID_STRING_GLOB_MATCH;
            case "__native_string_repeat" -> ID_STRING_REPEAT;
            // Bool operations
            case "__native_bool_to_string" -> ID_BOOL_TO_STRING;
            case "__native_bool_and" -> ID_BOOL_AND;
            case "__native_bool_or" -> ID_BOOL_OR;
            case "__native_bool_not" -> ID_BOOL_NOT;
            case "__native_bool_xor" -> ID_BOOL_XOR;
            // Console operations
            case "__native_console_print" -> ID_CONSOLE_PRINT;
            // List operations
            case "__native_list_length" -> ID_LIST_LENGTH;
            case "__native_list_get" -> ID_LIST_GET;
            case "__native_list_head" -> ID_LIST_HEAD;
            case "__native_list_tail" -> ID_LIST_TAIL;
            case "__native_list_cons" -> ID_LIST_CONS;
            case "__native_list_concat" -> ID_LIST_CONCAT;
            case "__native_list_reverse" -> ID_LIST_REVERSE;
            case "__native_list_is_empty" -> ID_LIST_IS_EMPTY;
            case "__native_list_from_array" -> ID_LIST_FROM_ARRAY;
            case "__native_list_to_array" -> ID_LIST_TO_ARRAY;
            case "__native_list_map" -> ID_LIST_MAP;
            case "__native_list_filter" -> ID_LIST_FILTER;
            case "__native_list_fold" -> ID_LIST_FOLD;
            case "__native_list_take" -> ID_LIST_TAKE;
            case "__native_list_drop" -> ID_LIST_DROP;
            case "__native_list_append" -> ID_LIST_APPEND;
            case "__native_list_last" -> ID_LIST_LAST;
            case "__native_list_init" -> ID_LIST_INIT;
            case "__native_list_zip" -> ID_LIST_ZIP;
            case "__native_list_unzip" -> ID_LIST_UNZIP;
            case "__native_list_sort" -> ID_LIST_SORT;
            case "__native_list_sort_by" -> ID_LIST_SORT_BY;
            case "__native_list_empty" -> ID_LIST_EMPTY;
            case "__native_list_uncons" -> ID_LIST_UNCONS;
            case "__native_list_range" -> ID_LIST_RANGE;
            case "__native_list_repeat" -> ID_LIST_REPEAT;
            case "__native_list_intersperse" -> ID_LIST_INTERSPERSE;
            case "__native_list_fold_left" -> ID_LIST_FOLD_LEFT;
            case "__native_list_fold_right" -> ID_LIST_FOLD_RIGHT;
            case "__native_list_reduce" -> ID_LIST_REDUCE;
            case "__native_list_any" -> ID_LIST_ANY;
            case "__native_list_all" -> ID_LIST_ALL;
            case "__native_list_contains" -> ID_LIST_CONTAINS;
            case "__native_list_find" -> ID_LIST_FIND;
            case "__native_list_find_index" -> ID_LIST_FIND_INDEX;
            case "__native_list_partition" -> ID_LIST_PARTITION;
            case "__native_list_flat_map" -> ID_LIST_FLAT_MAP;
            case "__native_list_equals" -> ID_LIST_EQUALS;
            default -> ID_UNKNOWN;
        };
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object arg = argumentNode.execute(frame);

        if (!(arg instanceof MezzeRecord record)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(functionName + " expects record argument, got: " +
                (arg == null ? "null" : arg.getClass().getName()));
        }

        return dispatch(record);
    }

    private Object dispatch(MezzeRecord record) {
        // JIT compiles this to a jump table since functionId is @CompilationFinal
        return switch (functionId) {
            // Int operations
            case ID_INT_ADD -> intAdd(record);
            case ID_INT_SUBTRACT -> intSubtract(record);
            case ID_INT_MULTIPLY -> intMultiply(record);
            case ID_INT_DIVIDE -> intDivide(record);
            case ID_INT_MODULO -> intModulo(record);
            case ID_INT_TO_STRING -> intToString(record);
            case ID_INT_ABS -> intAbs(record);
            case ID_INT_MIN -> intMin(record);
            case ID_INT_MAX -> intMax(record);
            case ID_INT_SIGN -> intSign(record);
            case ID_INT_NEG -> intNeg(record);
            case ID_INT_TO_FLOAT -> intToFloat(record);
            case ID_INT_CLAMP -> intClamp(record);
            case ID_INT_FROM_STRING -> intFromString(record);
            case ID_INT_FROM_NAT -> intFromNat(record);
            case ID_INT_TO_NAT -> intToNat(record);
            case ID_INT_POW -> intPow(record);
            // Float operations
            case ID_FLOAT_TO_STRING -> floatToString(record);
            case ID_FLOAT_FROM_INT -> floatFromInt(record);
            case ID_FLOAT_ABS -> floatAbs(record);
            case ID_FLOAT_MIN -> floatMin(record);
            case ID_FLOAT_MAX -> floatMax(record);
            case ID_FLOAT_SQRT -> floatSqrt(record);
            case ID_FLOAT_FLOOR -> floatFloor(record);
            case ID_FLOAT_CEIL -> floatCeil(record);
            case ID_FLOAT_ROUND -> floatRound(record);
            case ID_FLOAT_SIN -> floatSin(record);
            case ID_FLOAT_COS -> floatCos(record);
            case ID_FLOAT_TAN -> floatTan(record);
            case ID_FLOAT_POW -> floatPow(record);
            // String operations
            case ID_STRING_CONCAT -> stringConcat(record);
            case ID_STRING_LENGTH -> stringLength(record);
            case ID_STRING_TO_UPPER -> stringToUpper(record);
            case ID_STRING_TO_LOWER -> stringToLower(record);
            case ID_STRING_TRIM -> stringTrim(record);
            case ID_STRING_SUBSTRING -> stringSubstring(record);
            case ID_STRING_CONTAINS -> stringContains(record);
            case ID_STRING_STARTS_WITH -> stringStartsWith(record);
            case ID_STRING_ENDS_WITH -> stringEndsWith(record);
            case ID_STRING_CHAR_AT -> stringCharAt(record);
            case ID_STRING_INDEX_OF -> stringIndexOf(record);
            case ID_STRING_REPLACE -> stringReplace(record);
            case ID_STRING_REPLACE_ALL -> stringReplaceAll(record);
            case ID_STRING_SPLIT -> stringSplit(record);
            case ID_STRING_JOIN -> stringJoin(record);
            case ID_STRING_REVERSE -> stringReverse(record);
            case ID_STRING_TRIM_START -> stringTrimStart(record);
            case ID_STRING_TRIM_END -> stringTrimEnd(record);
            case ID_STRING_REPEAT -> stringRepeat(record);
            // Bool operations
            case ID_BOOL_TO_STRING -> boolToString(record);
            case ID_BOOL_AND -> boolAnd(record);
            case ID_BOOL_OR -> boolOr(record);
            case ID_BOOL_NOT -> boolNot(record);
            case ID_BOOL_XOR -> boolXor(record);
            // Console operations
            case ID_CONSOLE_PRINT -> consolePrint(record);
            // List operations
            case ID_LIST_LENGTH -> listLength(record);
            case ID_LIST_IS_EMPTY -> listIsEmpty(record);
            case ID_LIST_FROM_ARRAY -> listFromArray(record);
            case ID_LIST_EMPTY -> listEmpty();
            case ID_LIST_HEAD -> listHead(record);
            case ID_LIST_TAIL -> listTail(record);
            case ID_LIST_CONS -> listCons(record);
            case ID_LIST_GET -> listGet(record);
            case ID_LIST_CONCAT -> listConcat(record);
            case ID_LIST_APPEND -> listAppend(record);
            case ID_LIST_REVERSE -> listReverse(record);
            case ID_LIST_TO_ARRAY -> listToArray(record);
            case ID_LIST_TAKE -> listTake(record);
            case ID_LIST_DROP -> listDrop(record);
            case ID_LIST_LAST -> listLast(record);
            case ID_LIST_INIT -> listInit(record);
            case ID_LIST_ZIP -> listZip(record);
            case ID_LIST_UNZIP -> listUnzip(record);
            case ID_LIST_SORT -> listSort(record);
            case ID_LIST_SORT_BY -> listSortBy(record);
            case ID_LIST_UNCONS -> listUncons(record);
            case ID_LIST_RANGE -> listRange(record);
            case ID_LIST_REPEAT -> listRepeat(record);
            case ID_LIST_INTERSPERSE -> listIntersperse(record);
            case ID_LIST_MAP -> listMap(record);
            case ID_LIST_FILTER -> listFilter(record);
            case ID_LIST_FOLD_LEFT -> listFoldLeft(record);
            case ID_LIST_FOLD_RIGHT -> listFoldRight(record);
            case ID_LIST_REDUCE -> listReduce(record);
            case ID_LIST_ANY -> listAny(record);
            case ID_LIST_ALL -> listAll(record);
            case ID_LIST_CONTAINS -> listContains(record);
            case ID_LIST_FIND -> listFind(record);
            case ID_LIST_FIND_INDEX -> listFindIndex(record);
            case ID_LIST_PARTITION -> listPartition(record);
            case ID_LIST_FLAT_MAP -> listFlatMap(record);
            case ID_LIST_EQUALS -> listEquals(record);
            default -> unknownFunction(record);
        };
    }

    // ==================== Int Operations ====================

    private long intAdd(MezzeRecord record) {
        long l = getLong(record, "l");
        long r = getLong(record, "r");
        return Math.addExact(l, r);
    }

    private long intSubtract(MezzeRecord record) {
        long l = getLong(record, "l");
        long r = getLong(record, "r");
        return Math.subtractExact(l, r);
    }

    private long intMultiply(MezzeRecord record) {
        long l = getLong(record, "l");
        long r = getLong(record, "r");
        return Math.multiplyExact(l, r);
    }

    private long intDivide(MezzeRecord record) {
        long l = getLong(record, "l");
        long r = getLong(record, "r");
        if (r == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Division by zero");
        }
        return l / r;
    }

    private long intModulo(MezzeRecord record) {
        long l = getLong(record, "l");
        long r = getLong(record, "r");
        if (r == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Modulo by zero");
        }
        return l % r;
    }

    private String intToString(MezzeRecord record) {
        long n = getLong(record, "n");
        return longToString(n);
    }

    @TruffleBoundary
    private static String longToString(long n) {
        return Long.toString(n);
    }

    private long intAbs(MezzeRecord record) {
        long n = getLong(record, "n");
        return Math.abs(n);
    }

    private long intMin(MezzeRecord record) {
        long l = getLong(record, "l");
        long r = getLong(record, "r");
        return Math.min(l, r);
    }

    private long intMax(MezzeRecord record) {
        long l = getLong(record, "l");
        long r = getLong(record, "r");
        return Math.max(l, r);
    }

    private long intSign(MezzeRecord record) {
        long n = getLong(record, "n");
        return Long.signum(n);
    }

    private long intNeg(MezzeRecord record) {
        long n = getLong(record, "n");
        return Math.negateExact(n);
    }

    private double intToFloat(MezzeRecord record) {
        long n = getLong(record, "n");
        return (double) n;
    }

    private long intClamp(MezzeRecord record) {
        long n = getLong(record, "n");
        long min = getLong(record, "min");
        long max = getLong(record, "max");
        return Math.max(min, Math.min(max, n));
    }

    private MezzeVariant intFromString(MezzeRecord record) {
        String s = getString(record, "s");
        return parseIntFromString(s);
    }

    @TruffleBoundary
    private static MezzeVariant parseIntFromString(String s) {
        try {
            long value = Long.parseLong(s.trim());
            return MezzeVariant.some(value);
        } catch (NumberFormatException e) {
            return MezzeVariant.none();
        }
    }

    private long intFromNat(MezzeRecord record) {
        // Nat is represented as long in V2
        long n = getLong(record, "n");
        return n;
    }

    private MezzeVariant intToNat(MezzeRecord record) {
        long n = getLong(record, "n");
        if (n >= 0) {
            return MezzeVariant.some(n);
        } else {
            return MezzeVariant.none();
        }
    }

    private long intPow(MezzeRecord record) {
        long base = getLong(record, "base");
        long exp = getLong(record, "exp");
        return longPow(base, exp);
    }

    private static long longPow(long base, long exp) {
        if (exp < 0) {
            return 0; // Integer power of negative exponent is 0
        }
        long result = 1;
        while (exp > 0) {
            if ((exp & 1) == 1) {
                result = Math.multiplyExact(result, base);
            }
            base = Math.multiplyExact(base, base);
            exp >>= 1;
        }
        return result;
    }

    // ==================== Float Operations ====================

    private String floatToString(MezzeRecord record) {
        double n = getDouble(record, "n");
        return doubleToString(n);
    }

    @TruffleBoundary
    private static String doubleToString(double n) {
        return Double.toString(n);
    }

    private double floatFromInt(MezzeRecord record) {
        long n = getLong(record, "n");
        return (double) n;
    }

    private double floatAbs(MezzeRecord record) {
        double n = getDouble(record, "n");
        return Math.abs(n);
    }

    private double floatMin(MezzeRecord record) {
        double l = getDouble(record, "l");
        double r = getDouble(record, "r");
        return Math.min(l, r);
    }

    private double floatMax(MezzeRecord record) {
        double l = getDouble(record, "l");
        double r = getDouble(record, "r");
        return Math.max(l, r);
    }

    private double floatSqrt(MezzeRecord record) {
        double n = getDouble(record, "n");
        return Math.sqrt(n);
    }

    private double floatFloor(MezzeRecord record) {
        double n = getDouble(record, "n");
        return Math.floor(n);
    }

    private double floatCeil(MezzeRecord record) {
        double n = getDouble(record, "n");
        return Math.ceil(n);
    }

    private double floatRound(MezzeRecord record) {
        double n = getDouble(record, "n");
        return Math.round(n);
    }

    private double floatSin(MezzeRecord record) {
        double n = getDouble(record, "n");
        return Math.sin(n);
    }

    private double floatCos(MezzeRecord record) {
        double n = getDouble(record, "n");
        return Math.cos(n);
    }

    private double floatTan(MezzeRecord record) {
        double n = getDouble(record, "n");
        return Math.tan(n);
    }

    private double floatPow(MezzeRecord record) {
        double base = getDouble(record, "base");
        double exp = getDouble(record, "exp");
        return Math.pow(base, exp);
    }

    // ==================== String Operations ====================

    private String stringConcat(MezzeRecord record) {
        String l = getString(record, "l");
        String r = getString(record, "r");
        return concatStrings(l, r);
    }

    @TruffleBoundary
    private static String concatStrings(String l, String r) {
        return l + r;
    }

    private long stringLength(MezzeRecord record) {
        String s = getString(record, "s");
        return s.length();
    }

    private String stringToUpper(MezzeRecord record) {
        String s = getString(record, "s");
        return toUpperCase(s);
    }

    @TruffleBoundary
    private static String toUpperCase(String s) {
        return s.toUpperCase();
    }

    private String stringToLower(MezzeRecord record) {
        String s = getString(record, "s");
        return toLowerCase(s);
    }

    @TruffleBoundary
    private static String toLowerCase(String s) {
        return s.toLowerCase();
    }

    private String stringTrim(MezzeRecord record) {
        String s = getString(record, "s");
        return trimString(s);
    }

    @TruffleBoundary
    private static String trimString(String s) {
        return s.trim();
    }

    private String stringSubstring(MezzeRecord record) {
        String s = getString(record, "s");
        long start = getLong(record, "start");
        long end = getLong(record, "end");
        return substringBoundary(s, (int) start, (int) end);
    }

    @TruffleBoundary
    private static String substringBoundary(String s, int start, int end) {
        // Clamp start to [0, length]
        int startIdx = Math.max(0, Math.min(start, s.length()));
        // Clamp end to [startIdx, length] - ensures end >= start
        int endIdx = Math.max(startIdx, Math.min(end, s.length()));
        return s.substring(startIdx, endIdx);
    }

    private boolean stringContains(MezzeRecord record) {
        String s = getString(record, "s");
        // The stdlib uses "substring" as the parameter name
        String substring = getString(record, "substring");
        return containsString(s, substring);
    }

    @TruffleBoundary
    private static boolean containsString(String s, String substring) {
        return s.contains(substring);
    }

    private boolean stringStartsWith(MezzeRecord record) {
        String s = getString(record, "s");
        String prefix = getString(record, "prefix");
        return startsWithString(s, prefix);
    }

    @TruffleBoundary
    private static boolean startsWithString(String s, String prefix) {
        return s.startsWith(prefix);
    }

    private boolean stringEndsWith(MezzeRecord record) {
        String s = getString(record, "s");
        String suffix = getString(record, "suffix");
        return endsWithString(s, suffix);
    }

    @TruffleBoundary
    private static boolean endsWithString(String s, String suffix) {
        return s.endsWith(suffix);
    }

    private String stringReplaceAll(MezzeRecord record) {
        String s = getString(record, "s");
        String old = getString(record, "old");
        String newStr = getString(record, "new");
        return replaceAllString(s, old, newStr);
    }

    @TruffleBoundary
    private static String replaceAllString(String s, String old, String newStr) {
        return s.replace(old, newStr);
    }

    private MezzeList stringSplit(MezzeRecord record) {
        String s = getString(record, "s");
        String delimiter = getString(record, "delimiter");
        return splitString(s, delimiter);
    }

    @TruffleBoundary
    private static MezzeList splitString(String s, String delimiter) {
        String[] parts = s.split(java.util.regex.Pattern.quote(delimiter));
        Object[] result = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parts[i];
        }
        return MezzeList.fromObjects(result);
    }

    private String stringJoin(MezzeRecord record) {
        MezzeList strings = getList(record, "strings");
        String separator = getString(record, "separator");
        return joinStrings(strings, separator);
    }

    @TruffleBoundary
    private static String joinStrings(MezzeList strings, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strings.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(strings.get(i));
        }
        return sb.toString();
    }

    private String stringReverse(MezzeRecord record) {
        String s = getString(record, "s");
        return reverseString(s);
    }

    @TruffleBoundary
    private static String reverseString(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    /**
     * Get character at index.
     * Returns: Option Char (Some {val = char} or None)
     * Char is represented as a single-character String in V2.
     */
    private MezzeVariant stringCharAt(MezzeRecord record) {
        String s = getString(record, "s");
        long index = getLong(record, "i");
        return charAtBoundary(s, (int) index);
    }

    @TruffleBoundary
    private static MezzeVariant charAtBoundary(String s, int index) {
        if (index < 0 || index >= s.length()) {
            return MezzeVariant.none();
        }
        // Return char as single-character String
        String charStr = String.valueOf(s.charAt(index));
        return MezzeVariant.some(charStr);
    }

    private MezzeVariant stringIndexOf(MezzeRecord record) {
        String s = getString(record, "s");
        String substring = getString(record, "sub");
        return indexOfBoundary(s, substring);
    }

    @TruffleBoundary
    private static MezzeVariant indexOfBoundary(String s, String substring) {
        int idx = s.indexOf(substring);
        if (idx < 0) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some((long) idx);
    }

    private String stringReplace(MezzeRecord record) {
        String s = getString(record, "s");
        String old = getString(record, "old");
        String newStr = getString(record, "new");
        return replaceFirstString(s, old, newStr);
    }

    @TruffleBoundary
    private static String replaceFirstString(String s, String old, String newStr) {
        int idx = s.indexOf(old);
        if (idx < 0) {
            return s;
        }
        return s.substring(0, idx) + newStr + s.substring(idx + old.length());
    }

    private String stringTrimStart(MezzeRecord record) {
        String s = getString(record, "s");
        return trimStartString(s);
    }

    @TruffleBoundary
    private static String trimStartString(String s) {
        return s.stripLeading();
    }

    private String stringTrimEnd(MezzeRecord record) {
        String s = getString(record, "s");
        return trimEndString(s);
    }

    @TruffleBoundary
    private static String trimEndString(String s) {
        return s.stripTrailing();
    }

    private String stringRepeat(MezzeRecord record) {
        String s = getString(record, "s");
        long times = getLong(record, "times");
        return repeatString(s, (int) times);
    }

    @TruffleBoundary
    private static String repeatString(String s, int times) {
        return s.repeat(Math.max(0, times));
    }

    // ==================== Bool Operations ====================

    private String boolToString(MezzeRecord record) {
        boolean b = getBoolean(record, "b");
        return b ? "True" : "False";
    }

    private boolean boolAnd(MezzeRecord record) {
        boolean l = getBoolean(record, "l");
        boolean r = getBoolean(record, "r");
        return l && r;
    }

    private boolean boolOr(MezzeRecord record) {
        boolean l = getBoolean(record, "l");
        boolean r = getBoolean(record, "r");
        return l || r;
    }

    private boolean boolNot(MezzeRecord record) {
        boolean b = getBoolean(record, "b");
        return !b;
    }

    private boolean boolXor(MezzeRecord record) {
        boolean l = getBoolean(record, "l");
        boolean r = getBoolean(record, "r");
        return l ^ r;
    }

    // ==================== Console Operations ====================

    private Object consolePrint(MezzeRecord record) {
        String msg = getString(record, "msg");
        doPrint(msg);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    private static void doPrint(String msg) {
        System.out.print(msg);
    }

    // ==================== List Operations ====================

    private long listLength(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        return list.size();
    }

    private boolean listIsEmpty(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        return list.isEmpty();
    }

    private MezzeList listFromArray(MezzeRecord record) {
        MezzeList arr = getList(record, "xs");
        return arr; // Already a list
    }

    private MezzeList listEmpty() {
        return MezzeList.empty();
    }

    private MezzeVariant listHead(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.get(0));
    }

    private MezzeVariant listTail(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.tail());
    }

    private MezzeList listCons(MezzeRecord record) {
        Object head = getField(record, "head");
        MezzeList tail = getList(record, "tail");
        return tail.prepend(head);
    }

    private MezzeVariant listGet(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        long index = getLong(record, "index");
        if (index < 0 || index >= list.size()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.get((int) index));
    }

    private MezzeList listConcat(MezzeRecord record) {
        // concat takes a list of lists and flattens them
        MezzeList xss = getList(record, "xss");
        return listConcatImpl(xss);
    }

    @TruffleBoundary
    private MezzeList listConcatImpl(MezzeList xss) {
        MezzeList result = MezzeList.empty();
        for (int i = 0; i < xss.size(); i++) {
            MezzeList inner = (MezzeList) xss.get(i);
            result = result.concat(inner);
        }
        return result;
    }

    private MezzeList listAppend(MezzeRecord record) {
        MezzeList xs = getList(record, "xs");
        MezzeList ys = getList(record, "ys");
        return xs.concat(ys);
    }

    private MezzeList listReverse(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        return listReverseImpl(list);
    }

    @TruffleBoundary
    private MezzeList listReverseImpl(MezzeList list) {
        Object[] arr = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[list.size() - 1 - i] = list.get(i);
        }
        return MezzeList.fromObjects(arr);
    }

    private MezzeList listToArray(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        return list; // In V2, List is already the array type
    }

    private MezzeList listTake(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        long n = getLong(record, "n");
        int count = (int) Math.max(0, Math.min(n, list.size()));
        return list.slice(0, count);
    }

    private MezzeList listDrop(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        long n = getLong(record, "n");
        int skip = (int) Math.max(0, Math.min(n, list.size()));
        return list.slice(skip, list.size());
    }

    private MezzeVariant listLast(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.get(list.size() - 1));
    }

    private MezzeList listInit(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        if (list.isEmpty()) {
            return MezzeList.empty();
        }
        return list.slice(0, list.size() - 1);
    }

    private MezzeList listZip(MezzeRecord record) {
        MezzeList xs = getList(record, "xs");
        MezzeList ys = getList(record, "ys");
        return listZipImpl(xs, ys);
    }

    @TruffleBoundary
    private MezzeList listZipImpl(MezzeList xs, MezzeList ys) {
        int len = Math.min(xs.size(), ys.size());
        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = MezzeRecord.fromFields(
                new String[]{"fst", "snd"},
                new Object[]{xs.get(i), ys.get(i)}
            );
        }
        return MezzeList.fromObjects(result);
    }

    private MezzeRecord listUnzip(MezzeRecord record) {
        MezzeList xs = getList(record, "xs");
        return listUnzipImpl(xs);
    }

    @TruffleBoundary
    private MezzeRecord listUnzipImpl(MezzeList xs) {
        Object[] fsts = new Object[xs.size()];
        Object[] snds = new Object[xs.size()];
        for (int i = 0; i < xs.size(); i++) {
            MezzeRecord pair = (MezzeRecord) xs.get(i);
            int fstIdx = pair.getFieldIndex("fst");
            int sndIdx = pair.getFieldIndex("snd");
            fsts[i] = pair.getField(fstIdx);
            snds[i] = pair.getField(sndIdx);
        }
        return MezzeRecord.fromFields(
            new String[]{"fsts", "snds"},
            new Object[]{MezzeList.fromObjects(fsts), MezzeList.fromObjects(snds)}
        );
    }

    private MezzeList listSort(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        return listSortImpl(list);
    }

    @TruffleBoundary
    private MezzeList listSortImpl(MezzeList list) {
        Object[] arr = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        Arrays.sort(arr, (a, b) -> {
            if (a instanceof Long la && b instanceof Long lb) return Long.compare(la, lb);
            if (a instanceof Double da && b instanceof Double db) return Double.compare(da, db);
            if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
            return 0;
        });
        return MezzeList.fromObjects(arr);
    }

    private MezzeList listSortBy(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction cmp = getFunction(record, "cmp");
        return listSortByImpl(list, cmp);
    }

    @TruffleBoundary
    private MezzeList listSortByImpl(MezzeList list, MezzeFunction cmp) {
        Object[] arr = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        Arrays.sort(arr, (a, b) -> {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"a", "b"},
                new Object[]{a, b}
            );
            Object result = callNode.call(cmp.getCallTarget(),
                prepareArgsForFunction(cmp, arg));
            return ((Long) result).intValue();
        });
        return MezzeList.fromObjects(arr);
    }

    private MezzeVariant listUncons(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        MezzeRecord result = MezzeRecord.fromFields(
            new String[]{"head", "tail"},
            new Object[]{list.get(0), list.tail()}
        );
        return MezzeVariant.some(result);
    }

    private MezzeList listRange(MezzeRecord record) {
        long start = getLong(record, "start");
        long end = getLong(record, "end");
        return listRangeImpl(start, end);
    }

    @TruffleBoundary
    private MezzeList listRangeImpl(long start, long end) {
        int size = (int) Math.max(0, end - start);
        long[] arr = new long[size];
        for (int i = 0; i < size; i++) {
            arr[i] = start + i;
        }
        return MezzeList.fromLongs(arr);
    }

    private MezzeList listRepeat(MezzeRecord record) {
        Object x = getField(record, "x");
        long n = getLong(record, "n");
        return listRepeatImpl(x, n);
    }

    @TruffleBoundary
    private MezzeList listRepeatImpl(Object x, long n) {
        int size = (int) Math.max(0, n);
        Object[] arr = new Object[size];
        Arrays.fill(arr, x);
        return MezzeList.fromObjects(arr);
    }

    private MezzeList listIntersperse(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        Object sep = getField(record, "sep");
        return listIntersperseImpl(list, sep);
    }

    @TruffleBoundary
    private MezzeList listIntersperseImpl(MezzeList list, Object sep) {
        if (list.size() <= 1) {
            return list;
        }
        Object[] arr = new Object[list.size() * 2 - 1];
        for (int i = 0; i < list.size(); i++) {
            arr[i * 2] = list.get(i);
            if (i < list.size() - 1) {
                arr[i * 2 + 1] = sep;
            }
        }
        return MezzeList.fromObjects(arr);
    }

    // ===== Higher-Order List Functions =====

    private MezzeList listMap(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction f = getFunction(record, "f");
        return listMapImpl(list, f);
    }

    @TruffleBoundary
    private MezzeList listMapImpl(MezzeList list, MezzeFunction f) {
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"x"},
                new Object[]{list.get(i)}
            );
            result[i] = callNode.call(f.getCallTarget(),
                prepareArgsForFunction(f, arg));
        }
        return MezzeList.fromObjectsSmart(result);
    }

    private MezzeList listFilter(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction p = getFunction(record, "p");
        return listFilterImpl(list, p);
    }

    @TruffleBoundary
    private MezzeList listFilterImpl(MezzeList list, MezzeFunction p) {
        ArrayList<Object> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"x"},
                new Object[]{elem}
            );
            Object keep = callNode.call(p.getCallTarget(),
                prepareArgsForFunction(p, arg));
            if (keep instanceof Boolean && (Boolean) keep) {
                result.add(elem);
            }
        }
        return MezzeList.fromObjects(result.toArray());
    }

    private Object listFoldLeft(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        Object init = getField(record, "init");
        MezzeFunction f = getFunction(record, "f");
        return listFoldLeftImpl(list, init, f);
    }

    @TruffleBoundary
    private Object listFoldLeftImpl(MezzeList list, Object init, MezzeFunction f) {
        Object acc = init;
        for (int i = 0; i < list.size(); i++) {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"acc", "x"},
                new Object[]{acc, list.get(i)}
            );
            acc = callNode.call(f.getCallTarget(),
                prepareArgsForFunction(f, arg));
        }
        return acc;
    }

    private Object listFoldRight(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        Object init = getField(record, "init");
        MezzeFunction f = getFunction(record, "f");
        return listFoldRightImpl(list, init, f);
    }

    @TruffleBoundary
    private Object listFoldRightImpl(MezzeList list, Object init, MezzeFunction f) {
        Object acc = init;
        for (int i = list.size() - 1; i >= 0; i--) {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"x", "acc"},
                new Object[]{list.get(i), acc}
            );
            acc = callNode.call(f.getCallTarget(),
                prepareArgsForFunction(f, arg));
        }
        return acc;
    }

    private MezzeVariant listReduce(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction f = getFunction(record, "f");
        return listReduceImpl(list, f);
    }

    @TruffleBoundary
    private MezzeVariant listReduceImpl(MezzeList list, MezzeFunction f) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        Object acc = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"acc", "x"},
                new Object[]{acc, list.get(i)}
            );
            acc = callNode.call(f.getCallTarget(),
                prepareArgsForFunction(f, arg));
        }
        return MezzeVariant.some(acc);
    }

    private boolean listAny(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction p = getFunction(record, "p");
        return listAnyImpl(list, p);
    }

    @TruffleBoundary
    private boolean listAnyImpl(MezzeList list, MezzeFunction p) {
        for (int i = 0; i < list.size(); i++) {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"x"},
                new Object[]{list.get(i)}
            );
            Object result = callNode.call(p.getCallTarget(),
                prepareArgsForFunction(p, arg));
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    private boolean listAll(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction p = getFunction(record, "p");
        return listAllImpl(list, p);
    }

    @TruffleBoundary
    private boolean listAllImpl(MezzeList list, MezzeFunction p) {
        for (int i = 0; i < list.size(); i++) {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"x"},
                new Object[]{list.get(i)}
            );
            Object result = callNode.call(p.getCallTarget(),
                prepareArgsForFunction(p, arg));
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    private boolean listContains(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        Object x = getField(record, "x");
        return listContainsImpl(list, x);
    }

    @TruffleBoundary
    private boolean listContainsImpl(MezzeList list, Object x) {
        for (int i = 0; i < list.size(); i++) {
            if (deepEquals(list.get(i), x)) {
                return true;
            }
        }
        return false;
    }

    private MezzeVariant listFind(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction p = getFunction(record, "p");
        return listFindImpl(list, p);
    }

    @TruffleBoundary
    private MezzeVariant listFindImpl(MezzeList list, MezzeFunction p) {
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"x"},
                new Object[]{elem}
            );
            Object result = callNode.call(p.getCallTarget(),
                prepareArgsForFunction(p, arg));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(elem);
            }
        }
        return MezzeVariant.none();
    }

    private MezzeVariant listFindIndex(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction p = getFunction(record, "p");
        return listFindIndexImpl(list, p);
    }

    @TruffleBoundary
    private MezzeVariant listFindIndexImpl(MezzeList list, MezzeFunction p) {
        for (int i = 0; i < list.size(); i++) {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"x"},
                new Object[]{list.get(i)}
            );
            Object result = callNode.call(p.getCallTarget(),
                prepareArgsForFunction(p, arg));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some((long) i);
            }
        }
        return MezzeVariant.none();
    }

    private MezzeRecord listPartition(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction p = getFunction(record, "p");
        return listPartitionImpl(list, p);
    }

    @TruffleBoundary
    private MezzeRecord listPartitionImpl(MezzeList list, MezzeFunction p) {
        ArrayList<Object> matching = new ArrayList<>();
        ArrayList<Object> notMatching = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"x"},
                new Object[]{elem}
            );
            Object result = callNode.call(p.getCallTarget(),
                prepareArgsForFunction(p, arg));
            if (result instanceof Boolean && (Boolean) result) {
                matching.add(elem);
            } else {
                notMatching.add(elem);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"matching", "not_matching"},
            new Object[]{
                MezzeList.fromObjects(matching.toArray()),
                MezzeList.fromObjects(notMatching.toArray())
            }
        );
    }

    private MezzeList listFlatMap(MezzeRecord record) {
        MezzeList list = getList(record, "xs");
        MezzeFunction f = getFunction(record, "f");
        return listFlatMapImpl(list, f);
    }

    @TruffleBoundary
    private MezzeList listFlatMapImpl(MezzeList list, MezzeFunction f) {
        ArrayList<Object> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MezzeRecord arg = MezzeRecord.fromFields(
                new String[]{"x"},
                new Object[]{list.get(i)}
            );
            Object mapped = callNode.call(f.getCallTarget(),
                prepareArgsForFunction(f, arg));
            MezzeList inner = (MezzeList) mapped;
            for (int j = 0; j < inner.size(); j++) {
                result.add(inner.get(j));
            }
        }
        return MezzeList.fromObjects(result.toArray());
    }

    private boolean listEquals(MezzeRecord record) {
        MezzeList l = getList(record, "l");
        MezzeList r = getList(record, "r");
        return listEqualsImpl(l, r);
    }

    @TruffleBoundary
    private boolean listEqualsImpl(MezzeList l, MezzeList r) {
        if (l.size() != r.size()) {
            return false;
        }
        for (int i = 0; i < l.size(); i++) {
            if (!deepEquals(l.get(i), r.get(i))) {
                return false;
            }
        }
        return true;
    }

    // Helper method for calling functions with closure values
    private Object[] prepareArgsForFunction(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = arg;
        return args;
    }

    @TruffleBoundary
    private static boolean deepEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Long la && b instanceof Long lb) return la.equals(lb);
        if (a instanceof Double da && b instanceof Double db) return da.equals(db);
        if (a instanceof String sa && b instanceof String sb) return sa.equals(sb);
        if (a instanceof Boolean ba && b instanceof Boolean bb) return ba.equals(bb);
        if (a instanceof MezzeRecord ra && b instanceof MezzeRecord rb) return ra.equals(rb);
        if (a instanceof MezzeVariant va && b instanceof MezzeVariant vb) return va.equals(vb);
        if (a instanceof MezzeList la && b instanceof MezzeList lb) {
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) {
                if (!deepEquals(la.get(i), lb.get(i))) return false;
            }
            return true;
        }
        return false;
    }

    // ==================== Field Accessors ====================

    private long getLong(MezzeRecord record, String fieldName) {
        Object value = getField(record, fieldName);
        if (value instanceof Long l) return l;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException(functionName + " expects '" + fieldName + "' as Long");
    }

    private double getDouble(MezzeRecord record, String fieldName) {
        Object value = getField(record, fieldName);
        if (value instanceof Double d) return d;
        if (value instanceof Long l) return l.doubleValue();
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException(functionName + " expects '" + fieldName + "' as Double");
    }

    private String getString(MezzeRecord record, String fieldName) {
        Object value = getField(record, fieldName);
        if (value instanceof String s) return s;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException(functionName + " expects '" + fieldName + "' as String");
    }

    private boolean getBoolean(MezzeRecord record, String fieldName) {
        Object value = getField(record, fieldName);
        if (value instanceof Boolean b) return b;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException(functionName + " expects '" + fieldName + "' as Boolean");
    }

    private MezzeList getList(MezzeRecord record, String fieldName) {
        Object value = getField(record, fieldName);
        if (value instanceof MezzeList l) return l;
        // Also handle Object[] (V2 array literals return Object[])
        if (value instanceof Object[] arr) return MezzeList.fromObjects(arr);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException(functionName + " expects '" + fieldName + "' as List, got: " +
            (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private MezzeFunction getFunction(MezzeRecord record, String fieldName) {
        Object value = getField(record, fieldName);
        if (value instanceof MezzeFunction f) return f;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException(functionName + " expects '" + fieldName + "' as Function");
    }

    private Object getField(MezzeRecord record, String fieldName) {
        // Use shape caching for O(1) field access
        int index = record.getFieldIndex(fieldName);
        if (index < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(functionName + " expects field '" + fieldName + "'");
        }
        return record.getField(index);
    }

    @TruffleBoundary
    private Object unknownFunction(MezzeRecord record) {
        throw new RuntimeException("Unknown native function: " + functionName);
    }

    public static NativeFunctions create(String functionName, MezzeNode argumentNode) {
        return new NativeFunctions(functionName, argumentNode);
    }

    /**
     * Check if a function name is actually handled by this dispatcher.
     * Only returns true for functions we can fully execute.
     */
    public static boolean isNativeFunction(String name) {
        int id = getFunctionId(name);
        // Only claim functions that are actually in our dispatch switch
        return switch (id) {
            // Int operations
            case ID_INT_ADD, ID_INT_SUBTRACT, ID_INT_MULTIPLY, ID_INT_DIVIDE,
                 ID_INT_MODULO, ID_INT_TO_STRING, ID_INT_ABS, ID_INT_MIN,
                 ID_INT_MAX, ID_INT_SIGN, ID_INT_NEG, ID_INT_TO_FLOAT,
                 ID_INT_CLAMP, ID_INT_FROM_STRING, ID_INT_FROM_NAT,
                 ID_INT_TO_NAT, ID_INT_POW -> true;
            // Float operations
            case ID_FLOAT_TO_STRING, ID_FLOAT_FROM_INT, ID_FLOAT_ABS,
                 ID_FLOAT_MIN, ID_FLOAT_MAX, ID_FLOAT_SQRT, ID_FLOAT_FLOOR,
                 ID_FLOAT_CEIL, ID_FLOAT_ROUND, ID_FLOAT_SIN, ID_FLOAT_COS,
                 ID_FLOAT_TAN, ID_FLOAT_POW -> true;
            // String operations
            case ID_STRING_CONCAT, ID_STRING_LENGTH, ID_STRING_TO_UPPER,
                 ID_STRING_TO_LOWER, ID_STRING_TRIM, ID_STRING_SUBSTRING,
                 ID_STRING_CONTAINS, ID_STRING_STARTS_WITH, ID_STRING_ENDS_WITH,
                 ID_STRING_CHAR_AT, ID_STRING_INDEX_OF, ID_STRING_REPLACE,
                 ID_STRING_REPLACE_ALL, ID_STRING_SPLIT, ID_STRING_JOIN,
                 ID_STRING_REVERSE, ID_STRING_TRIM_START, ID_STRING_TRIM_END,
                 ID_STRING_REPEAT -> true;
            // Bool operations
            case ID_BOOL_TO_STRING, ID_BOOL_AND, ID_BOOL_OR, ID_BOOL_NOT,
                 ID_BOOL_XOR -> true;
            // Console operations
            case ID_CONSOLE_PRINT -> true;
            // All list operations
            case ID_LIST_LENGTH, ID_LIST_IS_EMPTY, ID_LIST_FROM_ARRAY,
                 ID_LIST_EMPTY, ID_LIST_HEAD, ID_LIST_TAIL, ID_LIST_CONS,
                 ID_LIST_GET, ID_LIST_CONCAT, ID_LIST_APPEND, ID_LIST_REVERSE,
                 ID_LIST_TO_ARRAY, ID_LIST_TAKE, ID_LIST_DROP, ID_LIST_LAST,
                 ID_LIST_INIT, ID_LIST_ZIP, ID_LIST_UNZIP, ID_LIST_SORT,
                 ID_LIST_SORT_BY, ID_LIST_UNCONS, ID_LIST_RANGE, ID_LIST_REPEAT,
                 ID_LIST_INTERSPERSE, ID_LIST_MAP, ID_LIST_FILTER,
                 ID_LIST_FOLD_LEFT, ID_LIST_FOLD_RIGHT, ID_LIST_REDUCE,
                 ID_LIST_ANY, ID_LIST_ALL, ID_LIST_CONTAINS, ID_LIST_FIND,
                 ID_LIST_FIND_INDEX, ID_LIST_PARTITION, ID_LIST_FLAT_MAP,
                 ID_LIST_EQUALS -> true;
            default -> false;
        };
    }
}
