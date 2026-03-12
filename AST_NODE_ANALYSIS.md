# AST Node-by-Node Analysis

This document analyzes each AST node to identify:
1. Dead code to delete
2. Type issues (wrong field types)
3. Missing required fields
4. Content hash usage (should be ONLY way, no fallbacks)
5. Parser fixes needed

---

## Phase 1: Delete Dead Code

### 1.1 RecordEntry (Expression.kt:376-379)
**Status: DELETE**

```kotlin
data class RecordEntry(
    val name: String,
    val value: Expression? = null
)
```

**Reason**: Duplicate of `RecordFieldEntry`. Only `RecordLiteralEntry` sealed hierarchy is used:
- `RecordFieldEntry` - for regular fields
- `RecordSpreadEntry` - for spread entries

**Verification needed**: Grep for `RecordEntry` usage outside of `RecordUpdate`.

---

### 1.2 RecordUpdate (Expression.kt:392-397)
**Status: DELETE** ✅ CONFIRMED DEAD CODE

```kotlin
data class RecordUpdate(
    val record: Expression,
    val updates: List<RecordEntry>,
    ...
)
```

**Evidence**:
- Parser never creates this node (no constructor calls found)
- Interpreter doesn't handle it (ASTToTruffleConverterV2.kt only handles RecordLiteral)
- No .mz files use `{ record | field = value }` syntax
- Syntax `{ ...record, field = newValue }` uses RecordLiteral with RecordSpreadEntry

---

### 1.3 RecordUpdateExpression (Expression.kt:400-405)
**Status: DELETE** ✅ CONFIRMED DEAD CODE

```kotlin
data class RecordUpdateExpression(
    val baseRecord: Expression,
    val updates: List<RecordUpdateEntry>,
    ...
)
```

**Evidence**:
- Parser never creates this node
- Interpreter doesn't handle it
- TypeChecker has TODO: "Implement nested field update constraints" (never finished)
- Was intended for `{ record | x.y.z = value }` nested path updates, but never implemented

---

### 1.4 RecordUpdateEntry (Expression.kt:408-411)
**Status: DELETE** ✅ CONFIRMED DEAD CODE

```kotlin
data class RecordUpdateEntry(
    val fieldPath: List<String>,
    val value: Expression
)
```

**Reason**: Only used by RecordUpdateExpression which is dead code.

---

### 1.5 PlaceholderExpression (Expression.kt:267-271)
**Status: DELETE**

```kotlin
data class PlaceholderExpression(
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression
```

**Reason**: Comment says "should never appear in final AST". Parser should ensure this.

**Parser fix**: Validate that PlaceholderExpression is replaced before AST is returned.

---

## Phase 2: Fix Type Issues

### 2.1 RecordLiteral.restField: String? → isOpen: Boolean
**Current**:
```kotlin
data class RecordLiteral(
    val entries: List<RecordLiteralEntry>,
    val restField: String? = null,  // BAD: stores identifier name
    ...
)
```

**Problem**: `restField` stores a string identifier name which is never used. What matters is whether the record is "open" (has `...`).

**Fix**:
```kotlin
data class RecordLiteral(
    val entries: List<RecordLiteralEntry>,
    val isOpen: Boolean = false,  // true if ... was present
    ...
)
```

---

### 2.2 FunctionCall.arguments: Expression → RecordLiteral
**Current**:
```kotlin
data class FunctionCall(
    val function: Expression,
    val arguments: Expression,  // BAD: should be RecordLiteral
    ...
)
```

**Problem**: Arguments to a function are always a record literal in Mezze. Allowing any `Expression` means we need runtime checks.

**Fix**:
```kotlin
data class FunctionCall(
    val function: Expression,
    val arguments: RecordLiteral,  // GOOD: type-safe
    ...
)
```

---

### 2.3 ResolvedAbility: Duplicate fields
**Current**:
```kotlin
data class ResolvedAbility(
    val abilityName: String,           // Name-based
    val typeArgs: List<MezzeType>,
    val isBuiltin: Boolean = false,
    val implementationHash: String? = null  // Optional content hash
)
```

**Problem**: Has both name-based (`abilityName`) and content-hash based (`implementationHash`) lookup. Content hash should be the ONLY way.

**Fix**:
```kotlin
data class ResolvedAbility(
    val implementationHash: String,    // REQUIRED - content hash is the only way
    val typeArgs: List<MezzeType>,
    val isBuiltin: Boolean = false
)
// abilityName can be derived from the hash if needed for error messages
```

---

### 2.4 ResolvedEffectHandler: Multiple optional hash fields
**Current**:
```kotlin
data class ResolvedEffectHandler(
    val effectName: String,
    val performerName: String,
    val implDeclarationHash: String? = null,   // Optional
    val effectDeclarationHash: String? = null, // Optional
    ...
)
```

**Problem**: Names AND optional hashes. Content hash should be REQUIRED.

**Fix**:
```kotlin
data class ResolvedEffectHandler(
    val implDeclarationHash: String,    // REQUIRED
    val effectDeclarationHash: String,  // REQUIRED
    val isScopedEffect: Boolean = false,
    ...
)
// effectName/performerName can be derived for error messages
```

---

### 2.5 FunctionCall: Redundant resolution fields
**Current**:
```kotlin
data class FunctionCall(
    ...
    val resolvedFunctionName: String? = null,       // Name-based
    val resolvedFunctionContentHash: String? = null // Hash-based - redundant
)
```

**Problem**: Two fields for the same purpose. `resolvedFunctionContentHash` should be the ONLY field.

**Fix**: Delete `resolvedFunctionName`, keep only `resolvedFunctionContentHash` and make it non-optional when the function is resolved.

---

### 2.6 ConstructorResolution: Mix of names and optional hashes
**Current**:
```kotlin
sealed class ConstructorResolution {
    data class EffectReference(
        val effectName: String,
        val effectContentHash: String? = null  // Optional
    ) : ConstructorResolution()

    data class HandlerReference(
        val handlerName: String,
        val effectName: String? = null  // Optional
    ) : ConstructorResolution()
    ...
}
```

**Problem**: Names with optional hashes. Should be required hashes.

**Fix**:
```kotlin
sealed class ConstructorResolution {
    data class EffectReference(
        val effectContentHash: String  // REQUIRED
    ) : ConstructorResolution()

    data class HandlerReference(
        val handlerContentHash: String,  // REQUIRED
        val effectContentHash: String    // REQUIRED
    ) : ConstructorResolution()
    ...
}
```

---

## Phase 3: Parser Fixes Needed

### 3.1 Spread position validation
**Problem**: Parser allows `{field = new_value, ...my_record}` but spread should only be at the end.

**Fix**: Parser should reject spread not in final position for record update syntax.

**Valid**:
```mezze
{...my_record, field = new_value}  # OK - spread first
{a = 1, b = 2, ...rest}            # OK - spread last in open record
```

**Invalid**:
```mezze
{field = new_value, ...my_record}  # ERROR - spread not at end
{...a, field = x, ...b}            # ERROR - multiple spreads
```

---

### 3.2 PlaceholderExpression cleanup
**Problem**: `PlaceholderExpression` can escape parser.

**Fix**: Parser should validate no `PlaceholderExpression` remains after pipe desugaring.

---

### 3.3 Qualified reference validation
**Problem**: Parser creates qualified references but doesn't validate they resolve.

**Current behavior**: Parser produces `Identifier(localName="foo", qualifier=ModulePath(["bar"]))`.

**Typechecker responsibility**: Validate `bar::foo` actually exists. This is correct - parser shouldn't resolve.

---

## Phase 4: Content Hash Migration

### 4.1 Make content hash REQUIRED (not optional)

**Files to update**:
- `Expression.kt`: Change `String?` to `String` for all resolution hashes
- `TypeChecker.kt`: Ensure typechecker ALWAYS populates hash (no optional paths)
- `ASTToTruffleConverterV2.kt`: Remove fallback lookups using names

**Affected nodes**:
1. `Identifier.resolvedContentHash` - make required after typecheck
2. `ResolvedAbility.implementationHash` - make required
3. `ResolvedEffectHandler.implDeclarationHash` - make required
4. `ResolvedEffectHandler.effectDeclarationHash` - make required
5. `FunctionCall.resolvedFunctionContentHash` - make required after typecheck
6. `MemberAccess.resolvedContentHash` - make required after typecheck
7. `ConstructorResolution.effectContentHash` / `handlerContentHash` - make required

### 4.2 Remove name-based fallbacks

**Current pattern** (bad):
```kotlin
// In interpreter
val hash = expr.resolvedContentHash
if (hash != null) {
    lookupByHash(hash)
} else {
    // Fallback to name-based lookup
    lookupByName(expr.name)  // DELETE THIS
}
```

**Target pattern** (good):
```kotlin
// In interpreter
val hash = expr.resolvedContentHash  // Required, never null
lookupByHash(hash)
```

---

## Phase 5: Remove String-Based Type Detection

### 5.1 Type variable detection: `startsWith("'")`
**Current**: Multiple places check `name.startsWith("'")` to detect type variables.

**Fix**: Type variables should be a distinct type, not strings starting with `'`.

```kotlin
// Current (bad)
sealed interface MezzeType {
    data class TypeVariable(val name: String) : MezzeType  // name like "'a"
}

// In usage
if (type is TypeVariable && type.name.startsWith("'")) { ... }

// Better - name never has the ' prefix, it's semantic
data class TypeVariable(val name: String) : MezzeType {
    // name is "a", not "'a" - the ' is syntax, not data
}
```

### 5.2 Row variable detection: `startsWith("row")`
**Current**: Check `name.startsWith("row")` to detect row variables.

**Fix**: Row variables are already a distinct type (`RowVar`), ensure usage doesn't rely on name prefix.

---

## Verification Commands

```bash
# Find usages of dead code
grep -rn "RecordEntry" --include="*.kt" | grep -v "RecordLiteralEntry\|RecordFieldEntry\|RecordSpreadEntry\|RecordUpdateEntry"
grep -rn "RecordUpdate[^E]" --include="*.kt"
grep -rn "PlaceholderExpression" --include="*.kt"

# Find name-based lookups that should use hash
grep -rn "resolvedFunctionName" --include="*.kt"
grep -rn "abilityName" --include="*.kt"
grep -rn "effectName" --include="*.kt"
grep -rn "performerName" --include="*.kt"

# Find string-based type detection
grep -rn "startsWith.*'" --include="*.kt"
grep -rn 'startsWith.*"row"' --include="*.kt"
```

---

## Implementation Order

1. **Delete dead code** (low risk, immediate benefit)
   - RecordEntry
   - RecordUpdate
   - RecordUpdateExpression + RecordUpdateEntry (if unused)
   - PlaceholderExpression

2. **Fix type issues** (medium risk, improves type safety)
   - RecordLiteral.restField → isOpen
   - FunctionCall.arguments: Expression → RecordLiteral

3. **Content hash migration** (high impact, requires coordinated changes)
   - Make all resolution hashes required
   - Remove name-based fallbacks in interpreter
   - Update typechecker to always populate hashes

4. **Parser fixes** (requires parser knowledge)
   - Spread position validation
   - PlaceholderExpression cleanup validation
