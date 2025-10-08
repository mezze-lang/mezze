# Truffle Integration Overview

## Introduction

Funk leverages GraalVM's Truffle framework to provide high-performance execution with Just-In-Time (JIT) compilation. This document outlines how Funk integrates with Truffle to achieve optimal performance through various optimization techniques.

## Architecture Components

### 1. Language Registration

Funk is registered as a Truffle language through several configuration files:

#### Truffle Language Descriptor
```json
// interpreter/src/main/resources/META-INF/truffle-languages/funk.json
{
  "id": "funk",
  "name": "Funk",
  "version": "1.0.0",
  "className": "com.funk.language.interpreter.FunkLanguage",
  "mimeTypes": ["application/x-funk"],
  "fileExtensions": [".funk"],
  "interactive": true,
  "internal": false
}
```

#### Service Registration
```
// interpreter/src/main/resources/META-INF/services/com.oracle.truffle.api.TruffleLanguage
com.funk.language.interpreter.FunkLanguage
```

### 2. Core Components

#### FunkLanguage - Main Entry Point
The `FunkLanguage` class extends `TruffleLanguage<FunkContext>` and serves as the primary integration point:

```kotlin
@Registration(
    id = FunkLanguage.ID,
    name = "Funk",
    implementationName = "Funk",
    version = "1.0.0",
    defaultMimeType = FunkLanguage.MIME_TYPE,
    characterMimeTypes = [FunkLanguage.MIME_TYPE],
    fileTypeDetectors = [FunkFileDetector::class],
    interactive = true,
    internal = false
)
class FunkLanguage : TruffleLanguage<FunkContext>() {
    
    companion object {
        const val ID = "funk"
        const val MIME_TYPE = "application/x-funk"
    }
    
    override fun createContext(env: Env): FunkContext {
        return FunkContext(this, env)
    }
    
    override fun parse(request: ParsingRequest): CallTarget {
        val source = request.source
        val sourceCode = source.characters.toString()
        
        // Parse and type-check the program
        val lexer = FunkLexer(CharStreams.fromString(sourceCode))
        val tokens = CommonTokenStream(lexer)
        val parser = FunkParser(tokens)
        
        val parserImpl = FunkParserImpl()
        val ast = parserImpl.parse(sourceCode)
        
        val typeChecker = TypeChecker()
        val typedProgram = typeChecker.typeCheck(ast)
        
        // Create Truffle execution root node
        val interpreter = FunkInterpreter()
        val rootNode = FunkProgramRootNode(this, typedProgram, interpreter)
        return rootNode.callTarget
    }
}
```

#### FunkContext - Execution Environment
Manages execution context, global variables, and runtime environment:

```kotlin
class FunkContext(
    val language: FunkLanguage,
    val env: TruffleLanguage.Env?
) {
    private val globals = ConcurrentHashMap<String, FunkValue>()
    private val locals = ConcurrentHashMap<String, FunkValue>()
    private val builtins = ConcurrentHashMap<String, FunkValue>()
    
    fun defineGlobal(name: String, value: FunkValue) {
        globals[name] = value
    }
    
    fun lookupGlobal(name: String): FunkValue? {
        return locals[name] ?: globals[name] ?: builtins[name]
    }
    
    // Host access capabilities
    fun lookupHostClass(className: String): Any? {
        return env?.lookupHostSymbol(className)
    }
    
    fun isHostAccessAllowed(): Boolean {
        return env?.isHostLookupAllowed == true
    }
}
```

#### FunkProgramRootNode - Execution Root
Truffle root node for program execution:

```kotlin
class FunkProgramRootNode(
    language: FunkLanguage,
    private val program: Program,
    private val interpreter: FunkInterpreter
) : RootNode(language) {
    
    override fun execute(frame: VirtualFrame): Any {
        return try {
            val result = interpreter.execute(program)
            // Convert FunkValue to appropriate return type for Truffle
            when (result) {
                is FunkInt -> result.value as Any
                is FunkFloat -> result.value as Any
                is FunkString -> result.value as Any
                is FunkBoolean -> result.value as Any
                is FunkNull -> null as Any
                else -> result.getDisplayString() as Any
            }
        } catch (e: Exception) {
            throw FunkException("Execution error: ${e.message}", e)
        }
    }
}
```

## Integration Benefits

### 1. JIT Compilation
- **Automatic compilation** of hot code paths based on execution frequency
- **Profile-guided optimizations** using runtime feedback
- **Speculative optimizations** with deoptimization fallbacks when assumptions fail
- **Inlining** of frequently called functions

### 2. Performance Optimizations
- **Type specialization** based on runtime type feedback
- **Escape analysis** for stack allocation of short-lived objects
- **Loop optimizations** including unrolling and vectorization
- **Dead code elimination** and constant folding

### 3. Memory Management
- **Efficient garbage collection** integration
- **Object layout optimization** for better cache utilization
- **Frame management** for function calls and local variables

### 4. Interoperability
- **Seamless integration** with other GraalVM languages
- **Host language interoperability** (Java, Kotlin, etc.)
- **Polyglot context support** for multi-language applications

## Execution Pipeline

### 1. Parse Phase
```
Funk Source Code → ANTLR Lexer/Parser → AST → Type Checker → Typed AST
```

### 2. Compilation Phase
```
Typed AST → FunkProgramRootNode → CallTarget → Truffle Compilation
```

### 3. Execution Phase
```
CallTarget.call() → Interpreter/Compiled Code → Result
```

### 4. Optimization Cycle
```
Execution → Profiling → JIT Compilation → Optimized Execution
```

## Current Implementation Status

### ✅ Implemented Features
- **Basic Truffle integration** with language registration
- **AST-based interpreter** using shared AST architecture
- **Context management** with global and local scopes
- **Error handling** with proper exception propagation
- **Host access foundation** for Java interoperability

### 🚧 In Development
- **Custom Truffle nodes** with `@Specialization` annotations
- **Frame-based execution** replacing map-based contexts
- **Advanced profiling** and compilation thresholds
- **Native image support** configuration

### 🔮 Future Enhancements
- **Specialized arithmetic nodes** for numeric operations
- **Pattern matching nodes** for efficient case analysis
- **Effect system nodes** for algebraic effects
- **Tail call optimization nodes** for recursive functions

## Performance Characteristics

### Interpretation Phase
- **Fast startup** with immediate execution
- **Type feedback collection** for future optimizations
- **Hot method identification** based on call frequency

### Compilation Phase
- **Selective compilation** of hot methods only
- **Aggressive optimizations** based on collected profiles
- **Deoptimization support** when assumptions are violated

### Optimized Execution
- **Near-native performance** for compiled code
- **Efficient type dispatch** through specialization
- **Minimal overhead** for function calls and memory access

## Integration with Funk Features

### 1. Pattern Matching
- **Efficient dispatch** through decision tree compilation
- **Type specialization** based on matched types
- **Guard condition optimization** for complex patterns

### 2. Effect System
- **Handler dispatch optimization** through inline caching
- **Effect call elimination** for pure computations
- **State machine compilation** for async effects

### 3. Functional Programming
- **Tail call optimization** to prevent stack overflow
- **Closure optimization** with escape analysis
- **Higher-order function inlining** for performance

## Debugging and Profiling

### Compilation Logging
```bash
# Enable compilation tracking
-Dgraal.TraceTruffleCompilation=true
-Dgraal.TraceTruffleCompilationDetails=true

# Monitor performance warnings
-Dgraal.TraceTrufflePerformanceWarnings=true
```

### Performance Analysis
```bash
# Enable profiling
-Dgraal.TruffleProfilingEnabled=true
-Dgraal.ProfileCompiledMethods=true

# Set compilation thresholds
-Dgraal.TruffleCompilationThreshold=1000
-Dgraal.TruffleMinInvokeThreshold=10
```

### Deoptimization Tracking
```bash
# Track deoptimizations
-Dgraal.TraceTruffleTransferToInterpreter=true
-Dgraal.TraceDeoptimization=true
```

## Best Practices

### 1. Consistent Type Usage
```funk
// Good: Enables type specialization
let process_numbers = {x: Int, y: Int} -> x * y + 1

// Avoid: Prevents optimization
let process_generic = {x, y} -> x * y + 1
```

### 2. Stable Object Shapes
```funk
// Good: Consistent record structure
type Point = { x: Int, y: Int }

// Avoid: Varying shapes
let mixed_points = [
  { x = 1, y = 2 },
  { x = 1, y = 2, z = 3 }  // Different shape!
]
```

### 3. Hot Path Optimization
```funk
// Structure code so hot paths are simple and consistent
let calculate_total = {items: [Item]} ->
  fold {
    list = items,
    init = 0,
    f = {acc, item} -> acc + item.price  // Hot path: simple arithmetic
  }
```

## Next Steps

1. **[JIT Optimizations](./02-jit-optimizations.md)** - Detailed optimization techniques
2. **[Type Specialization](./03-type-specialization.md)** - Runtime type optimization
3. **[Tail Call Optimization](./04-tail-call-optimization.md)** - Functional programming optimizations
4. **[State Machine Effects](./05-state-machine-effects.md)** - Effect system with Loom threads

---

This integration provides Funk with world-class performance while maintaining the language's functional programming paradigm and effect system design.
