# Consolidation Plan: AST, Parser, and Typechecker Modules

## Overview

This document outlines the consolidation opportunities identified across the ast, parser, and typechecker modules to reduce code duplication, improve maintainability, and establish clear single sources of truth.

---

## Phase 1: Immediate (Task #3 - ErrorPrettyPrinter Removal) ✅ COMPLETED

### 1.1 Remove ErrorPrettyPrinter, Consolidate into ErrorFormatter ✅

**Status: COMPLETED**

**Changes Made:**
- Added `prettyPrint()` unified entry point to `ErrorFormatter`
- Added `formatTypeCheckError()` for TypeCheckError exception handling
- Added `formatSpreadSyntaxError()` for spread syntax errors
- Migrated CLI usages: `BuildCommand.kt`, `DevCommand.kt`, `Main.kt`
- Migrated test usages: `SourceLocationErrorTest.kt`
- Deleted `ErrorPrettyPrinter.kt` (373 lines removed)

**Files Modified:**
- `typechecker/src/main/kotlin/com/mezze/language/typechecker/printing/ErrorFormatter.kt` (added ~130 lines)
- `cli/src/main/kotlin/com/mezze/language/cli/commands/BuildCommand.kt`
- `cli/src/main/kotlin/com/mezze/language/cli/commands/DevCommand.kt`
- `cli/src/main/kotlin/com/mezze/language/cli/Main.kt`
- `typechecker/src/test/kotlin/com/mezze/language/typechecker/SourceLocationErrorTest.kt`

**Files Deleted:**
- `typechecker/src/main/kotlin/com/mezze/language/typechecker/ErrorPrettyPrinter.kt`

---

## Phase 2: Short-term (Built-in Type Checks) ✅ COMPLETED

### 2.1 Extend BuiltinTypes with Missing Variant Checks ✅

**Status: COMPLETED**

**Added to BuiltinTypes in `ast/src/main/kotlin/com/mezze/language/ast/BuiltinAbilities.kt`:**
- `isOptionVariant(type: MezzeType): Boolean`
- `getOptionValueType(type: MezzeType.VariantType): MezzeType?`
- `isResultVariant(type: MezzeType): Boolean`
- `getResultTypes(type: MezzeType.VariantType): Pair<MezzeType, MezzeType>?`

### 2.2 Update TypePrinter to Use BuiltinTypes ✅

**Status: COMPLETED**

**Changes Made to `ast/src/main/kotlin/com/mezze/language/ast/TypePrinter.kt`:**
- Removed duplicate helper functions (isBoolVariant, isOptionVariant, getOptionValueType, getResultTypes) - ~53 lines removed
- Updated all usages to use `BuiltinTypes.isBoolVariant()`, `BuiltinTypes.isOptionVariant()`, etc.

### 2.3 Consolidate BuiltInAbilities in Typechecker ✅

**Status: COMPLETED**

**File:** `typechecker/src/main/kotlin/com/mezze/language/typechecker/abilities/BuiltInAbilities.kt`

**Changes Made:**
- Reduced from 316 lines to ~160 lines
- Now uses AST's `BuiltinAbilities` constants (`AstAbilities.NUM_ADD`, etc.)
- Delegates `isBoolVariant()` to `BuiltinTypes.isBoolVariant()`
- Delegates `getBoolType()` to `BuiltinTypes.getBoolType()`
- Delegates `isBuiltInAbility()` to `AstAbilities.isBuiltin()`
- Delegates `getAllAbilityNames()` to `AstAbilities.ALL`
- Kept unique functionality: `getAbilityMethodType()` and `getAvailableMethods()` (method type signatures)

---

## Phase 3: Medium-term (Type Formatting) ✅ COMPLETED

### 3.1 Consolidate TypePrinter and TypeDiffPrinter ✅

**Status: COMPLETED**

**Approach taken:** Option A - Made TypeDiffPrinter delegate to AST's prettyPrintType

**Changes Made to `typechecker/src/main/kotlin/com/mezze/language/typechecker/printing/TypeDiffPrinter.kt`:**
- Reduced from 418 lines to ~270 lines
- Removed duplicate `formatType()` implementation - now delegates to `prettyPrintType(type)` from AST
- Removed duplicate type-specific formatting functions (formatFunctionType, formatRecordType, formatVariantType, formatTypeApplication)
- Kept diff-specific logic: `highlightDifference`, `formatSideBySide`, `formatDiff`
- Kept multiline formatting for side-by-side comparison (different output format needed)

---

## Phase 4: Long-term (Utilities) - SKIPPED

### 4.1 Create SourceLocationUtils in AST Module - NOT NEEDED

**Analysis:** After investigation, `SourceContext.kt` utilities (`expandTabs`, `calculateVisualColumn`, `positionToLocation`) are only used in the typechecker module for error formatting. No duplication exists with the parser.

### 4.2 Create ReservedNames in AST Module - NOT NEEDED

**Analysis:** Reserved name checking (`checkReservedName`, `RESERVED_WORDS`) is only in `ParserHelpers.kt` and only used by the parser. The `__native` prefix is used extensively across the codebase but the checking logic is not duplicated.

---

## Summary by Risk Level

### LOW RISK (Easy Wins)
- ~~Extend BuiltinTypes with variant checks (Phase 2.1)~~ ✅ DONE
- ~~Update TypePrinter to use BuiltinTypes (Phase 2.2)~~ ✅ DONE
- ~~Create SourceLocationUtils (Phase 4.1)~~ - NOT NEEDED (no duplication)
- ~~Create ReservedNames (Phase 4.2)~~ - NOT NEEDED (no duplication)

### MEDIUM RISK (Moderate Effort)
- ~~Remove ErrorPrettyPrinter (Phase 1.1)~~ - **COMPLETED**
- ~~Consolidate typechecker's BuiltInAbilities.kt (Phase 2.3)~~ - **COMPLETED**

### MEDIUM-HIGH RISK (Careful Testing)
- ~~Consolidate TypePrinter/TypeDiffPrinter (Phase 3.1)~~ ✅ DONE

---

## Implementation Order

1. ~~**Phase 1.1** - Remove ErrorPrettyPrinter (Task #3)~~ ✅ DONE
2. ~~**Phase 2.1** - Extend BuiltinTypes with variant checks~~ ✅ DONE
3. ~~**Phase 2.2** - Update TypePrinter to use BuiltinTypes~~ ✅ DONE
4. ~~**Phase 2.3** - Consolidate typechecker's BuiltInAbilities.kt~~ ✅ DONE
5. ~~**Phase 4.1** - Create SourceLocationUtils~~ - SKIPPED (no duplication found)
6. ~~**Phase 4.2** - Create ReservedNames~~ - SKIPPED (no duplication found)
7. ~~**Phase 3.1** - Consolidate type formatting~~ ✅ DONE

---

## Additional Consolidation (Post-Plan)

### Extract Shared AbilityImplementationChecker ✅

**Status: COMPLETED**

**New File:** `typechecker/src/main/kotlin/com/mezze/language/typechecker/abilities/AbilityImplementationChecker.kt`

**Changes Made:**
- Extracted duplicate `isBuiltInAbilityImplementation()` logic from TypeValidator and ConstraintSolver
- Created shared `AbilityImplementationChecker.hasBuiltInImplementation()` function
- Both TypeValidator and ConstraintSolver now delegate to shared checker
- ~80 lines of duplicate code removed

### Add Type and Constructor Constants to BuiltinTypes ✅

**Status: COMPLETED**

**File:** `ast/src/main/kotlin/com/mezze/language/ast/BuiltinAbilities.kt`

**Constants Added:**
- Type name constants: `OPTION`, `RESULT`, `LIST`, `MAP`, `SET`
- Option constructors: `SOME`, `NONE`
- Result constructors: `OK`, `ERR`
- Field names: `VAL_FIELD`, `ERROR_FIELD`

**Files Updated:**
- `ContextBuilder.kt` - uses BuiltinTypes constants
- `ConstraintSolver.kt` - uses BuiltinTypes constants for built-in variant resolution

### Dead Code Removal ✅

**Status: COMPLETED**

**Files Cleaned:**
- `parser/src/main/kotlin/com/mezze/language/parser/ParseHints.kt` - Removed unused `ParseHints` object (~220 lines)
- `parser/src/main/kotlin/com/mezze/language/parser/ParseWarnings.kt` - Removed unused `CommonWarnings` object (~100 lines)
- `parser/src/main/kotlin/com/mezze/language/parser/internal/lib/ParserCombinators.kt` - Removed unused extension functions (~7 lines):
  - `.then()`, `.or()`, `.map()`, `.flatMap()`, `.optional()`, `.many()`, `.many1()`
  - These were never used - the codebase uses `Parsers.*` methods directly
- `ast/src/main/kotlin/com/mezze/language/ast/TypePrinter.kt` - Removed unused functions:
  - `isBuiltinType()`, `getBuiltinTypeName()`, `prettyPrintTypeWithBuiltinInfo()`, `areTypesAlphaEquivalent()` (~200 lines)

### String Constant Migration ✅

**Status: COMPLETED (All Files)**

**Core Typechecker Files Updated:**
- `NativeFunctionTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.RESULT`
- `Unification.kt` - Uses `BuiltinTypes.LIST`, `BuiltinTypes.SET`, `BuiltinTypes.MAP`
- `TypeSubstitutor.kt` - Uses `BuiltinTypes.LIST`, `BuiltinTypes.SET`, `BuiltinTypes.MAP`
- `DuplicateDeclarationValidator.kt` - Uses all constructor constants
- `BasicTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.RESULT`
- `CodecGenerator.kt` - Uses all type and field constants
- `SubstitutionApplier.kt` - Uses all type and constructor constants
- `ConstraintSolver.kt` - Uses `BuiltinTypes.OPTION`

**Type Definition Files Updated (types/codec/):**
- `JsonTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.RESULT`

**Type Definition Files Updated (types/collections/):**
- `CharTypes.kt` - Uses `BuiltinTypes.OPTION`
- `ListTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.SOME`, `BuiltinTypes.NONE`, `BuiltinTypes.VAL_FIELD`
- `MapTypes.kt` - Uses `BuiltinTypes.OPTION`
- `ArrayListTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.SOME`, `BuiltinTypes.NONE`, `BuiltinTypes.VAL_FIELD`
- `BytesTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.RESULT`
- `SortedMapTypes.kt` - Uses `BuiltinTypes.OPTION`
- `SortedSetTypes.kt` - Uses `BuiltinTypes.OPTION`
- `NonEmptyListTypes.kt` - Uses `BuiltinTypes.OPTION`
- `DequeTypes.kt` - Uses `BuiltinTypes.OPTION`

**Type Definition Files Updated (types/concurrent/):**
- `AtomTypes.kt` - Uses `BuiltinTypes.OPTION`
- `ChannelTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.LIST`
- `CancellationTokenTypes.kt` - Uses `BuiltinTypes.OPTION`

**Type Definition Files Updated (types/effects/):**
- `ContinuationTypes.kt` - Uses `BuiltinTypes.OPTION`
- `CoopTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.RESULT`
- `EnvironmentTypes.kt` - Uses `BuiltinTypes.OPTION`
- `FilesystemTypes.kt` - Uses `BuiltinTypes.RESULT`
- `HttpTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.RESULT`, `BuiltinTypes.MAP`
- `LoomTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.RESULT`
- `OSThreadTypes.kt` - Uses `BuiltinTypes.OPTION`, `BuiltinTypes.RESULT`
- `ParallelTypes.kt` - Uses `BuiltinTypes.OPTION`

**Total: ~30 files migrated to use BuiltinTypes constants instead of hardcoded strings.**
