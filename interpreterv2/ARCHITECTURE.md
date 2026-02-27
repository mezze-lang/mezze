# Mezze Interpreter v2 - Complete High Performance Architecture

## Goal: Near-Native Speed for ALL Code (Not Just fib!)

This document describes a complete rewrite following Truffle best practices from [SimpleLanguage](https://github.com/graalvm/simplelanguage), [TruffleRuby](https://github.com/oracle/truffleruby), and GraalJS.

---

## Part 0: Mezze's Advantage - Static Type Information

**Unlike dynamically typed languages, Mezze has a typechecker that provides EXACT type information at compile time.**

### What The Typechecker Gives Us

Every expression in the typed AST has:

```kotlin
// On EVERY expression:
val inferredType: MezzeType?  // The concrete type (IntType, StringType, etc.)

// On function calls specifically:
val typeSubstitution: Map<String, MezzeType>      // Polymorphic → Monomorphic
val instantiatedFunctionType: FunctionType?       // Concrete function signature
val resolvedFunctionName: String?                 // "map$Int$String" for direct dispatch
val resolvedParameterOrder: List<String>?         // For positional arguments
val resolvedAbilities: Map<String, ResolvedAbility>  // Resolved trait implementations
val isTailCall: Boolean                           // TCO marker
```

### Why This Is Better Than Standard Truffle

**Standard Truffle (for dynamic languages like Ruby/JS):**
```
1. Run code, observe types at runtime
2. Build type profiles after ~10,000 runs
3. JIT compile based on observed patterns
4. Deoptimize if types change
```

**Mezze with Type Information:**
```
1. Typechecker provides EXACT types at compile time
2. Generate specialized nodes IMMEDIATELY
3. JIT compiles optimally from first run
4. No deoptimization (types are guaranteed)
```

### Example: How We Use Type Info

```
Source Code:
  let add = {x: Int, y: Int} -> x + y
  add {x = 5, y = 3}

After Typechecking, the AST contains:
┌─────────────────────────────────────────────────────────┐
│ FunctionCall                                             │
│   inferredType: IntType                                 │
│   instantiatedFunctionType: {x: Int, y: Int} -> Int     │
│   resolvedParameterOrder: ["x", "y"]                    │
│                                                          │
│   arguments:                                             │
│     RecordLiteral                                        │
│       x: IntLiteral(5), inferredType: IntType           │
│       y: IntLiteral(3), inferredType: IntType           │
└─────────────────────────────────────────────────────────┘

AST-to-Node Converter sees inferredType and generates:
┌─────────────────────────────────────────────────────────┐
│ // Frame slots are TYPED based on inferredType:         │
│ slot 0: x = FrameSlotKind.Long  (not Object!)          │
│ slot 1: y = FrameSlotKind.Long  (not Object!)          │
│                                                          │
│ // Nodes are SPECIALIZED based on inferredType:         │
│ MezzeAddNode with addLongs() specialization pre-selected│
│ MezzeReadLocalNode with readLong() specialization       │
│                                                          │
│ // Function call uses positional args:                  │
│ InvokeNode with args = [5L, 3L] (primitives, not boxed) │
└─────────────────────────────────────────────────────────┘
```

### The Compilation Pipeline

```
┌──────────────┐     ┌──────────────┐     ┌──────────────────────┐
│    Parser    │ ──> │  TypeChecker │ ──> │  Typed AST           │
│              │     │              │     │  (inferredType on    │
│              │     │  Monomorphize│     │   every expression)  │
└──────────────┘     │  Resolve     │     └──────────┬───────────┘
                     └──────────────┘                │
                                                     ▼
                     ┌──────────────────────────────────────────┐
                     │  AST-to-Node Converter (interpreterv2)   │
                     │                                          │
                     │  For each expression:                    │
                     │    1. Check inferredType                 │
                     │    2. If IntType → Long frame slot       │
                     │    3. If FloatType → Double frame slot   │
                     │    4. Generate type-specialized node     │
                     │                                          │
                     └──────────────────────┬───────────────────┘
                                            │
                                            ▼
                     ┌──────────────────────────────────────────┐
                     │  Truffle AST (Pre-Specialized!)          │
                     │                                          │
                     │  - All frame slots typed correctly       │
                     │  - All nodes use optimal specialization  │
                     │  - No runtime type profiling needed      │
                     │  - JIT compiles perfectly from start     │
                     │                                          │
                     └──────────────────────────────────────────┘
```

### Type-Guided Optimizations

| Type Info | Optimization |
|-----------|--------------|
| `inferredType: IntType` | Use `FrameSlotKind.Long`, `frame.getLong()` |
| `inferredType: FloatType` | Use `FrameSlotKind.Double`, `frame.getDouble()` |
| `inferredType: BoolType` | Use `FrameSlotKind.Boolean`, `frame.getBoolean()` |
| `inferredType: FunctionType` | Use Object slot, but inline cache the CallTarget |
| `inferredType: RecordType` | Pre-compute field indices, use array access |
| `resolvedParameterOrder` | Pass args as array, not Map |
| `resolvedFunctionName` | Direct function lookup, no polymorphic dispatch |
| `resolvedAbilities` | Direct ability impl, no runtime search |
| `isTailCall` | Use TCO loop node |

---

## Part 1: Understanding Truffle (For Podcast/Explanation)

### What Problem Does Truffle Solve?

Traditional interpreters are slow because they:
1. Parse the same code every time
2. Check types on every operation
3. Look up operations in tables/maps
4. Create temporary objects constantly

Compilers are fast but take years to build well.

**Truffle's insight:** What if an interpreter could *observe* what your program actually does, then *compile itself* for that specific behavior?

### How Truffle Works

```
┌─────────────────────────────────────────────────────────────┐
│  PHASE 1: INTERPRETATION (~10,000 runs)                      │
│                                                              │
│  Your program runs through AST nodes.                       │
│  Truffle WATCHES and PROFILES:                              │
│                                                              │
│    "This add() always sees integers"                        │
│    "This variable 'x' is always a string"                   │
│    "This function 'process' always gets arrays"             │
│                                                              │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  PHASE 2: PARTIAL EVALUATION                                 │
│                                                              │
│  GraalVM takes:                                              │
│    - Your interpreter code (Java/Kotlin)                    │
│    - The observed type profiles                             │
│    - The specific AST being executed                        │
│                                                              │
│  And "mixes them together" into specialized machine code.   │
│                                                              │
│  The interpreter essentially DISAPPEARS.                    │
│                                                              │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  PHASE 3: NATIVE EXECUTION                                   │
│                                                              │
│  Your "interpreted" language now runs as fast as C/Java.    │
│                                                              │
│  Example: "x + 1" becomes literally:                        │
│    ADD RAX, 1                                               │
│                                                              │
│  No type checks, no boxing, no interpreter overhead.        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Key Concepts

**Node:** A piece of your program. AddNode adds things. IfNode does conditionals. Each knows how to execute itself.

**Frame:** Local memory for a function call. Like a stack frame, but Truffle can optimize it away.

**@Specialization:** "Here's how to handle THIS specific case." Truffle picks the right one based on observed types.

**@TruffleBoundary:** "Stop! Don't try to compile past this." Used for complex operations like I/O, Map manipulation.

**Partial Evaluation:** The magic. GraalVM "runs" your interpreter on a specific program at compile time, producing specialized code.

### The One Sentence Pitch

> "Truffle lets you write a simple tree-walking interpreter, and GraalVM automatically turns it into a JIT compiler that produces code as fast as C."

---

## Part 2: What's Wrong with v1?

### The Fundamental Problem

v1 was designed for **correctness** and **flexibility**, not **performance**.

```
Every operation in v1:

┌─────────────────────────────────────────────────────────────┐
│                                                              │
│  x + y                                                       │
│                                                              │
│  1. Get x → MezzeInt object (ALLOCATION)                    │
│  2. Get y → MezzeInt object (ALLOCATION)                    │
│  3. Check: is x an Int? (INSTANCEOF)                        │
│  4. Check: is y an Int? (INSTANCEOF)                        │
│  5. Unwrap x.value (FIELD ACCESS)                           │
│  6. Unwrap y.value (FIELD ACCESS)                           │
│  7. Add them                                                │
│  8. Wrap in new MezzeInt (ALLOCATION)                       │
│  9. Return                                                   │
│                                                              │
│  3 allocations, 2 type checks, for ONE addition!            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Specific Bottlenecks

| Location | Problem | Impact |
|----------|---------|--------|
| `MezzeInt`, `MezzeFloat` | Every number is boxed | 3+ allocations per operation |
| `MezzeRecord.fields` | Map<String, Value> | Map.get() on every field access |
| `propagateHandlers()` | Called every function entry | @TruffleBoundary blocks JIT |
| `FunctionCallNode` | Creates record for args | Map allocation per call |
| `BinaryArithmeticNode` | instanceof chains | Megamorphic dispatch |

### The Result

```
fib(35):
  - ~9 billion arithmetic operations
  - ~27 billion allocations (3 per operation)
  - ~9 billion Map lookups
  - ~9 billion @TruffleBoundary crossings

  Time: ~8 seconds
```

### What v1 Should Look Like

```
x + y (in a properly optimized Truffle interpreter):

┌─────────────────────────────────────────────────────────────┐
│                                                              │
│  After JIT compilation:                                     │
│                                                              │
│  ADD RAX, RBX    ; literally just one CPU instruction       │
│                                                              │
│  0 allocations, 0 type checks, 0 Map lookups                │
│                                                              │
└─────────────────────────────────────────────────────────────┘

fib(35) target: ~50 milliseconds
```

---

## Part 3: v2 Architecture (Following SimpleLanguage)

### Directory Structure

```
interpreterv2/
├── src/main/java/com/mezze/language/interpreterv2/
│   │
│   ├── MezzeLanguage.java        # Language entry point
│   ├── MezzeContext.java         # Execution context
│   ├── MezzeTypes.java           # @TypeSystem definition
│   │
│   ├── nodes/
│   │   │
│   │   ├── MezzeNode.java            # Base node class
│   │   ├── MezzeExpressionNode.java  # Base for value-producing nodes
│   │   ├── MezzeStatementNode.java   # Base for side-effect nodes
│   │   ├── MezzeRootNode.java        # Function entry point
│   │   │
│   │   ├── expression/
│   │   │   ├── arithmetic/
│   │   │   │   ├── MezzeAddNode.java
│   │   │   │   ├── MezzeSubNode.java
│   │   │   │   ├── MezzeMulNode.java
│   │   │   │   ├── MezzeDivNode.java
│   │   │   │   ├── MezzeModNode.java
│   │   │   │   └── MezzeNegNode.java
│   │   │   │
│   │   │   ├── comparison/
│   │   │   │   ├── MezzeEqNode.java
│   │   │   │   ├── MezzeNeqNode.java
│   │   │   │   ├── MezzeLtNode.java
│   │   │   │   ├── MezzeLeNode.java
│   │   │   │   ├── MezzeGtNode.java
│   │   │   │   └── MezzeGeNode.java
│   │   │   │
│   │   │   ├── logical/
│   │   │   │   ├── MezzeAndNode.java
│   │   │   │   ├── MezzeOrNode.java
│   │   │   │   └── MezzeNotNode.java
│   │   │   │
│   │   │   ├── literal/
│   │   │   │   ├── MezzeLongLiteralNode.java
│   │   │   │   ├── MezzeDoubleLiteralNode.java
│   │   │   │   ├── MezzeBooleanLiteralNode.java
│   │   │   │   ├── MezzeStringLiteralNode.java
│   │   │   │   └── MezzeNullLiteralNode.java
│   │   │   │
│   │   │   ├── access/
│   │   │   │   ├── MezzeReadPropertyNode.java
│   │   │   │   └── MezzeWritePropertyNode.java
│   │   │   │
│   │   │   └── call/
│   │   │       ├── MezzeInvokeNode.java
│   │   │       └── MezzeFunctionLiteralNode.java
│   │   │
│   │   ├── local/
│   │   │   ├── MezzeReadLocalNode.java
│   │   │   ├── MezzeWriteLocalNode.java
│   │   │   └── MezzeReadArgumentNode.java
│   │   │
│   │   └── controlflow/
│   │       ├── MezzeIfNode.java
│   │       ├── MezzeBlockNode.java
│   │       └── MezzeReturnNode.java
│   │
│   ├── runtime/
│   │   ├── MezzeFunction.java    # Callable function object
│   │   ├── MezzeRecord.java      # Array-backed record
│   │   ├── MezzeNull.java        # Singleton null value
│   │   ├── MezzeArray.java       # Array type
│   │   └── MezzeException.java   # Runtime exceptions
│   │
│   └── builtins/
│       ├── MezzeBuiltinNode.java
│       ├── MezzePrintBuiltin.java
│       └── ... other builtins
```

### The Type System

Following [SimpleLanguage's SLTypes](https://github.com/graalvm/simplelanguage/blob/master/language/src/main/java/com/oracle/truffle/sl/nodes/SLTypes.java):

```java
@TypeSystem({
    long.class,           // Integers - UNBOXED
    double.class,         // Floats - UNBOXED
    boolean.class,        // Booleans - UNBOXED
    String.class,         // Strings
    MezzeFunction.class,  // Functions
    MezzeRecord.class,    // Records
    MezzeArray.class,     // Arrays
    MezzeNull.class       // Null singleton
})
public abstract class MezzeTypes {

    /**
     * Implicit cast: Int can be used where Float is expected.
     * Example: 5 + 3.14 → addDouble(5.0, 3.14)
     */
    @ImplicitCast
    public static double castLongToDouble(long value) {
        return value;
    }

    /**
     * Type check for null.
     * MezzeNull is a singleton, so we use identity comparison.
     */
    @TypeCheck(MezzeNull.class)
    public static boolean isMezzeNull(Object value) {
        return value == MezzeNull.INSTANCE;
    }

    @TypeCast(MezzeNull.class)
    public static MezzeNull asMezzeNull(Object value) {
        assert isMezzeNull(value);
        return MezzeNull.INSTANCE;
    }
}
```

**Why primitives are first:** Truffle DSL tries specializations in order. By listing `long.class` first, we ensure integer arithmetic is tried before falling back to object types.

### The Base Node Classes

```java
/**
 * Base class for all Mezze AST nodes.
 * Extends Truffle's Node for tree rewriting and compilation support.
 */
public abstract class MezzeNode extends Node {
    // No common functionality yet - exists for type hierarchy
}

/**
 * Base class for nodes that produce values.
 *
 * KEY DESIGN: We have executeXxx methods for each primitive type.
 * This allows child nodes to request a specific type, enabling
 * unboxed primitive flow through the AST.
 */
@TypeSystemReference(MezzeTypes.class)
@NodeInfo(language = "mezze")
public abstract class MezzeExpressionNode extends MezzeNode {

    /**
     * Execute this node and return the result as a generic Object.
     * This is the fallback when we don't know the expected type.
     */
    public abstract Object executeGeneric(VirtualFrame frame);

    /**
     * Execute expecting a long result.
     * Default implementation calls executeGeneric and unboxes.
     * Specialized nodes override this for unboxed primitives.
     */
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        return MezzeTypesGen.expectLong(executeGeneric(frame));
    }

    /**
     * Execute expecting a double result.
     */
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return MezzeTypesGen.expectDouble(executeGeneric(frame));
    }

    /**
     * Execute expecting a boolean result.
     */
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        return MezzeTypesGen.expectBoolean(executeGeneric(frame));
    }
}

/**
 * Base class for nodes that don't produce values (statements).
 */
public abstract class MezzeStatementNode extends MezzeNode {
    public abstract void executeVoid(VirtualFrame frame);
}
```

**Why executeXxx methods?** This is how unboxed primitives flow:

```
Without executeXxx:

AddNode.execute() → returns Object (boxed Long)
         ↓
SubNode.execute() → receives Object, unwraps to long, operates, boxes result
         ↓
Compare: receives Object, unwraps to long...

LOTS OF BOXING/UNBOXING!
```

```
With executeXxx:

AddNode.executeLong() → returns long (UNBOXED!)
         ↓
SubNode receives long directly, operates, returns long
         ↓
Compare receives long directly...

NO BOXING IN HOT PATH!
```

### Arithmetic Node Example (Following SimpleLanguage)

```java
/**
 * Addition node with specializations for different type combinations.
 *
 * The Truffle DSL generates MezzeAddNodeGen which handles:
 * - Type checking
 * - Specialization selection
 * - Node rewriting when types change
 * - Compilation to native code
 */
@NodeChild("leftNode")
@NodeChild("rightNode")
public abstract class MezzeAddNode extends MezzeExpressionNode {

    /**
     * Fast path: integer addition.
     *
     * Uses Math.addExact to detect overflow.
     * If overflow occurs, ArithmeticException triggers rewrite to big integer path.
     */
    @Specialization(rewriteOn = ArithmeticException.class)
    protected long addLongs(long left, long right) {
        return Math.addExact(left, right);  // Throws on overflow
    }

    /**
     * Fallback for integer overflow: use BigInteger.
     * replaces = "addLongs" means once we hit this, don't try addLongs again.
     */
    @Specialization(replaces = "addLongs")
    @TruffleBoundary  // BigInteger operations are complex
    protected BigInteger addBigIntegers(BigInteger left, BigInteger right) {
        return left.add(right);
    }

    /**
     * Float addition.
     */
    @Specialization
    protected double addDoubles(double left, double right) {
        return left + right;
    }

    /**
     * Mixed: int + float → float.
     * The @ImplicitCast in MezzeTypes handles this automatically,
     * but explicit specialization can be clearer.
     */
    @Specialization
    protected double addLongDouble(long left, double right) {
        return left + right;
    }

    @Specialization
    protected double addDoubleLong(double left, long right) {
        return left + right;
    }

    /**
     * String concatenation.
     * TruffleBoundary because string operations are complex.
     */
    @Specialization
    @TruffleBoundary
    protected String addStrings(String left, String right) {
        return left + right;
    }

    /**
     * Type error fallback.
     */
    @Fallback
    protected Object typeError(Object left, Object right) {
        throw MezzeException.typeError(this, "Cannot add " +
            left.getClass().getSimpleName() + " and " +
            right.getClass().getSimpleName());
    }
}
```

### Local Variable Access (Following SimpleLanguage)

```java
/**
 * Read a local variable from a frame slot.
 *
 * KEY OPTIMIZATION: We have separate specializations for each slot type.
 * If a variable is always an integer, we use getLong() directly.
 * No boxing, no type checking in the hot path.
 */
public abstract class MezzeReadLocalNode extends MezzeExpressionNode {

    /** The slot index in the frame */
    private final int slot;

    protected MezzeReadLocalNode(int slot) {
        this.slot = slot;
    }

    protected int getSlot() {
        return slot;
    }

    /**
     * Fast path: variable is a long.
     * Guard checks the frame's slot type.
     */
    @Specialization(guards = "frame.isLong(getSlot())")
    protected long readLong(VirtualFrame frame) {
        return frame.getLong(slot);
    }

    /**
     * Fast path: variable is a double.
     */
    @Specialization(guards = "frame.isDouble(getSlot())")
    protected double readDouble(VirtualFrame frame) {
        return frame.getDouble(slot);
    }

    /**
     * Fast path: variable is a boolean.
     */
    @Specialization(guards = "frame.isBoolean(getSlot())")
    protected boolean readBoolean(VirtualFrame frame) {
        return frame.getBoolean(slot);
    }

    /**
     * General path: variable is an Object.
     * Used for functions, records, arrays, etc.
     */
    @Specialization(replaces = {"readLong", "readDouble", "readBoolean"})
    protected Object readObject(VirtualFrame frame) {
        return frame.getObject(slot);
    }
}

/**
 * Write to a local variable.
 */
@NodeChild("valueNode")
public abstract class MezzeWriteLocalNode extends MezzeStatementNode {

    private final int slot;

    protected MezzeWriteLocalNode(int slot) {
        this.slot = slot;
    }

    protected int getSlot() {
        return slot;
    }

    /**
     * Write a long value.
     * Sets the frame slot kind to Long for future reads.
     */
    @Specialization
    protected void writeLong(VirtualFrame frame, long value) {
        frame.setLong(slot, value);
    }

    @Specialization
    protected void writeDouble(VirtualFrame frame, double value) {
        frame.setDouble(slot, value);
    }

    @Specialization
    protected void writeBoolean(VirtualFrame frame, boolean value) {
        frame.setBoolean(slot, value);
    }

    /**
     * Write any other value as Object.
     */
    @Specialization(replaces = {"writeLong", "writeDouble", "writeBoolean"})
    protected void writeObject(VirtualFrame frame, Object value) {
        frame.setObject(slot, value);
    }
}
```

### Function Calls (Following SimpleLanguage)

```java
/**
 * Function invocation with inline caching.
 *
 * Inline caching is THE key optimization for function calls:
 * - First call: lookup function, cache it
 * - Subsequent calls: use cached DirectCallNode
 * - DirectCallNode allows GraalVM to INLINE the entire function body
 */
public abstract class MezzeInvokeNode extends MezzeExpressionNode {

    @Child private MezzeExpressionNode functionNode;
    @Children private final MezzeExpressionNode[] argumentNodes;

    protected MezzeInvokeNode(MezzeExpressionNode functionNode,
                              MezzeExpressionNode[] argumentNodes) {
        this.functionNode = functionNode;
        this.argumentNodes = argumentNodes;
    }

    /**
     * Fast path: same function as last time (monomorphic).
     *
     * guards = "function.getCallTarget() == cachedTarget"
     *   → Only use this specialization if the CallTarget matches
     *
     * @Cached creates a compile-time constant from the first observed value
     */
    @Specialization(
        guards = "function.getCallTarget() == cachedTarget",
        limit = "3"  // Allow up to 3 different call targets before going generic
    )
    @ExplodeLoop  // Unroll the argument evaluation loop
    protected Object callDirect(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getCallTarget()") CallTarget cachedTarget,
            @Cached("create(cachedTarget)") DirectCallNode callNode) {

        // Evaluate arguments - @ExplodeLoop unrolls this
        Object[] argValues = new Object[argumentNodes.length];
        for (int i = 0; i < argumentNodes.length; i++) {
            argValues[i] = argumentNodes[i].executeGeneric(frame);
        }

        // Direct call - can be INLINED by GraalVM!
        return callNode.call(argValues);
    }

    /**
     * Slow path: different function each time (megamorphic).
     * Uses IndirectCallNode which has more overhead but handles any target.
     */
    @Specialization(replaces = "callDirect")
    @ExplodeLoop
    protected Object callIndirect(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached IndirectCallNode callNode) {

        Object[] argValues = new Object[argumentNodes.length];
        for (int i = 0; i < argumentNodes.length; i++) {
            argValues[i] = argumentNodes[i].executeGeneric(frame);
        }

        return callNode.call(function.getCallTarget(), argValues);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object function = functionNode.executeGeneric(frame);
        if (function instanceof MezzeFunction) {
            return executeCall(frame, (MezzeFunction) function);
        }
        throw MezzeException.typeError(this, "Cannot call non-function");
    }

    protected abstract Object executeCall(VirtualFrame frame, MezzeFunction function);
}
```

### Control Flow

```java
/**
 * If-then-else expression.
 *
 * Uses ConditionProfile to help JIT predict branch direction.
 * After warmup, the unlikely branch may be compiled out entirely.
 */
public class MezzeIfNode extends MezzeExpressionNode {

    @Child private MezzeExpressionNode conditionNode;
    @Child private MezzeExpressionNode thenNode;
    @Child private MezzeExpressionNode elseNode;

    /** Profile for branch prediction */
    private final ConditionProfile profile = ConditionProfile.createCountingProfile();

    public MezzeIfNode(MezzeExpressionNode condition,
                       MezzeExpressionNode thenBranch,
                       MezzeExpressionNode elseBranch) {
        this.conditionNode = condition;
        this.thenNode = thenBranch;
        this.elseNode = elseBranch;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        boolean condition;
        try {
            condition = conditionNode.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw MezzeException.typeError(this, "Condition must be boolean");
        }

        // Profile-guided branch
        if (profile.profile(condition)) {
            return thenNode.executeGeneric(frame);
        } else {
            return elseNode.executeGeneric(frame);
        }
    }

    // Typed execution methods for primitive results
    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        boolean condition;
        try {
            condition = conditionNode.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw MezzeException.typeError(this, "Condition must be boolean");
        }

        if (profile.profile(condition)) {
            return thenNode.executeLong(frame);
        } else {
            return elseNode.executeLong(frame);
        }
    }
}

/**
 * Block of statements/expressions.
 * Returns the value of the last expression.
 */
public class MezzeBlockNode extends MezzeExpressionNode {

    @Children private final MezzeNode[] statements;

    public MezzeBlockNode(MezzeNode[] statements) {
        this.statements = statements;
    }

    @Override
    @ExplodeLoop  // Unroll the statement execution
    public Object executeGeneric(VirtualFrame frame) {
        Object result = MezzeNull.INSTANCE;

        for (int i = 0; i < statements.length; i++) {
            MezzeNode stmt = statements[i];
            if (stmt instanceof MezzeExpressionNode) {
                result = ((MezzeExpressionNode) stmt).executeGeneric(frame);
            } else if (stmt instanceof MezzeStatementNode) {
                ((MezzeStatementNode) stmt).executeVoid(frame);
            }
        }

        return result;
    }
}
```

### Records (Array-Backed)

```java
/**
 * Array-backed record for efficient field access.
 *
 * v1 used Map<String, Value> - slow!
 * v2 uses Object[] with compile-time field indices - fast!
 */
public final class MezzeRecord {

    /** The shape defines field names and their indices */
    private final MezzeRecordShape shape;

    /** Values stored by index (not by name!) */
    private final Object[] values;

    private MezzeRecord(MezzeRecordShape shape, Object[] values) {
        this.shape = shape;
        this.values = values;
    }

    /**
     * Get field by INDEX (fast path).
     * At compile time, we know field names → indices.
     */
    public Object getByIndex(int index) {
        return values[index];
    }

    /**
     * Get field by NAME (slow path, for interop only).
     */
    @TruffleBoundary
    public Object getByName(String name) {
        int index = shape.getIndex(name);
        if (index < 0) {
            throw new MezzeException("Unknown field: " + name);
        }
        return values[index];
    }

    public MezzeRecordShape getShape() {
        return shape;
    }

    public static MezzeRecord create(MezzeRecordShape shape, Object[] values) {
        return new MezzeRecord(shape, values);
    }
}

/**
 * Shape defines the structure of a record type.
 * Created once at compile time, shared by all records with same fields.
 */
public final class MezzeRecordShape {

    /** Field names in order */
    private final String[] fieldNames;

    /** Quick lookup: name → index (used only in slow path) */
    private final Map<String, Integer> nameToIndex;

    public MezzeRecordShape(String[] fieldNames) {
        this.fieldNames = fieldNames;
        this.nameToIndex = new HashMap<>();
        for (int i = 0; i < fieldNames.length; i++) {
            nameToIndex.put(fieldNames[i], i);
        }
    }

    public int getIndex(String name) {
        Integer idx = nameToIndex.get(name);
        return idx != null ? idx : -1;
    }

    public int getFieldCount() {
        return fieldNames.length;
    }

    public String getFieldName(int index) {
        return fieldNames[index];
    }
}
```

### Field Access with Shape Caching

```java
/**
 * Read a field from a record.
 *
 * Uses inline caching on the record shape:
 * - If we see the same shape, use cached index (fast)
 * - If shape changes, update cache (rare)
 */
public abstract class MezzeReadPropertyNode extends MezzeExpressionNode {

    @Child private MezzeExpressionNode receiverNode;

    /** The field name to read */
    private final String fieldName;

    protected MezzeReadPropertyNode(MezzeExpressionNode receiver, String fieldName) {
        this.receiverNode = receiver;
        this.fieldName = fieldName;
    }

    /**
     * Fast path: same shape as cached.
     */
    @Specialization(
        guards = "record.getShape() == cachedShape",
        limit = "3"
    )
    protected Object readCached(
            MezzeRecord record,
            @Cached("record.getShape()") MezzeRecordShape cachedShape,
            @Cached("cachedShape.getIndex(fieldName)") int cachedIndex) {

        // Direct array access - NO Map lookup!
        return record.getByIndex(cachedIndex);
    }

    /**
     * Slow path: unknown shape.
     */
    @Specialization(replaces = "readCached")
    @TruffleBoundary
    protected Object readUncached(MezzeRecord record) {
        return record.getByName(fieldName);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object receiver = receiverNode.executeGeneric(frame);
        if (receiver instanceof MezzeRecord) {
            return executeRead((MezzeRecord) receiver);
        }
        throw MezzeException.typeError(this, "Cannot read property of non-record");
    }

    protected abstract Object executeRead(MezzeRecord record);
}
```

### The Root Node (Minimal Overhead)

```java
/**
 * Root node for Mezze functions.
 *
 * CRITICAL DIFFERENCE FROM v1:
 * - NO propagateHandlers() call
 * - NO Map operations
 * - Just bind arguments and execute body
 *
 * Effect handlers are checked ONCE at program entry, not per-call.
 */
public class MezzeRootNode extends RootNode {

    @Child private MezzeExpressionNode bodyNode;

    /** For debugging - the function name */
    private final String name;

    /** Number of parameters */
    private final int parameterCount;

    public MezzeRootNode(TruffleLanguage<?> language,
                         FrameDescriptor frameDescriptor,
                         MezzeExpressionNode bodyNode,
                         String name,
                         int parameterCount) {
        super(language, frameDescriptor);
        this.bodyNode = bodyNode;
        this.name = name;
        this.parameterCount = parameterCount;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Arguments come in as Object[] from the caller
        // They're already in the frame (set by Truffle)
        // Just execute the body!
        return bodyNode.executeGeneric(frame);
    }

    @Override
    public String getName() {
        return name;
    }
}
```

---

## Part 4: What Makes This Fast?

### The Hot Path for `n - 1`

**v1 (8+ operations):**
```
1. ReadVariableNode.execute() → MezzeInt(n)
2. LiteralNode.execute() → MezzeInt(1)
3. SubtractNode.execute()
   3a. instanceof check: is left MezzeInt?
   3b. instanceof check: is right MezzeInt?
   3c. Unwrap: left.value
   3d. Unwrap: right.value
   3e. Compute: result = l - r
   3f. new MezzeInt(result)
4. Return boxed result
```

**v2 (After JIT: 1 operation):**
```
1. SUB RAX, 1    ; literally just this
```

### Why The Difference?

1. **Typed frame slots:** `frame.getLong(0)` not `frame.getObject(0)` cast to `MezzeInt`
2. **Primitive specializations:** `long subLongs(long l, long r)` returns primitive
3. **Constant folding:** Literal `1` becomes compile-time constant
4. **No boxing:** Value flows as `long` through entire computation
5. **Inlining:** All node execute() methods collapse into caller

### Expected Results

| Benchmark | v1 | v2 Target |
|-----------|-----|-----------|
| fib(35) | ~8s | <100ms |
| fib(40) | timeout | <1s |
| 1M additions | ~500ms | <10ms |
| Field access | ~50ns (Map) | <5ns (array) |

---

## Part 5: Implementation Plan

### Phase 1: Foundation
- [ ] Set up Gradle module for interpreterv2
- [ ] Implement MezzeTypes.java
- [ ] Implement MezzeNode, MezzeExpressionNode base classes
- [ ] Implement literal nodes (long, double, boolean, string, null)

### Phase 2: Variables & Arithmetic
- [ ] Implement MezzeReadLocalNode, MezzeWriteLocalNode
- [ ] Implement arithmetic: Add, Sub, Mul, Div, Mod, Neg
- [ ] Implement comparison: Eq, Neq, Lt, Le, Gt, Ge
- [ ] Test with simple expressions

### Phase 3: Control Flow
- [ ] Implement MezzeIfNode
- [ ] Implement MezzeBlockNode
- [ ] Implement MezzeReturnNode
- [ ] Test with conditionals

### Phase 4: Functions
- [ ] Implement MezzeFunction runtime type
- [ ] Implement MezzeRootNode
- [ ] Implement MezzeInvokeNode with inline caching
- [ ] Implement MezzeFunctionLiteralNode (lambda creation)
- [ ] Test with simple functions

### Phase 5: Records
- [ ] Implement MezzeRecordShape
- [ ] Implement MezzeRecord (array-backed)
- [ ] Implement MezzeReadPropertyNode
- [ ] Implement record literal creation
- [ ] Test with record operations

### Phase 6: Integration
- [ ] Create AST-to-v2 converter
- [ ] Wire up to existing parser/typechecker
- [ ] Run fib(35) benchmark
- [ ] Run full test suite

### Phase 7: Advanced Features
- [ ] Closures (array-based capture)
- [ ] Pattern matching
- [ ] Effect handlers (entry-point checking)
- [ ] Interop

---

## Part 6: Sources & References

- [SimpleLanguage (Truffle example language)](https://github.com/graalvm/simplelanguage)
- [Introduction to SimpleLanguage - GraalVM docs](https://www.graalvm.org/latest/graalvm-as-a-platform/implement-language/)
- [Truffle Language Implementation Framework](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/)
- [TruffleRuby Performance Optimization](https://shopify.engineering/optimizing-ruby-lazy-initialization-in-truffleruby-with-deoptimization)
- [A Systematic Approach to Improving TruffleRuby Performance](https://nirvdrum.com/2017/05/30/a-systematic-approach-to-improving-truffleruby-performance.html)
- [Chris Seaton's TruffleRuby research](https://chrisseaton.com/truffleruby/)
