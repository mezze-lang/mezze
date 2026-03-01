# Interpreter v2 - Implementation Tasks

## Overview

This document tracks the implementation of interpreter v2, designed for near-native performance through proper Truffle DSL usage, typed frame slots, and content-hash based caching.

**Design Principle: NO @TruffleBoundary in hot paths.** All nodes must be fully JIT-compilable.

---

## Current Status

### Performance Benchmarks (JVM mode, after warmup)

| Operation | Time/op | Status |
|-----------|---------|--------|
| Baseline Java arithmetic | 1.06 ns | Reference |
| Record create+access | 1.64 ns | ✅ Excellent |
| Record field access | 4.96 ns | ✅ Excellent |
| Variant tag match | 7.96 ns | ✅ Excellent |
| Direct CallTarget call | 20.57 ns | ✅ Good |
| Predictable branch | 20.78 ns | ✅ Good |
| Closure function call | 21.16 ns | ✅ Good |
| Simple function (a+b) | 21.66 ns | ✅ Good |
| If-then-else | 46.66 ns | ✅ Good |
| Integer comparison | 59.09 ns | ✅ Good |
| Double arithmetic | 54.33 ns | ✅ Good |
| Boolean AND | 66.44 ns | ✅ Good |
| Integer arithmetic (complex) | 148.36 ns | Acceptable |

### Optimizations Applied

**Foundational Optimizations:**
1. **Shared Empty Arrays**: All node types use shared static empty arrays for no-allocation fast paths
2. **Pre-computed Counts**: `@CompilationFinal` counts for `@ExplodeLoop` unrolling
3. **Branch Profiling**: `CountingConditionProfile` per match branch
4. **Typed Literal Matching**: Pre-computed typed literal values avoid boxing in pattern matching
5. **Direct Array Access**: No bounds checking in hot paths, deferred to interpreter
6. **System.arraycopy Optimization**: Used for closure value copying when > 0 elements

**Monomorphization & Content-Hash Optimizations:**
7. **StaticAbilityCallNode**: Pre-resolved CallTarget, no lazy initialization, zero runtime overhead
8. **StaticPerformNode**: Pre-resolved effect handlers, fully compile-time dispatch
9. **StaticInvokeNode**: Direct function calls for known top-level functions (arity-specialized: 0-3 args)
10. **StaticFieldAccessNode**: Type-specialized record field access (LongField, DoubleField, ObjectField)
11. **InlinedAbilityCallNode**: Operation body inlined directly at call site
12. **SpecializedRootNode**: Arity-specialized root nodes (ZeroArg, OneInt, TwoInt, TwoDouble, etc.)
13. **TagRegistry HashMap**: O(1) name→tag lookup instead of O(n) linear scan
14. **CompiledNodeCache**: Persistent content-hash based cache for warmup hints

**Aggressive Micro-Optimizations:**
15. **Zero-Closure Fast Paths**: InvokeNode, TailCallNode, MezzeFunction, PerformNode, AbilityCallNode all skip closure handling when no closure exists
16. **Inline Loops**: All closure/argument copying uses inline loops instead of System.arraycopy (JIT unrolls better for small N)
17. **Jump Table Dispatch**: MatchNode uses O(1) array indexing for dense variant tags instead of O(N) linear search
18. **Truffle DSL Specializations**: TailCallNode, InvokeNode use @Specialization with cached closure length for full loop unrolling
19. **Cached Closure Length**: InvokeNode caches closure length for compile-time constant unrolling

**Advanced Truffle Optimizations:**
20. **Inlined Profiles**: IfNode, AndNode, OrNode use `InlinedConditionProfile` for better memory layout and faster interpreter
21. **@Idempotent Guards**: Guard methods marked `@Idempotent` to enable fast-path guard elision
22. **@ReportPolymorphism.Megamorphic**: InvokeNode megamorphic path marked for Truffle splitting heuristics
23. **Primitive Arrays**: `MezzeIntArray` (long[]) and `MezzeFloatArray` (double[]) for ZERO boxing overhead
24. **Type-Specialized Array Access**: `ArrayAccessNode` returns primitive long/double directly, no boxing
25. **Shared Cached Profiles**: `@Shared("boundsError")` to share branch profiles across specializations
26. **LoopConditionProfile**: TailCallLoopNode uses `LoopConditionProfile` for loop continuation prediction

**Expert-Level Optimizations (LATEST):**
27. **@GenerateInline Nodes**: `InlineNegateNode`, `InlineNotNode`, `InlineAddNode` can be embedded directly in parent nodes
28. **Assumption Guards**: `ImplRegistry` provides `Assumption` objects to guard stable impl dispatch with deoptimization support
29. **StaticAbilityCallNode with Assumptions**: Uses `Assumption.check()` for zero-overhead guard on stable impls
30. **OSR Loop Nodes**: `WhileLoopNode` and `ForEachLoopNode` use Truffle's `LoopNode` for On-Stack Replacement
31. **Type-Specialized ForEach**: `ForEachLoopNode` iterates `MezzeIntArray`/`MezzeFloatArray` with NO BOXING
32. **Frame Slot Clearing**: `MezzeRootNode` clears frame slots after execution to help escape analysis
33. **Static Specialization Methods**: NegateNode, NotNode use `static` methods for better JIT inlining

**AST-to-Truffle Converter (Phase 11):**
34. **ASTToTruffleConverterV2.kt**: Full AST-to-node conversion with typed frame slots
35. **Content-Hash Dispatch**: Uses `inferredContentHash` for ability/impl resolution
36. **Typed Slot Allocation**: Based on `inferredType`, allocates KIND_LONG, KIND_DOUBLE, KIND_BOOLEAN, KIND_OBJECT
37. **Helper Nodes**: UseBindingNode, GlobalLookupNode, ArrayLiteralNode, InterpolatedStringNode, etc.
38. **Static Dispatch Integration**: Wires up StaticAbilityCallNode, StaticPerformNode for known calls
39. **Parameterized Impls**: ParameterizedImplDefinitionNode/RootNode for proper Truffle closure capture

**Converter Optimization Pass (Phase 11.1):**
40. **BuiltinBinaryNodes.java**: JIT-compiled arithmetic/comparison nodes with type specialization
    - BuiltinAddNode, BuiltinSubNode, BuiltinMulNode, BuiltinDivNode, BuiltinModNode
    - BuiltinEqNode, BuiltinNeqNode, BuiltinLtNode, BuiltinGtNode, BuiltinLeNode, BuiltinGeNode
    - BuiltinAndNode, BuiltinOrNode
    - Guards for isLongLongRecord/isDoubleDoubleRecord for typed fast paths
41. **BuiltinUnaryNodes.java**: JIT-compiled unary nodes (BuiltinNegNode, BuiltinNotNode)
42. **ResolvedAbilityCallNode.java**: Optimized ability dispatch with DirectCallNode caching
    - @Idempotent guard for hasLocalSlot()
    - Separate specializations for frame-slot vs context lookup
    - NO @TruffleBoundary in hot path
43. **inferKind Optimization**: Uses proper sealed class matching instead of toString() comparison
44. **InterpolatedStringNode**: Optimized to move @TruffleBoundary outside the @ExplodeLoop
45. **ArrayLiteralNode.java**: JIT-compiled array creation with fast paths for 0/1/2 elements
    - Direct MezzeList construction without @TruffleBoundary
    - Pre-computed elementCount for @ExplodeLoop
46. **MezzeList.of()**: Factory methods for 1/2 element lists with strategy selection

**Ultra-Advanced Optimizations (Phase 15 - LATEST):**
47. **Shape-based Inline Caching**: `CachedFieldAccessNode` - Polymorphic inline cache for record field access
    - Caches Shape + field location for O(1) repeated access
    - Monomorphic (single shape), polymorphic (2-3 shapes), megamorphic fallback
    - NO @TruffleBoundary in hot path - fully JIT compiled
48. **Speculative Function Inlining**: `InlinedCallNode` - Inline small functions at call site
    - Functions < 15 nodes are inlined via `NodeUtil.cloneNode()`
    - Assumption guards for deoptimization on function redefinition
    - Eliminates call/return overhead, enables cross-function optimization
49. **Profile-Guided Node Splitting**: Type profiling for optimized dispatch
    - `TypeProfileNode`: Records observed types (monomorphic/polymorphic/megamorphic)
    - `ValueProfileNode`: Constant propagation for known values
    - `BranchProfileNode`: Branch prediction for conditional optimization
    - `SplittableNode`: Wrapper for Truffle node splitting (@ReportPolymorphism)
50. **Allocation Sinking**: Delay/eliminate object allocation via escape analysis
    - `VirtualRecordNode`: Virtual records kept in registers until escape
    - `VirtualVariantNode`: Virtual variants with singleton optimization for nullary
    - `AllocationSinkingNode`: Type-specialized allocation tracking
    - `PartialEscapeNode`: Branch-aware escape analysis
51. **Bytecode Interpreter with OSR**: Compact bytecode for hot loops
    - `BytecodeNode`: Stack-based bytecode interpreter with 26 opcodes
    - `BytecodeLoopNode`: OSR-enabled bytecode execution via Truffle LoopNode
    - @ExplodeLoop(kind=MERGE_EXPLODE) for JIT loop unrolling
    - Zero boxing for int/double arithmetic operations

**Hyper-Aggressive Optimizations (Phase 16 - LATEST):**
52. **Per-Function Inlining Assumptions**: `MezzeFunction.getInlineAssumption()`
    - Each function has its own Assumption for deoptimization
    - `InlinedCallNode` uses function's assumption instead of ALWAYS_VALID
    - Proper deoptimization when functions are redefined
53. **Structural Sharing for Records**: `MezzeRecord.withField()` optimization
    - Only clones the array that needs modification
    - Other arrays are SHARED between versions (copy-on-write)
    - Added `withFields()` for batch updates with minimal allocation
54. **SIMD/Vectorization Hints**: `VectorizedArrayOps.java`
    - `VectorAddNode`, `VectorMulNode` - element-wise operations
    - `ScalarMulNode` - scalar-vector multiplication
    - `VectorSumNode`, `VectorDotNode` - 4-way parallel reduction for SIMD
    - `VectorMapNode` - map unary operations (negate, abs, sqrt, square)
55. **Strength Reduction**: `StrengthReductionNodes.java`
    - `OptimizedMulNode`: x*2→x<<1, x*4→x<<2, etc.
    - `OptimizedDivNode`: x/2→x>>1 for non-negative
    - `OptimizedModNode`: x%2→x&1, x%power_of_2→x&(n-1)
    - `ConstantFoldNode`: Compile-time constant evaluation
    - `IdentityAddNode`, `IdentityMulNode`, `ZeroMulNode` - algebraic identities
56. **Dispatch Table for Abilities**: `DispatchTableNode.java`
    - O(1) array-based dispatch by operation ID
    - `DispatchTable` with Assumption-guarded stability
    - `StaticDispatchNode` - resolved at AST construction time
    - Zero runtime lookup overhead after warmup
57. **Persistent Vector**: `PersistentVector.java`
    - 32-way branching tree for O(log32 n) ≈ O(1) operations
    - Structural sharing on append/update
    - Memory-efficient for functional programming patterns
    - Cache-friendly traversal

---

## Phase 1: Project Setup & Core Infrastructure ✅ COMPLETE

### 1.1 Create Gradle Module ✅
- [x] Create `interpreterv2/build.gradle.kts`
- [x] Configure Truffle DSL annotation processor
- [x] Set up Java 24 compilation
- [x] Add dependencies: truffle-api, graal-sdk, polyglot
- [x] Pure Java module (no Kotlin mixing)

### 1.2 Core Language Registration ✅
- [x] Create `MezzeLanguage.java` - Truffle language class
- [x] Create `MezzeContext.java` - Runtime context
- [x] Create `MezzeTypes.java` - Type system (long, double, boolean first)
- [x] Create `MezzePolyglotSupport.java` - Polyglot interop

### 1.3 Base Node Classes ✅
- [x] Create `MezzeNode.java` - Base node class
- [x] Create `MezzeRootNode.java` - Function root with typed slot binding

---

## Phase 2: Typed Frame Slots ✅ COMPLETE

### 2.1 Variable Nodes ✅
- [x] Create `ReadLocalNode.java` - Typed frame slot access (Long/Double/Boolean/Object)
- [x] Create `WriteLocalNode.java` - Typed frame slot writes
- [x] Create `ReadArgumentNode.java` - Direct argument array access

---

## Phase 3: Primitive Arithmetic (Truffle DSL) ✅ COMPLETE

### 3.1 Binary Arithmetic Nodes ✅
- [x] `AddNode.java` - long+long (overflow to double), double+double, string concat
- [x] `SubtractNode.java` - Typed subtraction
- [x] `MultiplyNode.java` - Typed multiplication
- [x] `DivideNode.java` - Typed division
- [x] `ModuloNode.java` - Typed modulo

### 3.2 Unary Arithmetic Nodes ✅
- [x] `NegateNode.java` - Long and double negation

### 3.3 Comparison Nodes ✅
- [x] `EqualNode.java` - Structural equality with identity fast path
- [x] `NotEqualNode.java` - Structural inequality
- [x] `LessThanNode.java` - Typed comparison
- [x] `LessThanOrEqualNode.java`
- [x] `GreaterThanNode.java`
- [x] `GreaterThanOrEqualNode.java`

### 3.4 Logical Nodes ✅
- [x] `AndNode.java` - Short-circuit AND
- [x] `OrNode.java` - Short-circuit OR
- [x] `NotNode.java` - Boolean negation

---

## Phase 4: Literals & Values ✅ COMPLETE

### 4.1 Literal Nodes ✅
- [x] `IntLiteralNode.java` - Returns `long` directly
- [x] `FloatLiteralNode.java` - Returns `double` directly
- [x] `BoolLiteralNode.java` - Returns `boolean` directly
- [x] `StringLiteralNode.java` - Returns `String`
- [x] `UnitLiteralNode.java` - Returns `MezzeUnit` singleton

### 4.2 Value Classes ✅
- [x] `MezzeRecord.java` - Typed field arrays (long[], double[], Object[])
- [x] `MezzeVariant.java` - Integer tags with TagRegistry
- [x] `MezzeFunction.java` - CallTarget + closure values
- [x] `MezzeUnit.java` - Unit singleton
- [x] `MezzeNull.java` - Null singleton

---

## Phase 5: Records ✅ COMPLETE

### 5.1 Record Nodes ✅
- [x] `RecordLiteralNode.java` - Creates typed records
- [x] `RecordFieldAccessNode.java` - O(1) field access by index
- [x] `RecordUpdateNode.java` - Copy-on-write field update

---

## Phase 6: Variants ✅ COMPLETE

### 6.1 Variant Nodes ✅
- [x] `VariantConstructNode.java` - Integer tag construction
- [x] `MatchNode.java` - Tag-based dispatch (integer comparison)
- [x] `MatchBranchNode.java` - Pattern binding

---

## Phase 7: Functions ✅ COMPLETE

### 7.1 Function Nodes ✅
- [x] `FunctionDefinitionNode.java` - Creates MezzeFunction with closure capture
- [x] `InvokeNode.java` - DirectCallNode caching (mono/polymorphic/megamorphic)
- [x] `TailCallNode.java` - Throws TailCallException
- [x] `TailCallLoopNode.java` - Catches exception, rebinds arguments
- [x] `TailCallException.java` - Control flow for TCO

---

## Phase 8: Control Flow ✅ COMPLETE

### 8.1 Control Flow Nodes ✅
- [x] `IfNode.java` - CountingConditionProfile for branch prediction
- [x] `BlockNode.java` - Sequential execution

---

## Phase 9: Effects ✅ COMPLETE (NO @TruffleBoundary)

### 9.1 Effect Nodes ✅
- [x] `PerformNode.java` - Fully JIT-compilable
  - Handler passed as node child (statically resolved)
  - Pre-computed operation index for O(1) access
  - DirectCallNode for operation calls
- [x] `HandleNode.java` - Fully JIT-compilable
  - Handler stored in frame slot (no context lookup)
  - Proper nested handler support

### 9.2 Ability Nodes ✅
- [x] `AbilityCallNode.java` - Fully JIT-compilable
  - Impl passed as node child (statically resolved)
  - Pre-computed operation index
  - DirectCallNode for inlined calls

---

## Phase 10: Polyglot ✅ COMPLETE

### 10.1 Polyglot Nodes ✅
- [x] `PolyglotImportNode.java` - Module import with caching
- [x] `PolyglotEvalNode.java` - Code eval with constant caching
- [x] `PolyglotInvokeNode.java` - Lazy InteropLibrary init (no @TruffleBoundary in hot path)
- [x] `PolyglotExecuteNode.java` - Lazy InteropLibrary init
- [x] `PolyglotReadMemberNode.java` - Lazy InteropLibrary init

---

## Phase 11: AST-to-Truffle Converter ✅ COMPLETE

### 11.1 Converter Infrastructure ✅
- [x] Create `ASTToTruffleConverterV2.kt`
- [x] Wire up to existing parser/typechecker output
- [x] Handle `inferredType` for typed slot allocation

### 11.2 Expression Conversion ✅
- [x] Convert all expression types to v2 nodes
- [x] Use content hash for ability/impl dispatch

### 11.3 Declaration Conversion ✅
- [x] Convert function declarations
- [x] Convert ability declarations
- [x] Convert impl declarations
- [x] Handle parameterized impls properly (no @TruffleBoundary)

---

## Phase 12: Content-Hash Caching ✅ COMPLETE

### 12.1 Caching Infrastructure ✅
- [x] `CompiledNodeCache.java` - Persistent cache with disk backing
- [x] In-memory ConcurrentHashMap for current session
- [x] Disk cache (~/.mezze/cache/compiled/) for warmup hints
- [x] Cache statistics tracking (hits, misses, hit rate)

### 12.2 Integration ✅
- [x] MezzeContext.getOrComputeCached() for cached CallTarget creation
- [x] MezzeContext.wasPreviouslyCompiled() for warmup prioritization
- [x] Content-hash keys for all cached items

### 12.3 Static Dispatch Nodes ✅
- [x] `StaticAbilityCallNode.java` - Pre-resolved ability calls
- [x] `StaticPerformNode.java` - Pre-resolved effect handlers
- [x] `StaticInvokeNode.java` - Pre-resolved top-level functions (arity-specialized)
- [x] `StaticFieldAccessNode.java` - Type-specialized field access
- [x] `InlinedAbilityCallNode.java` - Fully inlined ability operations
- [x] `SpecializedRootNode.java` - Arity-specialized function roots

---

## Phase 13: Testing & Benchmarking ✅ STARTED

### 13.1 Performance Tests ✅
- [x] `NodePerformanceTest.java` - Basic operation benchmarks
- [x] `FunctionCallPerformanceTest.java` - Function call overhead

### 13.2 Integration Tests
- [ ] Port existing interpreter tests
- [ ] Add effect handling tests
- [ ] Add ability dispatch tests

---

## Phase 14: Native Image 🔴 NOT STARTED

### 14.1 Native Image Support
- [ ] Configure native-image build
- [ ] Add reflection/resource configuration
- [ ] Verify JIT compilation in native mode
- [ ] Test that no @TruffleBoundary in hot paths

---

## Phase 15: Drop-in Replacement Integration 🔴 PARTIALLY COMPLETE

### 15.1 Build Configuration ✅
- [x] Added `interpreterv2` dependency to interpreter module
- [x] Created `V2AdapterNode.java` for converting V2 results to V1 MezzeValue types
- [x] Added `USE_V2` system property flag to `MezzeLanguage.kt`

### 15.2 Integration Challenges (BLOCKED)
The V2 interpreter cannot be used as a direct drop-in replacement because:

1. **Different Runtime Context**:
   - V1 uses `com.mezze.language.interpreter.MezzeContext`
   - V2 uses `com.mezze.interpreter.v2.runtime.MezzeContext`
   - V2 nodes call `MezzeContext.get(this)` which expects V2's context

2. **Different MezzeNode Base Classes**:
   - V1's `MezzeNode.execute()` returns `MezzeValue`
   - V2's `MezzeNode.execute()` returns `Object` (raw primitives)
   - Simple adapter wrapping doesn't work because V2 nodes need V2 context

3. **Different Type Systems**:
   - V2 uses `TagRegistry` for variants (integer tags, dynamic allocation)
   - V1 uses string-based variant constructors
   - The adapter causes `ArrayIndexOutOfBoundsException` due to mismatched tag registries

### 15.3 Recommended Solutions

**Option A: Separate Language Registration** (Recommended)
- Register V2 as `mezze-v2` language with its own context
- V2 would be a completely separate execution path
- Tests can choose which interpreter to use

**Option B: Unified Node Architecture**
- Modify V1 to return `Object` instead of `MezzeValue`
- Share MezzeNode base class between V1 and V2
- Significant refactoring required

**Option C: Keep V2 Standalone**
- V2 runs as standalone interpreter via `MezzeInterpreterV2`
- No integration with V1's test infrastructure
- Requires porting tests to V2 test helpers

---

## Key Design Decisions

1. **ReadArgumentNode vs ReadLocalNode**:
   - ReadArgumentNode: Direct frame.getArguments() access (simpler, for functions with no local variables)
   - ReadLocalNode: Typed frame slot access (for functions with locals and type-specialized values)

2. **Handler/Impl Resolution**:
   - Statically resolved by typechecker
   - Passed as @Child node to PerformNode/AbilityCallNode
   - NO runtime lookups in context

3. **Typed Frame Slots**:
   - MezzeRootNode.slotTypes[] controls argument binding
   - Empty slotTypes[] = use ReadArgumentNode pattern
   - Non-empty slotTypes[] = bind to typed frame slots

4. **@TruffleBoundary Usage**:
   - ONLY allowed in: module import, code eval, toString(), equals(), hashCode()
   - NEVER in: arithmetic, comparison, function calls, effect handling, ability dispatch
