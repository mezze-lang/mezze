# Implementation Plan

## Overview

This implementation plan converts the Loom-based state machine effect system design into a series of discrete, manageable coding tasks. Each task builds incrementally on previous tasks, following test-driven development practices and ensuring compatibility with the existing Funk effect system.

## Implementation Tasks

- [ ] 1. Core State Machine Infrastructure
  - Create the foundational state machine classes and execution engine
  - Implement basic state types (Compute, AsyncEffect, Join, Race, Conditional)
  - Add state machine compilation from effect-heavy code
  - Write comprehensive tests for state machine execution
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 1.1 Create EffectStateMachine Core Classes
  - Implement `EffectState` sealed class hierarchy with all state types
  - Create `EffectStateMachine` class with execution logic
  - Add `StateResult` types for transitions, suspensions, completions, and errors
  - Implement state machine context management and variable storage
  - _Requirements: 1.1, 1.2_

- [ ] 1.2 Implement State Machine Execution Engine
  - Create async state machine executor using coroutines
  - Add state transition logic with proper error handling
  - Implement state machine debugging and introspection capabilities
  - Add state machine serialization for debugging and persistence
  - _Requirements: 1.3, 1.4_

- [ ] 1.3 Add State Machine Compilation
  - Create `AsyncEffectCompiler` to convert effect-heavy code to state machines
  - Implement detection of async effects in code
  - Add optimization passes for state machine generation
  - Create state machine caching and reuse mechanisms
  - _Requirements: 1.5_

- [ ] 1.4 Write State Machine Tests
  - Create unit tests for all state types and transitions
  - Add integration tests for complex state machine scenarios
  - Implement state machine execution performance tests
  - Add debugging and introspection tests
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [ ] 2. Virtual Thread Integration with Project Loom
  - Implement virtual thread executor with structured concurrency
  - Create Loom-based dispatchers for async operations
  - Add virtual thread lifecycle management
  - Integrate with existing Truffle execution model
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [ ] 2.1 Create Virtual Thread Executor
  - Implement `VirtualThreadEffectExecutor` with Loom integration
  - Add structured concurrency support using `StructuredTaskScope`
  - Create custom virtual thread factory with proper naming
  - Implement virtual thread pool management and cleanup
  - _Requirements: 2.1, 2.2, 2.5_

- [ ] 2.2 Add Loom Dispatchers and Context Management
  - Create `Dispatchers.Loom` for coroutine integration
  - Implement context propagation between virtual threads
  - Add virtual thread-local storage for effect context
  - Create virtual thread monitoring and metrics collection
  - _Requirements: 2.3, 2.4_

- [ ] 2.3 Integrate with Truffle Execution Model
  - Modify existing Truffle nodes to support virtual thread execution
  - Add virtual thread support to `AsyncEffectCallNode`
  - Update frame management for virtual thread contexts
  - Ensure proper integration with Truffle's compilation and optimization
  - _Requirements: 2.4, 2.5_

- [ ] 2.4 Write Virtual Thread Integration Tests
  - Create tests for virtual thread creation and lifecycle
  - Add structured concurrency tests with proper cleanup
  - Implement performance tests comparing virtual threads to regular threads
  - Add integration tests with existing Truffle execution
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [ ] 3. Enhanced Effect Context System
  - Replace complex handler resolution with unified EffectContext
  - Implement immutable context with clear precedence rules
  - Add context debugging and introspection capabilities
  - Integrate with existing effect handler system
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 3.1 Create Unified EffectContext
  - Implement immutable `EffectContext` data class
  - Add handler resolution with clear precedence rules
  - Create context builder and manipulation methods
  - Implement context serialization for debugging
  - _Requirements: 5.1, 5.2_

- [ ] 3.2 Replace Existing Handler Resolution Logic
  - Update `EffectHandler` to use new `EffectContext`
  - Remove complex fallback logic in favor of unified resolution
  - Update effect call detection to use consistent logic
  - Ensure backward compatibility with existing code
  - _Requirements: 5.3, 5.4_

- [ ] 3.3 Add Context Debugging and Introspection
  - Create context inspection tools for debugging
  - Add context visualization for development tools
  - Implement context history tracking for error reporting
  - Create context validation and consistency checks
  - _Requirements: 5.5_

- [ ] 3.4 Write Effect Context Tests
  - Create unit tests for context creation and manipulation
  - Add handler resolution tests with various scenarios
  - Implement context debugging and introspection tests
  - Add integration tests with existing effect system
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 4. All-Effects-Are-Async System
  - Make all effects async by default using virtual threads
  - Create unified async effect operation interface
  - Update native function integration for async execution
  - Ensure all effect calls run in virtual threads
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [ ] 4.1 Create Unified Async Effect Interface
  - Implement single `EffectOperation` interface for all effects
  - Create `EffectHandler` interface with async-only support
  - Remove sync/async distinction - all effects use virtual threads
  - Implement operation execution with consistent async handling
  - _Requirements: 3.1, 3.2_

- [ ] 4.2 Update Native Function Integration
  - Create `AsyncNativeFunction` class for all native effect operations
  - Update existing native functions to run in virtual threads
  - Add native function registration for async-only effects
  - Implement proper argument handling for async native functions
  - _Requirements: 3.3, 3.4_

- [ ] 4.3 Ensure All Effects Use Virtual Threads
  - Update effect call detection to always use async execution
  - Create virtual thread execution for all effect operations
  - Add proper error handling for async-only scenarios
  - Implement performance optimization through virtual thread pooling
  - _Requirements: 3.5_

- [ ] 4.4 Write All-Async Effect Tests
  - Create tests verifying all effects run asynchronously
  - Add virtual thread usage validation tests
  - Implement performance tests for async-only behavior
  - Add integration tests with existing effect handlers
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [ ] 5. Abort and Resume Capabilities
  - Implement suspension point management system
  - Create abort and resume mechanisms for effect operations
  - Add timeout handling with proper cleanup
  - Integrate with state machine execution model
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [ ] 5.1 Create Suspension Point System
  - Implement `SuspensionPoint` data class with continuation callbacks
  - Create `SuspensionManager` for managing active suspensions
  - Add suspension point serialization and persistence
  - Implement suspension timeout handling with cleanup
  - _Requirements: 4.1, 4.2_

- [ ] 5.2 Add Abort Mechanisms
  - Implement effect operation abortion with proper cleanup
  - Create abort propagation through effect chains
  - Add abort handling in state machine execution
  - Implement resource cleanup on abort operations
  - _Requirements: 4.2, 4.3_

- [ ] 5.3 Implement Resume Capabilities
  - Create resume mechanisms for suspended operations
  - Add context restoration on resume operations
  - Implement resume with value and resume with error
  - Add resume validation and consistency checks
  - _Requirements: 4.3, 4.4_

- [ ] 5.4 Integrate with State Machine Execution
  - Add suspension states to state machine model
  - Implement state machine suspension and resumption
  - Create abort/resume handling in state transitions
  - Add proper error handling for suspended state machines
  - _Requirements: 4.5_

- [ ] 5.5 Write Abort/Resume Tests
  - Create unit tests for suspension point management
  - Add abort mechanism tests with proper cleanup verification
  - Implement resume capability tests with context restoration
  - Add integration tests with state machine execution
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [ ] 6. Structured Concurrency Patterns
  - Implement join pattern for waiting on multiple effects
  - Create race pattern for first-completed effect selection
  - Add timeout patterns with proper resource management
  - Integrate patterns with virtual thread execution
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [ ] 6.1 Implement Join Pattern
  - Create `JoinStateNode` for waiting on multiple async operations
  - Add result aggregation and error handling for join operations
  - Implement join with timeout and cancellation support
  - Create join pattern optimization for common use cases
  - _Requirements: 6.1, 6.4_

- [ ] 6.2 Create Race Pattern
  - Implement `RaceStateNode` for first-completed operation selection
  - Add proper cancellation of remaining operations on race completion
  - Create race with timeout and fallback handling
  - Implement race pattern optimization and resource cleanup
  - _Requirements: 6.2, 6.5_

- [ ] 6.3 Add Timeout Patterns
  - Create timeout handling for individual effect operations
  - Implement timeout patterns for composed operations
  - Add timeout with fallback and retry mechanisms
  - Create timeout pattern integration with abort/resume system
  - _Requirements: 6.3, 6.4_

- [ ] 6.4 Write Structured Concurrency Tests
  - Create tests for join pattern with various scenarios
  - Add race pattern tests with proper cleanup verification
  - Implement timeout pattern tests with resource management
  - Add integration tests with virtual thread execution
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [ ] 7. Effect Middleware System
  - Create middleware interface and chain execution
  - Implement built-in middleware (retry, caching, metrics)
  - Add middleware composition and configuration
  - Integrate middleware with effect execution pipeline
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [ ] 7.1 Create Middleware Infrastructure
  - Implement `EffectMiddleware` interface with intercept method
  - Create `MiddlewareChain` for composable middleware execution
  - Add middleware priority and ordering system
  - Implement middleware configuration and registration
  - _Requirements: 7.1, 7.2_

- [ ] 7.2 Implement Built-in Middleware
  - Create `RetryMiddleware` with configurable backoff strategies
  - Implement `CachingMiddleware` for pure effect operations
  - Add `MetricsMiddleware` for performance monitoring
  - Create `LoggingMiddleware` for effect call tracing
  - _Requirements: 7.3, 7.4, 7.5_

- [ ] 7.3 Add Middleware Composition
  - Implement middleware chain builder with fluent API
  - Create middleware composition validation and optimization
  - Add middleware dependency resolution and ordering
  - Implement middleware configuration inheritance and overrides
  - _Requirements: 7.2_

- [ ] 7.4 Integrate with Effect Execution Pipeline
  - Update effect handlers to support middleware execution
  - Add middleware integration with state machine compilation
  - Create middleware support for async and sync operations
  - Implement middleware performance optimization and caching
  - _Requirements: 7.1, 7.2_

- [ ] 7.5 Write Middleware System Tests
  - Create unit tests for middleware interface and chain execution
  - Add tests for built-in middleware with various configurations
  - Implement middleware composition and integration tests
  - Add performance tests for middleware overhead and optimization
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [ ] 8. Enhanced Error Handling and Messages
  - Create comprehensive effect error types with suggestions
  - Implement error reporter with actionable messages
  - Add error context and debugging information
  - Integrate with existing error handling system
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [ ] 8.1 Create Enhanced Error Types
  - Implement `EffectError` sealed class hierarchy
  - Add specific error types for common failure scenarios
  - Create error suggestion generation with similarity matching
  - Implement error context capture and formatting
  - _Requirements: 8.1, 8.2_

- [ ] 8.2 Implement Error Reporter
  - Create `EffectErrorReporter` with formatted error messages
  - Add error message templates with context substitution
  - Implement error suggestion ranking and prioritization
  - Create error reporting integration with development tools
  - _Requirements: 8.3, 8.4_

- [ ] 8.3 Add Error Context and Debugging
  - Implement error context capture from effect execution
  - Add stack trace enhancement for effect call chains
  - Create error debugging tools and introspection
  - Implement error correlation across async operations
  - _Requirements: 8.5_

- [ ] 8.4 Write Error Handling Tests
  - Create unit tests for all error types and scenarios
  - Add error reporter tests with message validation
  - Implement error context and debugging tests
  - Add integration tests with existing error handling
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [ ] 9. Built-in Async Effects Implementation
  - Create native implementations for IO, Network, Timer effects
  - Implement Database and Cache effects with async support
  - Add Channel and Stream effects for concurrency
  - Integrate all built-in effects with Loom virtual threads
  - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

- [ ] 9.1 Implement IO Effects
  - Create async native functions for file operations (read, write, exists, delete)
  - Add byte-level file operations with streaming support
  - Implement directory operations (list, create, delete)
  - Create file watching and monitoring capabilities
  - _Requirements: 12.1_

- [ ] 9.2 Create Network Effects
  - Implement HTTP client operations (GET, POST, PUT, DELETE)
  - Add support for headers, authentication, and custom configurations
  - Create WebSocket support for real-time communication
  - Implement network utilities (ping, DNS resolution)
  - _Requirements: 12.1_

- [ ] 9.3 Add Timer and Concurrency Effects
  - Implement sleep, delay, and timeout operations
  - Create scheduling effects for periodic operations
  - Add Channel effects for inter-thread communication
  - Implement Stream effects for data processing pipelines
  - _Requirements: 12.2, 12.3_

- [ ] 9.4 Create Database and Cache Effects
  - Implement database connection and query operations
  - Add transaction support with proper rollback handling
  - Create cache operations with TTL and eviction policies
  - Implement distributed cache support for clustering
  - _Requirements: 12.4, 12.5_

- [ ] 9.5 Write Built-in Effects Tests
  - Create comprehensive tests for all IO operations
  - Add network effects tests with mock servers
  - Implement timer and concurrency effects tests
  - Add database and cache effects integration tests
  - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

- [ ] 10. Effect Testing Framework
  - Create mock effect handlers and verification system
  - Implement effect call recording and assertion utilities
  - Add deterministic testing for concurrent effects
  - Integrate with existing test infrastructure
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

- [ ] 10.1 Create Mock Effect System
  - Implement `MockEffectHandler` with configurable responses
  - Create `EffectTestFramework` for test setup and teardown
  - Add mock operation builders with fluent API
  - Implement mock effect state management and isolation
  - _Requirements: 9.1, 9.2_

- [ ] 10.2 Add Effect Call Verification
  - Create `EffectCallVerifier` with comprehensive assertion methods
  - Implement call recording with timestamps and arguments
  - Add call ordering and timing verification
  - Create effect call pattern matching and filtering
  - _Requirements: 9.3_

- [ ] 10.3 Implement Deterministic Concurrent Testing
  - Create deterministic execution for concurrent effect tests
  - Add virtual time control for timer-based effects
  - Implement concurrent effect isolation and cleanup
  - Create test utilities for race conditions and timing issues
  - _Requirements: 9.4_

- [ ] 10.4 Write Testing Framework Tests
  - Create unit tests for mock effect handlers
  - Add verification system tests with various scenarios
  - Implement deterministic testing validation
  - Add integration tests with existing test infrastructure
  - _Requirements: 9.5_

- [ ] 11. Performance Optimizations
  - Implement effect call inlining for simple operations
  - Create effect batching for multiple operations
  - Add effect result caching for pure operations
  - Optimize state machine compilation and execution
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [ ] 11.1 Create Effect Call Inlining
  - Implement `EffectInliner` for simple, pure effect operations
  - Add inlining detection and optimization passes
  - Create inlined operation execution with direct calls
  - Implement inlining performance measurement and validation
  - _Requirements: 10.1_

- [ ] 11.2 Implement Effect Batching
  - Create `EffectBatcher` for combining similar operations
  - Add batching detection and grouping algorithms
  - Implement batch execution with proper result distribution
  - Create batching configuration and tuning parameters
  - _Requirements: 10.2_

- [ ] 11.3 Add Effect Result Caching
  - Implement `EffectCache` for pure operation results
  - Create cache key generation and invalidation strategies
  - Add cache configuration with TTL and size limits
  - Implement cache performance monitoring and optimization
  - _Requirements: 10.3_

- [ ] 11.4 Optimize State Machine Execution
  - Create state machine compilation optimizations
  - Add state machine execution profiling and analysis
  - Implement state machine caching and reuse
  - Create state machine performance benchmarks
  - _Requirements: 10.4, 10.5_

- [ ] 11.5 Write Performance Optimization Tests
  - Create performance tests for effect call inlining
  - Add batching performance and correctness tests
  - Implement caching effectiveness and performance tests
  - Add state machine optimization validation tests
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [ ] 12. Integration and Migration Support
  - Create backward compatibility layer for existing effects
  - Implement migration utilities and documentation
  - Add interoperability between old and new effect systems
  - Create performance comparison and validation tools
  - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

- [ ] 12.1 Create Backward Compatibility Layer
  - Implement compatibility wrappers for existing effect handlers
  - Add automatic migration of old effect calls to new system
  - Create compatibility mode for gradual migration
  - Implement compatibility validation and testing
  - _Requirements: 11.1, 11.2_

- [ ] 12.2 Add Migration Utilities
  - Create migration tools for converting existing effect code
  - Implement migration validation and verification
  - Add migration documentation and examples
  - Create migration progress tracking and reporting
  - _Requirements: 11.3_

- [ ] 12.3 Implement System Interoperability
  - Create bridges between old and new effect systems
  - Add interoperability testing and validation
  - Implement performance optimization for mixed systems
  - Create interoperability documentation and guidelines
  - _Requirements: 11.4_

- [ ] 12.4 Write Integration Tests
  - Create comprehensive integration tests for backward compatibility
  - Add migration utility tests with real-world scenarios
  - Implement interoperability tests between systems
  - Add performance comparison tests and benchmarks
  - _Requirements: 11.5_

## Implementation Notes

### Development Approach
- Each task should be implemented with comprehensive unit tests
- Integration tests should be added for cross-component functionality
- Performance benchmarks should be created for optimization tasks
- Documentation should be updated as features are implemented

### Dependencies
- Tasks within each major section can be worked on in parallel
- Cross-section dependencies are indicated in the requirements references
- State machine infrastructure (Task 1) should be completed before async integration
- Effect context system (Task 3) should be completed before middleware system

### Testing Strategy
- Unit tests for individual components and classes
- Integration tests for component interactions
- Performance tests for optimization validation
- End-to-end tests for complete effect system scenarios
- Backward compatibility tests for migration support

### Quality Assurance
- Code review for all implementations
- Performance profiling for optimization tasks
- Memory usage analysis for virtual thread integration
- Security review for network and database effects
- Documentation review for API and migration guides