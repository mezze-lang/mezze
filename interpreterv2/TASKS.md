# Interpreter v2 - Implementation Tasks

## Overview

This document tracks the implementation of interpreter v2, designed for near-native performance through proper Truffle DSL usage, typed frame slots, and content-hash based caching.

---

## Phase 1: Project Setup & Core Infrastructure

### 1.1 Create Gradle Module
- [ ] Create `interpreterv2/build.gradle.kts`
- [ ] Configure Truffle DSL annotation processor
- [ ] Set up Java 21+ compilation (required for latest Truffle)
- [ ] Add dependencies: truffle-api, graal-sdk
- [ ] Configure native-image plugin

### 1.2 Core Language Registration
- [ ] Create `MezzeLanguageV2.java` - Truffle language class
- [ ] Create `MezzeContextV2.java` - Runtime context
- [ ] Create `MezzeTypesV2.java` - Type system with primitives first
- [ ] Register language with Truffle

### 1.3 Base Node Classes
- [ ] Create `MezzeNodeV2.java` - Base node class
- [ ] Create `MezzeRootNodeV2.java` - Function root node with typed frame slots
- [ ] Create `MezzeExpressionNode.java` - Expression base class

---

## Phase 2: Typed Frame Slots

### 2.1 Frame Descriptor Builder
- [ ] Create `TypedFrameBuilder.java` - Builds frame descriptors with typed slots
- [ ] Map `inferredType` to `FrameSlotKind` (Long, Double, Boolean, Object)
- [ ] Handle unknown types as Object slots

### 2.2 Variable Nodes
- [ ] Create `ReadLocalNodeV2.java` with typed specializations
  - [ ] `@Specialization` for Long
  - [ ] `@Specialization` for Double
  - [ ] `@Specialization` for Boolean
  - [ ] `@Specialization` for Object (fallback)
- [ ] Create `WriteLocalNodeV2.java` with typed specializations
- [ ] Create `ReadArgumentNode.java` for function parameters

---

## Phase 3: Primitive Arithmetic (Truffle DSL)

### 3.1 Binary Arithmetic Nodes
- [ ] Create `AddNodeV2.java`
  - [ ] `long + long` specialization (with overflow to BigInteger)
  - [ ] `double + double` specialization
  - [ ] String concatenation specialization
- [ ] Create `SubtractNodeV2.java`
- [ ] Create `MultiplyNodeV2.java`
- [ ] Create `DivideNodeV2.java`
- [ ] Create `ModuloNodeV2.java`

### 3.2 Unary Arithmetic Nodes
- [ ] Create `NegateNodeV2.java`

### 3.3 Comparison Nodes
- [ ] Create `EqualNodeV2.java` (structural equality)
- [ ] Create `LessThanNodeV2.java`
- [ ] Create `LessThanOrEqualNodeV2.java`
- [ ] Create `GreaterThanNodeV2.java`
- [ ] Create `GreaterThanOrEqualNodeV2.java`

### 3.4 Logical Nodes
- [ ] Create `AndNodeV2.java` (short-circuit)
- [ ] Create `OrNodeV2.java` (short-circuit)
- [ ] Create `NotNodeV2.java`

---

## Phase 4: Literals & Values

### 4.1 Literal Nodes
- [ ] Create `IntLiteralNode.java` - returns `long`
- [ ] Create `FloatLiteralNode.java` - returns `double`
- [ ] Create `BoolLiteralNode.java` - returns `boolean`
- [ ] Create `StringLiteralNode.java` - returns `String`
- [ ] Create `UnitLiteralNode.java` - returns `MezzeUnit`

### 4.2 Value Classes
- [ ] Create `MezzeRecordV2.java` - typed field arrays
  - [ ] `long[]` for Int fields
  - [ ] `double[]` for Float fields
  - [ ] `Object[]` for other fields
  - [ ] Compile-time field index mapping
- [ ] Create `MezzeVariantV2.java` - integer tag + payload array
- [ ] Create `MezzeFunctionV2.java` - CallTarget + closure array
- [ ] Create `MezzeIntArray.java` - `long[]` backing
- [ ] Create `MezzeFloatArray.java` - `double[]` backing

---

## Phase 5: Records

### 5.1 Record Literal Node
- [ ] Create `RecordLiteralNodeV2.java`
- [ ] Pre-compute field indices from `RecordType`
- [ ] Generate typed field arrays based on field types

### 5.2 Record Access Node
- [ ] Create `RecordFieldAccessNodeV2.java`
- [ ] Use compile-time field index (no string lookup)
- [ ] Typed specializations for primitive fields

### 5.3 Record Update Node
- [ ] Create `RecordUpdateNodeV2.java`
- [ ] Optimistic in-place mutation when record doesn't escape
- [ ] Copy-on-write fallback

### 5.4 Record Pattern Destructuring
- [ ] Create `RecordDestructureNodeV2.java`
- [ ] Pre-compute extraction paths at construction time

---

## Phase 6: Variants

### 6.1 Variant Representation
- [ ] Create `VariantTagRegistry.java` - assigns integer tags to constructors
- [ ] Integrate with typechecker's `VariantType.constructors`

### 6.2 Variant Construction
- [ ] Create `VariantConstructNodeV2.java`
- [ ] Use integer tag instead of string constructor name

### 6.3 Pattern Matching
- [ ] Create `MatchNodeV2.java`
- [ ] Use integer switch for variant dispatch
- [ ] Create `MatchBranchNode.java` for individual branches
- [ ] Handle wildcard patterns
- [ ] Handle nested patterns

---

## Phase 7: Functions & Closures

### 7.1 Function Definition
- [ ] Create `FunctionDefinitionNodeV2.java`
- [ ] Capture closure variables as `Object[]` (already done in v1)
- [ ] Create `FunctionRootNodeV2.java` with typed parameter slots

### 7.2 Function Invocation
- [ ] Create `InvokeNodeV2.java`
- [ ] Use `DirectCallNode` for monomorphic calls
- [ ] Use `IndirectCallNode` for polymorphic calls
- [ ] Implement inline caching with `@Cached`

### 7.3 Positional Arguments
- [ ] Use `resolvedParameterOrder` from typechecker
- [ ] Pass arguments as positional array (no record construction)

### 7.4 Tail Call Optimization
- [ ] Create `TailCallLoopNodeV2.java`
- [ ] Create `TailCallExceptionV2.java`
- [ ] Use `isTailCall` from typechecker to identify tail positions

---

## Phase 8: Control Flow

### 8.1 Conditionals
- [ ] Create `IfNodeV2.java`
- [ ] Short-circuit evaluation
- [ ] Profile-guided branch prediction

### 8.2 Block/Sequence
- [ ] Create `BlockNodeV2.java`
- [ ] Execute children in sequence, return last value

### 8.3 Do Expressions
- [ ] Create `DoBlockNodeV2.java`
- [ ] Handle let bindings within do blocks

---

## Phase 9: Abilities

### 9.1 Ability Call with Hash-Based Dispatch
- [ ] Create `AbilityCallNodeV2.java`
- [ ] Use `implementationHash` from `ResolvedAbility`
- [ ] Inline cache with `DirectCallNode` and `@Cached`

### 9.2 Ability Implementation Storage
- [ ] Store by content hash in context
- [ ] Enable cross-module lookup by hash

---

## Phase 10: Effects

### 10.1 Effect Handler Node
- [ ] Create `PerformNodeV2.java`
- [ ] Create `HandleNodeV2.java`
- [ ] Handler stack as array (cache-friendly)

### 10.2 Continuation Support
- [ ] Integrate with existing continuation implementation
- [ ] Optimize single-shot continuations

### 10.3 Parameterized Handlers
- [ ] Create `ParameterizedImplNodeV2.java`
- [ ] Pre-construct operation functions as `@Children`

---

## Phase 11: AST to Truffle Converter

### 11.1 Core Converter
- [ ] Create `ASTToTruffleConverterV2.java`
- [ ] Use `inferredType` to select node variants
- [ ] Use `inferredType` to select `FrameSlotKind`
- [ ] Pre-compute field indices for records
- [ ] Pre-compute variant tags

### 11.2 Type-Guided Node Selection
- [ ] For arithmetic: select `long`/`double` variant based on operand types
- [ ] For comparisons: select specialized variants
- [ ] For records: compute field layout at conversion time

### 11.3 Module Support
- [ ] Handle imports/exports
- [ ] Content-hash based module resolution

---

## Phase 12: Content-Hash Based Caching

### 12.1 CallTarget Cache
- [ ] Create `CallTargetCache.java`
- [ ] Map content hash → compiled CallTarget
- [ ] Thread-safe concurrent access

### 12.2 Two-Level Cache
- [ ] L1: In-memory `ConcurrentHashMap`
- [ ] L2: SQLite persistent storage
- [ ] Cache warm-up on startup

### 12.3 Cache Invalidation
- [ ] Content hash changes → new entry (no invalidation needed)
- [ ] Memory pressure → LRU eviction

---

## Phase 13: Polyglot Interop

### 13.1 Polyglot Context Management
- [ ] Create `MezzePolyglotSupport.java`
- [ ] Lazy context creation
- [ ] Language availability detection

### 13.2 Polyglot Nodes
- [ ] Create `PolyglotImportNode.java`
- [ ] Create `PolyglotEvalNode.java`
- [ ] Create `PolyglotInvokeNode.java`
- [ ] Create `PolyglotReadMemberNode.java`
- [ ] Create `PolyglotWriteMemberNode.java`

### 13.3 Type Conversion
- [ ] Mezze → Polyglot value conversion
- [ ] Polyglot → Mezze value conversion
- [ ] Handle PolyglotObject wrapper type

### 13.4 Native Binary Detection
- [ ] Detect polyglot usage during type checking
- [ ] Helpful error messages for native binary users

---

## Phase 14: Primitive Arrays

### 14.1 Specialized Array Types
- [ ] `MezzeIntArray` backed by `long[]`
- [ ] `MezzeFloatArray` backed by `double[]`
- [ ] `MezzeBoolArray` backed by `boolean[]`

### 14.2 Array Operations
- [ ] Create `ArrayLiteralNodeV2.java` with type detection
- [ ] Create `ArrayAccessNodeV2.java` with typed specializations
- [ ] Create `ArrayLengthNodeV2.java`
- [ ] Create `ArraySliceNodeV2.java`

### 14.3 Array Iteration
- [ ] Optimized iteration for primitive arrays
- [ ] Avoid boxing during iteration

---

## Phase 15: Optimistic Mutation

### 15.1 Escape Analysis
- [ ] Track `doesEscape` on expressions (typechecker enhancement)
- [ ] Track `useCount` for single-use optimization

### 15.2 In-Place Record Updates
- [ ] Guard: `!record.hasEscaped()`
- [ ] Mutate `values[]` directly when safe
- [ ] Fall back to copy-on-write

### 15.3 Array Builder Optimization
- [ ] Mutable buffer for array construction
- [ ] Convert to immutable at boundary

---

## Phase 16: Integration & Testing

### 16.1 Test Suite
- [ ] Port existing interpreter tests to v2
- [ ] Add primitive unboxing tests
- [ ] Add typed frame slot tests
- [ ] Add variant tag tests
- [ ] Add ability dispatch tests

### 16.2 Benchmark Suite
- [ ] `fib(35)` - integer arithmetic
- [ ] Record field access benchmark
- [ ] Variant matching benchmark
- [ ] Ability call benchmark
- [ ] Array iteration benchmark

### 16.3 CLI Integration
- [ ] Add `--interpreter-v2` flag to mez
- [ ] Gradual rollout: v1 default, v2 opt-in
- [ ] Performance comparison tooling

---

## Phase 17: Native Image

### 17.1 Native Image Configuration
- [ ] Reflection configuration for Truffle nodes
- [ ] Resource inclusion for stdlib
- [ ] Language service registration

### 17.2 Build Variants
- [ ] Core build (Mezze only)
- [ ] Full build with polyglot (optional)

---

## Phase 18: Distributed Execution (Future)

### 18.1 Wire Protocol
- [ ] Task message format (hash + args)
- [ ] Result serialization

### 18.2 Worker Runtime
- [ ] Minimal JAR with interpreter only
- [ ] Code-fetch by hash
- [ ] Result streaming

---

## Progress Tracking

| Phase | Description | Status | Notes |
|-------|-------------|--------|-------|
| 1 | Project Setup | **Done** | Core infrastructure complete |
| 2 | Typed Frame Slots | **Done** | Read/Write nodes with typed access |
| 3 | Primitive Arithmetic | **Done** | All arithmetic nodes with DSL |
| 4 | Literals & Values | **Done** | All literals and value classes |
| 5 | Records | **Done** | Typed field arrays, field access |
| 6 | Variants | **Done** | Integer tags, pattern matching |
| 7 | Functions & Closures | **Done** | Definition, invocation with caching |
| 8 | Control Flow | **Done** | If, Block, And, Or, Not |
| 9 | Abilities | Not Started | |
| 10 | Effects | Not Started | |
| 11 | AST Converter | Not Started | |
| 12 | Hash Caching | Not Started | |
| 13 | Polyglot Interop | Not Started | |
| 14 | Primitive Arrays | Not Started | |
| 15 | Optimistic Mutation | Not Started | |
| 16 | Testing | Not Started | |
| 17 | Native Image | Not Started | |
| 18 | Distributed (Future) | Not Started | |

---

## Dependencies Between Phases

```
Phase 1 (Setup)
    │
    ├──► Phase 2 (Frame Slots)
    │        │
    │        └──► Phase 3 (Arithmetic)
    │        │
    │        └──► Phase 4 (Literals)
    │
    ├──► Phase 5 (Records) ◄── Phase 4
    │
    ├──► Phase 6 (Variants) ◄── Phase 4
    │
    ├──► Phase 7 (Functions) ◄── Phase 2, 3
    │        │
    │        └──► Phase 9 (Abilities)
    │        │
    │        └──► Phase 10 (Effects)
    │
    ├──► Phase 8 (Control Flow) ◄── Phase 2
    │
    └──► Phase 11 (Converter) ◄── All above
             │
             ├──► Phase 12 (Caching)
             │
             ├──► Phase 13 (Polyglot)
             │
             ├──► Phase 14 (Arrays)
             │
             └──► Phase 15 (Mutation)
                      │
                      └──► Phase 16 (Testing)
                               │
                               └──► Phase 17 (Native)
```

---

## Getting Started

Start with Phase 1.1 - Create the Gradle module and configure Truffle DSL.
