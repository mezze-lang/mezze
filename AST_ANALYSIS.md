# AST Node Analysis

Systematic analysis of every AST node to identify:
- Dead code (not used by parser, typechecker, or interpreter)
- String fields that should be structured types
- Nullable vs non-nullable correctness
- Syntax enforcement issues

---

## Expression.kt

### 1. ResolvedAbility
- **Status**: USED
- **Parser**: No (populated by typechecker)
- **TypeChecker**: Yes (ability resolution)
- **Interpreter**: Yes (ability dispatch)
- **Issues**: None

### 2. ResolvedEffectHandler
- **Status**: USED
- **Parser**: No (populated by typechecker)
- **TypeChecker**: Yes (effect resolution)
- **Interpreter**: Yes (effect dispatch)
- **Issues**:
  - `effectName: String` - could be `QualifiedRef`
  - `performerName: String` - could be `QualifiedRef`

### 3. IntLiteral, NatLiteral, FloatLiteral, BooleanLiteral, FractionLiteral
- **Status**: USED
- **Issues**: None

### 4. StringLiteral
- **Status**: USED
- **Issues**: None - `parts` being nullable is correct (null = simple, non-null = interpolated)

### 5. StringPart, ExpressionPart
- **Status**: USED
- **Issues**: None

### 6. SeqLiteral, SeqElement, SeqRegularElement, SeqSpreadElement
- **Status**: USED
- **Issues**: None

### 7. SeqAccess
- **Status**: USED
- **Issues**: None

### 8. Identifier
- **Status**: USED
- **Issues**: None - recently fixed to use structured `localName` + `qualifier: ModulePath?`

### 9. PlaceholderExpression
- **Status**: USED (temporary during pipe desugaring)
- **Issues**: Should never appear in final AST - is this enforced?

### 10. ParenthesizedExpression
- **Status**: USED
- **Issues**: None

### 11. RecordLiteral
- **Status**: USED
- **Issues**:
  - `restField: String?` - what is this? Is it used? Different from spread entries.
  - **SYNTAX**: Spread entries (`...old_rec`) can appear anywhere, but should only be at START

### 12. RecordFieldEntry
- **Status**: USED
- **Issues**: `value: Expression?` nullable is correct (null = punning `{x}`)

### 13. RecordSpreadEntry
- **Status**: USED
- **Issues**:
  - **SYNTAX**: Should only be allowed at START of record literal, not middle/end

### 14. RecordEntry ⚠️
- **Status**: DEAD CODE
- **Parser**: Never creates this
- **TypeChecker**: Never uses this
- **Interpreter**: Never uses this
- **Action**: DELETE

### 15. MemberAccess
- **Status**: USED
- **Issues**: `member: String` - correct, simple field name

### 16. RecordUpdate ⚠️
- **Status**: DEAD CODE
- **Parser**: Never creates this (uses RecordLiteral + RecordSpreadEntry instead)
- **TypeChecker**: Never uses this
- **Interpreter**: Never uses this
- **Action**: DELETE

### 17. RecordUpdateExpression ⚠️
- **Status**: DEAD CODE
- **Parser**: Never creates this
- **Action**: DELETE

### 18. RecordUpdateEntry ⚠️
- **Status**: DEAD CODE
- **Used by**: RecordUpdateExpression (which is dead)
- **Action**: DELETE

### 19. ConstructorResolution (sealed class)
- **Status**: USED
- **Issues**: None

### 20. VariantConstruction
- **Status**: USED
- **Issues**: `constructor: String` - correct for now (parser creates this)

### 21. LambdaExpression
- **Status**: USED
- **Issues**: None

### 22. TypedParameters, UntypedParameters
- **Status**: USED
- **Issues**: None

### 23. FunctionCall
- **Status**: USED
- **Issues**:
  - `isTailCall: Boolean` - is this read anywhere? Or only written?
  - `resolvedFunctionName: String?` - could be structured?

### 24. IfExpression, MatchExpression, MatchCase, IfLetGuard
- **Status**: USED
- **Issues**: None

### 25. DoExpression
- **Status**: USED
- **Issues**: None

### 26. PerformExpression
- **Status**: USED
- **Issues**: None

### 27. PerformHandler
- **Status**: USED
- **Issues**: None - uses `QualifiedRef` for performer (good!)

### 28. EffectCallExpression
- **Status**: USED
- **Issues**: None - uses `QualifiedRef` for operation (good!)

### 29. StateMachineExpression, ComputeState, YieldState, CompleteState
- **Status**: USED (compiler-generated during continuation transformation)
- **Parser**: No (compiler creates these)
- **Issues**: None

### 30. LetStatement, ExpressionStatement
- **Status**: USED
- **Issues**: None

---

## Pattern.kt

### 1. WildcardPattern
- **Status**: USED
- **Issues**: `dummy: Unit = Unit` - why is this needed? Serialization?

### 2. VariablePattern
- **Status**: USED
- **Issues**: None

### 3. ConstructorPattern
- **Status**: USED
- **Issues**: `constructor: String` - correct

### 4. RecordPattern, RecordPatternEntry
- **Status**: USED
- **Issues**:
  - `isOpen: Boolean` - for `{x, ...}` syntax at END
  - This is CORRECT behavior for patterns

### 5. LiteralPattern
- **Status**: USED
- **Issues**: None

### 6. SeqPattern
- **Status**: USED
- **Issues**: None

### 7. StringInterpolationPattern, StringPatternLiteral, StringPatternCapture
- **Status**: USED
- **Issues**: None

### 8. LiteralValue (IntLiteralValue, etc.)
- **Status**: USED
- **Issues**: None

---

## Type.kt

### 1. MezzeType (all variants)
- **Status**: USED
- **Issues**: None for most

### 2. TypeApplication
- **Status**: USED
- **Issues**: `constructor: String` - should this be `QualifiedRef` or `ModulePath`?

### 3. EffectInstance
- **Status**: USED
- **Issues**: `name: String` - should this be `QualifiedRef`?

### 4. TypeScheme
- **Status**: USED
- **Issues**: None

### 5. UsageContext (all variants)
- **Status**: USED
- **Issues**: None

### 6. UsageConstraint
- **Status**: USED
- **Issues**: None

---

## Declaration.kt

### 1. Comment
- **Status**: USED
- **Issues**: None

### 2. Program
- **Status**: USED
- **Issues**: None

### 3. ResolvedImport
- **Status**: USED
- **Issues**: `modulePath: String` - should be `ModulePath`?

### 4. TypeCheckResult, TypeCheckWarning
- **Status**: USED
- **Issues**: None

### 5. LetBinding
- **Status**: USED
- **Issues**: None

### 6. TypeDeclaration
- **Status**: USED
- **Issues**: `name: String` - correct

### 7. RecordDefinition, VariantDefinition, NewtypeDefinition
- **Status**: USED
- **Issues**: None

### 8. RecordFieldDef, VariantConstructorDef
- **Status**: USED
- **Issues**: None

### 9. EffectDeclaration
- **Status**: USED
- **Issues**: `name: String` - correct

### 10. EffectOperation
- **Status**: USED
- **Issues**: None

### 11. ImplDeclaration
- **Status**: USED
- **Issues**: Uses `QualifiedRef` for effect (good!)

### 12. PerformerParameter, PerformerOperation
- **Status**: USED
- **Issues**: None

### 13. AbilityDeclaration
- **Status**: USED
- **Issues**: `name: String` - correct

### 14. AbilityConstraint
- **Status**: USED
- **Issues**: `abilityName: String`, `typeParam: String` - correct

### 15. FunctionAbilityConstraint
- **Status**: USED
- **Issues**: `abilityName: String` - could be `QualifiedRef`?

### 16. InspectAbilityConstraint
- **Status**: USED?
- **Issues**: Check if this is actually used

### 17. AbilityDependency
- **Status**: USED
- **Issues**: None

### 18. AbilityOperation
- **Status**: USED
- **Issues**: None

### 19. ImplAbilityDeclaration
- **Status**: USED
- **Issues**: Uses `QualifiedRef` for ability (good!)

### 20. ImplAbilityOperation
- **Status**: USED
- **Issues**: None

### 21. TopLevelExpression
- **Status**: USED
- **Issues**: None

### 22. UseDeclaration, PubUseDeclaration
- **Status**: USED
- **Issues**: Uses `UsePath` (good!)

---

## TypeAnnotation.kt

### 1. IdentifierTypeAnnotation
- **Status**: USED
- **Issues**: `name: String` - correct for simple type names

### 2. QualifiedTypeAnnotation
- **Status**: USED
- **Issues**: `modulePath: List<String>` - should be `ModulePath`?

### 3. TypeApplicationAnnotation
- **Status**: USED
- **Issues**: `constructor: String` - correct

### 4. RecordTypeAnnotation
- **Status**: USED
- **Issues**: None

### 5. VariantTypeAnnotation
- **Status**: USED
- **Issues**: None

### 6. FunctionTypeAnnotation
- **Status**: USED
- **Issues**: None

### 7. SeqTypeAnnotation
- **Status**: USED
- **Issues**: None

### 8. EffectAnnotation
- **Status**: USED
- **Issues**: `name: String` - could be `QualifiedRef`?

### 9. EffectRowAnnotation
- **Status**: USED
- **Issues**: None

### 10. RecordTypeEntry, VariantTypeConstructor
- **Status**: USED
- **Issues**: None

---

## Common.kt

### 1. TypeVar
- **Status**: USED
- **Issues**: None

### 2. RowVar
- **Status**: USED
- **Issues**: None

### 3. SourceLocation
- **Status**: USED
- **Issues**: None

### 4. SyntaxMetadata
- **Status**: USED
- **Issues**: None

### 5. Visibility
- **Status**: USED
- **Issues**: None

### 6. UsePath (sealed), ModulePath, SelectivePath
- **Status**: USED
- **Issues**: None

### 7. ImportItemKind
- **Status**: USED (recently added)
- **Issues**: None

### 8. ImportItem
- **Status**: USED
- **Issues**: None

### 9. BindingKey
- **Status**: USED
- **Issues**: None

### 10. QualifiedRef
- **Status**: USED
- **Issues**: None

---

## Summary

### Dead Code to Delete:
1. `RecordEntry` (Expression.kt:374-377)
2. `RecordUpdate` (Expression.kt:390-395)
3. `RecordUpdateExpression` (Expression.kt:398-403)
4. `RecordUpdateEntry` (Expression.kt:406-409)

### Syntax Issues to Fix:
1. **Record literal spread**: `{...old, field = new}` should enforce spread FIRST
   - Currently parser allows `{field = new, ...old}` which is wrong
   - Pattern destructuring `{x, ...}` is correct (... at END)

### String Fields to Consider Structuring:
1. `ResolvedEffectHandler.effectName` → `QualifiedRef`?
2. `ResolvedEffectHandler.performerName` → `QualifiedRef`?
3. `TypeApplication.constructor` → `QualifiedRef`?
4. `EffectInstance.name` → `QualifiedRef`?
5. `QualifiedTypeAnnotation.modulePath: List<String>` → `ModulePath`
6. `ResolvedImport.modulePath: String` → `ModulePath`

---

## Action Plan

### Phase 1: Delete Dead Code (Safe, No Behavior Change)
1. Delete `RecordEntry` (Expression.kt:374-377)
2. Delete `RecordUpdate` (Expression.kt:390-395)
3. Delete `RecordUpdateExpression` (Expression.kt:398-403)
4. Delete `RecordUpdateEntry` (Expression.kt:406-409)
5. Delete `PlaceholderExpression` (Expression.kt:266-270) and all handlers

### Phase 2: Syntax Enforcement
1. Record literal spread must come FIRST
   - `{...old, x = new}` ✓ allowed
   - `{x = new, ...old}` ✗ should error
   - Update `recordLiteral` parser to validate entry order

### Phase 3: Structured Types (More Invasive)
Lower priority - these work but could be cleaner:
1. `QualifiedTypeAnnotation.modulePath` → `ModulePath`
2. `ResolvedImport.modulePath` → `ModulePath`
3. Consider `TypeApplication.constructor` for qualified type names

### Investigation Results:

1. **`FunctionCall.isTailCall`** - USED
   - Set by: `SubstitutionApplier.kt:1227` (typechecker detects tail calls)
   - Read by: `ASTToTruffleConverterV2.kt:1262` (interpreter creates TailCallNode)
   - Status: Correct, used for TCO

2. **`PlaceholderExpression`** - DEAD CODE?
   - Parser creates `Identifier("@")` for @ placeholder (ExpressionParsers.kt:2114)
   - `desugarPipe` replaces `Identifier("@")` with piped value (ExpressionParsers.kt:1536)
   - `PlaceholderExpression` type exists but is NEVER CREATED by parser
   - Typechecker has handler for it (ExpressionConstraints.kt:442) but says "should be replaced during parsing"
   - **Action**: Verify no code creates it, then DELETE

3. **`WildcardPattern.dummy: Unit`** - needed for Kotlin serialization of object-like data class

4. **`InspectAbilityConstraint`** - USED
   - Used by `TypePrinter.kt` for displaying constraints in error messages/debugging
   - Used in tests for constraint display
   - Status: Keep, but only for display purposes

5. **`RecordLiteral.restField`** vs spread:
   - `restField: String?` = row polymorphism marker `{x = 1, ...}` (open record TYPE)
   - `RecordSpreadEntry` = spread existing record VALUES `{...old_rec, x = 1}`
   - These are DIFFERENT features:
     - `restField` = type-level "this record may have more fields" (for function params)
     - `RecordSpreadEntry` = value-level "copy fields from another record"
   - Current parser allows BOTH in record literals which may be confusing
   - **QUESTION**: Should `restField` (`{x = 1, ...}`) be allowed in record LITERALS?
     Or only in type annotations and patterns?
