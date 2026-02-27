# Mezze Interpreter v2

## Overview

Interpreter v2 is a complete rewrite designed for **near-native performance** by leveraging:

1. **Static type information** from the typechecker
2. **Content-addressable hashing** for caching and dispatch
3. **Truffle DSL** for proper JIT compilation
4. **Optimistic mutation** where safe

## Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Complete technical architecture + polyglot interop |
| [DISTRIBUTED-EXECUTION.md](./DISTRIBUTED-EXECUTION.md) | Hot-loading and distributed computing via hashes |
| [DEPLOYMENT-MODELS.md](./DEPLOYMENT-MODELS.md) | What gets deployed where (workers, JARs, etc.) |
| [POLYGLOT-INTEROP.md](./POLYGLOT-INTEROP.md) | GraalVM language interop (Python, JS, Ruby) |
| [COMPLETE-ARCHITECTURE.md](./COMPLETE-ARCHITECTURE.md) | Full system diagrams and flows |

## Why v2?

### v1 Problems

```
fib(35) benchmark:
  - Java:   50ms
  - v1:     8000ms (160x slower!)
```

Root causes:
- **Boxing**: `MezzeInt` wraps every integer
- **@TruffleBoundary** in hot paths prevents JIT
- **HashMap lookups** for records and closures
- **String comparison** for variant matching
- **No Truffle DSL**: Manual type dispatch

### v2 Solutions

| Problem | v1 | v2 |
|---------|----|----|
| Integers | `MezzeInt` (boxed) | `long` (primitive) |
| Type dispatch | Manual if/else | `@Specialization` DSL |
| Frame slots | All `Object` | Typed (`Long`, `Double`, `Boolean`) |
| Records | `HashMap<String, Value>` | `Object[]` with compile-time indices |
| Variants | `String` constructor | `int` tag |
| Ability dispatch | Hash lookup at runtime | `DirectCallNode` (inlined) |
| Closures | `Map<String, Value>` | `Object[]` (done in v1) |

## Mezze's Unique Advantages

### Static Type Information

Unlike JavaScript/Python/Ruby, Mezze knows **exact types at compile time**:

```kotlin
// Every expression has:
inferredType: MezzeType           // IntType, StringType, RecordType{...}

// Function calls have:
resolvedParameterOrder: List<String>  // For positional args
resolvedAbilities: Map<String, ResolvedAbility>  // With implementationHash!
isTailCall: Boolean               // For TCO
```

This means:
- **No runtime profiling** needed
- **Pre-select specializations** at compile time
- **Never deoptimize** (types are guaranteed)

### Content-Addressable Hashing

Every declaration has a **stable SHA3-512 hash**:

```mezze
let fib = {n: Int} -> ...
// Hash: #a7f9e2c8b1d4f6e3... (based on structure, not name)
```

This enables:
- **Persistent caching**: Load pre-compiled code across restarts
- **Instant ability dispatch**: Hash → DirectCallNode
- **Distributed execution**: Send hash, not code
- **Hot code reload**: Update by referencing new hash

## Performance Targets

| Benchmark | v1 | v2 (target) | vs Java |
|-----------|----|----|---------|
| fib(35) | 8000ms | 50ms | 1x |
| Record access | HashMap | Array[i] | 1x |
| Variant match | strcmp | switch(int) | 1x |
| Ability call | indirect | inlined | 1x |

## Key Innovations

### 1. Type-Guided Specialization

```java
// Converter checks inferredType and generates specialized node
if (leftType == IntType && rightType == IntType) {
    return new AddLongNode(left, right);  // No type checks at runtime!
}
```

### 2. Hash-Based CallTarget Cache

```kotlin
// On startup, check if we have pre-compiled code
val callTarget = cache.get(hash) ?: compileAndCache(ast, hash)

// Next run: instant load, zero warmup!
```

### 3. Distributed Hot-Loading

```
Machine A: "Run #a7f9e2c8 with args [1, 2, 3]"
Machine B: cache.get("#a7f9e2c8") → execute instantly

// Code travels by hash, not by shipping source/bytecode
```

### 4. Optimistic Mutation

```java
// If record doesn't escape, mutate in place
@Specialization(guards = "!record.hasEscaped()")
MezzeRecord updateInPlace(MezzeRecord record, int field, Object value) {
    record.values[field] = value;  // No allocation!
    return record;
}
```

## Architecture Overview

```
Source → Parser → TypeChecker → [inferredType, contentHash, ...]
                                         │
                                         ▼
                              ┌─────────────────────┐
                              │ Cache Check (hash)  │
                              └─────────────────────┘
                                    │         │
                              cache hit    cache miss
                                    │         │
                                    ▼         ▼
                              Load nodes   Compile with
                              instantly    Truffle DSL
                                    │         │
                                    └────┬────┘
                                         ▼
                              ┌─────────────────────┐
                              │  Truffle Runtime    │
                              │  • Typed frame slots│
                              │  • JIT compilation  │
                              │  • Native speed     │
                              └─────────────────────┘
```

## Implementation Status

| Phase | Feature | Status |
|-------|---------|--------|
| 0 | Documentation | Done |
| 1 | Project setup + Truffle DSL | Not started |
| 2 | Typed frame slots | Not started |
| 3 | Arithmetic specialization | Not started |
| 4 | Integer variant tags | Not started |
| 5 | Hash-based CallTarget cache | Not started |
| 6 | DirectCallNode for abilities | Not started |
| 7 | Primitive arrays | Not started |
| 8 | Optimistic mutation | Not started |
| 9 | Persistent compilation cache | Not started |
| 10 | Distributed execution | Not started |
| 11 | Polyglot interop nodes | Not started |

## Execution Models

### Pure Mezze (Native Binary)

```bash
# Fast startup, full JIT, no polyglot
mez exec app.mz
mez dev
mez build
```

### Polyglot (JVM Mode)

```bash
# Requires language JARs on classpath
java -cp mez.jar:graalpy.jar com.mezze.language.cli.MainKt exec app.mz

# Or install languages via GraalVM Updater
gu install python
gu install js
```

### Production Deployment

```bash
# Build JAR
mez build -o app.jar

# Run on JVM (with polyglot if needed)
java -cp app.jar:graalpy.jar MainKt

# Or compile to native binary (embeds polyglot languages)
native-image --language:python -jar app.jar -o app
./app  # Self-contained
```

See [ARCHITECTURE.md](./ARCHITECTURE.md#part-16-polyglot-interop-architecture) for details.

## Getting Started

### Prerequisites

- GraalVM 23.1+
- Gradle 8+

### Build

```bash
./gradlew :interpreterv2:build
```

### Run Benchmarks

```bash
./gradlew :interpreterv2:test --tests "*Benchmark*"
```

## Further Reading

- [GraalVM Truffle Tutorial](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/)
- [SimpleLanguage](https://github.com/graalvm/simplelanguage) - Reference Truffle implementation
- [TruffleRuby](https://github.com/oracle/truffleruby) - Production Truffle language
- [Unison Language](https://www.unison-lang.org/) - Inspiration for content-addressable code
