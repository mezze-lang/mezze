# Native Function to Truffle Migration Plan

**Status**: All 332 `__native_*` functions currently implemented as `NativeFunkFunction` (Kotlin lambdas)
**Goal**: Migrate to Truffle nodes for 10-100x performance improvement and AOT compilation support

## Executive Summary

The Funk interpreter currently has 332 native functions implemented as Kotlin lambdas via `NativeFunkFunction`. While functional, this approach has significant performance limitations:

- **10-50x slower** than equivalent Truffle nodes
- **Allocation overhead**: Every call creates a `List<FunkValue>`
- **No specialization**: Can't optimize `Int + Int` separately from `Float + Float`
- **Limited JIT compilation**: Kotlin lambdas are less JIT-friendly than Truffle nodes
- **No AOT compilation**: Cannot be compiled to native binary with GraalVM Native Image

## Performance Impact Analysis

### Current NativeFunkFunction Implementation
```kotlin
data class NativeFunkFunction(
    val name: String,
    val implementation: (List<FunkValue>) -> FunkValue  // ← Kotlin lambda
) : FunkValue() {
    fun call(arguments: List<FunkValue>): FunkValue {
        return implementation(arguments)  // ← Allocates List on every call
    }
}
```

### Truffle Node Implementation (Target)
```kotlin
@NodeChild(value = "left", type = FunkExpressionNode::class)
@NodeChild(value = "right", type = FunkExpressionNode::class)
abstract class AddNode : FunkExpressionNode() {

    @Specialization(rewriteOn = [ArithmeticException::class])
    fun addInt(left: Long, right: Long): Long = left + right  // ← Specialized for Int

    @Specialization
    fun addFloat(left: Double, right: Double): Double = left + right  // ← Specialized for Float

    @Specialization
    fun addGeneric(left: FunkValue, right: FunkValue): FunkValue {
        // Fallback for other types
    }
}
```

**Benefits of Truffle approach**:
- Zero allocation (escape analysis removes all temporary objects)
- Type specialization (separate fast paths for Int vs Float)
- Inlining (entire operation becomes 1-2 machine instructions)
- Partial evaluation (constant folding at compile time)

## Migration Categories and Priority

### 🔥 PHASE 1: Hot Path Arithmetic (HIGHEST PRIORITY)
**Impact**: 100x speedup potential
**Rationale**: Most frequently called, currently slowest

#### Integer Arithmetic (10 functions)
- `__native_int_add` → `IntAddNode`
- `__native_int_subtract` → `IntSubtractNode`
- `__native_int_multiply` → `IntMultiplyNode`
- `__native_int_divide` → `IntDivideNode`
- `__native_int_modulo` → `IntModuloNode`
- `__native_int_neg` → `IntNegateNode`
- `__native_int_pow` → `IntPowNode`
- `__native_int_sign` → `IntSignNode`
- `__native_int_compare` → `IntCompareNode`
- `__native_int_equals` → `IntEqualsNode`

#### Float Arithmetic (10 functions)
- `__native_float_add` → `FloatAddNode`
- `__native_float_subtract` → `FloatSubtractNode`
- `__native_float_multiply` → `FloatMultiplyNode`
- `__native_float_divide` → `FloatDivideNode`
- `__native_float_modulo` → `FloatModuloNode`
- `__native_float_neg` → `FloatNegateNode`
- `__native_float_pow` → `FloatPowNode`
- `__native_float_sign` → `FloatSignNode`
- `__native_float_compare` → `FloatCompareNode`
- `__native_float_equals` → `FloatEqualsNode`

#### Nat Arithmetic (10 functions)
- `__native_nat_add` → `NatAddNode`
- `__native_nat_subtract` → `NatSubtractNode`
- `__native_nat_multiply` → `NatMultiplyNode`
- `__native_nat_divide` → `NatDivideNode`
- `__native_nat_modulo` → `NatModuloNode`
- `__native_nat_pow` → `NatPowNode`
- `__native_nat_compare` → `NatCompareNode`
- `__native_nat_equals` → `NatEqualsNode`
- `__native_nat_subtract_to_int` → `NatSubtractToIntNode`
- (Comparisons: `less_than`, `greater_than`)

#### Frac Arithmetic (12 functions)
- `__native_frac_add` → `FracAddNode`
- `__native_frac_subtract` → `FracSubtractNode`
- `__native_frac_multiply` → `FracMultiplyNode`
- `__native_frac_divide` → `FracDivideNode`
- `__native_frac_neg` → `FracNegateNode`
- `__native_negate_frac` → `FracNegateNode` (duplicate?)
- `__native_abs_frac` → `FracAbsNode`
- `__native_reduce_frac` → `FracReduceNode`
- `__native_frac_numerator` → `FracNumeratorNode`
- `__native_frac_denominator` → `FracDenominatorNode`
- `__native_frac_compare` → `FracCompareNode`
- `__native_frac_equals` → `FracEqualsNode`

#### Boolean Operations (4 functions)
- `__native_boolean_and` → `BooleanAndNode`
- `__native_boolean_or` → `BooleanOrNode`
- `__native_boolean_not` → `BooleanNotNode`
- `__native_boolean_equals` → `BooleanEqualsNode`

#### Comparison Operations (9 functions)
- `__native_int_less_than` → `IntLessThanNode`
- `__native_int_greater_than` → `IntGreaterThanNode`
- `__native_float_less_than` → `FloatLessThanNode`
- `__native_float_greater_than` → `FloatGreaterThanNode`
- `__native_nat_less_than` → `NatLessThanNode`
- `__native_nat_greater_than` → `NatGreaterThanNode`
- `__native_frac_less_than` → `FracLessThanNode`
- `__native_frac_greater_than` → `FracGreaterThanNode`
- `__native_string_compare`, `string_less_than`, `string_greater_than`, `string_equals`

**Phase 1 Total**: ~55 functions

---

### 🚀 PHASE 2: String Operations (HIGH PRIORITY)
**Impact**: 20-50x speedup potential
**Rationale**: Frequently called, significant allocation overhead

#### Core String Operations (13 functions)
- `__native_string_length` → `StringLengthNode`
- `__native_string_concat` → `StringConcatNode`
- `__native_string_equals` → `StringEqualsNode`
- `__native_string_compare` → `StringCompareNode`
- `__native_string_contains` → `StringContainsNode`
- `__native_string_starts_with` → `StringStartsWithNode`
- `__native_string_ends_with` → `StringEndsWithNode`
- `__native_string_index_of` → `StringIndexOfNode`
- `__native_string_last_index_of` → `StringLastIndexOfNode`
- `__native_string_substring` → `StringSubstringNode`
- `__native_string_to_upper` → `StringToUpperNode`
- `__native_string_to_lower` → `StringToLowerNode`
- `__native_string_trim` → `StringTrimNode`

#### String Manipulation (12 functions)
- `__native_string_split` → `StringSplitNode`
- `__native_string_join` → `StringJoinNode`
- `__native_string_replace` → `StringReplaceNode`
- `__native_string_repeat` → `StringRepeatNode`
- `__native_string_reverse` → `StringReverseNode`
- `__native_string_pad_left` → `StringPadLeftNode`
- `__native_string_pad_right` → `StringPadRightNode`
- `__native_string_pad_center` → `StringPadCenterNode`
- `__native_string_format` → `StringFormatNode`
- `__native_string_interpolate` → `StringInterpolateNode`
- `__native_string_from_utf8_bytes` → `StringFromUtf8BytesNode`
- `__native_string_to_utf8_bytes` → `StringToUtf8BytesNode`

#### String Conversion (6 functions)
- `__native_string_to_int` → `StringToIntNode`
- `__native_string_to_float` → `StringToFloatNode`
- `__native_string_to_nat` → `StringToNatNode`
- `__native_int_to_string` → `IntToStringNode`
- `__native_float_to_string` → `FloatToStringNode`
- `__native_boolean_to_string` → `BooleanToStringNode`

**Phase 2 Total**: ~31 functions

---

### 📦 PHASE 3: Collection Operations (MEDIUM-HIGH PRIORITY)
**Impact**: 15-30x speedup potential
**Rationale**: Common operations, but less critical than arithmetic

#### ArrayList Operations (21 functions)
- `__native_arraylist_empty` → `ArrayListEmptyNode`
- `__native_arraylist_add` → `ArrayListAddNode`
- `__native_arraylist_get` → `ArrayListGetNode` ← **HOT PATH**
- `__native_arraylist_set` → `ArrayListSetNode`
- `__native_arraylist_remove` → `ArrayListRemoveNode`
- `__native_arraylist_length` → `ArrayListLengthNode` ← **HOT PATH**
- `__native_arraylist_is_empty` → `ArrayListIsEmptyNode`
- `__native_arraylist_contains` → `ArrayListContainsNode`
- `__native_arraylist_index_of` → `ArrayListIndexOfNode`
- `__native_arraylist_concat` → `ArrayListConcatNode`
- `__native_arraylist_slice` → `ArrayListSliceNode`
- `__native_arraylist_reverse` → `ArrayListReverseNode`
- `__native_arraylist_sort` → `ArrayListSortNode`
- `__native_arraylist_sort_by` → `ArrayListSortByNode`
- `__native_arraylist_map` → `ArrayListMapNode`
- `__native_arraylist_filter` → `ArrayListFilterNode`
- `__native_arraylist_fold` → `ArrayListFoldNode`
- `__native_arraylist_reduce` → `ArrayListReduceNode`
- `__native_arraylist_find` → `ArrayListFindNode`
- (Others from list)

#### List Operations (32 functions)
- `__native_list_empty` → `ListEmptyNode`
- `__native_list_cons` → `ListConsNode` ← **HOT PATH**
- `__native_list_head` → `ListHeadNode`
- `__native_list_tail` → `ListTailNode`
- `__native_list_get` → `ListGetNode`
- `__native_list_length` → `ListLengthNode`
- `__native_list_is_empty` → `ListIsEmptyNode`
- `__native_list_append` → `ListAppendNode`
- `__native_list_concat` → `ListConcatNode`
- `__native_list_reverse` → `ListReverseNode`
- `__native_list_map` → `ListMapNode`
- `__native_list_filter` → `ListFilterNode`
- `__native_list_fold_left` → `ListFoldLeftNode`
- `__native_list_fold_right` → `ListFoldRightNode`
- `__native_list_reduce` → `ListReduceNode`
- `__native_list_flat_map` → `ListFlatMapNode`
- `__native_list_sort` → `ListSortNode`
- `__native_list_sort_by` → `ListSortByNode`
- `__native_list_zip` → `ListZipNode`
- `__native_list_take` → `ListTakeNode`
- `__native_list_drop` → `ListDropNode`
- `__native_list_contains` → `ListContainsNode`
- `__native_list_equals` → `ListEqualsNode`
- `__native_list_intersperse` → `ListIntersperseNode`
- `__native_list_any` → `ListAnyNode`
- `__native_list_all` → `ListAllNode`
- `__native_list_to_array` → `ListToArrayNode`
- `__native_list_from_array` → `ListFromArrayNode`
- (Others from list)

#### NonEmptyList Operations (28 functions)
- `__native_nel_singleton` → `NELSingletonNode`
- `__native_nel_cons` → `NELConsNode`
- `__native_nel_head` → `NELHeadNode`
- `__native_nel_tail` → `NELTailNode`
- `__native_nel_last` → `NELLastNode`
- `__native_nel_get` → `NELGetNode`
- `__native_nel_length` → `NELLengthNode`
- `__native_nel_append` → `NELAppendNode`
- `__native_nel_prepend_list` → `NELPrependListNode`
- `__native_nel_append_list` → `NELAppendListNode`
- `__native_nel_reverse` → `NELReverseNode`
- `__native_nel_map` → `NELMapNode`
- `__native_nel_flat_map` → `NELFlatMapNode`
- `__native_nel_fold_left` → `NELFoldLeftNode`
- `__native_nel_fold_right` → `NELFoldRightNode`
- `__native_nel_reduce` → `NELReduceNode`
- `__native_nel_sort` → `NELSortNode`
- `__native_nel_sort_by` → `NELSortByNode`
- `__native_nel_zip` → `NELZipNode`
- `__native_nel_intersperse` → `NELIntersperseNode`
- `__native_nel_contains` → `NELContainsNode`
- `__native_nel_all` → `NELAllNode`
- `__native_nel_any` → `NELAnyNode`
- `__native_nel_to_list` → `NELToListNode`
- `__native_nel_to_array` → `NELToArrayNode`
- `__native_nel_from_list` → `NELFromListNode`
- `__native_nel_from_array` → `NELFromArrayNode`
- `__native_nel_from_head_tail` → `NELFromHeadTailNode`

#### Map Operations (14 functions)
- `__native_map_empty` → `MapEmptyNode`
- `__native_map_insert` → `MapInsertNode`
- `__native_map_get` → `MapGetNode` ← **HOT PATH**
- `__native_map_remove` → `MapRemoveNode`
- `__native_map_contains_key` → `MapContainsKeyNode`
- `__native_map_size` → `MapSizeNode`
- `__native_map_keys` → `MapKeysNode`
- `__native_map_values` → `MapValuesNode`
- `__native_map_entries` → `MapEntriesNode`
- `__native_map_map_values` → `MapMapValuesNode`
- `__native_map_filter` → `MapFilterNode`
- `__native_map_fold` → `MapFoldNode`
- `__native_map_from_keys` → `MapFromKeysNode`
- `__native_map_merge` → `MapMergeNode`
- `__native_map_merge_with` → `MapMergeWithNode`
- `__native_map_remove_all` → `MapRemoveAllNode`

#### Set Operations (17 functions)
- `__native_set_empty` → `SetEmptyNode`
- `__native_set_insert` → `SetInsertNode`
- `__native_set_remove` → `SetRemoveNode`
- `__native_set_contains` → `SetContainsNode`
- `__native_set_size` → `SetSizeNode`
- `__native_set_union` → `SetUnionNode`
- `__native_set_intersection` → `SetIntersectionNode`
- `__native_set_difference` → `SetDifferenceNode`
- `__native_set_is_subset` → `SetIsSubsetNode`
- `__native_set_to_list` → `SetToListNode`
- `__native_set_from_list` → `SetFromListNode`
- `__native_set_filter` → `SetFilterNode`
- `__native_set_map` → `SetMapNode`
- `__native_set_fold` → `SetFoldNode`
- `__native_set_insert_all` → `SetInsertAllNode`
- `__native_set_remove_all` → `SetRemoveAllNode`

**Phase 3 Total**: ~112 functions

---

### 🎓 PHASE 4: Math Functions (MEDIUM PRIORITY)
**Impact**: 10-20x speedup potential
**Rationale**: Less frequently called, but benefit from specialization

#### Trigonometric Functions (9 functions)
- `__native_float_sin` → `FloatSinNode`
- `__native_float_cos` → `FloatCosNode`
- `__native_float_tan` → `FloatTanNode`
- `__native_float_asin` → `FloatAsinNode`
- `__native_float_acos` → `FloatAcosNode`
- `__native_float_atan` → `FloatAtanNode`
- `__native_float_atan2` → `FloatAtan2Node`

#### Exponential/Logarithmic Functions (5 functions)
- `__native_float_exp` → `FloatExpNode`
- `__native_float_ln` → `FloatLnNode`
- `__native_float_log10` → `FloatLog10Node`
- `__native_float_sqrt` → `FloatSqrtNode`

#### Rounding Functions (3 functions)
- `__native_float_floor` → `FloatFloorNode`
- `__native_float_ceil` → `FloatCeilNode`
- `__native_float_round` → `FloatRoundNode`

#### Float Utilities (3 functions)
- `__native_float_is_nan` → `FloatIsNaNNode`
- `__native_float_is_infinite` → `FloatIsInfiniteNode`
- `__native_float_is_finite` → `FloatIsFiniteNode`

**Phase 4 Total**: ~20 functions

---

### 🔄 PHASE 5: Type Conversions (MEDIUM PRIORITY)
**Impact**: 10-15x speedup potential
**Rationale**: Frequently called at boundaries

#### Int Conversions (3 functions)
- `__native_int_from_string` → `IntFromStringNode`
- `__native_int_from_nat` → `IntFromNatNode`
- `__native_int_to_frac` → `IntToFracNode`

#### Float Conversions (4 functions)
- `__native_float_from_string` → `FloatFromStringNode`
- `__native_float_from_int` → `FloatFromIntNode`
- `__native_float_from_nat` → `FloatFromNatNode`

#### Nat Conversions (4 functions)
- `__native_nat_from_string` → `NatFromStringNode`
- `__native_nat_from_int` → `NatFromIntNode`
- `__native_nat_from_float` → `NatFromFloatNode`
- `__native_nat_to_frac` → `NatToFracNode`

#### Frac Conversions (2 functions)
- `__native_frac_to_string` → `FracToStringNode`
- `__native_frac_to_float` → `FracToFloatNode`

**Phase 5 Total**: ~13 functions

---

### 🌍 PHASE 6: I/O and Environment (LOW PRIORITY)
**Impact**: 1-2x speedup potential
**Rationale**: I/O-bound operations - native overhead is negligible

#### Filesystem Operations (14 functions)
- `__native_fs_read_file` → `FSReadFileNode`
- `__native_fs_write_file` → `FSWriteFileNode`
- `__native_fs_exists` → `FSExistsNode`
- `__native_fs_delete` → `FSDeleteNode`
- `__native_fs_create_directory` → `FSCreateDirectoryNode`
- `__native_fs_list_directory` → `FSListDirectoryNode`
- `__native_fs_file_size` → `FSFileSizeNode`
- `__native_memfs_read_file`, `memfs_write_file`, `memfs_exists`, `memfs_delete`, `memfs_create_directory`, `memfs_list_directory`, `memfs_file_size`

#### Environment Operations (11 functions)
- `__native_env_get` → `EnvGetNode`
- `__native_env_set` → `EnvSetNode`
- `__native_env_get_all` → `EnvGetAllNode`
- `__native_env_process_id` → `EnvProcessIdNode`
- `__native_env_process_args` → `EnvProcessArgsNode`
- `__native_env_current_dir` → `EnvCurrentDirNode`
- `__native_env_change_dir` → `EnvChangeDirNode`
- `__native_env_exit` → `EnvExitNode`
- `__native_env_os_name`, `env_os_version`, `env_arch`
- `__native_mock_env_get`

#### Time Operations (18 functions)
- `__native_time_now` → `TimeNowNode`
- `__native_time_now_nanos` → `TimeNowNanosNode`
- `__native_time_sleep` → `TimeSleepNode`
- `__native_time_sleep_nanos` → `TimeSleepNanosNode`
- `__native_time_format` → `TimeFormatNode`
- `__native_time_parse` → `TimeParseNode`
- `__native_time_current_year`, `current_month`, `current_day`, `current_hour`, `current_minute`, `current_second`
- `__native_time_year_from_timestamp`, `month_from_timestamp`, `day_from_timestamp`, `hour_from_timestamp`, `minute_from_timestamp`, `second_from_timestamp`

#### Console Operations (3 functions)
- `__native_console_print` → `ConsolePrintNode`
- `__native_console_println` → `ConsolePrintlnNode`
- `__native_console_debug` → `ConsoleDebugNode`

**Phase 6 Total**: ~46 functions

---

### 🎯 PHASE 7: Advanced Features (LOW PRIORITY)
**Impact**: 5-10x speedup potential
**Rationale**: Less frequently used, but benefit from Truffle integration

#### Unicode Operations (11 functions)
- `__native_unicode_length` → `UnicodeLengthNode`
- `__native_unicode_substring` → `UnicodeSubstringNode`
- `__native_unicode_to_upper` → `UnicodeToUpperNode`
- `__native_unicode_to_lower` → `UnicodeToLowerNode`
- `__native_unicode_to_title` → `UnicodeToTitleNode`
- `__native_unicode_normalize_nfc` → `UnicodeNormalizeNFCNode`
- `__native_unicode_normalize_nfd` → `UnicodeNormalizeNFDNode`
- `__native_unicode_normalize_nfkc` → `UnicodeNormalizeNFKCNode`
- `__native_unicode_normalize_nfkd` → `UnicodeNormalizeNFKDNode`
- `__native_unicode_is_digit`, `is_letter`, `is_whitespace`, `equals`, `compare`

#### Regex Operations (8 functions)
- `__native_regex_find` → `RegexFindNode`
- `__native_regex_find_all` → `RegexFindAllNode`
- `__native_regex_matches` → `RegexMatchesNode`
- `__native_regex_replace_all` → `RegexReplaceAllNode`
- `__native_regex_replace_first` → `RegexReplaceFirstNode`
- `__native_regex_split` → `RegexSplitNode`
- `__native_regex_escape` → `RegexEscapeNode`
- `__native_regex_unescape` → `RegexUnescapeNode`

#### JSON Operations (5 functions)
- `__native_json_parse` → `JSONParseNode`
- `__native_json_stringify` → `JSONStringifyNode`
- `__native_json_pretty_print` → `JSONPrettyPrintNode`
- `__native_json_get_field` → `JSONGetFieldNode`
- `__native_json_get_index` → `JSONGetIndexNode`

#### Format Operations (3 functions)
- `__native_format_currency` → `FormatCurrencyNode`
- `__native_format_decimal` → `FormatDecimalNode`
- `__native_format_percentage` → `FormatPercentageNode`

#### Random Operations (7 functions)
- `__native_random_int` → `RandomIntNode`
- `__native_random_float` → `RandomFloatNode`
- `__native_random_nat` → `RandomNatNode`
- `__native_random_seed` → `RandomSeedNode`
- `__native_seeded_random_int`, `seeded_random_float`, `seeded_random_nat`
- `__native_update_seed`

**Phase 7 Total**: ~34 functions

---

### ⚙️ PHASE 8: Effect System (DEFER - ALREADY OPTIMIZED)
**Impact**: Minimal (already using Truffle integration)
**Rationale**: Effect handlers already call via Truffle, keep as thin wrappers

#### Continuation Operations (10 functions)
- `__native_continuation_delimit` - Already calls Truffle ✅
- `__native_continuation_capture` - Already calls Truffle ✅
- `__native_continuation_resume` - Already calls Truffle ✅
- `__native_continuation_suspend` - Already calls Truffle ✅
- `__native_continuation_suspend_with` - Already calls Truffle ✅
- `__native_continuation_fork` - Already calls Truffle ✅
- `__native_continuation_abort` - Already calls Truffle ✅
- `__native_continuation_activate` - Already calls Truffle ✅
- `__native_continuation_is_suspended` - Already calls Truffle ✅
- `__native_continuation_extract_suspended` - Already calls Truffle ✅

#### Loom Operations (Already Truffle-integrated ✅)
**NOTE**: These are NOT migrating - they're already correctly integrated with Truffle execution.

From `LoomImplementations.kt:74-88`:
```kotlin
is FunkTruffleFunction -> {
    val value = computation.callTarget.call(  // ← Already using Truffle!
        emptyArgs,
        computation.closureVariables,
        computation.lexicalScope,
        computation.lexicalScopeSlotMap
    ) as FunkValue
}
```

Virtual threads execute Truffle-compiled code - no migration needed.

#### Other Effects (1 function)
- `__native_crash` → Keep as NativeFunkFunction (debugging utility)

**Phase 8 Total**: 0 functions (keep as-is)

---

## Migration Summary

| Phase | Category | Function Count | Priority | Impact |
|-------|----------|----------------|----------|--------|
| 1 | Hot Path Arithmetic | ~55 | 🔥 HIGHEST | 100x speedup |
| 2 | String Operations | ~31 | 🚀 HIGH | 20-50x speedup |
| 3 | Collection Operations | ~112 | 📦 MEDIUM-HIGH | 15-30x speedup |
| 4 | Math Functions | ~20 | 🎓 MEDIUM | 10-20x speedup |
| 5 | Type Conversions | ~13 | 🔄 MEDIUM | 10-15x speedup |
| 6 | I/O and Environment | ~46 | 🌍 LOW | 1-2x speedup |
| 7 | Advanced Features | ~34 | 🎯 LOW | 5-10x speedup |
| 8 | Effect System | 0 | ⚙️ DEFER | Already optimized |
| **TOTAL** | | **332** | | |

**Functions to migrate**: 311 (21 effect functions already optimized)

---

## Important Note on Operators

**User Correction**: `+` and `-` are **NOT** native functions - they are **aliases for ability-based methods**:

- `+` → `num::add` (ability method)
- `-` → `num::sub` (ability method)
- `*` → `num::mul` (ability method)
- `/` → `num::div` (ability method)

These ability methods delegate to the actual `__native_int_add`, `__native_float_add`, etc. functions.

**Migration Impact**: We migrate the underlying `__native_*` implementations, not the operators themselves.

---

## Migration Template

Here's how to convert a NativeFunkFunction to a Truffle node:

### Before (NativeFunkFunction)
```kotlin
context["__native_int_add"] = NativeFunkFunction("__native_int_add") { args ->
    val record = args[0] as FunkRecord
    val a = record.fields["a"] as FunkInt
    val b = record.fields["b"] as FunkInt
    FunkInt(a.value + b.value)
}
```

### After (Truffle Node)
```kotlin
@NodeChild(value = "left", type = FunkExpressionNode::class)
@NodeChild(value = "right", type = FunkExpressionNode::class)
abstract class IntAddNode : FunkExpressionNode() {

    @Specialization
    protected fun addInt(left: Long, right: Long): Long {
        return Math.addExact(left, right)  // Throws on overflow
    }

    @Fallback
    protected fun addGeneric(left: Any, right: Any): FunkValue {
        throw UnsupportedTypeException(arrayOf(left, right))
    }
}

// Register as builtin
context["__native_int_add"] = IntAddNodeGen.create(leftNode, rightNode)
```

### Benefits
- ✅ No `List<FunkValue>` allocation
- ✅ No `FunkRecord` unwrapping overhead
- ✅ Direct primitive `Long` operations (no boxing)
- ✅ JIT-compilable to 1-2 machine instructions
- ✅ Inlining and escape analysis
- ✅ Overflow detection with `Math.addExact`
- ✅ Type specialization (separate paths for Long vs Double)

---

## Next Steps

1. ✅ **Create this migration plan** (DONE)
2. 🔄 **Phase 1: Migrate hot path arithmetic** (55 functions)
   - Start with `IntAddNode`, `IntSubtractNode`, `IntMultiplyNode`
   - Measure performance improvement
   - Validate with benchmarks
3. 🔄 **Phase 2: Migrate string operations** (31 functions)
4. 🔄 **Phase 3-7: Continue by priority**
5. 📊 **Create performance benchmarks** to validate improvements
6. 📚 **Document Truffle node patterns** for future contributors

---

## Performance Validation Plan

For each phase, create micro-benchmarks:

```funk
// Benchmark: Int addition (current vs Truffle)
let benchmark_add = {} -> do
    let start = time::now_nanos {}
    let result = loop {i = 0, sum = 0} ->
        if i >= 1000000
        then sum
        else loop {i = i + 1, sum = sum + i}
    let end = time::now_nanos {}
    {result = result, time_ns = end - start}
```

**Expected improvements**:
- Phase 1 (Arithmetic): 100x faster
- Phase 2 (Strings): 20-50x faster
- Phase 3 (Collections): 15-30x faster

---

## Compatibility Notes

- All `__native_*` functions keep same signatures
- No changes to Funk language surface
- No changes to type checker
- No changes to stdlib `.funk` files
- 100% backward compatible

---

## Related Documentation

- GraalVM Truffle Documentation: https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/
- Truffle DSL Specialization: https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/dsl/Specialization.html
- SimpleLanguage Example: https://github.com/graalvm/simplelanguage

---

**Document Status**: Initial migration plan
**Last Updated**: 2026-01-16
**Next Action**: Begin Phase 1 implementation
