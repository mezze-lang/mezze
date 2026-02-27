# Distributed Execution via Content-Addressable Code

## The Vision: Send Hash, Not Code

Traditional distributed computing:
```
Machine A: "Here's 500KB of code, please run it"
Machine B: *parses* *typechecks* *compiles* *runs*
```

Content-addressable distributed computing:
```
Machine A: "Run #a7f9e2c8 with args [1, 2, 3]"
Machine B: *cache hit* *runs compiled code instantly*
```

---

## Part 1: How It Works

### 1.1 The Hash Is The Identity

Every Mezze function has a deterministic hash:

```mezze
let process_item = {item: Item} ->
    transform item |> validate |> store
```

Hash: `#a7f9e2c8b1d4f6e3...` (SHA3-512, 128 hex chars)

This hash is:
- **Deterministic**: Same code = same hash, always
- **Global**: Same hash on every machine in the world
- **Stable**: Renaming variables doesn't change it
- **Content-based**: Only structure matters, not names

### 1.2 The Protocol

```
┌─────────────────┐                    ┌─────────────────┐
│   Coordinator   │                    │     Worker      │
│                 │                    │                 │
│  1. Has code    │   ──────────────>  │  2. Receives:   │
│     with hash   │   { hash, args }   │     - hash      │
│     #a7f9e2c8   │                    │     - args      │
│                 │                    │                 │
│                 │                    │  3. Checks:     │
│                 │                    │     cache[hash] │
│                 │                    │        │        │
│                 │                    │        ▼        │
│                 │                    │  ┌───────────┐  │
│                 │                    │  │ HIT: Run  │  │
│                 │                    │  │ compiled  │  │
│                 │                    │  │ instantly │  │
│                 │                    │  └───────────┘  │
│                 │                    │        │        │
│                 │   <──────────────  │  4. Return      │
│  5. Result      │      { result }    │     result      │
└─────────────────┘                    └─────────────────┘
```

### 1.3 Cache Miss: Fetch By Hash

If worker doesn't have the code:

```
Worker                              Coordinator (or CDN)
   │                                      │
   │  "I need code for #a7f9e2c8"        │
   │  ─────────────────────────────────> │
   │                                      │
   │  { ast: ..., dependencies: [...] }  │
   │  <───────────────────────────────── │
   │                                      │
   │  1. Verify: hash(ast) == #a7f9e2c8  │
   │  2. Fetch dependencies (also by hash)│
   │  3. Compile to Truffle nodes         │
   │  4. Cache compiled code              │
   │  5. Execute                          │
```

---

## Part 2: Wire Protocol

### 2.1 Task Message

```typescript
interface DistributedTask {
  // What to run
  functionHash: string;        // "#a7f9e2c8b1d4..."

  // Arguments (serialized MezzeValues)
  arguments: SerializedValue[];

  // Execution context
  effectHandlers?: {           // Handler hashes for effects
    [effectName: string]: string;  // e.g., "Log" -> "#handler123..."
  };

  // Metadata
  taskId: string;
  priority?: number;
  timeout?: number;
}
```

### 2.2 Serialized Values

```typescript
type SerializedValue =
  | { type: "int", value: number }
  | { type: "float", value: number }
  | { type: "string", value: string }
  | { type: "bool", value: boolean }
  | { type: "record", shape: string[], values: SerializedValue[] }
  | { type: "variant", tag: number, constructorName: string, payload?: SerializedValue }
  | { type: "array", elementType: string, values: SerializedValue[] }
  | { type: "function", hash: string }  // Functions are just hashes!
  | { type: "null" }
```

### 2.3 Code Fetch Request

```typescript
interface CodeFetchRequest {
  hash: string;
}

interface CodeFetchResponse {
  hash: string;

  // The normalized AST (for compilation)
  ast: NormalizedAST;

  // Dependencies (other hashes this code needs)
  dependencies: string[];

  // Type information for optimization
  typeInfo: {
    parameterTypes: TypeDescriptor[];
    returnType: TypeDescriptor;
    effectRow: string[];
  };

  // Optional: pre-compiled Truffle nodes (if transferable)
  compiledNodes?: SerializedNodes;
}
```

---

## Part 3: Implementation

### 3.1 Coordinator (Sends Tasks)

```kotlin
class DistributedCoordinator(
    private val codeRegistry: CodeRegistry,  // hash -> AST
    private val workerPool: WorkerPool
) {
    /**
     * Execute a function on a remote worker.
     */
    suspend fun execute(
        functionHash: String,
        arguments: List<MezzeValue>
    ): MezzeValue {
        // 1. Create task
        val task = DistributedTask(
            functionHash = functionHash,
            arguments = arguments.map { serialize(it) },
            taskId = UUID.randomUUID().toString()
        )

        // 2. Find worker (load balancing)
        val worker = workerPool.selectWorker(task)

        // 3. Send and await result
        val result = worker.execute(task)

        return deserialize(result)
    }

    /**
     * Handle code fetch requests from workers.
     */
    fun handleCodeFetch(request: CodeFetchRequest): CodeFetchResponse {
        val code = codeRegistry.get(request.hash)
            ?: throw CodeNotFoundError(request.hash)

        return CodeFetchResponse(
            hash = request.hash,
            ast = code.normalizedAST,
            dependencies = code.dependencies,
            typeInfo = code.typeInfo
        )
    }
}
```

### 3.2 Worker (Executes Tasks)

```kotlin
class DistributedWorker(
    private val codeCache: CodeCache,       // hash -> compiled CallTarget
    private val coordinator: CoordinatorClient
) {
    /**
     * Execute a task received from coordinator.
     */
    suspend fun execute(task: DistributedTask): SerializedValue {
        // 1. Get or compile the function
        val callTarget = getOrCompile(task.functionHash)

        // 2. Deserialize arguments
        val args = task.arguments.map { deserialize(it) }

        // 3. Set up effect handlers
        val handlers = task.effectHandlers?.mapValues { (_, hash) ->
            getOrCompile(hash)
        } ?: emptyMap()

        // 4. Execute
        val result = withHandlers(handlers) {
            callTarget.call(*args.toTypedArray())
        }

        // 5. Serialize result
        return serialize(result as MezzeValue)
    }

    /**
     * Get compiled code from cache, or fetch and compile.
     */
    private suspend fun getOrCompile(hash: String): RootCallTarget {
        // Check local cache
        codeCache.get(hash)?.let { return it }

        // Cache miss - fetch from coordinator
        val code = coordinator.fetchCode(hash)

        // Verify hash (trust but verify!)
        val computedHash = computeHash(code.ast)
        require(computedHash == hash) { "Hash mismatch! Possible tampering." }

        // Recursively fetch dependencies
        for (depHash in code.dependencies) {
            getOrCompile(depHash)  // Ensure all deps are compiled
        }

        // Compile AST to Truffle nodes
        val rootNode = ASTToTruffleConverter.convert(
            code.ast,
            code.typeInfo,
            codeCache  // For resolving dependency calls
        )

        val callTarget = rootNode.callTarget

        // Cache for future use
        codeCache.put(hash, callTarget)

        return callTarget
    }
}
```

### 3.3 Code Cache with Persistence

```kotlin
class CodeCache(
    private val cacheDir: Path  // ~/.mezze/cache/compiled/
) {
    private val memoryCache = ConcurrentHashMap<String, RootCallTarget>()

    fun get(hash: String): RootCallTarget? {
        // Check memory cache first
        memoryCache[hash]?.let { return it }

        // Check disk cache
        val diskFile = cacheDir.resolve("$hash.node")
        if (diskFile.exists()) {
            val callTarget = loadFromDisk(diskFile)
            memoryCache[hash] = callTarget  // Promote to memory
            return callTarget
        }

        return null
    }

    fun put(hash: String, callTarget: RootCallTarget) {
        memoryCache[hash] = callTarget

        // Persist to disk for next restart
        val diskFile = cacheDir.resolve("$hash.node")
        saveToDisk(diskFile, callTarget)
    }

    /**
     * Pre-warm cache with frequently used functions.
     */
    suspend fun prewarm(hashes: List<String>, fetcher: (String) -> CodeFetchResponse) {
        for (hash in hashes) {
            if (!memoryCache.containsKey(hash)) {
                val code = fetcher(hash)
                val callTarget = compile(code)
                put(hash, callTarget)
            }
        }
    }
}
```

---

## Part 4: Advanced Patterns

### 4.1 Speculative Prefetching

Workers can prefetch code they'll likely need:

```kotlin
class PrefetchingWorker(
    private val cache: CodeCache,
    private val coordinator: CoordinatorClient
) {
    /**
     * When we compile code, prefetch its dependencies in background.
     */
    private suspend fun prefetchDependencies(code: CodeFetchResponse) {
        // Launch background prefetch for each dependency
        for (depHash in code.dependencies) {
            if (!cache.has(depHash)) {
                launch {  // Background coroutine
                    try {
                        val depCode = coordinator.fetchCode(depHash)
                        compile(depCode)
                        cache.put(depHash, ...)
                    } catch (e: Exception) {
                        // Prefetch failed, will fetch on demand
                    }
                }
            }
        }
    }

    /**
     * Predict what code will be needed based on call patterns.
     */
    private fun predictNeededCode(recentTasks: List<DistributedTask>): List<String> {
        // Analyze recent execution patterns
        // Return hashes that are likely to be called soon
        return callGraphAnalyzer.predictNextCalls(recentTasks)
    }
}
```

### 4.2 Code Distribution Network (CDN)

For global distribution, use a CDN:

```
┌─────────────────────────────────────────────────────────────────┐
│                        Code Registry                             │
│                    (Source of truth)                             │
│                                                                  │
│  #a7f9e2c8 -> { ast: ..., deps: [...] }                        │
│  #b3c4d5e6 -> { ast: ..., deps: [...] }                        │
│  ...                                                             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Replicate
                              ▼
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  CDN Node   │    │  CDN Node   │    │  CDN Node   │
│  US-West    │    │  EU-West    │    │  Asia-East  │
│             │    │             │    │             │
│  Cache of   │    │  Cache of   │    │  Cache of   │
│  popular    │    │  popular    │    │  popular    │
│  hashes     │    │  hashes     │    │  hashes     │
└─────────────┘    └─────────────┘    └─────────────┘
       │                  │                  │
       ▼                  ▼                  ▼
   Workers            Workers            Workers
   in region          in region          in region
```

Workers fetch from nearest CDN node. Cache hits are instant.

### 4.3 Incremental Code Updates

When code changes, only affected hashes change:

```
Before:                          After edit:
┌─────────────────┐             ┌─────────────────┐
│ main #abc123    │             │ main #abc123    │  (unchanged)
│   │             │             │   │             │
│   ▼             │             │   ▼             │
│ process #def456 │             │ process #xyz789 │  (NEW hash)
│   │             │             │   │             │
│   ▼             │             │   ▼             │
│ helper #ghi789  │             │ helper #ghi789  │  (unchanged)
└─────────────────┘             └─────────────────┘

Workers need to fetch: only #xyz789
Everything else: cache hit!
```

### 4.4 Hot Code Reload

Update running workers without restart:

```kotlin
class HotReloadableWorker {
    private val codeCache = CodeCache()

    /**
     * Coordinator notifies: "hash #old123 is replaced by #new456"
     */
    fun handleCodeUpdate(oldHash: String, newHash: String) {
        // 1. Fetch new code (background)
        val newCode = coordinator.fetchCode(newHash)
        val newCallTarget = compile(newCode)

        // 2. Atomic swap in cache
        codeCache.put(newHash, newCallTarget)

        // 3. Mark old hash as deprecated
        codeCache.deprecate(oldHash)

        // 4. New tasks use new code
        // In-flight tasks continue with old code (safe!)
        // When all old tasks complete, old code is GC'd
    }
}
```

---

## Part 5: Security Considerations

### 5.1 Hash Verification

Always verify code matches its hash:

```kotlin
fun verifyAndCompile(hash: String, code: CodeFetchResponse): RootCallTarget {
    // Recompute hash from AST
    val computedHash = ASTHasher.hash(code.ast)

    if (computedHash != hash) {
        throw SecurityException(
            "Hash mismatch! Expected $hash, got $computedHash. " +
            "Possible code tampering detected."
        )
    }

    return compile(code)
}
```

### 5.2 Sandboxed Execution

Workers run untrusted code in sandbox:

```kotlin
class SandboxedWorker {
    fun execute(task: DistributedTask): SerializedValue {
        // Resource limits
        val limits = ResourceLimits(
            maxMemory = 512.megabytes,
            maxCpuTime = 30.seconds,
            maxStackDepth = 1000
        )

        // Effect restrictions
        val allowedEffects = setOf("Pure", "Log")  // No FileSystem, Network, etc.

        return sandbox(limits, allowedEffects) {
            executeTask(task)
        }
    }
}
```

### 5.3 Code Signing (Optional)

For enterprise deployments:

```kotlin
interface SignedCode {
    val hash: String
    val ast: NormalizedAST
    val signature: ByteArray      // Signed by trusted authority
    val signerPublicKey: ByteArray
}

fun verifySignature(code: SignedCode, trustedKeys: Set<PublicKey>): Boolean {
    // Verify signature matches hash
    val isValidSignature = Crypto.verify(
        data = code.hash.toByteArray(),
        signature = code.signature,
        publicKey = code.signerPublicKey
    )

    // Verify signer is trusted
    val isTrustedSigner = trustedKeys.contains(code.signerPublicKey)

    return isValidSignature && isTrustedSigner
}
```

---

## Part 6: Comparison with Other Systems

### 6.1 vs Traditional RPC (gRPC, REST)

| Aspect | Traditional RPC | Hash-Based |
|--------|-----------------|------------|
| Code deployment | Push to all servers | On-demand fetch |
| Version sync | Complex coordination | Automatic (hash = version) |
| Rollback | Redeploy old version | Reference old hash |
| Cache efficiency | Per-server | Global (same hash everywhere) |
| Cold start | Full initialization | Incremental (fetch missing) |

### 6.2 vs Unison

Mezze's approach is similar to Unison but with key differences:

| Aspect | Unison | Mezze |
|--------|--------|-------|
| Type system | Structural | Nominal + Structural |
| Effects | Algebraic | Algebraic (similar) |
| Compilation | Interpreted | Truffle JIT (faster) |
| Abilities | Part of type | Separate concept |
| Runtime | Custom | GraalVM (polyglot) |

### 6.3 vs WASM

| Aspect | WASM | Mezze Hash-Based |
|--------|------|------------------|
| Code format | Binary bytecode | AST + types |
| Compilation | AOT or JIT | Truffle JIT |
| Type safety | Limited | Full (static types) |
| Interop | FFI | GraalVM polyglot |
| Code sharing | Module imports | Hash references |

---

## Part 7: Example: Distributed Map-Reduce

### 7.1 The Code

```mezze
// This function has hash #map_fn_abc123
let process_batch = {items: Array<Item>} -> [Log]
    items
    |> Array.map {item} -> transform item
    |> Array.filter {item} -> is_valid item

// This function has hash #reduce_fn_def456
let combine_results = {batches: Array<Array<Result>>} -> [Log]
    batches |> Array.flatten |> Array.sort_by {r} -> r.score
```

### 7.2 The Execution

```kotlin
// Coordinator
val items = loadItems()  // 1 million items
val batches = items.chunked(10_000)  // 100 batches

// Distribute to workers
val results = batches.map { batch ->
    coordinator.execute(
        functionHash = "#map_fn_abc123",  // Just the hash!
        arguments = listOf(batch)
    )
}

// Reduce
val finalResult = coordinator.execute(
    functionHash = "#reduce_fn_def456",
    arguments = listOf(results)
)
```

### 7.3 What Happens

```
Coordinator                     Worker 1          Worker 2          Worker 3
    │                              │                 │                 │
    │ {hash: #map_fn, batch_0}    │                 │                 │
    │ ─────────────────────────>  │                 │                 │
    │                              │                 │                 │
    │ {hash: #map_fn, batch_1}    │                 │                 │
    │ ───────────────────────────────────────────>  │                 │
    │                              │                 │                 │
    │ {hash: #map_fn, batch_2}    │                 │                 │
    │ ─────────────────────────────────────────────────────────────>  │
    │                              │                 │                 │
    │                              │ Cache hit!     │ Cache miss      │ Cache miss
    │                              │ Execute        │ Fetch code      │ Fetch code
    │                              │ instantly      │ Compile         │ Compile
    │                              │                │ Execute         │ Execute
    │                              │                 │                 │
    │ <───────────────────────────  result_0       │                 │
    │ <─────────────────────────────────────────────  result_1       │
    │ <───────────────────────────────────────────────────────────────  result_2
    │                              │                 │                 │
    │                              │                 │                 │
    │ Workers 2,3 now have         │                 │                 │
    │ #map_fn compiled and cached  │                 │                 │
    │                              │                 │                 │
    │ {hash: #map_fn, batch_3}    │                 │                 │
    │ ───────────────────────────────────────────>  │                 │
    │                              │                 │                 │
    │                              │                 │ Cache hit now! │
    │                              │                 │ Execute        │
    │                              │                 │ instantly      │
```

---

## Part 8: Integration with Mezze Effects

### 8.1 Remote Effect Handlers

Effects can be handled locally or remotely:

```mezze
effect Database {
    query: {sql: String} -> Array<Row>
    insert: {table: String, row: Row} -> {}
}

// Local handler (same machine)
impl Database for LocalDB {
    query = {sql} -> sqlite.execute sql
    insert = {table, row} -> sqlite.insert table row
}

// Remote handler (different machine)
impl Database for RemoteDB {
    query = {sql} -> rpc.call "db-server" "query" {sql}
    insert = {table, row} -> rpc.call "db-server" "insert" {table, row}
}
```

### 8.2 Distributing with Effect Handlers

```kotlin
// Send task with specific handler
coordinator.execute(
    functionHash = "#process_data_abc123",
    arguments = listOf(data),
    effectHandlers = mapOf(
        "Database" to "#remote_db_handler_xyz789"  // Use remote DB
    )
)
```

Worker automatically uses the specified handler.

---

## Part 9: Performance Characteristics

### 9.1 Latency Breakdown

| Phase | First Call | Cached Call |
|-------|------------|-------------|
| Task serialization | 0.1ms | 0.1ms |
| Network (same region) | 1ms | 1ms |
| Code fetch | 50ms | 0ms (cache hit) |
| Compilation | 100ms | 0ms (cache hit) |
| Execution (interpreted) | 10ms | - |
| Execution (JIT compiled) | - | 0.5ms |
| **Total** | **~161ms** | **~1.6ms** |

### 9.2 Cache Hit Rates

Typical production workload:
- **First deploy**: 0% cache hits (cold start)
- **After warmup**: 95%+ cache hits
- **After code update**: 90%+ cache hits (only changed functions miss)
- **Steady state**: 99%+ cache hits

### 9.3 Memory Usage

Per-worker memory for code cache:
- 1000 functions: ~50MB
- 10,000 functions: ~500MB
- LRU eviction for cold functions

---

## Part 10: Future Extensions

### 10.1 Global Code Registry

Like npm/crates.io but for compiled code:

```
registry.mezze.dev/
  ├── #a7f9e2c8 -> { ast, compiled, metadata }
  ├── #b3c4d5e6 -> { ast, compiled, metadata }
  └── ...

# Anyone can fetch code by hash
# Code is verified by hash (tamper-proof)
# Popular code is CDN-cached globally
```

### 10.2 Capability-Based Security

Functions declare what they can do:

```mezze
// This function can only do pure computation + logging
let process: {data: Data} -> [Log] Result = ...

// This function can access network
let fetch: {url: String} -> [Network, Log] Response = ...
```

Workers only grant capabilities the function needs.

### 10.3 Distributed Debugging

Trace execution across machines:

```kotlin
// Each execution tagged with trace ID
val trace = DistributedTrace(
    traceId = "trace-12345",
    spans = listOf(
        Span(machine = "worker-1", hash = "#abc", duration = 5.ms),
        Span(machine = "worker-2", hash = "#def", duration = 10.ms),
        Span(machine = "worker-1", hash = "#ghi", duration = 3.ms)
    )
)

// Visualize call graph across machines
trace.visualize()
```

---

## Summary

Content-addressable code enables:

1. **Hot loading**: Send hash, not code
2. **Global caching**: Same hash = same code everywhere
3. **Incremental updates**: Only fetch changed functions
4. **Version-free deployment**: Hash IS the version
5. **Trust-but-verify**: Hash proves integrity
6. **Polyglot execution**: GraalVM runs anywhere

This is fundamentally different from traditional deployment:

| Traditional | Hash-Based |
|-------------|------------|
| Deploy code to servers | Servers fetch code on demand |
| Version numbers | Content hashes |
| Full redeploy | Incremental (only changed) |
| Server-specific state | Global cache |
| Complex rollback | Reference old hash |

Mezze + Content Hashes + Truffle JIT = **Distributed computing that's fast AND simple**.
