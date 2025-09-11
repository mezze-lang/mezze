# Async Effects & State Machine Implementation Action Plan

## Overview
This document outlines the implementation plan for adding full async/Loom state machine execution to the Funk language's effect system. The goal is to transform `do` blocks into state machines that can suspend and resume execution using Project Loom's virtual threads.

## Current State Analysis
✅ **Completed:**
- Basic effect system with `effect` declarations and `impl` handlers
- Effect call resolution through function calls with member access
- Type checking for effects and effect rows
- Basic interpreter support for effect calls

❌ **Missing (Critical for Async):**
- `DoExpression` AST node and parsing
- State machine infrastructure for suspension/resumption
- Continuation capture mechanism
- Project Loom virtual thread integration
- Async-capable effect handlers

## Implementation Plan

### Phase 1: Core State Machine Infrastructure
**Goal:** Establish the foundation for state machine execution

#### 1.1 Add Missing AST Nodes
- [ ] Add `DoExpression(statements: List<Statement>, result: Expression)` to AST
- [ ] Add `HandleExpression(expression: Expression, handlers: List<String>)` to AST  
- [ ] Add `UseExpression(handlerName: String, expression: Expression)` to AST
- [ ] Add `EffectCallExpression(effectName: String, operationName: String, arguments: Expression)` to AST
- [ ] Update parser to handle `do` block syntax properly

#### 1.2 Design State Machine Architecture
- [ ] Create `StateMachine` data class to represent execution state
- [ ] Design `Continuation` interface for suspension points
- [ ] Implement `ExecutionState` enum (Running, Suspended, Completed, Failed)
- [ ] Create `SuspensionPoint` data class for capturing local state

#### 1.3 Implement Continuation Capture
- [ ] Create `ContinuationCapture` mechanism to save execution context
- [ ] Implement variable scope preservation across suspensions
- [ ] Design stack frame management for nested `do` blocks
- [ ] Add support for capturing partial evaluation state

### Phase 2: Project Loom Integration
**Goal:** Enable true async execution with virtual threads

#### 2.1 Virtual Thread Infrastructure
- [ ] Add Project Loom dependency to build configuration
- [ ] Create `VirtualThreadExecutor` for managing async operations
- [ ] Implement `AsyncEffectHandler` base class
- [ ] Add thread-safe context management

#### 2.2 Async Effect Handlers
- [ ] Extend effect handlers to support async operations
- [ ] Implement non-blocking I/O effect handlers (File, Network, etc.)
- [ ] Add timeout and cancellation support
- [ ] Create async-aware error handling

#### 2.3 Scheduler Integration
- [ ] Implement cooperative scheduling for effect calls
- [ ] Add priority-based execution for different effect types
- [ ] Create work-stealing mechanism for parallel execution
- [ ] Implement back-pressure handling

### Phase 3: State Machine Execution Engine
**Goal:** Transform `do` blocks into executable state machines

#### 3.1 Do Block Lowering
- [ ] Implement AST transformation from `do` blocks to state machines
- [ ] Create state transition logic for sequential operations
- [ ] Handle branching (if/else) within `do` blocks
- [ ] Support nested `do` block compilation

#### 3.2 Suspension and Resumption
- [ ] Implement effect call suspension mechanism
- [ ] Create resumption logic after async operations complete
- [ ] Handle exception propagation across suspension points
- [ ] Add support for concurrent effect execution

#### 3.3 Memory Management
- [ ] Implement efficient continuation storage
- [ ] Add garbage collection for completed state machines
- [ ] Create memory pooling for frequently used continuations
- [ ] Optimize for minimal allocation overhead

### Phase 4: Advanced Features
**Goal:** Add sophisticated async programming capabilities

#### 4.1 Concurrent Effects
- [ ] Implement `par` combinator for parallel effect execution
- [ ] Add `race` combinator for competitive execution
- [ ] Create structured concurrency with automatic cleanup
- [ ] Support for fan-out/fan-in patterns

#### 4.2 Resource Management
- [ ] Implement automatic resource cleanup (try-with-resources)
- [ ] Add support for bracketed resource usage
- [ ] Create finalizer support for async resources
- [ ] Implement connection pooling for I/O effects

#### 4.3 Debugging and Observability
- [ ] Add execution tracing for state machine transitions
- [ ] Implement async stack trace reconstruction
- [ ] Create performance monitoring for effect execution
- [ ] Add debugging hooks for suspended computations

### Phase 5: Testing and Documentation
**Goal:** Ensure reliability and usability

#### 5.1 Comprehensive Testing
- [ ] Unit tests for state machine transformations
- [ ] Integration tests for async effect execution
- [ ] Performance benchmarks vs sequential execution
- [ ] Stress tests for high-concurrency scenarios
- [ ] Memory leak detection tests

#### 5.2 Documentation and Examples
- [ ] Complete README with async behavior documentation
- [ ] Create tutorial examples for common async patterns
- [ ] Document performance characteristics
- [ ] Add troubleshooting guide for async issues

## Technical Architecture

### State Machine Structure
```kotlin
data class StateMachine(
    val id: String,
    val currentState: Int,
    val localVariables: Map<String, FunkValue>,
    val continuations: List<Continuation>,
    val effectContext: EffectContext
)

interface Continuation {
    fun resume(value: FunkValue): StateMachine
    fun handleError(error: Throwable): StateMachine
}
```

### Async Effect Handler Interface
```kotlin
interface AsyncEffectHandler {
    suspend fun handle(operation: String, arguments: FunkValue): FunkValue
    fun canHandle(effectName: String): Boolean
    fun priority(): Int
}
```

### Do Block Transformation Example
**Input:**
```funk
do
  let x = AsyncIO.readFile {path = "test.txt"}
  let y = AsyncIO.readFile {path = "data.txt"}  
  x + y
```

**Lowered State Machine:**
```
State 0: Call AsyncIO.readFile("test.txt") -> Suspend -> State 1
State 1: Store result in x, Call AsyncIO.readFile("data.txt") -> Suspend -> State 2  
State 2: Store result in y, Evaluate x + y -> Complete
```

## Success Criteria
- [ ] `do` blocks with async effects execute without blocking threads
- [ ] Proper suspension and resumption of computations
- [ ] Exception handling works across async boundaries
- [ ] Performance scales with number of concurrent operations
- [ ] Memory usage remains constant regardless of suspension duration
- [ ] Full compatibility with existing synchronous effect system

## Risk Mitigation
- **Complexity Risk:** Start with simple sequential state machines before adding concurrency
- **Performance Risk:** Benchmark against synchronous execution early and often
- **Debugging Risk:** Implement comprehensive tracing from the beginning
- **Compatibility Risk:** Maintain backward compatibility with existing effect system

## Timeline Estimate
- **Phase 1:** 1-2 weeks (Core Infrastructure)
- **Phase 2:** 1-2 weeks (Loom Integration)  
- **Phase 3:** 2-3 weeks (State Machine Engine)
- **Phase 4:** 1-2 weeks (Advanced Features)
- **Phase 5:** 1 week (Testing & Documentation)

**Total Estimated Time:** 6-10 weeks for complete implementation
