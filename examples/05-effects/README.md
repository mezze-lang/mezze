# Effects - Algebraic Effects and Handlers

Learn Funk's algebraic effect system for managing side effects in a functional way.

## Examples in this folder

### [console-effect.funk](./console-effect.funk)
Basic console I/O using effects.
- Defining effects
- Effect implementations
- Performing effects

### [error-handling.funk](./error-handling.funk)
Error handling with effects.
- Error effects
- Exception-like behavior
- Graceful error recovery

### [state-effect.funk](./state-effect.funk)
State management through effects.
- Stateful computations
- State threading
- Functional state updates

## Key Concepts Covered

- **Effect Definitions**: `effect Console { print: ... }`
- **Effect Implementations**: `impl Console for ...`
- **Effect Handlers**: `perform Handler in ...`
- **Effect Composition**: Combining multiple effects

## Effect Syntax Reference

```funk
# Effect definition
effect Console {
  print: {message: String} -> Unit <>
}

# Effect implementation
impl Console for ConsoleLogger where {
  print: {message: String} -> Unit = {message} -> unit
}

# Using effects
let program = do
  Console.print {message = "Hello, Effects!"}
```

## Running the Examples

```bash
funk-dev exec console-effect.funk
funk-dev exec error-handling.funk
```

## Next Steps

- **[Advanced](../06-advanced/)** - Complex effect patterns
- **[Language Guide](../../docs/language-guide/effects.md)** - Detailed effect documentation
