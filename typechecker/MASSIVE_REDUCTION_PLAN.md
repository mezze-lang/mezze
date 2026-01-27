# TypeChecker Massive Size Reduction Plan

**Created:** 2026-01-27
**Goal:** Divide TypeChecker (18,231 lines) into multiple files under 2,000 lines each
**Status:** Architecture analysis complete, extraction strategy defined

---

## Session Accomplishments

### ✅ Pattern Utilities Extraction (COMPLETE)
- **Extracted:** PatternUtilities.kt (242 lines)
- **Deleted from TypeChecker:** 167 lines
- **Added to ResolutionEngine:** getUnderlyingVariantType() method (+97 lines)
- **Tests:** All 1,778 tests passing ✅
- **Net TypeChecker reduction:** -165 lines (18,097 → 17,932 actual)

### ✅ Critical Bug Fix
- **Problem:** 36 test failures due to TypeApplication not being expanded
- **Solution:** Implemented proper type expansion in ResolutionEngine
- **Impact:** Fixed nested pattern matching like `Ok { val = Pair { first, second } }`

---

## The Challenge: TypeChecker God Class

### Current State
```
TypeChecker.kt: 18,231 lines
  ├── inferBasicType: 3,260 lines (17.9% of file!)
  ├── inferBasicTypeWithExpected: 638 lines (3.5%)
  ├── validateAbilityConstraintsAtCallSite: 175 lines
  ├── validateAbilityImplementation: 234 lines
  ├── validatePerformerImplementationBodies: 162 lines
  ├── validateTypeAgainstAnnotation: 165 lines
  └── 100+ other functions: ~13,600 lines
```

### Monster Functions Identified

| Function | Lines | % of File | Complexity |
|----------|-------|-----------|------------|
| **inferBasicType** | 3,260 | 17.9% | 50+ expression types, calls 50+ methods |
| **inferBasicTypeWithExpected** | 638 | 3.5% | Bidirectional inference |
| **Validation functions** | ~2,000 | 11.0% | 15+ functions |
| **Helper functions** | ~1,000 | 5.5% | Type operations |

**Total extractable:** ~6,900 lines (38% of file)

---

## Architecture Problem

### Why Simple Extraction Fails

**inferBasicType alone calls:**
- 23 recursive calls to `inferBasicType`
- 7 calls to `inferBasicTypeWithExpected`
- 22 calls to `prettyPrintType`
- 17 calls to `collectAbilityConstraintsFromExpression`
- 9 calls to `replaceTypeVars`
- 8 calls to `unifyTypes`
- 8 calls to `inferTypeWithUnification`
- 6 calls to `convertTypeAnnotation`
- Plus 30+ other private methods

**And accesses private state:**
- `context` (variable bindings)
- `polymorphicContext` (type schemes)
- `constraints` (unification constraints)
- `effectDeclarationMap`
- `typeDeclarationMap`
- `currentProgram`
- Plus 20+ other fields

### Kotlin Constraints

1. **No partial classes** - Can't split across files like C#
2. **Extension functions** - Can't access private members
3. **Internal visibility** - Module-level, but requires changing ALL called methods
4. **Performance** - Must maintain zero overhead

---

## Viable Extraction Strategy

### Option A: Internal Visibility + Extension Functions (RECOMMENDED)

**Approach:**
1. Change frequently-called methods from `private` to `internal`
2. Extract large functions to separate files as extension functions
3. All files stay in same package - module-private, not public
4. Zero performance overhead (extensions compile to static methods)

**File Structure:**
```
typechecker/
├── TypeChecker.kt (~2,000 lines)
│   - Main class with internal helper methods
│   - Delegates to extension functions
│
├── TypeChecker+ExpressionInference.kt (~3,300 lines)
│   - inferBasicType as extension function
│   - Handles all expression types
│
├── TypeChecker+BidirectionalInference.kt (~700 lines)
│   - inferBasicTypeWithExpected as extension
│   - Bidirectional type inference
│
├── TypeChecker+Validation.kt (~2,000 lines)
│   - All validation functions as extensions
│   - Ability validation, match exhaustiveness, etc.
│
├── TypeChecker+Helpers.kt (~1,000 lines)
│   - Helper functions as extensions
│   - setInferredTypesWithType, type operations
│
└── engines/ (already extracted)
    ├── UnificationEngine.kt
    ├── ResolutionEngine.kt
    ├── InferenceEngine.kt
    └── ValidationEngine.kt
```

**Result:**
- TypeChecker.kt: 2,000 lines (from 18,231)
- 4 extension files: 3,300 + 700 + 2,000 + 1,000 = 7,000 lines
- Largest file: 3,300 lines (much better than 18,231!)
- All module-private, clean API

### Option B: Move to Existing Engines

**Approach:**
1. Move inferBasicType logic to InferenceEngine
2. Move validation logic to ValidationEngine
3. Pass TypeChecker as context parameter
4. Use interface methods for cross-cutting concerns

**Challenges:**
- Circular dependencies (Inference needs Validation needs Inference)
- Need to refactor state management
- More invasive changes
- Longer implementation time

### Option C: Gradual Extraction

**Approach:**
1. Extract small, self-contained functions first
2. Slowly reduce dependencies
3. Eventually tackle monster functions
4. Multi-session effort

**Trade-offs:**
- Lower risk per change
- Slower overall progress
- May not reach goal of <2000 line files quickly

---

## Recommended Next Steps

### Immediate (Next Session)

**1. Implement Option A - Extension Function Extraction**

```bash
# Step 1: Change visibility (10 minutes)
sed -i 's/private fun inferBasicType(/internal fun inferBasicType(/g' TypeChecker.kt
sed -i 's/private fun prettyPrintType(/internal fun prettyPrintType(/g' TypeChecker.kt
# ... (change ~20 key methods to internal)

# Step 2: Extract inferBasicType (20 minutes)
# Create TypeChecker+ExpressionInference.kt with:
internal fun TypeChecker.inferBasicTypeExtracted(expr: Expression): FunkType {
    return when (expr) {
        // ... full 3,260 line implementation
    }
}

# Step 3: Replace in TypeChecker (5 minutes)
private fun inferBasicType(expr: Expression): FunkType {
    return inferBasicTypeExtracted(expr)
}

# Step 4: Test (5 minutes)
gradle :typechecker:test
```

**Expected Result:** TypeChecker: 18,231 → 14,971 lines (-3,260 lines)

**2. Extract inferBasicTypeWithExpected**

Same process, extract 638 lines.

**Expected Result:** TypeChecker: 14,971 → 14,333 lines (-638 lines)

**3. Extract Validation Functions**

Extract all validation functions to TypeChecker+Validation.kt

**Expected Result:** TypeChecker: 14,333 → 12,333 lines (-2,000 lines)

**4. Extract Helpers**

Extract helper functions to TypeChecker+Helpers.kt

**Expected Result:** TypeChecker: 12,333 → 11,333 lines (-1,000 lines)

###Total After Next Session

- **TypeChecker.kt:** ~11,000 lines (from 18,231)
- **Reduction:** -7,200 lines (39.5%)
- **Files:** 5 files, largest 3,300 lines

### Medium Term (Following Sessions)

**5. Continue Breaking Down**

Extract more specialized logic:
- Type conversion functions
- Constraint solving helpers
- Effect system logic
- Module resolution

**Target:** TypeChecker.kt under 5,000 lines

**6. Move Logic to Engines**

Gradually move extracted functions into proper engines:
- Expression inference → InferenceEngine
- Validation → ValidationEngine
- Eventually delete extension files

**Target:** TypeChecker.kt under 2,000 lines (just orchestration)

---

## Methods That Need to Become Internal

For extension functions to work, these methods must change from `private` to `internal`:

### Core Inference (8 methods)
- `inferBasicType`
- `inferBasicTypeWithExpected`
- `inferTypeWithUnification`
- `inferLambdaBidirectionally`
- `inferParameterTypesFromUsage`
- `instantiateScheme`
- `collectAbilityConstraintsFromExpression`
- `collectEffectsFromExpression`

### Type Operations (10 methods)
- `prettyPrintType`
- `convertTypeAnnotation`
- `convertTypeAnnotationWithSubstitution`
- `replaceTypeVars`
- `applySubstitutionToType`
- `isTypeCompatible`
- `unifyTypes`
- `addConstraint`
- `getFieldEntries`
- `findField`

### Resolution (5 methods)
- `findVariantConstructorType`
- `lookupVariable`
- `lookupWithModulePreference`
- `resolveAbilityOperationCall`
- `findMatchingAbilityImplementation`

### Validation (5 methods)
- `validateArithmeticOperation`
- `validateEffectHandlerUsage`
- `validateAbilityConstraintsAtCallSite`
- `validateTypeAgainstAnnotation`
- `checkMatchExhaustivenessAndWildcards`

### State Fields (6 fields)
- `context`
- `polymorphicContext`
- `constraints`
- `effectDeclarationMap`
- `typeDeclarationMap`
- `currentProgram`

**Total:** ~34 methods + 6 fields need `internal` visibility

---

## Success Criteria

### Phase 1 (Next Session) ✅
- [ ] TypeChecker.kt under 12,000 lines
- [ ] 4 extension files created
- [ ] All 1,778 tests passing
- [ ] No performance regression

### Phase 2 (Following Sessions) ✅
- [ ] TypeChecker.kt under 5,000 lines
- [ ] Logic moved to proper engines
- [ ] Extension files deprecated
- [ ] Clean architecture

### Phase 3 (Final Goal) ✅
- [ ] TypeChecker.kt under 2,000 lines
- [ ] All monster functions eliminated
- [ ] Maintainable codebase
- [ ] Well-documented structure

---

## Lessons Learned This Session

### What Worked ✅
1. **PatternUtilities extraction** - Clean separation, proper testing
2. **ResolutionEngine enhancement** - Added TypeApplication expansion cleanly
3. **Interface-based design** - Made extension possible
4. **Comprehensive testing** - 1,778 tests caught all issues

### What Was Challenging ⚠️
1. **God class complexity** - 18K lines with tight coupling
2. **Private method access** - Extension functions can't see private members
3. **State management** - 20+ fields accessed throughout
4. **Circular dependencies** - Inference ↔ Validation ↔ Resolution

### Key Insights 💡
1. **Extension functions are the answer** - But require internal visibility
2. **Incremental is safer** - Change visibility, extract, test, repeat
3. **Architecture first** - Engines provide clean boundaries
4. **Module-private is fine** - Internal doesn't mean public API

---

## Next Session Checklist

**Before starting:**
- [ ] Review this document
- [ ] Ensure all 1,778 tests passing
- [ ] Check git status is clean

**During session:**
- [ ] Change 34 methods to `internal`
- [ ] Extract inferBasicType (3,260 lines)
- [ ] Extract inferBasicTypeWithExpected (638 lines)
- [ ] Extract validation functions (2,000 lines)
- [ ] Extract helper functions (1,000 lines)

**After session:**
- [ ] Verify all tests pass
- [ ] Check no performance regression
- [ ] Update documentation
- [ ] Commit changes

**Expected Time:** 2-3 hours

---

## Resources

- **TypeChecker.kt:** `/Users/shriyans/Projects/funk/typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt`
- **Current size:** 18,231 lines
- **Target size:** <2,000 lines
- **Reduction needed:** -16,231 lines (89%)

---

**Status:** Ready for extraction
**Risk:** Low (extension functions are zero-overhead)
**Confidence:** High (architecture proven with PatternUtilities)
