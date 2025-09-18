# Pattern Matching - Powerful Control Flow

Master Funk's most distinctive feature: pattern matching with the new `match ... is` syntax.

## Examples in this folder

### [basic-match.funk](./basic-match.funk)
Introduction to pattern matching fundamentals.
- Basic `match ... is` syntax
- Constructor patterns
- Wildcard patterns

### [multiple-patterns.funk](./multiple-patterns.funk)
Using multiple patterns with the `|` operator.
- Grouping similar cases
- `Red | Blue -> "primary"` syntax
- Cleaner, more concise code

### [guards.funk](./guards.funk)
Guard conditions for conditional pattern matching.
- `if condition` syntax in patterns
- Pattern variables in guards
- Complex conditional logic

### [logical-operators.funk](./logical-operators.funk)
Using `&&` and `||` operators in guard conditions.
- Boolean logic in guards
- Complex conditional expressions
- Combining multiple conditions

### [exhaustiveness.funk](./exhaustiveness.funk)
Understanding exhaustiveness checking.
- Complete pattern coverage
- Compiler error prevention
- Using wildcards appropriately

### [nested-patterns.funk](./nested-patterns.funk)
Advanced pattern matching techniques.
- Nested match expressions
- Complex data destructuring
- Pattern composition

## Key Concepts Covered

- **New Syntax**: `match expression is` instead of `match expression {}`
- **Multiple Patterns**: Using `|` to group patterns: `Red | Blue -> result`
- **Guard Conditions**: Adding `if condition` to patterns
- **Logical Operators**: `&&` and `||` in guard expressions
- **Exhaustiveness**: Ensuring all cases are handled
- **Pattern Variables**: Accessing data from matched patterns

## Pattern Matching Syntax Reference

```funk
# Basic syntax
match value is
  Pattern1 -> result1
  Pattern2 -> result2
  _ -> default

# Multiple patterns
match color is
  Red | Blue -> "primary"
  Green | Yellow -> "secondary"

# Guard conditions
match temperature is
  Hot { degrees } if degrees > 100 -> "boiling"
  Hot { degrees } if degrees > 80 -> "very hot"
  Hot { degrees } -> "warm"

# Logical operators in guards
match score is
  Score { points } if points >= 90 && points <= 100 -> "A"
  Score { points } if points >= 80 && points < 90 -> "B"
  _ -> "other"
```

## Running the Examples

```bash
# Test basic pattern matching
funk-dev exec basic-match.funk

# See multiple patterns in action
funk-dev exec multiple-patterns.funk

# Explore guard conditions
funk-dev exec guards.funk
```

## Next Steps

After mastering pattern matching:
- **[Functions](../03-functions/)** - Combine patterns with functional programming
- **[Types](../04-types/)** - Create complex types for advanced patterns
