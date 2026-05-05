# Durable Continuations for Mezze: 2024–2026 Research

(Sources at bottom. Date of compilation: 2026-04-29. Cutoff: GraalVM 25.0.2, JDK 26 GA.)

## TL;DR — the recommendation up front

Build the **Temporal-style replay model** as Mezze's primary durable-execution mechanism, and use **Truffle Bytecode DSL `ContinuationResult` (24.2+) only as an in-memory micro-suspension primitive** under the hood (for the synchronous parts of an effect handler, generators, and the `await`-style API). Do **not** rely on serializable native continuations as the cross-process durability boundary. Every system that has actually shipped to production for multi-day workflows — Temporal, Cadence, Restate, Inngest, DBOS, Cloudflare Durable Objects, Microsoft Durable Functions — uses **journal + deterministic replay**, not serialized stacks. Espresso's serializable continuations exist (since 24.1, late 2024) but remain experimental, are documented to break under code change, don't work in Native Image, can't suspend across native frames or with locks held, and explicitly warn that deserializing untrusted state is a JVM takeover. They are not the foundation you want for the "30-day workflow that survives redeploys" story.

The rest of this doc is the evidence.

## 1. Loom continuations — actual state in JDK 21–26

`jdk.internal.vm.Continuation` is still in `jdk.internal`. There is no JEP that exposes it as a public API in JDK 21, 22, 23, 24, 25, or 26. Pressler and the Loom team have repeatedly stated continuations are unsafe to expose directly (they can change `Thread.currentThread()` mid-method, they interact poorly with synchronized/native code, and naive use breaks structured concurrency). The roadmap that *did* land:

- **JDK 21 (Sep 2023):** virtual threads (JEP 444) — uses continuations internally, but only via the threading API.
- **JDK 22, 23:** scoped values, structured concurrency previews — no continuation API surface.
- **JDK 24 (Mar 2025):** **JEP 491** — "Synchronize Virtual Threads without Pinning." This is *implementation* work on the underlying continuation: monitors are now associated with the virtual thread, not the carrier, so `synchronized` no longer pins. Removes one of the two big restrictions on Loom-based suspension. Native frames still pin.
- **JDK 25 (May 2025):** JEP 505 (Structured Concurrency, 5th preview). Still no public continuation.
- **JDK 26 (Nov 2025):** JEP 525 (Structured Concurrency, 6th preview). Still no public continuation.

You *can* `--add-opens java.base/jdk.internal.vm=ALL-UNNAMED` and access `Continuation` reflectively. The class is `Serializable` in the Loom branch source, and Pressler has said in JVMLS talks that the format is designed for backwards-compatible evolution. **But:** this is a private API, multi-shot semantics, and the JVM does not guarantee that a continuation captured on one JDK build deserializes on another. Quasar (Parallel Universes) was the previous "fiber + serializable continuation" library and is effectively unmaintained since Loom landed; nothing has succeeded it as a production-grade serializable-Loom-continuation library. **No publicly documented production system uses Loom continuations for Temporal-style cross-process durability.** Temporal Java SDK does not — see §3.

For Mezze on Truffle this is doubly irrelevant: Loom continuations capture *host JVM* frames. Mezze interpreter frames are Truffle `MaterializedFrame` objects, not host frames. A Loom continuation would only capture the Java-side dispatch loop, not the guest semantic state.

## 2. GraalVM 24.x / 25.x continuation features

Two distinct systems exist; do not conflate them.

### 2a. Truffle Bytecode DSL `ContinuationResult` (host-language facing)

- Introduced as a DSL feature in **24.2 (Dec 2024)**, refined in 25.0 (Sep 2025). API: `@GenerateBytecode(enableYield = true)`, plus `yield` operation, returning `ContinuationResult` that you `continueWith(value)`.
- It is **single-root-node only**. Verbatim from the user guide: "the Bytecode DSL supports single-method continuations." A `ContinuationResult` captures *one* `MaterializedFrame` plus a `BytecodeLocation`. There is no built-in support for capturing a chain of caller frames — i.e., **no delimited control across guest call boundaries** out of the box.
- `getBytecodeLocation()` notes the location can carry a *different* `BytecodeNode` than the source root, since bytecode is regenerated when guards are added or the node is reparsed. So same-process resume after on-stack replacement works. There is no mention of cross-process serialization for `ContinuationResult` in the API docs.
- 25.0 added builder-perf wins (creating bytecode nodes 40% faster, 5× less temporary memory). No new continuation semantics.
- Native Image: works (Bytecode DSL is host Java, AOT-compiled like any other Truffle interpreter). No special caveats.

For Mezze: this is the right tool for **single-method coroutines, generators, and `await` inside one performer**. To get *cross-frame* delimited capture (capturing a multi-call stack), you have to build it yourself by structuring your interpreter as a CPS/trampolined dispatcher where each call is its own root node and you reify the spine yourself. Several Truffle languages do this; PyPy/RPython similarly does manual stack reification.

### 2b. Espresso `org.graalvm.continuations` (guest-Java facing)

- Added in **Espresso 24.1 (Oct 2024)**. Stack-copy implementation: on suspend the stack is unwound into a chained `HostFrameRecord` linked list of plain Java objects on the heap; on resume the records are pushed back onto the Espresso stack.
- 24.2: liveness-based slot clearing on suspend (deterministic captured-variable set).
- 25.0: `IdentityHashCodes` utility class to help reconstruct identity hash codes during deserialization.
- `Continuation implements Serializable`. With `--java.Continuum=true` *all lambdas* become serializable. Multi-shot when you re-deserialize from bytes.
- Hard limits: cannot suspend with locks held, cannot suspend with non-Java frames on stack (so no native, no JNI, no VM intrinsic), no continuation-in-continuation.
- **Native Image: not supported / not documented.** Espresso itself runs the guest Java bytecode; the Continuum machinery requires the Espresso interpreter and isn't part of the AOT story for ordinary Java programs.
- **Status: experimental.** Requires `--experimental-options --java.Continuum=true`. As of GraalVM 25.0.2 (Jan 2026) it has not been promoted to stable.
- **Security**: Oracle's docs explicitly say "Deserializing a continuation supplied by an attacker will allow complete takeover of the JVM. Only resume continuations you persisted yourself." Implication: any storage layer holding workflow state is a TCB component.
- **Code-change story:** the docs claim a backwards-compatible *format*. They do **not** claim resilience to *user code* change. Slot-liveness serialization is keyed by bytecode index; if a method's bytecode changes (recompiled `.class`) the offsets are no longer meaningful. Nothing in the public docs or `espresso/docs/continuations.md` describes a method-version mechanism, a class-redefinition contract, or migration hooks. In practice: redeploy = throw away in-flight continuations.

There is no polyglot suspend/resume. A `ContinuationResult` from one Truffle language does not resume in another.

## 3. Temporal Java SDK — what it actually does

It is **not** continuation-based. It is event-sourced replay. Reading `temporalio/sdk-java`:

- `io.temporal.internal.sync.DeterministicRunner` and `WorkflowThreadImpl` schedule workflow code on **regular platform threads** (cached one-per-workflow-instance), wrapped in a cooperative scheduler. They use `park`/`unpark` style coordination, not fibers. (This is why "cached workflows tie up physical threads" — a known scaling pain point that pre-dates Loom.) The Java SDK does not yet officially run workflow code on Loom virtual threads, though community discussion indicates this is the obvious next step now that JEP 491 unpinned synchronized.
- The SDK uses an internal cooperative scheduler with a single notion of "currently executing thread"; only one workflow thread runs at a time per workflow execution, matching the deterministic-replay model.
- On replay, `ReplayWorkflowExecutor` / `ReplayWorkflowRunTaskHandler` re-run the workflow function from the top. Every SDK operation that touches the outside world (`activity.execute`, `Workflow.sleep`, `Workflow.newTimer`, `Workflow.sideEffect`, `Workflow.getVersion`, signals, queries) consults the event history first. If the next event matches the command being issued, the SDK returns the recorded result instead of executing. If it doesn't match, that's a non-determinism error.
- The "thread" abstraction exists purely so that user code can write `result = activity.foo(); next = activity.bar(result);` in straight-line style; the cooperative scheduler suspends the workflow thread at every command and the orchestrator decides whether to feed it a recorded result or a real one.

This is the same architecture in Cadence (Temporal's predecessor — Uber, written by the same people), the Temporal Go SDK (uses goroutines + channels, same logical model), TypeScript SDK (uses V8 isolate snapshots + `vm.runInContext` plus replay — a hybrid that is closer to "continuation-ish" but still does event-sourced replay).

### Code-change-during-workflow: the real solution

`Workflow.getVersion(changeId, minVersion, maxVersion)` writes a marker into the event history the *first* time it runs, then returns that pinned version on every replay forever. Code does `if (version == DEFAULT_VERSION) { oldPath() } else { newPath() }`. This is the explicitly recommended pattern. Newer Temporal also has **Worker Versioning** (2024–2025), where workers are tagged with a build ID and the server pins each running workflow execution to its starting build ID, retiring old versions when no executions remain. Temporal's docs as of 2025 recommend Worker Versioning over Patching for new deployments.

The architectural lesson is that *you cannot* solve "code changed mid-workflow" with continuations alone. Even if the bytes deserialize, the code's *meaning* changed. You need either (a) pinning of in-flight workflows to the old code (Worker Versioning) or (b) explicit branch-on-version (Patching). Both are orthogonal to the suspend/resume mechanism.

## 4. Other modern systems — strategy summary

- **Restate (Cloud GA 2025):** journal + replay. Lightweight sidecar between your service and clients; intercepts effects, persists each step. On resume, replays handler from top and skips completed steps. Same model as Temporal but simpler runtime.
- **DBOS:** journal in Postgres. Three tables: `workflow_status`, `workflow_inputs`, `operation_outputs`. Step decorator memoizes results. In-process library, no separate cluster. Same replay model.
- **Inngest:** durable execution by composing `step.run(...)` calls. Each step is a separately-retried fragment; the function is re-invoked from the top per step, returning early when it hits an unfinished step. Pure replay, no continuations.
- **Microsoft Durable Functions / Orleans:** same. C# uses async/await + a custom `TaskScheduler` that stores awaited results in history; replay re-runs orchestrator and short-circuits awaits to recorded values. Not real continuations — async state machines run from the top each invocation.
- **Cloudflare Durable Objects WebSocket Hibernation:** **not** continuation capture. The DO is evicted from memory; user supplies `ws.serializeAttachment(state)` / `ws.deserializeAttachment()` to persist app-level state across hibernation. On message arrival the DO is reconstructed and the appropriate handler runs. The "continuation" is the user's JSON payload, not the JS stack.
- **Unison (1.0, Nov 2025):** abilities + content-addressed code. The `Remote` ability serializes *thunks* for distributed execution — i.e., already-paused computations expressed as values, plus their content hashes. Receiving node fetches missing dependencies by hash. Long-running computations across processes are achieved by *making the suspended state a first-class value* via `Remote`/`distributed` handlers — not by capturing a native stack. Closest in spirit to what Mezze could become, and notable that even Unison, with full control of the runtime, did not pursue native serializable continuations.
- **Ballerina:** transactions and persistent state, but the durable-workflow story has been comparatively quiet and uses the same write-ahead-log + replay pattern.

**Universal observation: zero production durable-execution systems persist native serialized continuations across processes for multi-day workflows.** The closest is Espresso's experimental Continuum, used (as far as public information shows) only in research/demo settings. Every production system uses journal + deterministic replay because that's also what gives you free retries, debuggability (the history *is* the debugger), forking/branching, and the version-pinning escape hatch.

## 5. Recommendation for Mezze

Pick replay. Concretely:

1. **Effect system as journal boundary.** Mezze already has abilities. Treat each ability operation invocation as the unit of journaling: when a workflow performs `Db.query(...)` or `Http.get(...)`, the runtime checks the journal first; on cache hit, return the recorded result; on miss, perform the effect and append the result. This maps perfectly onto Mezze's existing handler architecture — the durable handler for `Db` simply wraps the live handler with journal-check / journal-append.
2. **Make the workflow a pure function over its journal.** Mezze workflows = a top-level function whose only side effects are ability operations. On resume, the runtime re-runs the function from the top with a journal-replaying handler; reaches the latest unrecorded effect; promotes to a live handler from there. This is exactly the Temporal model but native to abilities, which is cleaner than what Temporal had to bolt on top of plain Java.
3. **Determinism rules baked into the type system.** `Workflow.now()`, `Workflow.random()`, `Workflow.uuid()` are ability operations whose handlers in workflow context journal the value the first time. Direct `System.currentTimeMillis` etc. in workflow code is a type error (different ability set in workflow context). This is *much* cleaner than Temporal Java's runtime checks.
4. **Versioning ability** — `Workflow.getVersion(changeId)` as an ability. Same semantics as Temporal: write-once into the journal. Plus, for the easy case, build-id worker pinning so most deploys don't even need branches.
5. **Use Bytecode DSL `ContinuationResult` for the in-memory `await`.** Inside one workflow task between two journaled events, your Truffle interpreter can use `enableYield = true` to single-method-suspend the workflow root node when it hits an effect that needs to wait, without burning a host thread per workflow (Temporal Java's well-known pain point). This is the right scope for native continuations: short-lived, in-process, never serialized.
6. **Do not** rely on Espresso Continuum or `jdk.internal.vm.Continuation` serialization. They are experimental, exclude Native Image, and don't survive code change anyway. The only artifact that needs to be durable is the journal — which is just a list of `(opName, argsHash, resultBytes)` tuples and survives any redeploy that respects ability-signature compatibility.
7. **AI-agent / multi-step LLM use case** (the modern reason to want this): the Inngest / Restate market is exploding around this. Mezze's ability system positions you well — every LLM call is the natural journal point.

The thing you actually wanted from "serializable continuations" — survives process restart, survives code redeploy, performant on Truffle, cross-frame — is delivered cleanly by replay-of-effects without needing any native serialization machinery. Native continuations would also fail constraint #3 (code change), which is the killer.

## Sources

- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- [JEP 505: Structured Concurrency (5th Preview, JDK 25)](https://openjdk.org/jeps/505)
- [Espresso Continuation API (GraalVM JDK 25)](https://www.graalvm.org/jdk25/reference-manual/espresso/continuations/)
- [Espresso Continuation Serialization (JDK 24)](https://www.graalvm.org/jdk24/reference-manual/espresso/continuations/serialization/)
- [Espresso CHANGELOG](https://github.com/oracle/graal/blob/master/espresso/CHANGELOG.md)
- [Espresso continuations.md design doc](https://github.com/oracle/graal/blob/master/espresso/docs/continuations.md)
- [Truffle ContinuationResult Javadoc](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/bytecode/ContinuationResult.html)
- [Truffle ContinuationRootNode Javadoc](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/bytecode/ContinuationRootNode.html)
- [Truffle Bytecode DSL UserGuide](https://github.com/oracle/graal/blob/master/truffle/docs/bytecode_dsl/UserGuide.md)
- [Bytecode DSL Continuations Tutorial example](https://github.com/oracle/graal/blob/master/truffle/src/com.oracle.truffle.api.bytecode.test/src/com/oracle/truffle/api/bytecode/test/examples/ContinuationsTutorial.java)
- [GraalVM 25 Release Notes](https://www.graalvm.org/release-notes/JDK_25/)
- [Temporal Java SDK source](https://github.com/temporalio/sdk-java)
- [Temporal Java SDK DeterministicRunner](https://github.com/temporalio/sdk-java/blob/master/temporal-sdk/src/main/java/io/temporal/internal/sync/DeterministicRunner.java)
- [Temporal Workflow Task Processing (DeepWiki)](https://deepwiki.com/temporalio/sdk-java/4.2-workflow-task-processing)
- [Temporal Patching docs](https://docs.temporal.io/patching)
- [Temporal Java SDK Versioning](https://docs.temporal.io/develop/java/versioning)
- [Temporal Worker Versioning](https://docs.temporal.io/production-deployment/worker-deployments/worker-versioning)
- [Restate Durable Execution concepts](https://docs.restate.dev/concepts/durable_execution)
- [DBOS — Lightweight Durable Execution](https://www.dbos.dev/blog/what-is-lightweight-durable-execution)
- [Inngest — How Functions Are Executed](https://www.inngest.com/docs/learn/how-functions-are-executed)
- [Cloudflare Durable Objects WebSocket Hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
- [Unison Abilities and Handlers](https://www.unison-lang.org/docs/language-reference/abilities-and-ability-handlers/)
- [Unison: The Big Idea](https://www.unison-lang.org/docs/the-big-idea/)
- [Java 24 Thread Pinning Revisited (mikemybytes)](https://mikemybytes.com/2025/04/09/java24-thread-pinning-revisited/)
- [Hacking GraalVM Espresso — Continuation API attack surface](https://exp10it.io/2025/08/hacking-graalvm-espresso-abusing-continuation-api-to-make-rop-like-attack/)
- [Kai Waehner — Rise of the Durable Execution Engine (2025)](https://www.kai-waehner.de/blog/2025/06/05/the-rise-of-the-durable-execution-engine-temporal-restate-in-an-event-driven-architecture-apache-kafka/)
- [The Emerging Landscape of Durable Computing (Golem)](https://www.golem.cloud/post/the-emerging-landscape-of-durable-computing)
