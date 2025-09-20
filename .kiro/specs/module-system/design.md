# Funk Module System Design

## Overview

This document outlines the design for Funk's module system, focusing on simplicity, clarity, and seamless integration with the existing type system.

## Core Principles

1. **File-based modules**: Each `.funk` file is a module
2. **snake_case naming**: Consistent naming convention for files and folders
3. **Hierarchical organization**: Directory structure maps to module hierarchy using `::`
4. **Private by default**: Safe encapsulation with opt-in `pub` visibility
5. **Internal modules**: Special `internal/` folders for module-private code
6. **Minimal syntax**: Clean import/export syntax without boilerplate
7. **Top-level imports only**: All `use` statements must be at the top of the file

## 1. Files & Folders → Modules

Each file becomes a module with snake_case names for files and folders.
Module path = file path using `::` separator.

```
src/
├── main.funk                    → main
├── utils.funk                   → utils
├── math/
│   ├── geometry.funk           → math::geometry
│   ├── vector_ops.funk         → math::vector_ops
│   └── internal/
│       └── helpers.funk        → math::internal::helpers (private to math)
└── data_structures/
    ├── binary_tree.funk        → data_structures::binary_tree
    └── hash_map.funk           → data_structures::hash_map
```

## 2. Internal Modules

Any folder named `internal/` marks its modules as private to their parent module.

- Code inside `math/geometry.funk` can use `math::internal::helpers`
- Code outside the `math` module cannot access `math::internal::*`

```
math/
├── geometry.funk        # Can access math::internal::helpers
├── algebra.funk         # Can access math::internal::helpers  
└── internal/
    └── helpers.funk     # Only accessible within math module
```

## 3. Imports

Clean, flexible import syntax:

```funk
use math::geometry                  # import full module
use math::geometry::{area, Shape}   # import selected names
use math::geometry as geo           # import with alias
use math::geometry::{area} as geo   # import + alias
```

## 4. Visibility

**Private by default** - Add `pub` to expose at the module boundary:

```funk
# math/geometry.funk
pub type Shape = | Circle { radius: Float }, Square { size: Float } |

pub let area = { shape: Shape } -> match shape is
  Circle { radius } -> 3.14 * radius * radius
  Square { size } -> size * size

let perimeter = { shape: Shape } -> ...   # private - not exported
```

## 5. Re-exports

Re-export another module or its members with minimal syntax:

```funk
# math.funk - Main math module
pub math::geometry                  # re-export whole module
pub math::geometry::{Shape, area}   # re-export selected items
pub math::geometry as geo           # re-export with alias
```

## 6. Usage Examples

### Example 1: Basic Geometry Module

```funk
# src/math/geometry.funk
pub type Shape = | Circle { radius: Float }, Square { size: Float } |

pub let area = { shape: Shape } ->
  match shape is
    Circle { radius } -> do
      3.14 * radius * radius
    Square { size } -> do
      size * size

pub let perimeter = { shape: Shape } ->
  match shape is
    Circle { radius } -> do
      2.0 * 3.14 * radius
    Square { size } -> do
      4.0 * size
```

### Example 2: Math Module Re-exports

```funk
# src/math.funk
pub math::geometry::{Shape, area}
pub math::vector_ops as vec
pub math::algebra::{solve, Equation}
```

### Example 3: Main Application

```funk
# src/main.funk
use math

let circle = Circle { radius = 3.0 }  # Circle constructor available via math module
let circleArea = math::area { shape = circle }

let v1 = { x = 1, y = 2 }
let v2 = { x = 3, y = 4 }
let dotProduct = math::vec::dot { v1, v2 }

do
  print { text = "Circle area: " ++ floatToString { val = circleArea } }
  print { text = "Dot product: " ++ intToString { val = dotProduct } }
```

### Example 4: Internal Helpers

```funk
# src/math/internal/helpers.funk
let validatePositive = { value: Float } ->
  if value <= 0.0 then
    error { message = "Value must be positive" }
  else
    value

let PI = 3.14159265359
```

```funk
# src/math/geometry.funk
use math::internal::helpers::{validatePositive, PI}

pub type Shape = | Circle { radius: Float }, Square { size: Float } |

pub let area = { shape: Shape } ->
  match shape is
    Circle { radius } -> do
      let validRadius = validatePositive { value = radius }
      PI * validRadius * validRadius
    Square { size } -> do
      let validSize = validatePositive { value = size }
      validSize * validSize
```

```funk
# src/main.funk
use math::geometry::{Shape, area}
# use math::internal::helpers  # ✗ Error: Cannot access internal module

let circle = Circle { radius = 5.0 }  # Circle constructor available when Shape is imported
let result = area { shape = circle }
```

## Integration with Type System

### Type Visibility

```funk
# shapes.funk
pub type PublicShape = | Circle { radius: Float } |  # Circle constructor also exported
type PrivateHelper = { x: Float, y: Float }  # Not exported, constructors not available

pub let createCircle = { radius: Float } -> Circle { radius = radius }
```

### Effect Visibility

```funk
# io.funk
pub effect FileIO {
  read: { path: String } -> String <FileIO>
  write: { path: String, content: String } -> {} <FileIO>
}

effect InternalLogging {  # Private effect
  log: { message: String } -> {} <InternalLogging>
}
```

### Ability Visibility

```funk
# collections.funk
pub ability Iterable 'a {
  iterate: { collection: 'a } -> Iterator 'b
}

ability InternalCache 'a {  # Private ability
  cache: { key: String, value: 'a } -> 'a
}
```

## Error Handling

### Import Errors

```funk
use non_existent::module           # Error: Module not found
use math::geometry::{BadType}      # Error: 'BadType' not exported
use math::internal::helpers        # Error: Cannot access internal module

# Error: use statements must be at top of file
let x = 5
use math::geometry                 # ✗ Error: use statements must come before other declarations
```

### Circular Dependencies

```funk
# a.funk
use b::{something}

# b.funk  
use a::{something_else}  # Error: Circular dependency detected
```

## Standard Library Integration

```funk
use std::collections::{List, Map, Set}
use std::io::{print, read_line}
use std::math::{sin, cos, sqrt}
use std::string::{length, substring}
```

## Implementation Phases

### Phase 1: Core Module System
- [x] File-based modules with snake_case naming
- [x] Basic `use` import syntax
- [x] `pub` visibility modifier
- [x] Module path resolution with `::`

### Phase 2: Advanced Features  
- [ ] Re-exports with `pub use`
- [ ] Internal modules (`internal/` folders)
- [ ] Module aliases
- [ ] Import with selective exports `{item1, item2}`

### Phase 3: Tooling Integration
- [ ] IDE support for auto-imports
- [ ] Module dependency analysis
- [ ] Refactoring support across modules

### Phase 4: Package Management
- [ ] External dependencies
- [ ] Version management
- [ ] Package registry

## Project Structure

```
my_project/
├── funk.toml                    # Project configuration
├── src/
│   ├── main.funk               # Entry point
│   ├── lib.funk                # Library entry point  
│   ├── math/
│   │   ├── geometry.funk
│   │   ├── vector_ops.funk
│   │   └── internal/
│   │       └── helpers.funk
│   └── data_structures/
│       ├── binary_tree.funk
│       └── hash_map.funk
├── tests/
│   └── integration_tests.funk
└── examples/
    └── basic_usage.funk
```

## AST Extensions

```kotlin
// Visibility modifier for declarations
enum class Visibility {
    PUBLIC,    // pub - visible to other modules
    PRIVATE    // default - only visible within module
}

// Updated declarations with visibility
@Serializable
data class LetBinding(
    val variableName: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val typeAnnotation: TypeAnnotation? = null,
    val inferredType: FunkType? = null,
    val expression: Expression
) : ProgramItem

@Serializable
data class TypeDeclaration(
    val name: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val typeParams: List<String> = emptyList(),
    val definition: TypeDefinition,
    val inferredType: FunkType? = null
) : ProgramItem

// Import declarations
@Serializable
data class UseDeclaration(
    val path: UsePath,
    val alias: String? = null
) : ProgramItem

@Serializable
@JsonClassDiscriminator("usePathKind")
sealed interface UsePath

@Serializable
data class ModulePath(val segments: List<String>) : UsePath  // math::geometry

@Serializable
data class SelectivePath(
    val module: List<String>,
    val items: List<String>
) : UsePath  // math::geometry::{area, Shape}

// Re-export declarations
@Serializable
data class PubUseDeclaration(
    val path: UsePath,
    val alias: String? = null
) : ProgramItem
```

## Grammar Extensions

```antlr
// Add to program rule - use statements must come first
program: useDeclaration* pubUseDeclaration* (statement | NEWLINE)* EOF;

// Update existing declarations to support pub keyword
letBinding:
    PUB? LET Identifier (COLON typeAnnotation)? ASSIGN expression NEWLINE?
    ;

typeDeclaration:
    PUB? TYPE Identifier typeParams? ASSIGN typeDefinition NEWLINE?
    ;

effectDeclaration:
    PUB? EFFECT Identifier LBRACE effectOperation* RBRACE NEWLINE?
    ;

abilityDeclaration:
    PUB? ABILITY Identifier typeParams? abilityConstraints? LBRACE abilityOperation* RBRACE NEWLINE?
    ;

// Use declarations
useDeclaration:
    USE usePath (AS Identifier)? NEWLINE
    ;

// Re-export declarations
pubUseDeclaration:
    PUB usePath (AS Identifier)? NEWLINE
    ;

usePath:
    moduleSegments                          // math::geometry
    | moduleSegments DOUBLE_COLON LBRACE useList RBRACE  // math::geometry::{area, Shape}
    ;

moduleSegments:
    Identifier (DOUBLE_COLON Identifier)*   // math or math::geometry
    ;

useList:
    Identifier (COMMA Identifier)*
    ;

// Keywords
USE: 'use';
PUB: 'pub';
AS: 'as';
DOUBLE_COLON: '::';
```

This module system provides a clean, intuitive way to organize Funk code while maintaining the language's principles of simplicity, type safety, and full type inference.