# Native Compilation for Funk Programs

## Overview
Add support for compiling Funk programs directly to native binaries with JIT optimization using GraalVM's `native-image`.

## Current State
- ✅ **Funk toolchain** → Native binary (58MB `funk` executable)
- ✅ **Funk programs** → JAR files (9.5MB with Truffle runtime)
- ❌ **Funk programs** → Native binaries (not implemented yet)

## Proposed Commands

### Basic Native Compilation
```bash
funk compile-native main.funk
# → Creates: main (native executable, ~20-40MB)
```

### AOT Optimized Compilation
```bash
funk compile-native --aot main.funk
# → Creates: main (optimized native executable, ~15-30MB)
```

## Implementation Plan

### 1. CLI Extension
Add `compile-native` command to the CLI with options:
- `--output` - Specify output executable name
- `--aot` - Enable ahead-of-time optimizations
- `--minimal` - Create minimal runtime (smaller size)

### 2. Native Compilation Pipeline
```kotlin
private fun handleCompileNativeCommand(args: List<String>) {
    // 1. Parse and type-check the Funk program
    val program = parseAndTypeCheck(inputFile)
    
    // 2. Generate minimal Java main class
    val javaWrapper = generateNativeWrapper(program)
    
    // 3. Create temporary JAR with minimal runtime
    val tempJar = createMinimalJar(program, javaWrapper)
    
    // 4. Use GraalVM native-image to compile
    val nativeExecutable = compileToNative(tempJar, outputFile)
    
    println("✅ Native executable created: $nativeExecutable")
}
```

### 3. Minimal Runtime Components
Include only necessary components:
- Truffle AST nodes for the specific program
- Core interpreter without parser/typechecker
- Minimal standard library
- JIT compiler for runtime optimization

## Expected Benefits

### Performance
- **Startup time**: Instant (vs ~1-2 seconds for JAR)
- **Memory usage**: Lower baseline (no JVM heap overhead)
- **Runtime performance**: Same JIT optimization as JAR

### Deployment
- **Single file**: No dependencies required
- **Cross-platform**: Native binaries for each target platform
- **Production ready**: Easy deployment to servers/containers

### Size Comparison
- **Current JAR**: 9.5MB (includes full runtime)
- **Native binary**: ~20-40MB (includes minimal runtime + native code)
- **Optimized native**: ~15-30MB (with dead code elimination)

## Technical Challenges

### GraalVM Integration
- Configure `native-image` reflection and resources
- Ensure Truffle compatibility in native mode
- Handle dynamic features (module loading, reflection)

### Build System
- Integration with existing Gradle build
- Cross-compilation support
- CI/CD pipeline updates

### Module System
- Static linking of modules at compile time
- Dynamic module loading in native binaries
- Module resolution optimization

## Implementation Priority
**High Value** - This would give Funk the deployment characteristics of Go/Rust while maintaining JVM ecosystem benefits.

## Future Enhancements
- Cross-compilation support
- Profile-guided optimization (PGO)
- Link-time optimization
- Static analysis for dead code elimination