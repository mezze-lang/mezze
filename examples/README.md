# Funk Language Examples

This directory contains comprehensive examples of the Funk programming language, organized by feature category.

## Directory Structure

### `basic/`
- `hello_world.funk` - Basic literals, variables, and arithmetic
- `arithmetic_demo.funk` - Mathematical operations
- `boolean_demo.funk` - Boolean operations and logic
- `string_demo.funk` - String manipulation

### `functions/`
- `lambda_examples.funk` - Lambda functions and function calls
- `function_demo_working.funk` - Working function examples
- `pipe_*.funk` - Pipe operator examples
- `partial_application.funk` - Partial application examples

### `records/`
- `record_examples.funk` - Record creation, access, and punning
- `record_demo.funk` - Record manipulation
- `record_punning.funk` - Record punning syntax

### `variants/`
- `color_examples.funk` - Pattern matching with color variants
- `variant_syntax_demo.funk` - Variant syntax demonstrations
- `test_variants_correct.funk` - Correct variant usage

### `effects/`
- `simple_effect_example.funk` - Basic effect usage with constraint propagation
- `progressive_effect_handling.funk` - Step-by-step effect removal demo
- `basic_effects.funk` - Comprehensive effect system examples
- `working_*.funk` - Various working effect demonstrations

### `abilities/`
- `ability_examples.funk` - Typeclass-like abilities (Work in Progress)

### `modules/`
- Module system examples (Work in Progress)
- `module_system_test.funk` - Module imports and exports

### `polyglot/`
- `polyglot_demo.funk` - Funk with polyglot capabilities
- `polyglot_js_demo.js` - JavaScript integration
- `polyglot_python_demo.py` - Python integration

### `advanced/`
- `do_expressions.funk` - Sequential operations with do blocks
- `comprehensive_*.funk` - Complex feature combinations
- `nested_expressions.funk` - Nested expression examples

## Running Examples

Use the Funk CLI to parse, type-check, and execute examples:

```bash
# Parse an example
funk parse examples/basic/hello_world.funk

# Type check an example  
funk check examples/functions/lambda_examples.funk

# Execute an example
funk exec examples/variants/color_examples.funk
```

## Language Features Demonstrated

- ✅ **Literals**: Integers, floats, strings, booleans
- ✅ **Variables**: Let bindings with type inference
- ✅ **Functions**: Lambda expressions and function calls
- ✅ **Records**: Record literals, field access, punning
- ✅ **Variants**: Pattern matching with match expressions
- ✅ **Do Expressions**: Sequential computation blocks
- ✅ **Effects**: Algebraic effects with direct-style programming
- ✅ **Effect Rows**: Effect row polymorphism and progressive handling
- ✅ **Arithmetic**: Basic mathematical operations
- ✅ **Boolean Logic**: Logical operations and conditionals
- 🚧 **Abilities**: Typeclass-like behavior (partial)
- 🚧 **Modules**: Module system (partial)
- 🚧 **Polyglot**: Multi-language integration (partial)

## Notes

- Examples marked with ✅ are fully working
- Examples marked with 🚧 are work in progress
- The language is actively being developed, so some advanced features may not be fully implemented yet
