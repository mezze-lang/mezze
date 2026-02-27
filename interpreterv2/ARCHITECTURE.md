# Mezze Interpreter v2 - Complete High Performance Architecture

## Goal: Near-Native Speed for ALL Code

This document describes a complete interpreter rewrite that leverages:
1. **Static type information** from the typechecker
2. **Content-addressable hashing** for caching and dispatch
3. **Proper Truffle DSL** patterns from SimpleLanguage/TruffleRuby
4. **Optimistic mutation** where safe
5. **Efficient data structures** with compile-time layout

---

## Part 0: What Mezze Has That Dynamic Languages Don't

### 0.1 Static Type Information

Every AST expression carries rich type info after typechecking:

```kotlin
// On EVERY expression:
inferredType: MezzeType?              // Concrete type (IntType, StringType, RecordType, etc.)

// On function calls:
typeSubstitution: Map<String, MezzeType>      // Generic → Concrete mapping
instantiatedFunctionType: FunctionType?       // Concrete function signature
resolvedFunctionName: String?                 // Monomorphized name "map$Int$String"
resolvedParameterOrder: List<String>?         // Positional argument order
resolvedAbilities: Map<String, ResolvedAbility>  // Resolved ability implementations
isTailCall: Boolean                           // TCO marker

// On variants:
resolution: ConstructorResolution             // Variant vs Ability vs Effect vs Handler
```

### 0.2 Content-Addressable Hashing

Every declaration has a stable SHA3-512 hash:

```kotlin
contentHash: String?          // On LetBinding, TypeDeclaration, ImplDeclaration, etc.
implementationHash: String?   // On ResolvedAbility for dispatch
```

Hash properties:
- **Structural**: Based on AST structure, not names
- **Alpha-invariant**: Renaming doesn't change hash
- **Stable**: Same code = same hash across machines

### 0.3 Why This Is Game-Changing

| Feature | Dynamic Languages | Mezze |
|---------|-------------------|-------|
| Type discovery | Runtime profiling | Compile-time known |
| Specialization | After 10,000+ runs | Immediate |
| Deoptimization | Required | Never needed |
| Function dispatch | Name lookup | Hash-based O(1) |
| Ability dispatch | Dynamic search | Hash-keyed direct |
| Caching | Per-session | Persistent across runs |

---

## Part 1: Truffle DSL - The Right Way

### 1.1 Current v1 Problems

```kotlin
// v1 AddNode - Manual type dispatch
class AddNode(...) : BinaryArithmeticNode(...) {
    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeValue {
        return MezzeInt(Math.addExact(left.value, right.value))  // BOXING!
    }
}
```

Problems:
- Returns `MezzeValue` (boxed) not `long` (primitive)
- Manual type profiling instead of DSL
- Kotlin intrinsics may trigger JIT blockers

### 1.2 v2 Solution: Truffle DSL with Primitives

```java
@NodeChild("left")
@NodeChild("right")
@TypeSystemReference(MezzeTypesV2.class)
public abstract class AddNode extends MezzeNode {

    // FAST PATH: Unboxed primitives
    @Specialization(rewriteOn = ArithmeticException.class)
    protected long addLongs(long left, long right) {
        return Math.addExact(left, right);  // No boxing!
    }

    @Specialization
    protected double addDoubles(double left, double right) {
        return left + right;  // No boxing!
    }

    // SLOW PATH: Boxed types (strings, fractions, etc.)
    @Specialization
    @TruffleBoundary
    protected MezzeFrac addFracs(MezzeFrac left, MezzeFrac right) {
        return left.add(right);
    }
}
```

### 1.3 Type-Guided Specialization Selection

Since we have `inferredType`, we can **pre-select** the specialization:

```java
// In AST-to-Node converter
MezzeNode createAddNode(Expression left, Expression right) {
    MezzeType leftType = left.getInferredType();
    MezzeType rightType = right.getInferredType();

    // Generate specialized node based on known types
    if (leftType == IntType.INSTANCE && rightType == IntType.INSTANCE) {
        return AddNodeGen.create(
            createNode(left),
            createNode(right),
            SpecializationHint.LONG_LONG  // Skip polymorphic dispatch!
        );
    }
    // ... other type combinations
}
```

---

## Part 2: Typed Frame Slots

### 2.1 The Problem with Object Slots

```kotlin
// v1: Everything is Object
frame.setObject(slot, MezzeInt(42))  // Boxed!
val value = frame.getObject(slot) as MezzeInt  // Unbox + cast
```

### 2.2 v2: Primitive Slots Based on inferredType

```java
// During frame descriptor construction
for (param in function.parameters) {
    MezzeType type = param.inferredType;

    FrameSlotKind kind = switch (type) {
        case IntType -> FrameSlotKind.Long;
        case FloatType -> FrameSlotKind.Double;
        case BoolType -> FrameSlotKind.Boolean;
        default -> FrameSlotKind.Object;
    };

    frameBuilder.addSlot(kind, param.name, null);
}

// At runtime - no boxing!
frame.setLong(slot, 42L);           // Direct primitive
long value = frame.getLong(slot);   // Direct primitive
```

### 2.3 ReadLocalNode with Type Hints

```java
public abstract class ReadLocalNode extends MezzeNode {
    private final int slot;
    private final FrameSlotKind expectedKind;  // From inferredType!

    @Specialization(guards = "expectedKind == FrameSlotKind.Long")
    protected long readLong(VirtualFrame frame) {
        return frame.getLong(slot);  // No boxing
    }

    @Specialization(guards = "expectedKind == FrameSlotKind.Double")
    protected double readDouble(VirtualFrame frame) {
        return frame.getDouble(slot);  // No boxing
    }

    @Specialization(guards = "expectedKind == FrameSlotKind.Object")
    protected Object readObject(VirtualFrame frame) {
        return frame.getObject(slot);
    }
}
```

---

## Part 3: Array-Backed Records

### 3.1 Current v1: Already Array-Based!

Good news - MezzeRecord already uses array storage:

```kotlin
class MezzeRecord private constructor(
    val shape: RecordShape,           // Field name → index mapping
    private val values: Array<MezzeValue>  // Array storage!
)
```

### 3.2 v2 Enhancement: Compile-Time Field Indices

Since typechecker knows field types, we can:

```java
// From RecordType.fields we know exact layout
RecordType type = (RecordType) expr.inferredType;
Map<String, MezzeType> fields = type.getFields();

// Assign compile-time indices
int xIndex = 0;  // {x: Int, y: String} → x at 0
int yIndex = 1;  //                     → y at 1

// Generate direct array access
class RecordFieldAccessNode extends MezzeNode {
    private final int fieldIndex;  // Known at compile time!

    @Specialization
    protected Object access(MezzeRecord record) {
        return record.values[fieldIndex];  // Direct array access!
    }
}
```

### 3.3 Typed Record Fields

For records with all primitive fields:

```java
// RecordType: {x: Int, y: Int, z: Float}
class TypedRecordShape {
    long[] longFields;    // [x, y] - unboxed!
    double[] doubleFields; // [z] - unboxed!
    Object[] objectFields; // empty
}
```

---

## Part 4: Integer-Tagged Variants

### 4.1 Current: String-Based Constructor Matching

```kotlin
data class MezzeVariant(
    val constructor: String,  // "Some", "None", etc.
    val value: MezzeValue?
)

// Pattern match does string comparison
if (variant.constructor == "Some") { ... }
```

### 4.2 v2: Integer Tags from TypeChecker

The typechecker knows all variant constructors:

```kotlin
// VariantType.constructors: Map<String, MezzeType?>
// {"Some" → {value: 'a}, "None" → null}

// Assign integer tags at compile time
enum class OptionTag { SOME = 0, NONE = 1 }
```

New variant representation:

```java
class MezzeVariant {
    int tag;           // 0 = Some, 1 = None (no strings!)
    Object[] payload;  // Array for payload fields
}

// Pattern match becomes integer switch
switch (variant.tag) {
    case 0: /* Some */ payload = variant.payload[0]; ...
    case 1: /* None */ ...
}
```

---

## Part 5: Content-Hash Based Dispatch

### 5.1 Ability Dispatch with Hashes

Current: ResolvedAbilityCallNode looks up by hash but uses @TruffleBoundary.

v2: **Inline caching** with hash as cache key:

```java
public abstract class AbilityCallNode extends MezzeNode {
    private final String implementationHash;

    @Specialization(
        guards = "cachedHash.equals(implementationHash)",
        assumptions = "implNotRedefined"
    )
    protected Object callCached(
        Object[] args,
        @Cached("implementationHash") String cachedHash,
        @Cached("lookupImpl(cachedHash)") MezzeRecord cachedImpl,
        @Cached("getOperation(cachedImpl)") MezzeFunction cachedOp,
        @Cached("create(cachedOp.getCallTarget())") DirectCallNode callNode
    ) {
        return callNode.call(args);  // INLINED after JIT!
    }
}
```

### 5.2 CallTarget Caching by Hash

```java
class CallTargetCache {
    // Hash → compiled CallTarget
    ConcurrentHashMap<String, RootCallTarget> cache;

    RootCallTarget getOrCompile(String hash, Supplier<AST> astSupplier) {
        return cache.computeIfAbsent(hash, h -> {
            AST ast = astSupplier.get();
            RootNode root = astToTruffle(ast);
            return root.getCallTarget();
        });
    }
}
```

### 5.3 Persistent Compilation Cache

Since hashes are stable, we can persist across runs:

```
~/.mezze/cache/
  compiled/
    a7f9e2c8.node    # Serialized node tree
    b3c4d5e6.node
  profiles/
    a7f9e2c8.profile # Type profiles from previous runs
```

On startup:
1. Check if hash exists in cache
2. Load pre-built nodes (skip AST→Node conversion)
3. Apply cached profiles (skip warmup!)

---

## Part 6: Optimistic Mutation

### 6.1 When Mutation Is Safe

Mezze is immutable-by-default, but we can optimize specific patterns:

**Safe to mutate in-place:**
1. **Record updates** where original isn't used after
2. **Array appends** in isolated builders
3. **ByteBuffer** operations (already mutable internally)

### 6.2 Escape Analysis

Track whether values escape their creation scope:

```kotlin
// Typechecker adds escape info
data class Expression(
    val inferredType: MezzeType?,
    val doesEscape: Boolean,      // NEW: Does this value escape?
    val useCount: Int             // NEW: How many times used?
)
```

### 6.3 In-Place Record Update

```java
class RecordUpdateNode extends MezzeNode {
    @Specialization(guards = "!record.hasEscaped()")
    protected MezzeRecord updateInPlace(
        MezzeRecord record,
        int fieldIndex,
        Object newValue
    ) {
        // MUTATE IN PLACE - no allocation!
        record.values[fieldIndex] = newValue;
        return record;
    }

    @Specialization
    protected MezzeRecord updateCopy(
        MezzeRecord record,
        int fieldIndex,
        Object newValue
    ) {
        // Fallback: copy-on-write
        Object[] newValues = record.values.clone();
        newValues[fieldIndex] = newValue;
        return new MezzeRecord(record.shape, newValues);
    }
}
```

### 6.4 Array Builder Optimization

When building arrays in loops, use mutable buffer:

```java
class ArrayBuilderNode extends MezzeNode {
    @Specialization
    protected MezzeArrayList buildArray(
        VirtualFrame frame,
        @Cached("createBuffer()") ArrayList<MezzeValue> buffer
    ) {
        // Accumulate in mutable buffer
        buffer.clear();
        for (element in source) {
            buffer.add(evaluate(element, frame));
        }
        // Convert to immutable at boundary
        return MezzeArrayList.fromList(buffer);
    }
}
```

---

## Part 7: Tail Call Optimization

### 7.1 TypeChecker Provides TCO Markers

```kotlin
// On FunctionCall
val isTailCall: Boolean  // true if self-recursive in tail position
```

### 7.2 Tail Call Loop Node

```java
class TailCallLoopNode extends MezzeNode {
    @Child private MezzeNode body;

    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        while (true) {
            try {
                return body.execute(frame);
            } catch (TailCallException e) {
                // Rebind arguments and loop (no stack growth!)
                for (int i = 0; i < e.args.length; i++) {
                    frame.setObject(i, e.args[i]);
                }
            }
        }
    }
}

class TailCallNode extends MezzeNode {
    @Specialization
    protected Object tailCall(Object[] args) {
        throw new TailCallException(args);  // Caught by loop
    }
}
```

---

## Part 8: Effect Handler Optimization

### 8.1 Static Handler Resolution

When handler is statically known (most cases):

```java
// PerformExpression has resolvedHandlers
Map<String, ResolvedEffectHandler> handlers;

// Generate direct handler call
class StaticPerformNode extends MezzeNode {
    @Cached("lookupHandler(hash)") HandlerRecord handler;
    @Cached("create(handler.callTarget)") DirectCallNode callNode;

    @Specialization
    protected Object perform(Object[] args) {
        return callNode.call(args);  // Direct, no handler stack search
    }
}
```

### 8.2 Handler Stack as Array

```java
// Instead of linked list of handlers
class HandlerStack {
    MezzeRecord[] handlers;  // Array for cache-friendly access
    int depth;

    MezzeRecord find(String effectName) {
        // Linear scan is fine - usually <5 handlers
        for (int i = depth - 1; i >= 0; i--) {
            if (handlers[i].handles(effectName)) {
                return handlers[i];
            }
        }
        return null;
    }
}
```

---

## Part 9: Specialized Collections

### 9.1 Primitive Arrays

When `Array<Int>` is detected:

```java
class MezzeIntArray extends MezzeValue {
    long[] elements;  // Unboxed primitives!

    @Specialization
    protected long get(int index) {
        return elements[index];  // No boxing
    }
}
```

Similarly for `Array<Float>` → `double[]`, `Array<Bool>` → `boolean[]`.

### 9.2 Type-Guided Array Creation

```java
MezzeNode createArrayLiteral(ArrayLiteral ast) {
    MezzeType elementType = getArrayElementType(ast.inferredType);

    if (elementType == IntType.INSTANCE) {
        return new IntArrayLiteralNode(elements);
    } else if (elementType == FloatType.INSTANCE) {
        return new FloatArrayLiteralNode(elements);
    } else {
        return new GenericArrayLiteralNode(elements);
    }
}
```

---

## Part 10: Complete Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           SOURCE CODE                                     │
│                         let fib = {n: Int} -> ...                        │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                           PARSER                                          │
│                     AST with source locations                             │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         TYPECHECKER                                       │
│                                                                           │
│  • inferredType on every expression                                      │
│  • resolvedAbilities with implementationHash                             │
│  • resolvedParameterOrder for positional args                            │
│  • isTailCall markers                                                    │
│  • contentHash on every declaration                                      │
│  • monomorphized function names                                          │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    CONTENT HASH CACHE CHECK                               │
│                                                                           │
│  if (cache.has(contentHash)) {                                           │
│      return cache.loadCompiledNodes(contentHash);  // INSTANT!           │
│  }                                                                        │
└──────────────────────────────────────────────────────────────────────────┘
                                    │ (cache miss)
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    AST-TO-NODE CONVERTER (v2)                             │
│                                                                           │
│  For each expression:                                                     │
│    1. Check inferredType → select FrameSlotKind                          │
│    2. Check inferredType → select specialized node variant                │
│    3. Check isTailCall → wrap in TailCallNode                            │
│    4. Check resolvedAbilities → use DirectCallNode with cached impl       │
│    5. Store contentHash in node for cache persistence                     │
│                                                                           │
│  Output: Truffle DSL nodes with pre-selected specializations             │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         TRUFFLE RUNTIME                                   │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │ INTERPRETED EXECUTION (first few runs)                               │ │
│  │ • Typed frame slots: Long, Double, Boolean, Object                  │ │
│  │ • Specialization already selected (no profiling needed!)            │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
│                                    │                                      │
│                                    ▼                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │ JIT COMPILATION (Graal)                                              │ │
│  │ • Partial evaluation: "run" interpreter at compile time             │ │
│  │ • Type guards become constants (types are statically known!)        │ │
│  │ • Specializations inline completely                                  │ │
│  │ • Function calls inline via DirectCallNode                          │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
│                                    │                                      │
│                                    ▼                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │ NATIVE MACHINE CODE                                                  │ │
│  │ • Same as hand-written Java/C                                       │ │
│  │ • No boxing, no type checks, no map lookups                         │ │
│  │ • fib(35): ~50ms (same as Java)                                     │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    PERSISTENT CACHE STORE                                 │
│                                                                           │
│  cache.store(contentHash, compiledNodes);                                │
│  // Next run: instant load, no recompilation!                            │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Part 11: Data Structure Summary

| Type | v1 Storage | v2 Storage | Access Pattern |
|------|------------|------------|----------------|
| **Int** | `MezzeInt` (boxed) | `long` (primitive) | Frame slot typed |
| **Float** | `MezzeFloat` (boxed) | `double` (primitive) | Frame slot typed |
| **Bool** | `MezzeBool` (boxed) | `boolean` (primitive) | Frame slot typed |
| **String** | `MezzeString` | `String` (unchanged) | Interop compatible |
| **Record** | `Array<MezzeValue>` | `long[] + double[] + Object[]` | Typed field arrays |
| **Variant** | `String + MezzeValue?` | `int tag + Object[]` | Integer switch |
| **Array<Int>** | `Vector<MezzeValue>` | `long[]` | Unboxed array |
| **Closure** | `Map<String, MezzeValue>` | `Object[]` | Positional (done in v1) |
| **Function** | CallTarget + closures | CallTarget + closures | DirectCallNode inline |
| **Ability** | Hash lookup | Cached DirectCallNode | Inline after warmup |

---

## Part 12: Implementation Priority

| Phase | Feature | Impact | Effort |
|-------|---------|--------|--------|
| **1** | Truffle DSL for arithmetic | Eliminates boxing in math | Medium |
| **2** | Typed frame slots | Primitives stay unboxed | Medium |
| **3** | Type-guided node selection | Skip runtime profiling | Low |
| **4** | Integer variant tags | Fast pattern matching | Low |
| **5** | Primitive arrays | Fast numerical code | Medium |
| **6** | Hash-based CallTarget cache | Instant startup | Medium |
| **7** | DirectCallNode for abilities | Ability calls inline | Medium |
| **8** | Optimistic record mutation | Less allocation | High |
| **9** | Persistent compilation cache | Zero warmup on restart | High |
| **10** | AOT native image | Instant cold start | High |

---

## Part 13: Expected Performance

### Fibonacci Benchmark: fib(35)

| Version | Time | Relative |
|---------|------|----------|
| v1 (current) | ~8 seconds | 1x |
| v2 (unboxed primitives) | ~500ms | 16x faster |
| v2 (after JIT warmup) | ~50ms | 160x faster |
| Java native | ~50ms | Baseline |

### Real-World Code

For code with records, variants, abilities:

| Operation | v1 | v2 (expected) |
|-----------|----|----|
| Record field access | HashMap lookup | Array index |
| Variant match | String compare | Integer switch |
| Ability call | Hash lookup + indirect | Inlined (after warmup) |
| Closure access | Map lookup | Array index (already done) |

---

## Part 14: Files to Create

```
interpreterv2/
├── src/main/java/com/mezze/language/interpreterv2/
│   ├── MezzeLanguageV2.java          # Truffle language registration
│   ├── MezzeTypesV2.java             # Type system (long, double, bool first)
│   ├── MezzeContextV2.java           # Runtime context
│   │
│   ├── nodes/
│   │   ├── MezzeNode.java            # Base node class
│   │   ├── MezzeRootNode.java        # Function root with typed slots
│   │   │
│   │   ├── arithmetic/
│   │   │   ├── AddNode.java          # @Specialization DSL
│   │   │   ├── SubtractNode.java
│   │   │   ├── MultiplyNode.java
│   │   │   └── DivideNode.java
│   │   │
│   │   ├── comparison/
│   │   │   ├── EqualNode.java
│   │   │   ├── LessThanNode.java
│   │   │   └── ...
│   │   │
│   │   ├── control/
│   │   │   ├── IfNode.java
│   │   │   ├── MatchNode.java        # Integer tag dispatch
│   │   │   └── TailCallLoopNode.java
│   │   │
│   │   ├── literal/
│   │   │   ├── IntLiteralNode.java   # Returns long
│   │   │   ├── FloatLiteralNode.java # Returns double
│   │   │   ├── RecordLiteralNode.java
│   │   │   └── ArrayLiteralNode.java # Typed arrays
│   │   │
│   │   ├── variable/
│   │   │   ├── ReadLocalNode.java    # Typed frame access
│   │   │   └── WriteLocalNode.java
│   │   │
│   │   ├── function/
│   │   │   ├── InvokeNode.java       # DirectCallNode caching
│   │   │   └── FunctionDefinitionNode.java
│   │   │
│   │   └── effect/
│   │       ├── PerformNode.java      # Static handler dispatch
│   │       └── HandlerNode.java
│   │
│   ├── values/
│   │   ├── MezzeRecord.java          # Typed field arrays
│   │   ├── MezzeVariant.java         # Integer tags
│   │   ├── MezzeIntArray.java        # long[]
│   │   ├── MezzeFloatArray.java      # double[]
│   │   └── MezzeFunction.java        # CallTarget + closure array
│   │
│   ├── converter/
│   │   └── ASTToTruffleConverterV2.java  # Uses inferredType
│   │
│   └── cache/
│       ├── CallTargetCache.java      # Hash → CallTarget
│       └── CompilationPersistence.java  # Disk cache
│
└── build.gradle.kts                   # Truffle DSL annotation processor
```

---

## Part 15: Getting Started

### Step 1: Setup Truffle DSL

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.graalvm.truffle:truffle-api:23.1.0")
    annotationProcessor("org.graalvm.truffle:truffle-dsl-processor:23.1.0")
}
```

### Step 2: Create Type System

```java
@TypeSystem({
    long.class,      // Int - FIRST for fast path
    double.class,    // Float
    boolean.class,   // Bool
    String.class,    // String
    MezzeRecord.class,
    MezzeVariant.class,
    MezzeFunction.class,
    Object.class     // Catch-all
})
public abstract class MezzeTypesV2 {
    @ImplicitCast
    public static double castLongToDouble(long value) {
        return (double) value;
    }
}
```

### Step 3: Implement One Node with DSL

```java
@NodeChild("left")
@NodeChild("right")
@TypeSystemReference(MezzeTypesV2.class)
public abstract class AddNode extends MezzeNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long addLongs(long left, long right) {
        return Math.addExact(left, right);
    }

    @Specialization(replaces = "addLongs")
    protected double addDoubles(double left, double right) {
        return left + right;
    }
}
```

### Step 4: Benchmark and Iterate

```bash
# Build with Truffle DSL
./gradlew :interpreterv2:build

# Run fib benchmark
./gradlew :interpreterv2:test --tests "*FibBenchmark*"

# Expected: 16-160x improvement
```

---

## Summary

Mezze has **unique advantages** over dynamically-typed languages:
1. **Static types** → Skip runtime profiling, pre-select specializations
2. **Content hashes** → Persistent caching, instant dispatch
3. **Monomorphization** → No generic code at runtime

v2 leverages these fully with:
1. **Truffle DSL** → Proper specialization, no manual dispatch
2. **Typed frame slots** → Primitives stay unboxed
3. **Hash-based caching** → Zero warmup on restart
4. **Optimistic mutation** → Less allocation where safe

The result: **Near-native speed for all Mezze code**, not just simple benchmarks.

---

## Part 16: Polyglot Interop Architecture

### 16.1 Execution Models

Mezze supports two execution modes with different capabilities:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  EXECUTION MODEL 1: Native Binary (mez)                                      │
└─────────────────────────────────────────────────────────────────────────────┘

$ mez exec app.mz
$ mez dev
$ mez build
$ mez lsp

Capabilities:
├── Full Mezze language support
├── Truffle JIT compilation
├── Fast startup (~20ms)
├── Self-contained binary
└── NO polyglot interop (languages not embedded)

Use for:
├── Pure Mezze development
├── CLI tools and scripts
├── LSP / IDE integration
└── Building/compiling projects

┌─────────────────────────────────────────────────────────────────────────────┐
│  EXECUTION MODEL 2: JVM Mode (for polyglot)                                  │
└─────────────────────────────────────────────────────────────────────────────┘

$ java -cp mez.jar:graalpy.jar:graaljs.jar \
    com.mezze.language.cli.MainKt exec app.mz

Or with helper script:
$ mez-polyglot exec app.mz

Capabilities:
├── Full Mezze language support
├── Truffle JIT compilation
├── FULL polyglot interop (Python, JS, Ruby, etc.)
├── Slower startup (~500ms)
└── Requires GraalVM + language JARs

Use for:
├── Development with Python/JS/Ruby libraries
├── Data science (NumPy, Pandas, etc.)
├── Web scraping, ML, etc.
└── Any code using std::interop::*
```

### 16.2 Polyglot Language Installation

Users install polyglot languages via Maven dependencies or GraalVM Updater:

```bash
# Option 1: GraalVM Updater (installs to $GRAALVM_HOME)
gu install python
gu install js
gu install ruby

# Option 2: Maven/Gradle dependencies (for project-specific)
# build.gradle.kts
dependencies {
    implementation("org.graalvm.polyglot:python:24.2.2")
    implementation("org.graalvm.polyglot:js:24.2.2")
}
```

### 16.3 Userland Interop Libraries

Polyglot is exposed through **userland Mezze libraries**, not language primitives:

```mezze
// std/interop/python.mz
module std::interop::python

effect Python {
    import: {module: String} -> PolyglotObject
    eval: {code: String} -> PolyglotObject
}

// std/interop/javascript.mz
module std::interop::javascript

effect JavaScript {
    import: {module: String} -> PolyglotObject
    eval: {code: String} -> PolyglotObject
}
```

User code:

```mezze
use std::interop::python

let main = {} -> {
    let np = Python.import { module = "numpy" }
    let arr = np.array { data = [1, 2, 3, 4, 5] }
    let mean = np.mean { a = arr }
    print { value = mean }
}
```

### 16.4 Interpreter Polyglot Nodes

The interpreter needs nodes for polyglot operations:

```java
/**
 * Import a module from another polyglot language.
 * Uses GraalVM's polyglot Context to access guest languages.
 */
@NodeInfo(shortName = "polyglot-import")
public abstract class PolyglotImportNode extends MezzeNode {

    private final String languageId;  // "python", "js", "ruby"

    @Child private MezzeNode moduleNameNode;

    @Specialization
    @TruffleBoundary
    protected Object doImport(VirtualFrame frame, String moduleName) {
        Context context = getPolyglotContext();

        // Check if language is available
        if (!context.getEngine().getLanguages().containsKey(languageId)) {
            throw new MezzeRuntimeException(
                "Language '" + languageId + "' not available. " +
                "Run with JVM mode: java -cp mez.jar:" + languageId + ".jar ..."
            );
        }

        // Import the module
        Value bindings = context.getBindings(languageId);
        return context.eval(languageId, "import " + moduleName);
    }
}

/**
 * Call a method on a polyglot object.
 */
@NodeInfo(shortName = "polyglot-invoke")
public abstract class PolyglotInvokeNode extends MezzeNode {

    @Child private MezzeNode receiverNode;
    @Child private MezzeNode argsNode;

    private final String methodName;

    @Specialization
    @TruffleBoundary
    protected Object doInvoke(Object receiver, Object args) {
        if (receiver instanceof Value polyglotValue) {
            // Convert Mezze args to polyglot
            Object[] convertedArgs = convertArgs(args);
            return polyglotValue.invokeMember(methodName, convertedArgs);
        }
        throw new MezzeRuntimeException("Cannot invoke on non-polyglot value");
    }
}

/**
 * Read a member from a polyglot object.
 */
@NodeInfo(shortName = "polyglot-read-member")
public abstract class PolyglotReadMemberNode extends MezzeNode {

    private final String memberName;

    @Specialization
    @TruffleBoundary
    protected Object doRead(Object receiver) {
        if (receiver instanceof Value polyglotValue) {
            return polyglotValue.getMember(memberName);
        }
        throw new MezzeRuntimeException("Cannot read member from non-polyglot value");
    }
}
```

### 16.5 Type Mapping Between Languages

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  TYPE MAPPING: Mezze ↔ Polyglot Languages                                    │
└─────────────────────────────────────────────────────────────────────────────┘

Mezze Type      │ Java/Truffle    │ Python         │ JavaScript
────────────────┼─────────────────┼────────────────┼────────────────
Int             │ long            │ int            │ number
Float           │ double          │ float          │ number
Bool            │ boolean         │ bool           │ boolean
String          │ String          │ str            │ string
Array<T>        │ Object[]        │ list           │ Array
Record          │ MezzeRecord     │ dict           │ Object
Option<T>       │ MezzeVariant    │ T | None       │ T | null
Function        │ MezzeFunction   │ callable       │ function
PolyglotObject  │ Value           │ (any)          │ (any)

Auto-Conversion:
────────────────
• Mezze primitives → polyglot: automatic (Truffle handles)
• Polyglot → Mezze primitives: automatic
• Mezze Record → Python dict: explicit via interop helper
• Mezze Function → Python callable: wrapped automatically
• Python object → Mezze: stays as PolyglotObject (opaque)
```

### 16.6 Polyglot Context Management

```java
/**
 * Manages the polyglot context for the Mezze runtime.
 * Context is created lazily when first polyglot operation is requested.
 */
public class MezzePolyglotSupport {

    private volatile Context polyglotContext;

    @TruffleBoundary
    public Context getOrCreateContext() {
        if (polyglotContext == null) {
            synchronized (this) {
                if (polyglotContext == null) {
                    polyglotContext = Context.newBuilder()
                        .allowAllAccess(true)
                        .option("engine.WarnInterpreterOnly", "false")
                        .build();
                }
            }
        }
        return polyglotContext;
    }

    /**
     * Check which languages are available in the current runtime.
     */
    @TruffleBoundary
    public Set<String> getAvailableLanguages() {
        return getOrCreateContext().getEngine().getLanguages().keySet();
    }

    /**
     * Called when Mezze program exits.
     */
    public void close() {
        if (polyglotContext != null) {
            polyglotContext.close();
        }
    }
}
```

### 16.7 Compiled JAR with Polyglot

When `mez compile` produces a JAR, it tracks polyglot dependencies:

```
app.jar
├── META-INF/
│   └── MANIFEST.MF
├── codebase.db              # Serialized typed AST
├── mezze-manifest.json      # Build manifest
│   {
│     "main": "app::main",
│     "mezzeVersion": "0.1.0",
│     "polyglotLanguages": ["python"],    ← Detected during typecheck
│     "contentHashes": ["#a7f9e2...", ...]
│   }
└── com/mezze/...            # Runtime classes
```

Running the compiled JAR:

```bash
# Pure Mezze app (no polyglot)
java -jar app.jar

# App with Python
java -cp app.jar:graalpy.jar MainKt

# Compile to native with Python embedded
native-image --language:python -jar app.jar -o app
./app  # Self-contained binary with Python
```

### 16.8 Native Binary Detection

The `mez` native binary should detect polyglot usage and give helpful errors:

```java
/**
 * Check if program uses polyglot features before execution.
 * Called during type checking phase (before execution).
 */
public class PolyglotUsageDetector {

    public Set<String> detectPolyglotLanguages(Program program) {
        Set<String> languages = new HashSet<>();

        for (Import imp : program.getImports()) {
            if (imp.getModule().startsWith("std::interop::")) {
                String lang = imp.getModule().replace("std::interop::", "");
                languages.add(lang);
            }
        }

        return languages;
    }
}

// In mez exec command:
Set<String> polyglotLangs = detector.detectPolyglotLanguages(program);

if (!polyglotLangs.isEmpty() && isNativeBinary()) {
    System.err.println("Error: This program uses polyglot features: " + polyglotLangs);
    System.err.println();
    System.err.println("The native `mez` binary doesn't include polyglot languages.");
    System.err.println("Run with JVM mode instead:");
    System.err.println();
    System.err.println("  java -cp mez.jar:" +
        polyglotLangs.stream().map(l -> l + ".jar").collect(joining(":")) +
        " com.mezze.language.cli.MainKt exec " + filename);
    System.exit(1);
}
```

---

## Part 17: Complete Execution Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    COMPLETE MEZZE EXECUTION FLOW                             │
└─────────────────────────────────────────────────────────────────────────────┘

                              USER CODE
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  mez check / mez build                                                       │
│  (Works in native binary - no execution needed)                              │
│                                                                              │
│  1. Parse source files                                                       │
│  2. Type check (detects polyglot imports)                                    │
│  3. Generate content hashes                                                  │
│  4. Output: Typed AST + manifest                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                 │
             ┌───────────────────┴───────────────────┐
             │                                       │
      Pure Mezze?                             Uses Polyglot?
             │                                       │
             ▼                                       ▼
┌─────────────────────────┐           ┌─────────────────────────────────────┐
│  mez exec (native)      │           │  JVM Mode Required                   │
│                         │           │                                      │
│  • Fast startup         │           │  java -cp mez.jar:graalpy.jar ...    │
│  • Full JIT             │           │                                      │
│  • No polyglot          │           │  • Slower startup                    │
└─────────────────────────┘           │  • Full JIT                          │
             │                         │  • Full polyglot                     │
             │                         └─────────────────────────────────────┘
             │                                       │
             └───────────────────┬───────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  INTERPRETER V2                                                              │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ AST → Truffle Nodes                                                     │ │
│  │                                                                         │ │
│  │ • Typed frame slots (Long, Double, Boolean, Object)                    │ │
│  │ • Type-guided specialization selection                                  │ │
│  │ • DirectCallNode for functions/abilities                                │ │
│  │ • PolyglotImportNode, PolyglotInvokeNode (when in JVM mode)            │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                         │
│                                    ▼                                         │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Truffle/Graal JIT                                                       │ │
│  │                                                                         │ │
│  │ • Partial evaluation                                                    │ │
│  │ • Inlining across language boundaries (polyglot!)                       │ │
│  │ • Native machine code                                                   │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
                              OUTPUT

┌─────────────────────────────────────────────────────────────────────────────┐
│  PRODUCTION DEPLOYMENT                                                       │
│                                                                              │
│  Option A: JAR (needs JVM)                                                  │
│  ─────────────────────────                                                  │
│  mez build → app.jar                                                        │
│  java -cp app.jar:graalpy.jar MainKt                                        │
│                                                                              │
│  Option B: Native Binary (self-contained)                                   │
│  ─────────────────────────────────────────                                  │
│  mez build → app.jar                                                        │
│  native-image --language:python -jar app.jar -o app                         │
│  ./app   ← Single binary, ~150MB with Python embedded                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 18: Polyglot Performance Considerations

### 18.1 Cross-Language Call Overhead

GraalVM's Truffle framework enables **zero-overhead polyglot** after JIT warmup:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  POLYGLOT CALL OVERHEAD                                                      │
└─────────────────────────────────────────────────────────────────────────────┘

Before JIT (interpreted):
  Mezze → Python call: ~1-10μs (micros)

After JIT warmup:
  Mezze → Python call: ~10-100ns (nanos) - INLINED!

This is because Graal:
1. Sees both Mezze and Python ASTs
2. Performs partial evaluation across both
3. Inlines the cross-language call
4. Generates single native code path
```

### 18.2 Data Sharing

```java
// FAST: Primitives and strings share directly
long mezzeInt = 42;
// Python sees: 42 (no copy)

// MEDIUM: Arrays may need conversion
long[] mezzeArray = new long[] {1, 2, 3};
// Python numpy can wrap without copy (if compatible layout)

// SLOW: Complex objects need marshalling
MezzeRecord mezzeRecord = ...;
// Python dict needs field-by-field conversion
```

### 18.3 Best Practices for Polyglot Code

```mezze
// GOOD: Batch operations in guest language
let result = Python.eval { code = """
    import numpy as np
    arr = np.array([1, 2, 3, 4, 5])
    np.mean(arr) * np.std(arr)  # All computation in Python
""" }

// BAD: Many small cross-language calls
let np = Python.import { module = "numpy" }
let arr = np.array { data = [1, 2, 3, 4, 5] }
let mean = np.mean { a = arr }      // Cross-language call
let std = np.std { a = arr }        // Cross-language call
let result = mean * std             // Back in Mezze
```
