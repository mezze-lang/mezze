# Comprehensive Concurrency System Design

## Overview

This design implements a comprehensive concurrency system for Funk that prioritizes simplicity through runtime-managed implicit parallelism while providing full concurrency primitives for advanced use cases. The system is built on Project Loom virtual threads and provides both automatic parallelization and explicit control when needed.

## Architecture

### Core Principles

1. **Runtime-First**: 99% of code uses implicit parallelism managed by the runtime
2. **Virtual Thread Foundation**: All concurrency built on Project Loom virtual threads
3. **Zero-Overhead Abstraction**: Simple code should perform optimally without manual tuning
4. **Safety by Default**: Structured concurrency and automatic resource management
5. **Escape Hatch**: Explicit control available for complex scenarios

### System Components

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Code                         │
│  (Sequential-looking, runtime handles parallelism)         │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│                Smart Effect Scheduler                      │
│  • Dependency Analysis    • Virtual Thread Pool            │
│  • Automatic Parallelism • Resource Management             │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│              Concurrency Primitives Layer                  │
│  • Atoms & STM       • Channels        • Agents            │
│  • Streams           • Explicit Tasks  • Lock-free DS      │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│                Project Loom Foundation                     │
│  • Virtual Threads   • Structured Concurrency             │
│  • Continuation API  • Thread-local Storage                │
└─────────────────────────────────────────────────────────────┘
```

## Components and Interfaces

### 1. Smart Effect Scheduler

The core component that automatically parallelizes effect operations:

```kotlin
class SmartEffectScheduler {
    // Analyzes effect dependencies and creates execution plan
    fun analyzeEffectBlock(block: EffectBlock): ExecutionPlan
    
    // Executes effects with automatic parallelization
    fun executeWithParallelism(plan: ExecutionPlan): Any
    
    // Manages virtual thread lifecycle
    fun spawnVirtualThread(task: Runnable): VirtualThread
    
    // Handles cancellation and cleanup
    fun cancelScope(scope: ConcurrencyScope)
}
```

### 2. Runtime Parallelism Detection

```kotlin
data class EffectDependency(
    val operation: EffectOperation,
    val dependencies: Set<EffectOperation>,
    val canRunInParallel: Boolean
)

class DependencyAnalyzer {
    fun analyzeDependencies(effects: List<EffectOperation>): DependencyGraph
    fun findParallelizableGroups(graph: DependencyGraph): List<ParallelGroup>
    fun createExecutionPlan(groups: List<ParallelGroup>): ExecutionPlan
}
```

### 3. Concurrency Primitives

#### Atomic References
```funk
effect Atomic {
    new: { value: 'a } -> Atom 'a <Atomic>,
    get: { atom: Atom 'a } -> 'a <Atomic>,
    set: { atom: Atom 'a, value: 'a } -> {} <Atomic>,
    cas: { atom: Atom 'a, expected: 'a, new: 'a } -> Bool <Atomic>,
    update: { atom: Atom 'a, f: 'a -> 'a } -> 'a <Atomic>
}
```

#### Software Transactional Memory
```funk
effect STM {
    new_ref: { value: 'a } -> TRef 'a <STM>,
    read_ref: { ref: TRef 'a } -> 'a <STM>,
    write_ref: { ref: TRef 'a, value: 'a } -> {} <STM>,
    transaction: { action: {} -> 'a <STM> } -> 'a <STM>
}
```

#### Channels
```funk
effect Channel {
    new: { buffer_size: Int } -> Chan 'a <Channel>,
    send: { chan: Chan 'a, value: 'a } -> {} <Channel>,
    receive: { chan: Chan 'a } -> 'a <Channel>,
    close: { chan: Chan 'a } -> {} <Channel>,
    select: { cases: [SelectCase 'a] } -> 'a <Channel>
}
```

#### Agents
```funk
effect Agent {
    new: { initial: 'a } -> Agent 'a <Agent>,
    send: { agent: Agent 'a, action: 'a -> 'a } -> {} <Agent>,
    get: { agent: Agent 'a } -> 'a <Agent>,
    await: { agent: Agent 'a } -> {} <Agent>
}
```

#### Explicit Concurrency (Advanced)
```funk
effect Concurrent {
    scope: { action: {} -> 'a <Concurrent> } -> 'a <Concurrent>,
    spawn: { task: {} -> 'a } -> Task 'a <Concurrent>,
    cancel: { task: Task 'a } -> {} <Concurrent>,
    await_all: { tasks: [Task 'a] } -> ['a] <Concurrent>,
    race: { tasks: [Task 'a] } -> 'a <Concurrent>
}
```

#### Semaphores (Resource Control)
```funk
effect Semaphore {
    new: { permits: Int } -> Semaphore <Semaphore>,
    acquire: { sem: Semaphore } -> {} <Semaphore>,
    release: { sem: Semaphore } -> {} <Semaphore>,
    try_acquire: { sem: Semaphore, timeout: Duration } -> Bool <Semaphore>
}
```

#### Barriers (Phase Coordination)
```funk
effect Barrier {
    new: { parties: Int } -> CyclicBarrier <Barrier>,
    await: { barrier: CyclicBarrier } -> {} <Barrier>,
    reset: { barrier: CyclicBarrier } -> {} <Barrier>
}
```

#### Actor Model (Message Passing)
```funk
effect Actor {
    spawn: { behavior: Message -> Behavior } -> ActorRef <Actor>,
    send: { actor: ActorRef, message: Message } -> {} <Actor>,
    become: { new_behavior: Message -> Behavior } -> {} <Actor>,
    stop: { actor: ActorRef } -> {} <Actor>
}
```

#### Reactive Streams
```funk
effect Stream {
    from_list: { items: ['a] } -> Stream 'a <Stream>,
    map: { stream: Stream 'a, f: 'a -> 'b } -> Stream 'b <Stream>,
    filter: { stream: Stream 'a, predicate: 'a -> Bool } -> Stream 'a <Stream>,
    merge: { streams: [Stream 'a] } -> Stream 'a <Stream>,
    subscribe: { stream: Stream 'a, handler: 'a -> {} } -> {} <Stream>
}
```

## Data Models

### Virtual Thread Management
```kotlin
data class VirtualThreadContext(
    val threadId: Long,
    val scope: ConcurrencyScope,
    val cancellationToken: CancellationToken,
    val parentContext: VirtualThreadContext?
)

data class ConcurrencyScope(
    val scopeId: String,
    val childThreads: MutableSet<VirtualThread>,
    val cancellationToken: CancellationToken
)
```

### Effect Execution Plan
```kotlin
data class ExecutionPlan(
    val parallelGroups: List<ParallelGroup>,
    val dependencies: DependencyGraph,
    val estimatedExecutionTime: Duration
)

data class ParallelGroup(
    val effects: List<EffectOperation>,
    val maxConcurrency: Int,
    val timeout: Duration?
)
```

### Concurrency Primitives Data Models
```kotlin
// Atomic Reference
class AtomicRef<T>(private var value: T) {
    fun get(): T = value
    fun set(newValue: T) { value = newValue }
    fun compareAndSet(expected: T, new: T): Boolean
}

// STM Reference
class TransactionalRef<T>(initialValue: T) {
    private var value: T = initialValue
    private var version: Long = 0
}

// Channel
class Channel<T>(private val bufferSize: Int) {
    private val buffer: BlockingQueue<T>
    private val closed: AtomicBoolean = AtomicBoolean(false)
}

// Agent
class Agent<T>(initialState: T) {
    private var state: T = initialState
    private val actionQueue: BlockingQueue<(T) -> T>
    private val executor: ExecutorService
}
```

## Error Handling

### Automatic Error Propagation
```kotlin
class ConcurrentErrorHandler {
    // Cancels sibling operations when one fails
    fun handleParallelFailure(error: Throwable, scope: ConcurrencyScope)
    
    // Aggregates multiple errors from parallel operations
    fun aggregateErrors(errors: List<Throwable>): CompositeException
    
    // Provides structured error reporting
    fun createErrorReport(error: Throwable, context: ExecutionContext): ErrorReport
}
```

### STM Conflict Resolution
```kotlin
class STMConflictResolver {
    fun detectConflict(transaction: Transaction): Boolean
    fun retryTransaction(transaction: Transaction, backoffStrategy: BackoffStrategy)
    fun resolveDeadlock(transactions: Set<Transaction>)
}
```

## Testing Strategy

### Unit Testing
- Test individual concurrency primitives in isolation
- Verify atomic operations and STM transactions
- Test channel operations and backpressure
- Validate agent state management

### Integration Testing
- Test runtime parallelism detection and execution
- Verify structured concurrency and cancellation
- Test error propagation across concurrent operations
- Validate resource cleanup and memory management

### Performance Testing
- Benchmark automatic parallelization vs manual
- Measure virtual thread overhead and scalability
- Test backpressure handling under load
- Validate lock-free data structure performance

### Concurrency Testing
- Use property-based testing for race conditions
- Test with various thread counts and workloads
- Verify linearizability of concurrent operations
- Test cancellation and timeout scenarios

## Implementation Phases

### Phase 1: Foundation
- Implement Smart Effect Scheduler
- Basic virtual thread management
- Simple dependency analysis
- Runtime parallelism for independent effects

### Phase 2: Core Primitives
- Atomic references with CAS operations
- Basic STM implementation
- Unbuffered channels
- Simple agent implementation

### Phase 3: Advanced Features
- Buffered channels with backpressure
- STM conflict resolution and retry logic
- Reactive streams with operators
- Explicit concurrency control

### Phase 4: Optimization
- Advanced dependency analysis
- Performance tuning and profiling
- Memory management optimization
- Debugging and monitoring tools

## Performance Considerations

### Virtual Thread Efficiency
- Minimize virtual thread creation overhead
- Optimize continuation serialization
- Efficient thread-local storage management
- Smart work-stealing for load balancing

### Memory Management
- Minimize allocation in hot paths
- Efficient data structure implementations
- Proper cleanup of cancelled operations
- Memory-efficient channel implementations

### Scalability
- Support for millions of virtual threads
- Efficient scheduling algorithms
- Minimal contention in shared data structures
- Adaptive parallelism based on system resources

## Library Integration Strategy

### JVM Library Mapping (Open Source + High Performance)

```kotlin
// PRIMARY: Project Loom (JDK 21+) - Use as much as possible
class LoomConcurrencyImpl {
    // Virtual Threads - Core foundation
    fun spawnVirtualThread(task: Runnable): Thread = 
        Thread.ofVirtual().start(task)
    
    // Structured Concurrency - Automatic cleanup
    fun <T> withScope(action: StructuredTaskScope<T>.() -> T): T =
        StructuredTaskScope.ShutdownOnFailure().use { scope ->
            action(scope)
        }
    
    // Scoped Values - Better than ThreadLocal
    private val CONTEXT = ScopedValue.newInstance<ExecutionContext>()
}

// Semaphores -> java.util.concurrent.Semaphore (JDK built-in)
class SemaphoreImpl : SemaphoreEffect {
    private val semaphore = java.util.concurrent.Semaphore(permits)
    override fun acquire() = semaphore.acquire()
    override fun release() = semaphore.release()
}

// Barriers -> java.util.concurrent.CyclicBarrier (JDK built-in)
class BarrierImpl : BarrierEffect {
    private val barrier = java.util.concurrent.CyclicBarrier(parties)
    override fun await() = barrier.await()
}

// Channels -> JCTools MPSC/SPSC Queues (Ultra high-performance)
class ChannelImpl<T> : ChannelEffect<T> {
    private val queue = org.jctools.queues.MpscArrayQueue<T>(bufferSize)
    override fun send(value: T) = queue.offer(value)
    override fun receive(): T = queue.poll() ?: suspendUntilAvailable()
}

// STM -> ScalaSTM (Open source, mature)
class STMImpl : STMEffect {
    override fun <T> transaction(action: () -> T): T = 
        scala.concurrent.stm.atomic { action() }
}

// Actors -> Vert.x (Open source, high performance)
class ActorImpl : ActorEffect {
    private val vertx = io.vertx.core.Vertx.vertx()
    override fun spawn(behavior: Behavior): ActorRef = 
        vertx.deployVerticle(ActorVerticle(behavior))
}

// Reactive Streams -> Project Reactor (Spring's library, very fast)
class StreamImpl : StreamEffect {
    override fun <T> fromList(items: List<T>) = Flux.fromIterable(items)
    override fun <T, R> map(stream: Flux<T>, f: (T) -> R) = stream.map(f)
}

// Lock-free Data Structures -> JCTools + Chronicle Map
class LockFreeCollections {
    // Ultra-fast concurrent queues
    fun <T> newMPSCQueue(size: Int) = org.jctools.queues.MpscArrayQueue<T>(size)
    
    // Off-heap concurrent maps
    fun <K, V> newConcurrentMap() = net.openhft.chronicle.map.ChronicleMap
        .of(keyClass, valueClass).create()
}
```

### Library Selection Criteria & Benefits

#### **Primary: Project Loom (JDK 21+)**
- ✅ **Built into JDK** - No external dependencies
- ✅ **Virtual threads** - Millions of lightweight threads
- ✅ **Structured concurrency** - Automatic resource cleanup
- ✅ **Scoped values** - Better than ThreadLocal
- ✅ **Continuation API** - Foundation for our effect system

#### **High-Performance Open Source Libraries**
- **JCTools** (Apache 2.0) - Ultra-fast lock-free queues, 10x faster than JDK
- **Chronicle Map** (Apache 2.0) - Off-heap concurrent maps, microsecond latency
- **Project Reactor** (Apache 2.0) - Reactive streams, used by Spring
- **ScalaSTM** (BSD) - Mature STM implementation
- **Vert.x** (Apache 2.0/Eclipse) - High-performance actor-like system
- **Disruptor** (Apache 2.0) - Ultra-low latency ring buffer

#### **Performance Benefits**
- **10-100x better throughput** using JCTools vs JDK collections
- **Microsecond latencies** with Chronicle Map off-heap storage
- **Zero garbage collection** pressure with off-heap data structures
- **Native virtual thread integration** - No thread pool overhead
- **Lock-free algorithms** - No contention, perfect scaling

## Security Considerations

### Resource Limits
- Maximum virtual thread limits per scope
- Memory usage limits for channels and agents
- CPU time limits for long-running operations
- Protection against resource exhaustion attacks

### Isolation
- Proper isolation between concurrent operations
- Secure handling of shared state
- Protection against timing attacks
- Safe cancellation without resource leaks