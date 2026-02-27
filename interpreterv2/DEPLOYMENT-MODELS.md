# Deployment Models for Distributed Mezze

## The Core Question

When Worker B needs to execute code by hash `#abc123`:
1. **What** does it fetch?
2. **What** must be pre-installed on Worker B?
3. **What** does Worker B do with what it fetched?

---

## Option 1: Fetch Typed AST (Recommended)

### What Gets Transferred

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CodeBundle {                                                                 │
│     hash: "#a7f9e2c8b1d4...",                                               │
│                                                                              │
│     // The normalized, typed AST (after typechecker)                        │
│     typedAST: {                                                              │
│         kind: "LambdaExpression",                                           │
│         parameters: [{name: "n", inferredType: "IntType"}],                 │
│         body: {                                                              │
│             kind: "IfExpression",                                           │
│             condition: {..., inferredType: "BoolType"},                     │
│             thenBranch: {..., inferredType: "IntType"},                     │
│             elseBranch: {                                                    │
│                 kind: "BinaryOp",                                           │
│                 op: "+",                                                     │
│                 left: {kind: "FunctionCall", isTailCall: false, ...},       │
│                 right: {kind: "FunctionCall", isTailCall: true, ...},       │
│                 inferredType: "IntType"                                     │
│             }                                                                │
│         },                                                                   │
│         inferredType: "FunctionType({n: Int} -> Int)"                       │
│     },                                                                       │
│                                                                              │
│     // Hashes of code this depends on                                       │
│     dependencies: [                                                          │
│         "#numAdd_Int_impl...",   // NumAdd ability for Int                  │
│         "#numSub_Int_impl...",   // NumSub ability for Int                  │
│         "#lessThanEq_Int_impl..."                                           │
│     ],                                                                       │
│                                                                              │
│     // Resolved ability implementations (so worker knows which impl to use) │
│     resolvedAbilities: {                                                     │
│         "NumAdd$Int": "#numAdd_Int_impl...",                                │
│         "NumSub$Int": "#numSub_Int_impl...",                                │
│         "NumLe$Int": "#lessThanEq_Int_impl..."                              │
│     }                                                                        │
│ }                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘

Serialization format: JSON, MessagePack, or Protobuf
Typical size: 1-10 KB per function
```

### What Worker Needs Pre-Installed

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ WORKER INSTALLATION                                                          │
│                                                                              │
│ Required:                                                                    │
│ ├── GraalVM (JVM with Truffle)                                              │
│ ├── mezze-runtime.jar                                                       │
│ │   ├── Truffle nodes (AddNode, IfNode, etc.)                              │
│ │   ├── AST-to-Truffle converter                                           │
│ │   ├── MezzeValue types (MezzeRecord, MezzeVariant, etc.)                 │
│ │   ├── Type definitions (IntType, StringType, etc.)                       │
│ │   └── Effect handler infrastructure                                       │
│ │                                                                            │
│ │   Does NOT need:                                                          │
│ │   ├── Parser (AST already parsed)                                         │
│ │   ├── TypeChecker (types already inferred)                               │
│ │   └── Source files                                                        │
│ │                                                                            │
│ └── Standard library ability implementations (pre-compiled)                 │
│     ├── NumAdd$Int, NumSub$Int, etc.                                       │
│     └── These are fetched by hash like any other code                      │
│                                                                              │
│ Size: ~20-50 MB (mostly GraalVM)                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### What Worker Does

```
1. Receive task: { hash: "#abc123", args: [35] }

2. Check cache: cache.get("#abc123")
   └── HIT: Jump to step 6
   └── MISS: Continue to step 3

3. Fetch code bundle from coordinator:
   GET /code/#abc123
   Response: CodeBundle (see above)

4. Verify integrity:
   computedHash = hashAST(bundle.typedAST)
   assert(computedHash == "#abc123")  // Tamper detection

5. Recursively fetch dependencies:
   for depHash in bundle.dependencies:
       if not cache.has(depHash):
           fetchAndCompile(depHash)

6. Convert typed AST to Truffle nodes:
   // This is the key step - uses pre-installed converter
   rootNode = ASTToTruffleV2.convert(bundle.typedAST, bundle.resolvedAbilities)
   callTarget = rootNode.getCallTarget()

7. Cache the compiled code:
   cache.put("#abc123", callTarget)

8. Execute:
   result = callTarget.call(35L)

9. Return result
```

### Pros/Cons

| Pros | Cons |
|------|------|
| Worker doesn't need parser/typechecker | Need to serialize AST format |
| Types are pre-resolved (fast conversion) | AST format must be stable |
| Small transfer size (1-10 KB) | Worker needs runtime JAR |
| Hash verifiable | |

---

## Option 2: Fetch Source Code

### What Gets Transferred

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ SourceBundle {                                                               │
│     hash: "#a7f9e2c8b1d4...",                                               │
│     sourceCode: "let fib = {n: Int} -> if n <= 1 then n else ...",         │
│     modulePath: "main",                                                      │
│     dependencies: ["std::num"]  // Module dependencies                      │
│ }                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### What Worker Needs Pre-Installed

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ WORKER INSTALLATION                                                          │
│                                                                              │
│ Required:                                                                    │
│ ├── GraalVM                                                                 │
│ ├── mezze-full.jar (COMPLETE toolchain)                                    │
│ │   ├── Parser                                                              │
│ │   ├── TypeChecker                                                         │
│ │   ├── Monomorphizer                                                       │
│ │   ├── AST-to-Truffle converter                                           │
│ │   └── Runtime                                                             │
│ │                                                                            │
│ └── Standard library source files                                           │
│                                                                              │
│ Size: ~50-100 MB                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Pros/Cons

| Pros | Cons |
|------|------|
| Simple transfer format | Worker needs full toolchain |
| Source is human-readable | Re-parsing, re-typechecking on each worker |
| Easy debugging | Larger installation |
| | Slower first execution |

---

## Option 3: Fetch Bytecode (Like JVM/WASM)

### The Idea

Create a Mezze bytecode format that's:
- Compact and fast to deserialize
- Contains type information
- Maps directly to Truffle nodes

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ MezzeBytecode {                                                              │
│     magic: 0x4D455A5A,  // "MEZZ"                                           │
│     version: 1,                                                              │
│     hash: "#a7f9e2c8b1d4...",                                               │
│                                                                              │
│     // Constant pool                                                         │
│     constants: [                                                             │
│         { type: "int", value: 1 },                                          │
│         { type: "int", value: 2 },                                          │
│         { type: "hash", value: "#numAdd_Int..." }                           │
│     ],                                                                       │
│                                                                              │
│     // Frame layout                                                          │
│     frameSlots: [                                                            │
│         { name: "n", kind: "Long" }                                         │
│     ],                                                                       │
│                                                                              │
│     // Bytecode instructions                                                 │
│     instructions: [                                                          │
│         { op: "LOAD_LOCAL", slot: 0 },           // n                       │
│         { op: "LOAD_CONST", index: 0 },          // 1                       │
│         { op: "CALL_ABILITY", hash: 2 },         // NumLe.le                │
│         { op: "JUMP_IF_FALSE", target: 10 },                                │
│         { op: "LOAD_LOCAL", slot: 0 },           // return n                │
│         { op: "RETURN" },                                                    │
│         // ... else branch                                                   │
│     ]                                                                        │
│ }                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### What Worker Does

```
1. Fetch bytecode
2. Verify hash
3. Deserialize bytecode → Truffle nodes (very fast, direct mapping)
4. Execute
```

### Pros/Cons

| Pros | Cons |
|------|------|
| Fastest deserialization | Need to design bytecode format |
| Smallest transfer size | More work to implement |
| Clear separation | Another compilation target |
| Like JVM class files | |

---

## Recommended: Option 1 (Typed AST) with Lazy Stdlib

### Why Typed AST?

1. **Already exists**: TypeChecker already produces typed AST
2. **Type info preserved**: No re-inference needed
3. **Hash verifiable**: Can recompute hash from AST
4. **Reasonable size**: JSON/MessagePack is compact enough

### Deployment Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              COORDINATOR                                     │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ Full Mezze Installation                                              │    │
│  │                                                                      │    │
│  │ ├── Parser                                                          │    │
│  │ ├── TypeChecker                                                     │    │
│  │ ├── Content Hash Computer                                           │    │
│  │ ├── Interpreter (optional, for local execution)                     │    │
│  │ │                                                                    │    │
│  │ └── SQLite Database                                                 │    │
│  │     ├── definitions (hash → typed AST)                              │    │
│  │     ├── dependencies                                                │    │
│  │     └── Standard library (pre-compiled, by hash)                    │    │
│  │                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  Responsibilities:                                                           │
│  • Parse and typecheck source code                                          │
│  • Compute content hashes                                                   │
│  • Serve code bundles to workers                                            │
│  • Distribute tasks                                                          │
│  • Aggregate results                                                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ gRPC / HTTP
                                      │
           ┌──────────────────────────┼──────────────────────────┐
           │                          │                          │
           ▼                          ▼                          ▼
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│      WORKER 1       │  │      WORKER 2       │  │      WORKER N       │
│                     │  │                     │  │                     │
│ ┌─────────────────┐ │  │ ┌─────────────────┐ │  │ ┌─────────────────┐ │
│ │ Mezze Runtime   │ │  │ │ Mezze Runtime   │ │  │ │ Mezze Runtime   │ │
│ │ (minimal JAR)   │ │  │ │ (minimal JAR)   │ │  │ │ (minimal JAR)   │ │
│ │                 │ │  │ │                 │ │  │ │                 │ │
│ │ • Truffle nodes │ │  │ │ • Truffle nodes │ │  │ │ • Truffle nodes │ │
│ │ • AST converter │ │  │ │ • AST converter │ │  │ │ • AST converter │ │
│ │ • Value types   │ │  │ │ • Value types   │ │  │ │ • Value types   │ │
│ │ • Code fetcher  │ │  │ │ • Code fetcher  │ │  │ │ • Code fetcher  │ │
│ │                 │ │  │ │                 │ │  │ │                 │ │
│ │ NO parser       │ │  │ │ NO parser       │ │  │ │ NO parser       │ │
│ │ NO typechecker  │ │  │ │ NO typechecker  │ │  │ │ NO typechecker  │ │
│ └─────────────────┘ │  │ └─────────────────┘ │  │ └─────────────────┘ │
│                     │  │                     │  │                     │
│ ┌─────────────────┐ │  │ ┌─────────────────┐ │  │ ┌─────────────────┐ │
│ │ Local Cache     │ │  │ │ Local Cache     │ │  │ │ Local Cache     │ │
│ │ (SQLite)        │ │  │ │ (SQLite)        │ │  │ │ (SQLite)        │ │
│ │                 │ │  │ │                 │ │  │ │                 │ │
│ │ Fetched code    │ │  │ │ Fetched code    │ │  │ │ Fetched code    │ │
│ │ Compiled nodes  │ │  │ │ Compiled nodes  │ │  │ │ Compiled nodes  │ │
│ │ JIT profiles    │ │  │ │ JIT profiles    │ │  │ │ JIT profiles    │ │
│ └─────────────────┘ │  │ └─────────────────┘ │  │ └─────────────────┘ │
│                     │  │                     │  │                     │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘

Worker JAR contents (~20MB without GraalVM):
├── com.mezze.language.interpreterv2.*     # Truffle nodes
├── com.mezze.language.ast.*               # AST types (for deserialization)
├── com.mezze.language.interpreter.Mezze*  # Value types
└── com.mezze.language.distributed.*       # Worker protocol
```

### Standard Library Handling

The stdlib (NumAdd, List.map, etc.) is **also fetched by hash**:

```
1. Worker receives task for user function #user123
2. User function depends on #numAdd_Int (NumAdd for Int)
3. Worker checks: cache.has("#numAdd_Int")?
   └── MISS: Fetch from coordinator
4. Coordinator serves stdlib the same way as user code
5. Worker caches stdlib implementations

Result: Workers lazily populate their stdlib cache
        Only fetch what's actually used
        Same mechanism for all code
```

### Bootstrapping a New Worker

```bash
# 1. Install GraalVM
sdk install java 23.1.0-graal

# 2. Install Mezze worker runtime
wget https://releases.mezze.dev/mezze-worker-0.1.0.jar

# 3. Start worker (connects to coordinator)
java -jar mezze-worker.jar \
    --coordinator=grpc://coordinator:50051 \
    --cache-dir=/var/mezze/cache

# Worker is now ready to receive tasks
# It will fetch code on-demand as tasks arrive
```

---

## Data Transfer Example

### Task: Execute `fib(35)` on Worker

**Step 1: Coordinator sends task**
```json
{
  "taskId": "task-001",
  "functionHash": "#fib_abc123...",
  "arguments": [{"type": "long", "value": 35}]
}
```
Size: ~100 bytes

**Step 2: Worker requests code (cache miss)**
```
GET /code/#fib_abc123...
```

**Step 3: Coordinator responds with CodeBundle**
```json
{
  "hash": "#fib_abc123...",
  "typedAST": {
    "kind": "LambdaExpression",
    "parameters": [{"name": "n", "type": "IntType"}],
    "body": {
      "kind": "IfExpression",
      "condition": {
        "kind": "BinaryOp",
        "op": "<=",
        "left": {"kind": "Identifier", "name": "n", "inferredType": "IntType"},
        "right": {"kind": "IntLiteral", "value": 1, "inferredType": "IntType"},
        "inferredType": "BoolType",
        "resolvedAbility": "#numLe_Int..."
      },
      "thenBranch": {"kind": "Identifier", "name": "n", "inferredType": "IntType"},
      "elseBranch": {
        "kind": "BinaryOp",
        "op": "+",
        "left": {
          "kind": "FunctionCall",
          "function": {"kind": "Identifier", "name": "fib"},
          "arguments": {...},
          "inferredType": "IntType",
          "isTailCall": false
        },
        "right": {
          "kind": "FunctionCall",
          "function": {"kind": "Identifier", "name": "fib"},
          "arguments": {...},
          "inferredType": "IntType",
          "isTailCall": false
        },
        "inferredType": "IntType",
        "resolvedAbility": "#numAdd_Int..."
      },
      "inferredType": "IntType"
    },
    "inferredType": "FunctionType"
  },
  "dependencies": ["#numLe_Int...", "#numAdd_Int...", "#numSub_Int..."],
  "resolvedAbilities": {
    "NumLe$Int": "#numLe_Int...",
    "NumAdd$Int": "#numAdd_Int...",
    "NumSub$Int": "#numSub_Int..."
  }
}
```
Size: ~2-5 KB (JSON), ~1-2 KB (MessagePack)

**Step 4: Worker fetches dependencies (if not cached)**
```
GET /code/#numLe_Int...
GET /code/#numAdd_Int...
GET /code/#numSub_Int...
```

**Step 5: Worker converts AST → Truffle nodes**
```java
// In worker's ASTToTruffleV2 converter
RootNode fib = convertLambda(bundle.typedAST);
// Creates: IfNode, LessThanEqNode (Long specialization), AddNode (Long), etc.
```

**Step 6: Worker executes and caches**
```java
cache.put("#fib_abc123...", fib.getCallTarget());
Object result = fib.getCallTarget().call(35L);
// result = 9227465
```

**Step 7: Worker returns result**
```json
{
  "taskId": "task-001",
  "result": {"type": "long", "value": 9227465}
}
```

---

## Summary

| Question | Answer |
|----------|--------|
| **What is fetched?** | Typed AST (JSON/MessagePack) with type info + dependencies |
| **What's on worker?** | Minimal runtime JAR (~20MB) + GraalVM |
| **What's NOT on worker?** | Parser, TypeChecker, source files |
| **How is stdlib handled?** | Fetched by hash like any other code |
| **Transfer size?** | ~1-5 KB per function |
| **Can verify integrity?** | Yes, recompute hash from AST |

The key insight: **Workers only need the ability to convert typed AST to Truffle nodes**. All the expensive work (parsing, type inference) happens once on the coordinator.
