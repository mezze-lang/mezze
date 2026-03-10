# Import Resolution Refactoring Plan

## Goal
Make `::` purely a parser concern. After parsing, all resolution uses structured paths and content hashes.

## Design Principles

1. **Parser owns `::`** - Only the parser knows about `::` syntax
2. **Paths are lists** - `["std", "effects", "log", "Console"]` not `"std::effects::log::Console"`
3. **Content hash addressing** - Runtime lookups use content hashes, not names
4. **No string reconstruction** - Never join paths with `::` for lookups

## QualifiedRef Simplification

Current:
```kotlin
data class QualifiedRef(
    val localName: String,
    val qualifier: ModulePath?,
    val resolvedContentHash: String?,
    val sourceLocation: SourceLocation?
)
```

Simplified:
```kotlin
data class QualifiedRef(
    val path: List<String>,  // ["std", "effects", "log", "Console"]
    val resolvedContentHash: String? = null,
    val sourceLocation: SourceLocation? = null
) {
    val localName: String get() = path.last()
    val qualifierSegments: List<String> get() = path.dropLast(1)
    val isQualified: Boolean get() = path.size > 1
}
```

## Import Scenarios

### 1. Full path (no import needed)
```mezze
perform std::effects::log::Console in std::effects::log::info {msg = "hello"}
```
- Parser produces: `QualifiedRef(path = ["std", "effects", "log", "Console"])`
- Resolver: traverses path segments to find module, then item

### 2. Module import + qualified access
```mezze
use std::effects::log

perform log::Console in log::info {msg = "hello"}
```
- Parser produces: `QualifiedRef(path = ["log", "Console"])`
- Resolver: `"log"` is import alias → maps to `["std", "effects", "log"]` → resolve `"Console"` there

### 3. Selective import (unqualified access)
```mezze
use std::effects::log::{Console, info}

perform Console in info {msg = "hello"}
```
- Parser produces: `QualifiedRef(path = ["Console"])`
- Resolver: `"Console"` in selective imports → directly resolve with content hash

### 4. Aliased import
```mezze
use std::effects::log as logging

perform logging::Console in logging::info {msg = "hello"}
```
- Parser produces: `QualifiedRef(path = ["logging", "Console"])`
- Resolver: `"logging"` is aliased import → maps to `["std", "effects", "log"]` → resolve `"Console"`

### 5. Aliased selective import
```mezze
use std::effects::log::{Console as StdConsole}

perform StdConsole in info {msg = "hello"}
```
- Parser produces: `QualifiedRef(path = ["StdConsole"])`
- Resolver: `"StdConsole"` in selective imports (was aliased from `Console`) → resolve with content hash

## Resolution Algorithm

```kotlin
fun resolve(ref: QualifiedRef, context: ResolutionContext): ResolvedRef {
    val path = ref.path

    if (path.size == 1) {
        // Unqualified: check local scope, then selective imports
        val name = path[0]
        return context.lookupUnqualified(name)
            ?: throw UnresolvedReferenceError(name)
    }

    // Qualified: first segment might be import alias
    val firstSegment = path[0]
    val remainingPath = path.drop(1)

    // Check if first segment is an import alias
    val resolvedModule = context.resolveImportAlias(firstSegment)

    if (resolvedModule != null) {
        // e.g., "log" → ["std", "effects", "log"]
        // Then resolve remaining path within that module
        return resolvedModule.resolve(remainingPath)
    }

    // Not an import alias - treat as absolute path
    // e.g., ["std", "effects", "log", "Console"]
    return context.resolveAbsolutePath(path)
}
```

## Implementation Tasks

### Phase 1: Simplify QualifiedRef
- [ ] Change QualifiedRef from `(localName, qualifier)` to `(path: List<String>)`
- [ ] Update all usages in AST (PerformHandler, ImplDeclaration, etc.)
- [ ] Update parser to construct new QualifiedRef format
- [ ] Update ASTHasher and ASTNormalizer

### Phase 2: Update AST Types
- [ ] PerformHandler.performer: QualifiedRef (done)
- [ ] ImplDeclaration.effect: QualifiedRef (in progress)
- [ ] EffectCallExpression: add operation: QualifiedRef
- [ ] ImplAbilityDeclaration.abilityName: QualifiedRef
- [ ] Remove all deprecated string getters after migration

### Phase 3: Fix Import Resolution
- [ ] ImportResolver: stop reconstructing `::` strings
- [ ] ImportResolver: work directly with path segments
- [ ] ModuleResolver: accept List<String> not joined strings
- [ ] Build import alias map: Map<String, List<String>>

### Phase 4: Content Hash Resolution
- [ ] TypeChecker: populate resolvedContentHash on QualifiedRef
- [ ] Store content hashes in ModuleImportContext
- [ ] Interpreter: use resolvedContentHash for O(1) lookup

### Phase 5: Remove `::` Reconstruction
- [ ] Remove `effectName` deprecated getter
- [ ] Remove `handler.name` deprecated getter
- [ ] Remove `fullPath` from ModulePath (or keep for display only)
- [ ] Audit all `.split("::")` and `.joinToString("::")` calls

## Test Plan

Create `typechecker/src/test/kotlin/.../ImportResolutionTest.kt`:

### Path Resolution Tests
1. Unqualified reference resolves from local scope
2. Unqualified reference resolves from selective import
3. Qualified reference with module alias resolves correctly
4. Full absolute path resolves without import
5. Aliased module import (`as`) resolves correctly
6. Aliased selective import (`as`) resolves correctly

### Effect/Performer Resolution Tests
7. `perform Console in ...` - unqualified performer
8. `perform log::Console in ...` - qualified performer with module alias
9. `perform std::effects::log::Console in ...` - full path performer
10. Effect call `log::info {msg}` - qualified operation
11. Effect call with full path `std::effects::log::info {msg}`

### Impl Declaration Resolution Tests
12. `impl Log for Console` - unqualified effect
13. `impl log::Log for Console` - qualified effect with alias
14. `impl std::effects::log::Log for Console` - full path effect

### Ability Resolution Tests
15. `impl Show for MyType` - unqualified ability
16. `impl std::Show for MyType` - qualified ability

### Content Hash Tests
17. Resolved references have content hash populated
18. Same item from different import paths gets same hash
19. Interpreter can lookup by content hash

### Error Cases
20. Unresolved unqualified reference
21. Unresolved qualified reference (bad alias)
22. Unresolved absolute path
23. Ambiguous import (same name from multiple modules)

## Files to Modify

### AST Module
- `ast/.../Common.kt` - QualifiedRef refactoring
- `ast/.../Expression.kt` - PerformHandler, EffectCallExpression
- `ast/.../Declaration.kt` - ImplDeclaration, ImplAbilityDeclaration
- `ast/.../hashing/ASTHasher.kt` - hash QualifiedRef paths
- `ast/.../hashing/ASTNormalizer.kt` - normalize QualifiedRef

### Parser Module
- `parser/.../ExpressionParsers.kt` - parseQualifiedRef
- `parser/.../DeclarationParsers.kt` - impl declarations

### TypeChecker Module
- `typechecker/.../module/ImportResolver.kt` - stop `::` reconstruction
- `typechecker/.../module/ModuleResolver.kt` - accept path lists
- `typechecker/.../constraints/ExpressionConstraints.kt` - resolve QualifiedRef
- `typechecker/.../constraints/EffectConstraints.kt` - effect resolution

### Interpreter Module
- `interpreter/.../ASTToTruffleConverterV2.kt` - use content hashes
