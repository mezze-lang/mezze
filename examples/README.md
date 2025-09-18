# Funk Language Examples

Welcome to the Funk language examples! This collection demonstrates the key features and capabilities of Funk through practical, runnable code examples.

## 📁 Examples Organization

### [01-basics](./01-basics/) - Language Fundamentals
Learn the core concepts of Funk:
- Variables and expressions
- Basic data types
- Simple functions
- Comments and syntax

### [02-pattern-matching](./02-pattern-matching/) - Pattern Matching
Master Funk's powerful pattern matching:
- Basic match expressions
- Multiple patterns
- Guard conditions
- Exhaustiveness checking

### [03-functions](./03-functions/) - Functions and Composition
Explore functional programming in Funk:
- Lambda expressions
- Function composition
- Partial application
- Higher-order functions

### [04-types](./04-types/) - Type System
Understand Funk's type system:
- Variant types
- Record types
- Type annotations
- Type inference

### [05-effects](./05-effects/) - Algebraic Effects
Learn about Funk's effect system:
- Effect definitions
- Effect handlers
- Effect composition
- Built-in effects

### [06-advanced](./06-advanced/) - Advanced Features
Advanced patterns and techniques:
- Complex data structures
- Advanced pattern matching
- Performance considerations
- Integration patterns

## 🚀 Running Examples

To run any example:

```bash
# Check syntax and types
funk-dev check examples/01-basics/hello-world.funk

# Execute the example
funk-dev exec examples/01-basics/hello-world.funk
```

## 🎯 Learning Path

**New to Funk?** Follow this recommended learning path:

1. **Start with Basics** - `01-basics/` folder
2. **Learn Pattern Matching** - `02-pattern-matching/` folder  
3. **Understand Functions** - `03-functions/` folder
4. **Master Types** - `04-types/` folder
5. **Explore Effects** - `05-effects/` folder
6. **Advanced Techniques** - `06-advanced/` folder

## 📚 Additional Resources

- **[Language Guide](../docs/language-guide/)** - Comprehensive language documentation
- **[Reference](../docs/reference/)** - Technical reference materials
- **[Tutorials](../docs/tutorials/)** - Step-by-step tutorials

## 🔍 Example Index

### Quick Start Examples
- [`01-basics/hello-world.funk`](./01-basics/hello-world.funk) - Your first Funk program
- [`02-pattern-matching/basic-match.funk`](./02-pattern-matching/basic-match.funk) - Simple pattern matching
- [`03-functions/simple-function.funk`](./03-functions/simple-function.funk) - Basic function definition

### Feature Demonstrations
- [`02-pattern-matching/guards.funk`](./02-pattern-matching/guards.funk) - Guard conditions in patterns
- [`02-pattern-matching/multiple-patterns.funk`](./02-pattern-matching/multiple-patterns.funk) - Multiple patterns with `|`
- [`04-types/variants.funk`](./04-types/variants.funk) - Variant type definitions
- [`05-effects/console-effect.funk`](./05-effects/console-effect.funk) - Console I/O with effects

### Real-World Examples
- [`06-advanced/calculator.funk`](./06-advanced/calculator.funk) - Expression calculator
- [`06-advanced/state-machine.funk`](./06-advanced/state-machine.funk) - State machine implementation

## 💡 Tips for Learning

1. **Read the code comments** - Each example includes detailed explanations
2. **Try modifications** - Experiment with changing values and patterns
3. **Check the output** - Run examples to see results
4. **Explore variations** - Many examples include alternative approaches
5. **Build incrementally** - Start simple and add complexity gradually

## 🛠️ Contributing Examples

Found a great use case? Want to add an example? 

1. Choose the appropriate folder
2. Create a descriptive `.funk` file
3. Add comments explaining the concepts
4. Update the folder's README.md
5. Test your example works correctly

Happy coding with Funk! 🎉
