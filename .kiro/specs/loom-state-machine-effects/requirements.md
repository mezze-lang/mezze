# Requirements Document

## Introduction

This specification defines a comprehensive redesign of Funk's effect system to be built on state machines with Project Loom virtual threads, making effects async by default while providing abort and resume capabilities. The new system will address the current limitations identified in the effect system improvements document while providing a more robust, performant, and developer-friendly effect handling mechanism.

## Requirements

### Requirement 1: State Machine-Based Effect Execution

**User Story:** As a developer, I want effects to be compiled into efficient state machines so that I can have predictable, debuggable, and memory-efficient async operations.

#### Acceptance Criteria

1. WHEN an effect-heavy function is compiled THEN the system SHALL generate a state machine representation with discrete states for each effect operation
2. WHEN a state machine executes THEN each state SHALL have a clear transition to the next state or completion
3. WHEN debugging state machine execution THEN the system SHALL provide visibility into current state, local variables, and transition history
4. WHEN a state machine encounters an error THEN the system SHALL provide clear error context including the failing state and available recovery options
5. WHEN state machines are nested THEN the system SHALL properly manage the state hierarchy and context propagation

### Requirement 2: Project Loom Virtual Thread Integration

**User Story:** As a developer, I want each effect to run in its own virtual thread so that I can achieve massive concurrency without the overhead of traditional threads.

#### Acceptance Criteria

1. WHEN an async effect is executed THEN the system SHALL create a dedicated virtual thread for that effect operation
2. WHEN multiple effects run concurrently THEN each SHALL execute in its own virtual thread with proper isolation
3. WHEN virtual threads are created THEN the system SHALL use structured concurrency to ensure proper resource management and cleanup
4. WHEN a virtual thread blocks THEN it SHALL NOT impact the performance of other virtual threads or the main execution
5. WHEN virtual threads complete THEN the system SHALL automatically clean up resources and propagate results back to the state machine

### Requirement 3: All-Effects-Are-Async System

**User Story:** As a developer, I want all effects to be async by default so that I have a consistent execution model without complexity.

#### Acceptance Criteria

1. WHEN an effect is declared THEN it SHALL always be treated as async and run in a virtual thread
2. WHEN an effect call is made THEN the system SHALL automatically execute it asynchronously using virtual threads
3. WHEN multiple effects are composed THEN the system SHALL automatically handle the coordination and result aggregation using structured concurrency
4. WHEN effects are used THEN there SHALL be no distinction between sync and async - all effects use the same async execution model
5. WHEN simple operations complete quickly THEN the virtual thread overhead SHALL be minimal due to Loom's efficiency

### Requirement 4: Abort and Resume Capabilities

**User Story:** As a developer, I want to be able to abort and resume effect operations so that I can handle cancellation, timeouts, and complex control flow scenarios.

#### Acceptance Criteria

1. WHEN an effect operation is running THEN the system SHALL provide a mechanism to abort the operation cleanly
2. WHEN an effect is aborted THEN the system SHALL clean up any resources and notify dependent operations
3. WHEN an effect operation is suspended THEN the system SHALL provide a mechanism to resume execution from the suspension point
4. WHEN resuming an effect THEN the system SHALL restore the complete execution context including local variables and handler state
5. WHEN abort/resume operations are nested THEN the system SHALL maintain proper hierarchy and propagation rules

### Requirement 5: Enhanced Effect Context Management

**User Story:** As a developer, I want a unified effect context system so that handler resolution is predictable and debugging is straightforward.

#### Acceptance Criteria

1. WHEN effects are executed THEN the system SHALL maintain a single, immutable effect context for handler resolution
2. WHEN handlers are pushed/popped THEN the context SHALL maintain clear precedence rules and avoid complex fallback logic
3. WHEN effect calls are made THEN the system SHALL use consistent logic to determine if something is an effect call vs regular member access
4. WHEN debugging effect resolution THEN the system SHALL provide clear information about available handlers and resolution paths
5. WHEN context is passed between functions THEN the system SHALL ensure proper handler inheritance and isolation

### Requirement 6: Structured Concurrency Patterns

**User Story:** As a developer, I want built-in support for common concurrency patterns so that I can easily compose async operations safely.

#### Acceptance Criteria

1. WHEN multiple effects need to run concurrently THEN the system SHALL provide a join pattern that waits for all to complete
2. WHEN multiple effects are racing THEN the system SHALL provide a race pattern that returns the first completed result
3. WHEN effects need timeouts THEN the system SHALL provide built-in timeout handling with proper cleanup
4. WHEN effects are composed in parallel THEN the system SHALL ensure structured concurrency with automatic resource management
5. WHEN concurrent operations fail THEN the system SHALL provide proper error propagation and cleanup of remaining operations

### Requirement 7: Effect Middleware and Composition

**User Story:** As a developer, I want to add cross-cutting concerns like logging, metrics, caching, and retry logic to effects so that I can build robust applications.

#### Acceptance Criteria

1. WHEN effects are executed THEN the system SHALL support middleware that can intercept and modify effect calls
2. WHEN middleware is applied THEN it SHALL be composable and allow multiple middleware to be chained
3. WHEN effects need retry logic THEN the system SHALL provide built-in retry middleware with configurable backoff strategies
4. WHEN effects need caching THEN the system SHALL provide caching middleware for pure operations
5. WHEN effects need monitoring THEN the system SHALL provide metrics middleware for performance tracking

### Requirement 8: Improved Error Handling and Messages

**User Story:** As a developer, I want clear, actionable error messages when effects fail so that I can quickly identify and fix issues.

#### Acceptance Criteria

1. WHEN an effect handler is not found THEN the system SHALL provide suggestions for available alternatives and how to fix the issue
2. WHEN an effect operation doesn't exist THEN the system SHALL suggest similar operations and show available operations
3. WHEN effect calls fail THEN the system SHALL provide context about the failure including state machine state and available recovery options
4. WHEN debugging effect issues THEN the system SHALL provide clear information about handler resolution, context, and execution flow
5. WHEN errors occur in state machines THEN the system SHALL provide state-specific error information and transition history

### Requirement 9: Effect Testing Framework

**User Story:** As a developer, I want comprehensive testing support for effectful code so that I can write reliable tests for my applications.

#### Acceptance Criteria

1. WHEN testing effectful code THEN the system SHALL provide mock effect handlers that can simulate various scenarios
2. WHEN verifying effect calls THEN the system SHALL provide assertions for call counts, arguments, and execution order
3. WHEN testing concurrent effects THEN the system SHALL provide deterministic testing capabilities
4. WHEN testing error scenarios THEN the system SHALL provide easy ways to simulate failures and timeouts
5. WHEN isolating tests THEN the system SHALL ensure proper cleanup and isolation between test cases

### Requirement 10: Performance Optimizations

**User Story:** As a developer, I want the effect system to be highly performant so that it doesn't become a bottleneck in my applications.

#### Acceptance Criteria

1. WHEN effects are pure and deterministic THEN the system SHALL provide automatic caching of results
2. WHEN simple effects are called THEN the system SHALL inline the handler call directly for optimal performance
3. WHEN multiple operations of the same effect are made THEN the system SHALL provide batching capabilities
4. WHEN state machines are compiled THEN the system SHALL optimize for minimal memory allocation and fast state transitions
5. WHEN effects are composed THEN the system SHALL eliminate unnecessary intermediate allocations and optimize the execution path

### Requirement 11: Backward Compatibility and Migration

**User Story:** As a developer, I want to migrate from the current effect system gradually so that I don't have to rewrite all my code at once.

#### Acceptance Criteria

1. WHEN the new system is introduced THEN existing effect code SHALL continue to work without modification
2. WHEN migrating to the new system THEN the system SHALL provide clear migration utilities and documentation
3. WHEN both old and new APIs are used THEN they SHALL interoperate correctly
4. WHEN deprecating old APIs THEN the system SHALL provide clear timelines and migration paths
5. WHEN the migration is complete THEN the system SHALL provide performance and functionality improvements over the old system

### Requirement 12: Built-in Async Effects

**User Story:** As a developer, I want comprehensive built-in async effects for common operations so that I don't have to implement basic functionality myself.

#### Acceptance Criteria

1. WHEN performing I/O operations THEN the system SHALL provide built-in File, Network, and Database effects
2. WHEN handling timing operations THEN the system SHALL provide Timer, Sleep, and Timeout effects
3. WHEN working with concurrency THEN the system SHALL provide Channel, Queue, and Stream effects
4. WHEN caching data THEN the system SHALL provide built-in Cache effects with TTL and eviction policies
5. WHEN all built-in effects are used THEN they SHALL follow consistent patterns and provide comprehensive operation sets