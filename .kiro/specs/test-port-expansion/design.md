# Test Port Expansion and Organization Design

## Overview

This design outlines the approach for reorganizing existing ported tests into separate files matching the original test structure, and systematically porting large batches of tests from the original typechecker to maximize coverage while maintaining quality.

## Architecture

### Test File Organization Structure

```
typechecker-v2/src/test/kotlin/com/funk/language/typechecker/v2/
├── BasicTypeCheckingTest.kt (existing)
├── ConstraintPropagationTest.kt (existing)  
├── ParserIntegrationTest.kt (existing)
├── ported/
│   ├── DebugTestPort.kt
│   ├── DoExpressionBasicTestPort.kt
│   ├── RecordDebugTestPort.kt
│   ├── ErrorReportingTestPort.kt
│   ├── AdvancedTypeInferenceTestPort.kt
│   ├── RecordDestructuringTestPort.kt
│   ├── DoExpressionArithmeticTestPort.kt
│   ├── StringConcatDebugTestPort.kt
│   ├── UnificationBugTestPort.kt
│   ├── FractionDebugTestPort.kt
│   ├── CompleteInferenceDemoPort.kt
│   ├── ArrayListTypeCheckerTestPort.kt
│   └── TypeCheckerComprehensiveTestPort.kt
```

### Porting Strategy

1. **File-by-File Approach**: Process one original test file at a time
2. **Batch Processing**: Port 5-10+ compatible tests per session
3. **Compatibility Filtering**: Skip tests requiring unsupported features
4. **Quality Assurance**: Ensure 100% pass rate for each batch

## Components and Interfaces

### Test Port Files

Each ported test file will follow this structure:

```kotlin
package com.funk.language.typechecker.v2.ported

import com.funk.language.ast.*
import com.funk.language.parser.FunkParserImpl
import com.funk.language.typechecker.v2.SimpleTypeChecker
import com.funk.language.typechecker.v2.TypeCheckError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Port of tests from [OriginalTestFileName] to ensure compatibility
 * with SimpleTypeChecker v2
 */
class OriginalTestFileNamePort {
    
    private val parser = FunkParserImpl()
    private val typeChecker = SimpleTypeChecker()
    
    private fun getVariableType(program: Program, varName: String): FunkType? {
        return program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == varName }?.inferredType
    }
    
    // Ported tests...
}
```

### Original Test File Analysis

Priority order for porting based on compatibility:

1. **High Priority** (Most Compatible):
   - DebugTest.kt - Basic lambda and arithmetic tests
   - DoExpressionBasicTest.kt - Do expression fundamentals
   - RecordDebugTest.kt - Record operations
   - UnificationBugTest.kt - Type inference edge cases
   - FractionDebugTest.kt - Fraction arithmetic

2. **Medium Priority** (Partially Compatible):
   - AdvancedTypeInferenceTest.kt - Complex inference patterns
   - RecordDestructuringTest.kt - Parameter destructuring
   - StringConcatDebugTest.kt - String operations
   - DoExpressionArithmeticTest.kt - Arithmetic in do blocks

3. **Lower Priority** (Limited Compatibility):
   - ErrorReportingTest.kt - Error handling tests
   - ArrayListTypeCheckerTest.kt - Array operations
   - CompleteInferenceDemo.kt - Complex scenarios

## Data Models

### Test Compatibility Categories

```kotlin
enum class TestCompatibility {
    FULLY_COMPATIBLE,    // Can be ported as-is
    PARTIALLY_COMPATIBLE, // Requires minor modifications
    INCOMPATIBLE         // Requires unsupported features
}

data class TestAnalysis(
    val testName: String,
    val compatibility: TestCompatibility,
    val requiredFeatures: List<String>,
    val reason: String?
)
```

## Error Handling

### Incompatible Test Handling

1. **Documentation**: Record why tests cannot be ported
2. **Future Planning**: Mark tests for future implementation
3. **Alternative Testing**: Create simplified versions when possible

### Test Failure Handling

1. **Immediate Fix**: Address simple compatibility issues
2. **Feature Gaps**: Identify missing SimpleTypeChecker features
3. **Test Modification**: Adapt tests to current capabilities when appropriate

## Testing Strategy

### Batch Testing Approach

1. **Pre-Port Analysis**: Analyze original test file for compatibility
2. **Batch Creation**: Create new port file with compatible tests
3. **Incremental Testing**: Run tests after each batch addition
4. **Quality Gate**: Ensure 100% pass rate before proceeding

### Regression Testing

1. **Full Suite**: Run all existing tests after each new port file
2. **Performance**: Monitor test execution time
3. **Coverage**: Track test coverage metrics

## Implementation Phases

### Phase 1: Reorganization
- Split existing OriginalTestsPort.kt into separate files
- Maintain all existing functionality
- Verify no test regressions

### Phase 2: High-Priority Porting
- Port DebugTest.kt tests (target: 15+ tests)
- Port DoExpressionBasicTest.kt tests (target: 10+ tests)  
- Port RecordDebugTest.kt tests (target: 8+ tests)

### Phase 3: Medium-Priority Porting
- Port AdvancedTypeInferenceTest.kt compatible tests (target: 12+ tests)
- Port RecordDestructuringTest.kt tests (target: 8+ tests)
- Port StringConcatDebugTest.kt tests (target: 6+ tests)

### Phase 4: Comprehensive Coverage
- Port remaining compatible tests from all files
- Target: 150+ total tests
- Comprehensive documentation update

## Success Metrics

- **Test Count**: Achieve 150+ total tests
- **Pass Rate**: Maintain 100% pass rate
- **Organization**: Clear file structure matching original
- **Documentation**: Complete test coverage documentation
- **Performance**: Test suite execution under 30 seconds