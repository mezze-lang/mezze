# Plan: Ability Dictionary Passing for Polymorphic Functions

## Problem

When a polymorphic function has ability constraints like:
```mezze
pub let post_body: { url: String, body: 'a } -> ... with Encode Json 'a = { url, body } ->
    match Encode.encode { val = body, config = default_config } is ...
```

The `Encode.encode` call is on type variable `'a`. The typechecker can't statically resolve which `Encode` implementation to use because `'a` isn't concrete until the function is called.

Currently, we fall back to `RuntimeAbilityCallNode` which does a global lookup by ability name - but this is wrong when there are multiple implementations for different types.

## Solution: Dictionary Passing

Pass the resolved ability implementations as implicit closure parameters, similar to Haskell's dictionary passing for type classes.

## Implementation Steps

### Phase 1: Track Ability Constraints in Converter

**File: `ASTToTruffleConverterV2.kt`**

1. When converting a `LambdaExpression` with ability constraints:
   - Extract `abilityConstraints` from the lambda's inferred `FunctionType`
   - For each constraint, allocate a frame slot to hold the ability dictionary
   - Store mapping: `constraintKey -> slotIndex` in a new `abilityDictionarySlots` map

2. Add new field to track ability dictionary slots:
```kotlin
// Maps ability constraint key (e.g., "Encode:Json:'a") to frame slot
private val abilityDictionarySlots = mutableMapOf<String, Int>()
```

### Phase 2: Extend FunctionDefinitionNode

**File: `FunctionDefinitionNode.java`**

1. Add new fields:
```java
// Slot indices for ability dictionaries (captured from caller)
private final int[] abilityDictSlots;
// Names of abilities (for debugging)
private final String[] abilityNames;
```

2. During closure capture, include ability dictionary slots alongside regular closure slots.

### Phase 3: Pass Dictionaries at Call Sites

**File: `ASTToTruffleConverterV2.kt` - `convertFunctionCall`**

When calling a function that has ability constraints AND we have `resolvedAbilities`:

1. For each resolved ability, look up or create the implementation
2. Write the implementation to the appropriate frame slot before invoking

**File: `InvokeNode.java`**

1. Before calling a function with ability constraints:
   - Read `resolvedAbilities` from the call
   - For each ability, get the implementation (either from parent frame or resolve it)
   - Pass as additional arguments or set in callee's frame slots

### Phase 4: Use Dictionaries for Ability Calls

**File: `ASTToTruffleConverterV2.kt` - ability call handling**

When inside a polymorphic function and calling an ability method on a type variable:

1. Instead of using `RuntimeAbilityCallNode`, use a new `DictionaryAbilityCallNode`
2. This node reads the ability impl from the frame slot
3. Calls the operation on the impl record

### Phase 5: Create DictionaryAbilityCallNode

**New File: `DictionaryAbilityCallNode.java`**

```java
@NodeInfo(shortName = "dict-ability-call")
public final class DictionaryAbilityCallNode extends MezzeNode {
    // Frame slot where the ability dictionary is stored
    private final int dictionarySlot;
    // Operation name to call
    private final String operationName;

    @Child private MezzeNode argumentsNode;
    @Child private DirectCallNode callNode;

    @Override
    public Object execute(VirtualFrame frame) {
        // Read the ability impl from frame
        MezzeRecord impl = (MezzeRecord) frame.getObject(dictionarySlot);

        // Get the operation function
        MezzeFunction operation = (MezzeFunction) impl.getFieldByName(operationName);

        // Initialize call node on first execution
        if (callNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callNode = insert(DirectCallNode.create(operation.getCallTarget()));
        }

        // Execute arguments and call
        Object args = argumentsNode.execute(frame);
        return callNode.call(args);
    }
}
```

## Key Design Decisions

### 1. Constraint Key Format
Use a deterministic key for each ability constraint:
```
"{abilityName}:{typeArg1}:{typeArg2}:..."
```
For type variables, use their bound name (e.g., `'a`).

### 2. Frame Slot Organization
Ability dictionary slots are allocated alongside regular local variable slots:
- Local variables: slots 0..N
- Ability dictionaries: slots N+1..M
- The function knows which slots are dictionaries via `abilityDictSlots` array

### 3. Monomorphized vs Polymorphic Functions
- **Monomorphized functions** (concrete types): Use `OptimizedAbilityCallNode` with resolved hash
- **Polymorphic functions** (type variables): Use `DictionaryAbilityCallNode` with frame lookup

### 4. Nested Polymorphic Calls
When a polymorphic function calls another polymorphic function:
- The caller's dictionaries are passed through
- The callee receives the appropriate dictionaries for ITS constraints

## Testing Strategy

1. **Unit Tests**: Test dictionary passing with simple polymorphic functions
2. **Integration Tests**: Test `std::effects::http` module functions
3. **Nested Tests**: Polymorphic functions calling other polymorphic functions
4. **Multiple Constraints**: Functions with multiple ability constraints

## Migration Path

1. First, add dictionary infrastructure without changing existing behavior
2. Then, modify ability call handling to use dictionaries when inside polymorphic functions
3. Finally, remove `RuntimeAbilityCallNode` fallback (or keep for edge cases)

## Files to Modify

| File | Changes |
|------|---------|
| `ASTToTruffleConverterV2.kt` | Track ability constraints, allocate slots, pass dictionaries |
| `FunctionDefinitionNode.java` | Store ability dictionary slot indices |
| `InvokeNode.java` | Pass ability dictionaries to called functions |
| `DictionaryAbilityCallNode.java` | NEW: Call ability methods via frame-stored dictionaries |
| `MezzeFunction.java` | Store ability dictionary requirements |

## Success Criteria

1. All existing tests pass
2. `std::effects::http` module works without `RuntimeAbilityCallNode`
3. No runtime ability lookups for resolved constraints
4. Polymorphic functions correctly dispatch to type-specific implementations
