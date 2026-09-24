# GraalVM Truffle Instrumentation Integration

## Current State

The foundation exists but isn't wired up:

- **SourceInfo on every IR node** — startLine, startCol, endLine, endCol
- **`createSourceSection()` in TruffleCompiler** — converts SourceInfo to Truffle SourceSection
- **Frame descriptor with named slots** — captures, params, locals all named
- **RootNode implementation** — MezzeRootNode and MezzeFunctionRootNode have SourceSection
- **InteropLibrary on MezzeRecord** — records are already inspectable via Truffle interop

What's missing is the **instrumentation layer** that connects these to Truffle's debugging/profiling tools.

## What's Missing

### 1. `@ProvidedTags` on MezzeLanguage

MezzeLanguage needs to declare which tags it supports so Truffle tools know what to instrument:

```java
@TruffleLanguage.Registration(id = "mezze", name = "Mezze", ...)
@ProvidedTags({
    StandardTags.RootTag.class,
    StandardTags.RootBodyTag.class,
    StandardTags.StatementTag.class,
    StandardTags.ExpressionTag.class,
    StandardTags.CallTag.class,
    StandardTags.ReadVariableTag.class,
    StandardTags.WriteVariableTag.class,
    DebuggerTags.AlwaysHalt.class
})
public class MezzeLanguage extends TruffleLanguage<MezzeContext> { ... }
```

### 2. InstrumentableNode Base Classes

Create instrumentable base classes that nodes can extend:

```java
@GenerateWrapper
public abstract class MezzeStatementNode extends MezzeNode implements InstrumentableNode {
    @Override
    public boolean isInstrumentable() { return getSourceSection() != null; }

    @Override
    public WrapperNode createWrapper(ProbeNode probe) {
        return new MezzeStatementNodeWrapper(this, probe);
    }
}
```

### 3. `hasTag()` on All Node Types

Each node declares what kind of construct it is:

```java
// IfNode, MatchNode, WriteLocalNode, BlockNode, etc.
@Override
public boolean hasTag(Class<? extends Tag> tag) {
    return tag == StandardTags.StatementTag.class;
}

// MonomorphicCallNode, InvokeNode, etc.
@Override
public boolean hasTag(Class<? extends Tag> tag) {
    return tag == StandardTags.CallTag.class
        || tag == StandardTags.ExpressionTag.class;
}
```

### 4. SourceSection on All Nodes

Currently only root nodes get SourceSection. Every node needs it:

```kotlin
// In TruffleCompiler.compileNode(), for each node creation:
val node = IfNode(condition, thenBranch, elseBranch)
node.sourceSection = createSourceSection(irNode.src)  // Add this line
```

The IR already carries `src: SourceInfo` on every NodeIR. The conversion method exists. Just call it.

### 5. MezzeScope for Variable Inspection

A scope object that the debugger uses to show local variables at breakpoints:

```java
@ExportLibrary(InteropLibrary.class)
public class MezzeScope implements TruffleObject {
    private final Frame frame;
    private final MezzeNode node;

    // Exposes all variables in scope as named members
    @ExportMessage boolean hasMembers() { return true; }
    @ExportMessage Object getMembers(boolean includeInternal) { ... }
    @ExportMessage boolean isMemberReadable(String member) { ... }
    @ExportMessage Object readMember(String member) { ... }
}
```

### 6. NodeLibrary Export on Statement Nodes

Tell the debugger how to find the scope at each statement:

```java
@ExportMessage boolean hasScope(Frame frame) { return true; }
@ExportMessage Object getScope(Frame frame, boolean nodeEnter) {
    return new MezzeScope(frame, this);
}
```

## What We Get For Free

Once the above is wired up, Truffle provides all of these automatically:

### DAP Debugging (Debug Adapter Protocol)
- Breakpoints on any line (SourceSection + StatementTag)
- Step into / step over / step out (InstrumentableNode + tags)
- Variable inspection at breakpoints (MezzeScope + NodeLibrary)
- Call stack with source locations (SourceSection on all nodes)
- Conditional breakpoints (Truffle debug API)
- Works with VSCode, Neovim, Emacs, any DAP client

### Chrome DevTools Protocol
- Same debugging in Chrome DevTools
- `--inspect` flag on the CLI
- Remote debugging of running processes

### CPU Profiler
- Per-function and per-expression execution time
- Hot path identification
- CallTag enables call-graph profiling

### Coverage Tool
- Statement-level coverage (StatementTag + SourceSection)
- Branch coverage (with additional instrumentation)
- Integrates with `mez sample --cover` for verification

### Memory Tracer
- Allocation tracking (needs AllocationReporter, optional)
- Object lifecycle monitoring

## Implementation Priority

### Phase 1: Basic Debugging (smallest useful increment)
1. `@ProvidedTags` on MezzeLanguage
2. SourceSection on all nodes in TruffleCompiler
3. StatementTag on statement nodes (if, match, let, block, write)
4. CallTag on call nodes
5. ExpressionTag on expression nodes

This gives: breakpoints, stepping, call stacks with line numbers.

### Phase 2: Variable Inspection
6. MezzeScope class
7. NodeLibrary export on statement nodes
8. ReadVariableTag / WriteVariableTag

This gives: see local variables at breakpoints.

### Phase 3: Profiling & Coverage
9. CPU sampler integration
10. Coverage tool integration
11. AllocationReporter for memory tracking

### Phase 4: Integration with Correctness Tools
12. Use instrumentation for `mez sample` pipeline view (capture intermediate values)
13. Use instrumentation for trace recording
14. Use coverage data for `--cover` mode verification

## Connection to Correctness Tooling

The instrumentation layer enables deeper integration between Truffle tools and our correctness features:

- **Sample pipeline view** could use Truffle instrumentation to capture intermediate values at each chain step, instead of a custom recording layer
- **Trace** could build on Truffle's stepping infrastructure for the recorded execution
- **Coverage** from Truffle feeds directly into `mez sample --cover` to verify all branches were actually hit
- **Continuation capture** can use Truffle's suspension mechanisms

The correctness tools (sample, trace, live edit) work without instrumentation — they have their own recording layer. But instrumentation makes them better by providing a standard, well-tested mechanism for observation.
