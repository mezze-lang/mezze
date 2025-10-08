# Funk Truffle Architecture Documentation

This directory contains comprehensive documentation about Funk's integration with GraalVM Truffle, including JIT optimizations, effect systems, state machines, and interoperability.

## 📚 Documentation Structure

### Core Architecture
- **[Truffle Integration Overview](./01-truffle-integration.md)** - How Funk integrates with GraalVM Truffle
- **[JIT Optimizations](./02-jit-optimizations.md)** - Just-In-Time compilation and performance optimizations
- **[Type Specialization](./03-type-specialization.md)** - Runtime type optimization and specialization
- **[Tail Call Optimization](./04-tail-call-optimization.md)** - Functional programming optimizations

### Effect System & Concurrency
- **[State Machine Effects](./05-state-machine-effects.md)** - Effects as state machines with Project Loom
- **[Async Effects with Loom](./06-async-effects-loom.md)** - Virtual threads and structured concurrency
- **[Effect Handler Implementation](./07-effect-handlers.md)** - Implementation details of the effect system

### Interoperability
- **[Host Language Integration](./08-host-interop.md)** - Calling Java/Kotlin from Funk
- **[Polyglot Language Integration](./09-polyglot-interop.md)** - Calling other GraalVM languages from Funk

## 🎯 Key Features

### High-Performance Execution
- **JIT Compilation**: Automatic compilation of hot code paths with profile-guided optimizations
- **Type Specialization**: Runtime type feedback for optimal code generation
- **Tail Call Optimization**: Stack-safe recursion for functional programming patterns

### Advanced Effect System
- **Algebraic Effects**: Pure functional effect handling with composable handlers
- **State Machines**: Effects compiled to state machines for efficient execution
- **Virtual Threads**: Project Loom integration for scalable concurrency

### Seamless Interoperability
- **Host Access**: Direct access to Java/Kotlin APIs with type safety
- **Polyglot Support**: Integration with JavaScript, Python, R, and other GraalVM languages
- **Automatic Conversion**: Transparent type conversion between languages

## 🚀 Getting Started

1. **Read the [Truffle Integration Overview](./01-truffle-integration.md)** to understand the basic architecture
2. **Explore [JIT Optimizations](./02-jit-optimizations.md)** to learn about performance benefits
3. **Study [State Machine Effects](./05-state-machine-effects.md)** to understand the effect system
4. **Check [Host Language Integration](./08-host-interop.md)** for practical interop examples

## 🔗 Related Documentation

- [Language Guide](../language-guide/) - Learn Funk syntax and features
- [Examples](../../examples/) - Practical code examples
- [Reference](../reference/) - Complete language reference

---

*This documentation covers Funk's advanced runtime architecture and optimization techniques.*
