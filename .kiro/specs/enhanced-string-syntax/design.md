# Design Document

## Overview

This design implements multi-line strings and string interpolation for Funk using Rust-like syntax. The implementation uses a **single specialized interpolated string node** for ALL strings, providing optimal performance through StringBuilder-based evaluation while maintaining a unified representation.

## Architecture

The enhanced string syntax uses a **performance-optimized unified approach**:

1. **Single Specialized AST Node**: All strings use `InterpolatedStringLiteral` - even simple strings without interpolation
2. **Performance Optimized**: Uses StringBuilder internally to avoid creating multiple intermediate string objects
3. **Show Ability Integration**: Uses `to_string` method from `Show` ability for type conversion
4. **Unified Pipeline**: All strings go through the same parsing, type checking, and interpretation pipeline

The key insight is that **all strings are interpolated strings** - simple strings just have zero interpolation parts. This provides maximum performance and eliminates the need for multiple string representations.

## Components and Interfaces

### 1. Lexer Changes (Funk.g4)

**Current String Rule:**
```antlr
String: '"' (~["\r\n] | '\\' .)* '"';
```

**Enhanced String Rule:**
```antlr
String: '"' StringContent* '"';

fragment StringContent:
    StringChar
    | EscapeSequence
    | Interpolation
    ;

fragment StringChar:
    ~["\\$\r\n]  // Regular characters except quotes, backslash, dollar
    | [\r\n]     // Allow newlines in strings
    ;

fragment EscapeSequence:
    '\\' ["\\/bfnrt$]  // Standard escapes plus \$ for literal dollar
    ;

fragment Interpolation:
    '$(' InterpolationContent* ')'
    ;

fragment InterpolationContent:
    ~[()]
    | '(' InterpolationContent* ')'  // Handle nested parentheses
    ;
```

### 2. Specialized AST Node for All Strings

**Single Interpolated String Node:**
Replace `StringLiteral` with `InterpolatedStringLiteral` for ALL strings.

```kotlin
@Serializable
data class InterpolatedStringLiteral(
    val parts: List<StringPart>,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
@JsonClassDiscriminator("partKind")
sealed interface StringPart

@Serializable
data class StringTextPart(
    val text: String
) : StringPart

@Serializable
data class StringInterpolationPart(
    val expression: Expression
) : StringPart
```

**Unified Representation Examples:**
- **Simple string**: `"hello"` → `InterpolatedStringLiteral([StringTextPart("hello")])`
- **Multi-line**: `"line1\nline2"` → `InterpolatedStringLiteral([StringTextPart("line1\nline2")])`
- **Interpolated**: `"Hello $(name)"` → `InterpolatedStringLiteral([StringTextPart("Hello "), StringInterpolationPart(Identifier("name"))])`

**Performance Benefits:**
- Single StringBuilder used for all string construction
- No intermediate string objects created
- Optimal memory usage and performance

### 3. Parser Implementation (FunkParserImpl.kt)

**Unified String Parsing:**
```kotlin
fun parseStringLiteral(ctx: FunkParser.StringContext): Expression {
    val text = ctx.text
    val content = text.substring(1, text.length - 1) // Remove quotes
    
    return parseInterpolatedString(content, ctx)
}

private fun parseInterpolatedString(content: String, ctx: ParserRuleContext): InterpolatedStringLiteral {
    val parts = mutableListOf<StringPart>()
    var currentPos = 0
    
    while (currentPos < content.length) {
        val interpolationStart = content.indexOf("$(", currentPos)
        
        if (interpolationStart == -1) {
            // No more interpolations, add remaining text
            if (currentPos < content.length) {
                val text = processEscapeSequences(content.substring(currentPos))
                if (text.isNotEmpty()) {
                    parts.add(StringTextPart(text))
                }
            }
            break
        }
        
        // Add text before interpolation
        if (interpolationStart > currentPos) {
            val text = processEscapeSequences(content.substring(currentPos, interpolationStart))
            if (text.isNotEmpty()) {
                parts.add(StringTextPart(text))
            }
        }
        
        // Find matching closing parenthesis
        val interpolationEnd = findMatchingParen(content, interpolationStart + 2)
        if (interpolationEnd == -1) {
            throw ParseException("Unclosed interpolation expression at ${getSourceLocation(ctx)}")
        }
        
        // Parse the interpolation expression
        val exprText = content.substring(interpolationStart + 2, interpolationEnd)
        val expression = parseExpression(exprText)
        parts.add(StringInterpolationPart(expression))
        
        currentPos = interpolationEnd + 1
    }
    
    // Handle empty string case
    if (parts.isEmpty()) {
        parts.add(StringTextPart(""))
    }
    
    return InterpolatedStringLiteral(parts, sourceLocation = getSourceLocation(ctx))
}
```

### 4. Type Checker Integration

**Unified Type Checking for InterpolatedStringLiteral:**
```kotlin
fun checkInterpolatedString(expr: InterpolatedStringLiteral, context: TypeContext): FunkType {
    // Type check each interpolation part
    for (part in expr.parts) {
        when (part) {
            is StringTextPart -> { /* No type checking needed */ }
            is StringInterpolationPart -> {
                val exprType = checkExpression(part.expression, context)
                // Verify Show instance exists for this type
                if (!hasShowInstance(exprType, context)) {
                    throw TypeCheckException(
                        "No Show instance found for type ${prettyPrintType(exprType)}",
                        part.expression.sourceLocation
                    )
                }
            }
        }
    }
    
    return FunkType.StringType
}

private fun hasShowInstance(type: FunkType, context: TypeContext): Boolean {
    return context.abilityResolver.hasInstance("Show", type)
}
```

**Show Ability with `to_string` Method:**
```funk
ability Show 'a where {
    to_string: { value: 'a } -> String
}

impl Show Int where {
    to_string = { value } -> __native_int_to_string { value = value }
}

impl Show String where {
    to_string = { value } -> value  # Identity for strings
}
```

### 5. Interpreter Implementation

**Performance-Optimized String Evaluation:**
```kotlin
// In ASTToTruffleConverter.kt
fun convertInterpolatedString(expr: InterpolatedStringLiteral): FunkNode {
    return InterpolatedStringNode(
        expr.parts.map { part ->
            when (part) {
                is StringTextPart -> StringTextNode(part.text)
                is StringInterpolationPart -> StringInterpolationNode(
                    convertExpression(part.expression)
                )
            }
        }
    )
}

// Optimized Truffle node using StringBuilder
class InterpolatedStringNode(
    private val parts: List<StringPartNode>
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): Any {
        val result = StringBuilder()
        
        for (part in parts) {
            when (part) {
                is StringTextNode -> result.append(part.text)
                is StringInterpolationNode -> {
                    val value = part.execute(frame)
                    // Call to_string method from Show ability
                    val stringValue = callToString(value, frame)
                    result.append(stringValue)
                }
            }
        }
        
        return result.toString()
    }
    
    private fun callToString(value: Any, frame: VirtualFrame): String {
        // Resolve Show instance and call to_string method
        val showInstance = resolveShowInstance(getTypeOf(value))
        return showInstance.callToString(value)
    }
}
```

**Show Ability Integration:**
```funk
ability Show 'a where {
    to_string: { value: 'a } -> String
}

impl Show Int where {
    to_string = { value } -> __native_int_to_string { value = value }
}

impl Show Person where {
    to_string = { person } -> "$(person.name) ($(person.age))"  # Can use interpolation in Show instances!
}
```

## Data Models

### Unified String Representation

**Single Specialized AST Node for All Strings:**
- **Simple strings**: `InterpolatedStringLiteral([StringTextPart("hello")])`
- **Multi-line strings**: `InterpolatedStringLiteral([StringTextPart("line1\nline2")])`
- **Interpolated strings**: `InterpolatedStringLiteral([StringTextPart("Hello "), StringInterpolationPart(expr)])`

**Performance-Optimized Design:**
- All strings use the same AST node type
- Runtime evaluation uses single StringBuilder
- No intermediate string objects created
- Optimal memory usage and performance

**Type System Integration:**
- All string expressions have type `FunkType.StringType`
- Interpolation expressions type-checked through `Show` ability resolution
- Uses `to_string` method name from `Show` ability

### Memory Layout

**Runtime Representation:**
- Single StringBuilder used for string construction
- No intermediate string allocations
- Final result is single Java String object
- Performance superior to concatenation-based approaches

## Error Handling

### Parse-Time Errors

1. **Unclosed Interpolation**: `"Hello $(name"` → "Unclosed interpolation expression"
2. **Invalid Expression**: `"Hello $(invalid syntax)"` → Standard expression parsing error
3. **Nested Quote Issues**: Handled by proper escape sequence processing

### Type-Time Errors

1. **Missing Show Instance**: `"Value: $(someComplexType)"` → "No Show instance found for type X"
2. **Undefined Variables**: `"Hello $(undefinedVar)"` → "Undefined variable: undefinedVar"  
3. **Type Mismatches**: Standard type checking applies to interpolated expressions through `to_string` calls

### Runtime Errors

1. **Expression Evaluation Errors**: Propagated from interpolated expressions
2. **Conversion Errors**: Handled gracefully with toString() fallback
3. **Memory Errors**: Handled by JVM garbage collection

## Testing Strategy

### Unit Tests

1. **Lexer Tests**: Verify multi-line string tokenization and interpolation syntax recognition
2. **Parser Tests**: Test `InterpolatedStringLiteral` AST generation for all string types
3. **Type Checker Tests**: Verify `Show` ability resolution using `to_string` method
4. **Interpreter Tests**: Test StringBuilder-based evaluation performance and correctness

### Integration Tests

1. **End-to-End String Processing**: Parse → Type Check → Evaluate all string types uniformly
2. **Performance Tests**: Verify StringBuilder approach outperforms concatenation
3. **Complex Interpolation**: Nested expressions, function calls, record access in `$(...)` 
4. **Show Ability Integration**: Test with custom `Show` instances using `to_string` method
5. **Error Propagation**: Verify errors are properly reported with original source locations

### Performance Tests

1. **StringBuilder Efficiency**: Verify single StringBuilder approach outperforms concatenation
2. **Memory Allocation**: Ensure minimal object allocation during string construction
3. **Large String Performance**: Test with multi-line strings containing many interpolations
4. **Comparison Benchmarks**: Compare against manual concatenation and other approaches

## Implementation Phases

### Phase 1: AST Node Implementation
- Replace `StringLiteral` with `InterpolatedStringLiteral` in AST
- Update all existing code to use new node type
- Modify ANTLR lexer to accept newlines and interpolation syntax
- Test basic parsing with new AST structure

### Phase 2: Parser Implementation
- Implement unified string parsing logic
- Handle interpolation syntax recognition and parsing
- Support complex expressions in interpolations
- Add comprehensive error handling with proper source locations

### Phase 3: Type Checker Integration
- Add type checking for `InterpolatedStringLiteral`
- Implement `Show` ability resolution using `to_string` method
- Verify Show instances exist for all interpolated types
- Test with custom `Show` instances

### Phase 4: Interpreter Optimization
- Implement StringBuilder-based evaluation in Truffle nodes
- Add `to_string` method resolution and calling
- Performance optimization and testing
- Comprehensive integration testing and validation