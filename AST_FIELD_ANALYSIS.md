# AST Field-by-Field Analysis

Deep analysis of each AST node's fields:
- Parser syntax that creates each field
- Nullability analysis
- Content-addressable hashing considerations
- Optimization opportunities

---

## RecordLiteral

```kotlin
data class RecordLiteral(
    val entries: List<RecordLiteralEntry>,
    val restField: String? = null,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression
```

### Field: `entries: List<RecordLiteralEntry>`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `{x = 1, y = 2}` or `{x, y}` (punning) or `{...spread, x = 1}` |
| **Can be empty** | Yes: `{}` parses to `RecordLiteral(entries = [])` |
| **Nullable?** | No - List is never null (can be empty) |
| **Hashing** | Field entries sorted by name, spread entries in order |
| **Issue** | Spread entries can appear anywhere - should be FIRST only |

### Field: `restField: String?`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `{x = 1, ...}` - trailing ellipsis (NO expression after) |
| **Values** | `null` or `"..."` |
| **Nullable?** | Yes - null means closed record |
| **Hashing** | Included in hash |
| **Issue** | Always `"..."` when present - should this be `Boolean isOpen` instead? |

**Current behavior:**
- `{x = 1}` → `restField = null` (closed record)
- `{x = 1, ...}` → `restField = "..."` (open record for row polymorphism)

**Optimization:** Change to `isOpen: Boolean = false` instead of `String?`

### Field: `inferredType: MezzeType?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always `null` (set by typechecker) |
| **Nullable?** | Yes - null during parsing, non-null after typechecking |
| **Hashing** | EXCLUDED (set to null in ASTNormalizer) |
| **Status** | Correct |

### Field: `sourceLocation: SourceLocation?`

| Aspect | Details |
|--------|---------|
| **Parser** | Set from `{` token position |
| **Nullable?** | Yes - but always set by parser |
| **Hashing** | EXCLUDED (set to null in ASTNormalizer) |
| **Status** | Correct |

---

## RecordFieldEntry

```kotlin
data class RecordFieldEntry(
    val name: String,
    val value: Expression? = null
) : RecordLiteralEntry()
```

### Field: `name: String`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `x = 1` → name="x", `x` (punning) → name="x" |
| **Nullable?** | No - always required |
| **Hashing** | Included, sorted by name |
| **Status** | Correct |

### Field: `value: Expression?`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `x = 1` → value=IntLiteral(1), `x` → value=null (punning) |
| **Nullable?** | Yes - null means punning `{x}` = `{x = x}` |
| **Hashing** | Included |
| **Status** | Correct |

---

## RecordSpreadEntry

```kotlin
data class RecordSpreadEntry(
    val expression: Expression
) : RecordLiteralEntry()
```

### Field: `expression: Expression`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `...expr` |
| **Nullable?** | No - spread always has an expression |
| **Hashing** | Included in order |
| **Issue** | Can appear anywhere in entries list - should be FIRST only |

---

## MemberAccess

```kotlin
data class MemberAccess(
    val receiver: Expression,
    val member: String,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null,
    val resolvedContentHash: String? = null
)
```

### Field: `receiver: Expression`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `expr.field` → receiver=expr |
| **Nullable?** | No |
| **Hashing** | Included |
| **Status** | Correct |

### Field: `member: String`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `expr.field` → member="field" |
| **Nullable?** | No - always required |
| **Hashing** | Included |
| **Status** | Correct - simple field name, not qualified |

### Field: `resolvedContentHash: String?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always `null` |
| **TypeChecker** | Set for module-qualified access |
| **Nullable?** | Yes - null during parsing |
| **Hashing** | Should be EXCLUDED (not part of syntax) |
| **Status** | Check if excluded from hashing |

---

## RecordPattern

```kotlin
data class RecordPattern(
    val entries: List<RecordPatternEntry>,
    val isOpen: Boolean = false,
    val sourceLocation: SourceLocation? = null
) : Pattern
```

### Field: `entries: List<RecordPatternEntry>`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `{x, y}` or `{x = pattern}` |
| **Can be empty** | Yes: `{}` |
| **Nullable?** | No |
| **Status** | Correct |

### Field: `isOpen: Boolean`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `{x, ...}` → isOpen=true |
| **Values** | true or false |
| **Nullable?** | No - Boolean |
| **Status** | Correct - this is better than `restField: String?` |

**NOTE:** RecordPattern uses `isOpen: Boolean` while RecordLiteral uses `restField: String?`. Should be consistent!

---

## RecordPatternEntry

```kotlin
data class RecordPatternEntry(
    val name: String,
    val pattern: Pattern? = null,
    val sourceLocation: SourceLocation? = null
)
```

### Field: `name: String`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `x` or `x = Pattern` |
| **Nullable?** | No |
| **Status** | Correct |

### Field: `pattern: Pattern?`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `{x}` → pattern=null (binds x), `{x = Some {val}}` → pattern=ConstructorPattern |
| **Nullable?** | Yes - null means shorthand binding |
| **Status** | Correct |

---

## Summary: Record-related AST

### Issues Found:

1. **Inconsistent open record representation:**
   - `RecordLiteral.restField: String?` (always "..." or null)
   - `RecordPattern.isOpen: Boolean`
   - Should both use `isOpen: Boolean`

2. **Spread position not enforced:**
   - `RecordSpreadEntry` can appear anywhere in `entries` list
   - Should only be allowed at START in literals
   - Already correct in patterns (isOpen at END)

3. **Dead code:**
   - `RecordEntry` - not used (duplicate of `RecordFieldEntry`)
   - `RecordUpdate`, `RecordUpdateExpression`, `RecordUpdateEntry` - not used

### Proposed Changes:

```kotlin
// Before
data class RecordLiteral(
    val entries: List<RecordLiteralEntry>,
    val restField: String? = null,  // "..." or null
    ...
)

// After
data class RecordLiteral(
    val spreadEntries: List<RecordSpreadEntry>,  // Must come first (order preserved)
    val fieldEntries: List<RecordFieldEntry>,    // Named fields (order doesn't matter for semantics)
    val isOpen: Boolean = false,                  // true for {x, ...}
    ...
)
```

Benefits:
1. Enforces spread-first at the type level
2. Consistent with RecordPattern's `isOpen: Boolean`
3. No magic string "..."
4. Clear separation of concerns

---

---

## Identifier

```kotlin
data class Identifier(
    val localName: String,
    val qualifier: ModulePath? = null,
    val resolvedContentHash: String? = null,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression
```

### Field: `localName: String`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `foo` → "foo", `bytes::from_string` → "from_string" |
| **Nullable?** | No |
| **Hashing** | Included |
| **Status** | Correct |

### Field: `qualifier: ModulePath?`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `foo` → null, `bytes::from_string` → ModulePath(["bytes"]) |
| **Nullable?** | Yes - null for unqualified names |
| **Hashing** | Included (each segment) |
| **Status** | Correct - recently fixed to use structured type |

### Field: `resolvedContentHash: String?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always `null` |
| **TypeChecker** | Set for cross-module references |
| **Nullable?** | Yes |
| **Hashing** | EXCLUDED (not in ASTHasher) |
| **Status** | Correct |

---

## FunctionCall

```kotlin
data class FunctionCall(
    val function: Expression,
    val arguments: Expression,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null,
    val resolvedAbilities: Map<String, ResolvedAbility> = emptyMap(),
    val resolvedFunctionName: String? = null,
    override val typeSubstitution: Map<String, MezzeType> = emptyMap(),
    val instantiatedFunctionType: MezzeType.FunctionType? = null,
    val isTailCall: Boolean = false,
    val resolvedFunctionContentHash: String? = null
) : Expression
```

### Field: `function: Expression`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `foo {x = 1}` → function=Identifier("foo") |
| **Nullable?** | No |
| **Hashing** | Included |
| **Status** | Correct |

### Field: `arguments: Expression`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `foo {x = 1}` → arguments=RecordLiteral |
| **Nullable?** | No - always a record |
| **Hashing** | Included |
| **Issue** | Type is `Expression` but always `RecordLiteral`. Should be `RecordLiteral`? |

### Field: `resolvedAbilities: Map<String, ResolvedAbility>`

| Aspect | Details |
|--------|---------|
| **Parser** | Always empty |
| **TypeChecker** | Populated for ability method calls |
| **Hashing** | EXCLUDED |
| **Status** | Correct |

### Field: `resolvedFunctionName: String?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always null |
| **TypeChecker** | Set for monomorphic dispatch |
| **Hashing** | EXCLUDED |
| **Issue** | Could be content hash instead of name? |

### Field: `typeSubstitution: Map<String, MezzeType>`

| Aspect | Details |
|--------|---------|
| **Parser** | Always empty |
| **TypeChecker** | Populated for generic instantiation |
| **Hashing** | EXCLUDED |
| **Status** | Correct |

### Field: `instantiatedFunctionType: MezzeType.FunctionType?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always null |
| **TypeChecker** | Set for polymorphic calls |
| **Hashing** | EXCLUDED |
| **Status** | Correct |

### Field: `isTailCall: Boolean`

| Aspect | Details |
|--------|---------|
| **Parser** | Always false |
| **TypeChecker** | Set to true for self-recursive tail calls |
| **Hashing** | EXCLUDED (set to false in ASTNormalizer) |
| **Status** | Correct |

### Field: `resolvedFunctionContentHash: String?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always null |
| **TypeChecker** | Set for content-addressed dispatch |
| **Hashing** | EXCLUDED |
| **Issue** | Duplicate of resolvedFunctionName? Should remove resolvedFunctionName? |

### Summary: FunctionCall Fields

**Semantic fields (included in hash):**
- `function`, `arguments`

**Computed fields (excluded from hash):**
- `inferredType`, `sourceLocation` - standard
- `resolvedAbilities` - ability resolution
- `resolvedFunctionName` - REDUNDANT with resolvedFunctionContentHash?
- `typeSubstitution` - generic instantiation
- `instantiatedFunctionType` - polymorphic type info
- `isTailCall` - TCO optimization
- `resolvedFunctionContentHash` - content-addressed dispatch

**Issues:**
1. `arguments: Expression` should be `arguments: RecordLiteral` (always a record)
2. `resolvedFunctionName` might be redundant with `resolvedFunctionContentHash`

---

## LambdaExpression

```kotlin
data class LambdaExpression(
    val parameters: LambdaParameters,
    val body: Expression,
    val returnType: TypeAnnotation? = null,
    val abilityConstraints: List<FunctionAbilityConstraint> = emptyList(),
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression
```

### Field: `parameters: LambdaParameters`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `{x, y} -> body` or `{x: Int} -> body` |
| **Nullable?** | No |
| **Hashing** | Included |
| **Status** | Correct - sealed class TypedParameters/UntypedParameters |

### Field: `body: Expression`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | Part after `->` |
| **Nullable?** | No |
| **Hashing** | Included |
| **Status** | Correct |

### Field: `returnType: TypeAnnotation?`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `{x} -> Int: body` (return type annotation) |
| **Nullable?** | Yes - optional |
| **Hashing** | Included |
| **Status** | Correct |

### Field: `abilityConstraints: List<FunctionAbilityConstraint>`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `{x} -> body with NumAdd 'a` |
| **Nullable?** | No - empty list by default |
| **Hashing** | Included |
| **Status** | Correct |

---

## LetBinding (Declaration)

```kotlin
data class LetBinding(
    val pattern: Pattern,
    val visibility: Visibility = Visibility.PRIVATE,
    val typeAnnotation: TypeAnnotation? = null,
    val inferredType: MezzeType? = null,
    val typeScheme: TypeScheme? = null,
    val expression: Expression,
    val isMonomorphicInstance: Boolean = false,
    val originalFunctionName: String? = null,
    val monomorphicTypeArgs: Map<String, MezzeType> = emptyMap(),
    val contentHash: String? = null
) : ProgramItem
```

### Field: `pattern: Pattern`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `let x = ...` or `let {a, b} = ...` |
| **Nullable?** | No |
| **Hashing** | Included |
| **Status** | Correct |

### Field: `visibility: Visibility`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `pub let` vs `let` |
| **Nullable?** | No - enum |
| **Hashing** | Included |
| **Status** | Correct |

### Field: `typeAnnotation: TypeAnnotation?`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | `let x: Int = ...` |
| **Nullable?** | Yes - optional |
| **Hashing** | Included |
| **Status** | Correct |

### Field: `inferredType: MezzeType?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always null |
| **TypeChecker** | Set to monomorphic instantiated type |
| **Hashing** | EXCLUDED |
| **Status** | Correct |

### Field: `typeScheme: TypeScheme?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always null |
| **TypeChecker** | Set to polymorphic type scheme |
| **Hashing** | EXCLUDED |
| **Status** | Correct |

### Field: `expression: Expression`

| Aspect | Details |
|--------|---------|
| **Parser syntax** | RHS of `=` |
| **Nullable?** | No |
| **Hashing** | Included |
| **Status** | Correct |

### Field: `isMonomorphicInstance: Boolean`

| Aspect | Details |
|--------|---------|
| **Parser** | Always false |
| **Compiler** | Set for generated specialized functions |
| **Hashing** | EXCLUDED |
| **Status** | Correct |

### Field: `originalFunctionName: String?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always null |
| **Compiler** | Set for monomorphic instances |
| **Hashing** | EXCLUDED |
| **Issue** | Should be content hash? |

### Field: `monomorphicTypeArgs: Map<String, MezzeType>`

| Aspect | Details |
|--------|---------|
| **Parser** | Always empty |
| **Compiler** | Set for monomorphic instances |
| **Hashing** | EXCLUDED |
| **Status** | Correct |

### Field: `contentHash: String?`

| Aspect | Details |
|--------|---------|
| **Parser** | Always null |
| **TypeChecker** | Computed from AST structure |
| **Hashing** | This IS the hash output |
| **Status** | Correct |

---

## Summary: Fields Analysis

### Pattern: Semantic vs Computed Fields

Every AST node has two categories of fields:

**Semantic fields** (part of syntax, included in hash):
- Core structure (name, body, entries, etc.)
- User-written annotations (types, constraints)
- Visibility modifiers

**Computed fields** (set by compiler, excluded from hash):
- `inferredType` - always null from parser
- `sourceLocation` - for error messages
- `resolvedContentHash` - for cross-module lookup
- `typeScheme` - polymorphic type
- Monomorphization metadata

### Issues Found:

1. **RecordLiteral.restField: String?** → should be `isOpen: Boolean`
2. **FunctionCall.arguments: Expression** → should be `RecordLiteral`
3. **FunctionCall.resolvedFunctionName** → redundant with `resolvedFunctionContentHash`?
4. **LetBinding.originalFunctionName** → should use content hash?
5. **Record spread position** → not enforced by parser
   - **SeqLiteral already has this**: `validateSeqSpreadPosition()` at line 553
   - RecordLiteral should have similar validation

---

## Literals (Simple Nodes)

### IntLiteral, NatLiteral, FloatLiteral, BooleanLiteral

```kotlin
data class IntLiteral(
    val value: Long,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `value` | No | Included | Correct |
| `inferredType` | Yes | Excluded | Correct |
| `sourceLocation` | Yes | Excluded | Correct |

**Status:** All correct. Simple and clean.

### FractionLiteral

```kotlin
data class FractionLiteral(
    val numerator: Long,
    val denominator: Long,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `numerator` | No | Included | Correct |
| `denominator` | No | Included | Correct |
| `inferredType` | Yes | Excluded | Correct |
| `sourceLocation` | Yes | Excluded | Correct |

**Status:** Correct.

### StringLiteral

```kotlin
data class StringLiteral(
    val value: String,
    val parts: List<StringInterpolationPart>? = null,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `value` | No | Included | Correct |
| `parts` | Yes | Included | Correct - null=simple, non-null=interpolated |
| `inferredType` | Yes | Excluded | Correct |
| `sourceLocation` | Yes | Excluded | Correct |

**Issue:** When `parts` is non-null, `value` is redundant (can be reconstructed from parts).
**Consideration:** Could use sealed class `SimpleString(value)` vs `InterpolatedString(parts)` instead?

---

## SeqLiteral

```kotlin
data class SeqLiteral(
    val elements: List<SeqElement>,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `elements` | No (can be empty) | Included | Correct |
| `inferredType` | Yes | Excluded | Correct |
| `sourceLocation` | Yes | Excluded | Correct |

**Spread validation:** Parser already validates spread-first with `validateSeqSpreadPosition()`

**Status:** Correct! Parser enforces spread position.

---

---

## VariantConstruction

```kotlin
data class VariantConstruction(
    val constructor: String,
    val value: Expression? = null,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null,
    val resolution: ConstructorResolution = ConstructorResolution.Unresolved
)
```

| Field | Parser Creates | Nullable | Hashing | Status |
|-------|----------------|----------|---------|--------|
| `constructor` | From uppercase name | No | Included | Correct |
| `value` | `Some {x}` → RecordLiteral, `None` → null | Yes | Included | Correct |
| `inferredType` | null | Yes | Excluded | Correct |
| `sourceLocation` | Set | Yes | Excluded | Correct |
| `resolution` | Unresolved | No | EXCLUDED | Correct |

**ConstructorResolution** is computed by typechecker, excluded from hash.

**Issue:** `constructor: String` - could be `QualifiedRef` for cross-module constructor references?

---

## IfExpression

```kotlin
data class IfExpression(
    val condition: Expression,
    val thenBranch: Expression,
    val elseBranch: Expression,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `condition` | No | Included | Correct |
| `thenBranch` | No | Included | Correct |
| `elseBranch` | No | Included | Correct |
| `inferredType` | Yes | Excluded | Correct |
| `sourceLocation` | Yes | Excluded | Correct |

**Status:** Perfect. Simple and clean.

---

## MatchExpression

```kotlin
data class MatchExpression(
    val expression: Expression,
    val cases: List<MatchCase>,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `expression` | No | Included | Correct |
| `cases` | No (can be empty?) | Included | Check parser |
| `inferredType` | Yes | Excluded | Correct |
| `sourceLocation` | Yes | Excluded | Correct |

**Question:** Can `cases` be empty? Parser probably requires at least one case.

---

## MatchCase

```kotlin
data class MatchCase(
    val patterns: List<Pattern>,
    val guard: Expression? = null,
    val expression: Expression,
    val ifLetBindings: List<IfLetGuard> = emptyList()
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `patterns` | No - at least 1 | Included | Correct |
| `guard` | Yes | Included | Correct |
| `expression` | No | Included | Correct |
| `ifLetBindings` | No (empty list) | Included | Correct |

**Status:** Correct.

---

## DoExpression

```kotlin
data class DoExpression(
    val statements: List<Statement>,
    val result: Expression,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `statements` | No (can be empty) | Included | Correct |
| `result` | No | Included | Correct |
| `inferredType` | Yes | Excluded | Correct |
| `sourceLocation` | Yes | Excluded | Correct |

**Status:** Correct.

---

## PerformExpression

```kotlin
data class PerformExpression(
    val expression: Expression,
    val handlers: List<PerformHandler>,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null,
    val resolvedHandlers: Map<String, ResolvedEffectHandler> = emptyMap()
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `expression` | No | Included | Correct |
| `handlers` | No (at least 1?) | Included | Correct |
| `inferredType` | Yes | Excluded | Correct |
| `sourceLocation` | Yes | Excluded | Correct |
| `resolvedHandlers` | No (empty map) | EXCLUDED | Correct |

**Status:** Correct. `resolvedHandlers` is computed, excluded from hash.

---

## PerformHandler

```kotlin
data class PerformHandler(
    val performer: QualifiedRef,
    val parameters: RecordLiteral? = null
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `performer` | No | Included | Correct - uses QualifiedRef! |
| `parameters` | Yes | Included | Correct |

**Status:** Correct! Uses structured `QualifiedRef` for performer.

---

## EffectCallExpression

```kotlin
data class EffectCallExpression(
    val operation: QualifiedRef,
    val arguments: Expression,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
)
```

| Field | Nullable | Hashing | Status |
|-------|----------|---------|--------|
| `operation` | No | Included | Correct - uses QualifiedRef! |
| `arguments` | No | Included | Correct |
| `inferredType` | Yes | Excluded | Correct |
| `sourceLocation` | Yes | Excluded | Correct |

**Status:** Correct! Uses structured `QualifiedRef` for operation.

---

## Summary So Far

### Consistent Patterns:

1. **Semantic fields** (included in hash): Core syntax structure
2. **`inferredType: MezzeType? = null`**: Always nullable, excluded from hash
3. **`sourceLocation: SourceLocation? = null`**: Always nullable, excluded from hash
4. **Resolved/computed fields**: Always excluded from hash (resolution, resolvedHandlers, etc.)

### Issues Found:

| Node | Field | Issue |
|------|-------|-------|
| `RecordLiteral` | `restField: String?` | Should be `isOpen: Boolean` |
| `RecordLiteral` | spread position | Not validated (SeqLiteral does validate) |
| `FunctionCall` | `arguments: Expression` | Should be `RecordLiteral` |
| `FunctionCall` | `resolvedFunctionName` | Redundant with `resolvedFunctionContentHash`? |
| `VariantConstruction` | `constructor: String` | Could be `QualifiedRef`? |
| `StringLiteral` | `value` + `parts` | Redundant when interpolated |

### Good Practices Found:

| Node | Field | Good Practice |
|------|-------|---------------|
| `PerformHandler` | `performer: QualifiedRef` | Uses structured type! |
| `EffectCallExpression` | `operation: QualifiedRef` | Uses structured type! |
| `Identifier` | `qualifier: ModulePath?` | Uses structured type! |
| `SeqLiteral` | spread validation | Parser validates spread-first |

---

---

## Declaration Nodes

### TypeDeclaration

```kotlin
data class TypeDeclaration(
    val name: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val typeParams: List<String> = emptyList(),
    val definition: TypeDefinition,
    val inferredType: MezzeType? = null,
    val sourceLocation: SourceLocation? = null,
    val contentHash: String? = null
)
```

| Field | Parser | Nullable | Hashing | Notes |
|-------|--------|----------|---------|-------|
| `name` | Set | No | NOT included | Name is external identifier, not part of structure |
| `visibility` | Set | No | NOT included | Same - external |
| `typeParams` | Set | No | Included | Part of type definition |
| `definition` | Set | No | Included | Core structure |
| `inferredType` | null | Yes | Excluded | |
| `sourceLocation` | Set | Yes | Excluded | |
| `contentHash` | null | Yes | OUTPUT | This IS the hash result |

**Important:** `name` and `visibility` are NOT included in hash - the hash represents the STRUCTURE, not the name.

### EffectDeclaration

```kotlin
data class EffectDeclaration(
    val name: String,
    val operations: List<EffectOperation>,
    val associatedTypes: List<String> = emptyList(),
    val inferredType: MezzeType? = null,
    val sourceLocation: SourceLocation? = null,
    val contentHash: String? = null
)
```

| Field | Parser | Nullable | Hashing | Notes |
|-------|--------|----------|---------|-------|
| `name` | Set | No | NOT included | Name is identifier |
| `operations` | Set | No | Included (sorted by name) | |
| `associatedTypes` | Set | No | Included (sorted) | |
| `inferredType` | null | Yes | Excluded | |
| `sourceLocation` | Set | Yes | Excluded | |
| `contentHash` | null | Yes | OUTPUT | |

**Note:** Effects are always public (no visibility field).

### AbilityDeclaration

```kotlin
data class AbilityDeclaration(
    val name: String,
    val typeParams: List<String>,
    val associatedTypes: List<String> = emptyList(),
    val constraints: List<AbilityConstraint> = emptyList(),
    val dependencies: List<AbilityDependency> = emptyList(),
    val operations: List<AbilityOperation>,
    val inferredType: MezzeType? = null,
    val sourceLocation: SourceLocation? = null,
    val contentHash: String? = null
)
```

| Field | Hashing | Notes |
|-------|---------|-------|
| `name` | NOT included | |
| `typeParams` | Included | |
| `associatedTypes` | Included (sorted) | |
| `constraints` | Included (sorted by abilityName) | |
| `dependencies` | Included (sorted) | |
| `operations` | Included (sorted by name) | |
| `inferredType` | Excluded | |
| `sourceLocation` | Excluded | |
| `contentHash` | OUTPUT | |

**Note:** Abilities are always public.

### ImplDeclaration (Effect Implementation)

```kotlin
data class ImplDeclaration(
    val effect: QualifiedRef,
    val performerName: String,
    val performerParams: List<PerformerParameter> = emptyList(),
    val associatedTypeBindings: Map<String, TypeAnnotation> = emptyMap(),
    val operations: List<PerformerOperation>,
    val inferredType: MezzeType? = null,
    val sourceLocation: SourceLocation? = null,
    val contentHash: String? = null
)
```

| Field | Hashing | Notes |
|-------|---------|-------|
| `effect` | Included (localName + qualifier segments) | Uses QualifiedRef! |
| `performerName` | Included | |
| `performerParams` | Included (sorted by name) | |
| `associatedTypeBindings` | Included (sorted by key) | |
| `operations` | Included (sorted by name) | |
| `inferredType` | Excluded | |
| `sourceLocation` | Excluded | |
| `contentHash` | OUTPUT | |

### ImplAbilityDeclaration (Ability Implementation)

```kotlin
data class ImplAbilityDeclaration(
    val ability: QualifiedRef,
    val targetTypes: List<TypeAnnotation>,
    val forallVars: List<String> = emptyList(),
    val associatedTypeBindings: Map<String, TypeAnnotation> = emptyMap(),
    val operations: List<ImplAbilityOperation>,
    val inferredType: MezzeType? = null,
    val sourceLocation: SourceLocation? = null,
    val contentHash: String? = null
)
```

| Field | Hashing | Notes |
|-------|---------|-------|
| `ability` | Included (localName + qualifier) | Uses QualifiedRef! |
| `targetTypes` | Included | |
| `forallVars` | Included | |
| `associatedTypeBindings` | Included (sorted by key) | |
| `operations` | Included (sorted by name) | |
| `inferredType` | Excluded | |
| `sourceLocation` | Excluded | |
| `contentHash` | OUTPUT | |

### UseDeclaration, PubUseDeclaration

```kotlin
data class UseDeclaration(
    val path: UsePath,
    val alias: String? = null,
    val sourceLocation: SourceLocation? = null
)
```

| Field | Hashing | Notes |
|-------|---------|-------|
| `path` | Included | Uses UsePath (ModulePath or SelectivePath) |
| `alias` | Included | |
| `sourceLocation` | Excluded | |

**Status:** Uses structured `UsePath` type.

---

## Complete Summary

### All Issues Found:

| # | Node | Field | Current | Proposed | Impact |
|---|------|-------|---------|----------|--------|
| 1 | `RecordLiteral` | `restField` | `String?` ("..." or null) | `isOpen: Boolean` | Minor |
| 2 | `RecordLiteral` | spread position | Not validated | Validate spread-first (like SeqLiteral) | Parser change |
| 3 | `FunctionCall` | `arguments` | `Expression` | `RecordLiteral` | Type safety |
| 4 | `FunctionCall` | `resolvedFunctionName` | `String?` | Remove (redundant) | Cleanup |
| 5 | `LetBinding` | `originalFunctionName` | `String?` | Use content hash? | Cleanup |
| 6 | `VariantConstruction` | `constructor` | `String` | Consider `QualifiedRef` | Future |
| 7 | `StringLiteral` | `value` + `parts` | Both present | Sealed class? | Cleanup |
| 8 | `RecordEntry` | - | Exists | DELETE (dead code) | Cleanup |
| 9 | `RecordUpdate` | - | Exists | DELETE (dead code) | Cleanup |
| 10 | `RecordUpdateExpression` | - | Exists | DELETE (dead code) | Cleanup |
| 11 | `RecordUpdateEntry` | - | Exists | DELETE (dead code) | Cleanup |
| 12 | `PlaceholderExpression` | - | Exists | DELETE (dead code) | Cleanup |

### Hashing Patterns Verified:

1. **Semantic fields** are included in hash
2. **Names/visibility** of declarations are NOT included (hash represents structure)
3. **Lists are sorted** before hashing (determinism)
4. **Computed fields** (`inferredType`, `resolvedX`, `sourceLocation`) are excluded
5. **`contentHash` field** is the OUTPUT, not input

### Good Practices Found:

1. `QualifiedRef` used in: `PerformHandler.performer`, `EffectCallExpression.operation`, `ImplDeclaration.effect`, `ImplAbilityDeclaration.ability`
2. `ModulePath` used in: `Identifier.qualifier`
3. `UsePath` used in: `UseDeclaration.path`, `PubUseDeclaration.path`
4. Spread validation exists for `SeqLiteral` - add same for `RecordLiteral`

### Priority Actions:

**Phase 1 (Safe cleanup):**
1. Delete dead code: `RecordEntry`, `RecordUpdate`, `RecordUpdateExpression`, `RecordUpdateEntry`, `PlaceholderExpression`

**Phase 2 (Parser fix):**
2. Add `validateRecordSpreadPosition()` like `validateSeqSpreadPosition()`

**Phase 3 (Type improvements):**
3. Change `RecordLiteral.restField: String?` to `isOpen: Boolean`
4. Change `FunctionCall.arguments: Expression` to `RecordLiteral`

**Phase 4 (Redundancy cleanup):**
5. Remove `FunctionCall.resolvedFunctionName` if `resolvedFunctionContentHash` is sufficient
6. Review `LetBinding.originalFunctionName` usage
