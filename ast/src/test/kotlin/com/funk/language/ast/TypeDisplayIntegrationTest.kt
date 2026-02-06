package com.funk.language.ast

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TypeDisplayIntegrationTest {

    @Test
    fun `test enhanced function type display with constraints`() {
        // Simulate a polymorphic arithmetic function: {x: 'a, y: 'a} -> 'a with NumAdd 'a
        val typeVar = TypeVar("'a")
        val paramType = FunkType.RecordType(mapOf(
            "x" to FunkType.VarType(typeVar),
            "y" to FunkType.VarType(typeVar)
        ))
        val returnType = FunkType.VarType(typeVar)
        val functionType = FunkType.FunctionType(paramType, returnType)
        val constraints = listOf(
            InspectAbilityConstraint("'a", "NumAdd")
        )

        val result = printFunctionTypeWithConstraints(functionType, constraints)
        assertEquals("{ x: 'a, y: 'a } -> 'a with 'a : NumAdd", result)
    }

    @Test
    fun `test comparison function type display`() {
        // Simulate a comparison function: {x: 'a, y: 'a} -> Bool with Eq 'a
        val typeVar = TypeVar("'a")
        val paramType = FunkType.RecordType(mapOf(
            "x" to FunkType.VarType(typeVar),
            "y" to FunkType.VarType(typeVar)
        ))
        val returnType = FunkType.BooleanType
        val functionType = FunkType.FunctionType(paramType, returnType)
        val constraints = listOf(
            InspectAbilityConstraint("'a", "Eq")
        )

        val result = printFunctionTypeWithConstraints(functionType, constraints)
        assertEquals("{ x: 'a, y: 'a } -> Bool with 'a : Eq", result)
    }

    @Test
    fun `test complex function with multiple constraints`() {
        // Simulate: {x: 'a, y: 'b} -> 'c with NumAdd 'a, Eq 'b, Ord 'c
        val typeVarA = TypeVar("'a")
        val typeVarB = TypeVar("'b")
        val typeVarC = TypeVar("'c")

        val paramType = FunkType.RecordType(mapOf(
            "x" to FunkType.VarType(typeVarA),
            "y" to FunkType.VarType(typeVarB)
        ))
        val returnType = FunkType.VarType(typeVarC)
        val functionType = FunkType.FunctionType(paramType, returnType)
        val constraints = listOf(
            InspectAbilityConstraint("'a", "NumAdd"),
            InspectAbilityConstraint("'b", "Eq"),
            InspectAbilityConstraint("'c", "Ord")
        )

        val result = printFunctionTypeWithConstraints(functionType, constraints)
        assertEquals("{ x: 'a, y: 'b } -> 'c with 'a : NumAdd, 'b : Eq, 'c : Ord", result)
    }

    @Test
    fun `test type scheme with constraints for let-polymorphism`() {
        // Simulate: ∀'a. 'a -> 'a
        // Note: TypeVar name should NOT include the quote - it's added by printTypeScheme
        val typeVar = TypeVar("a")
        val functionType = FunkType.FunctionType(
            FunkType.VarType(typeVar),
            FunkType.VarType(typeVar)
        )
        val scheme = TypeScheme(setOf(typeVar), functionType)

        val result = printTypeScheme(scheme)
        assertEquals("∀'a. 'a -> 'a", result)
    }

    @Test
    fun `test effectful function with constraints`() {
        // Simulate: 'a -> 'a <IO> with NumAdd 'a
        val typeVar = TypeVar("'a")
        val paramType = FunkType.VarType(typeVar)
        val returnType = FunkType.VarType(typeVar)
        val effects = FunkType.EffectRow(setOf("IO"))
        val functionType = FunkType.FunctionType(paramType, returnType, effects)
        val constraints = listOf(
            InspectAbilityConstraint("'a", "NumAdd")
        )

        val result = printFunctionTypeWithConstraints(functionType, constraints)
        assertEquals("'a -> 'a <IO> with 'a : NumAdd", result)
    }

    @Test
    fun `test nested function types with constraints`() {
        // Simulate: ('a -> 'b) -> 'c -> 'd with a : NumAdd, b : Eq, c : Ord, d : Ord
        // Note: Function types are right-associative, so ('c -> 'd) doesn't need parens
        val typeVarA = TypeVar("a")
        val typeVarB = TypeVar("b")
        val typeVarC = TypeVar("c")
        val typeVarD = TypeVar("d")

        val innerFunction1 = FunkType.FunctionType(
            FunkType.VarType(typeVarA),
            FunkType.VarType(typeVarB)
        )
        val innerFunction2 = FunkType.FunctionType(
            FunkType.VarType(typeVarC),
            FunkType.VarType(typeVarD)
        )
        val outerFunction = FunkType.FunctionType(innerFunction1, innerFunction2)

        val constraints = listOf(
            InspectAbilityConstraint("a", "NumAdd"),
            InspectAbilityConstraint("b", "Eq"),
            InspectAbilityConstraint("c", "Ord"),
            InspectAbilityConstraint("d", "Ord")
        )

        val result = printTypeWithConstraints(outerFunction, constraints)
        assertEquals("('a -> 'b) -> 'c -> 'd with a : NumAdd, b : Eq, c : Ord, d : Ord", result)
    }

    @Test
    fun `test CLI type display format`() {
        // Test the format that would be shown in CLI type checking output
        val typeVar = TypeVar("'a")
        val addFunctionType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "x" to FunkType.VarType(typeVar),
                "y" to FunkType.VarType(typeVar)
            )),
            FunkType.VarType(typeVar)
        )

        val constraints = listOf(
            InspectAbilityConstraint("'a", "NumAdd")
        )

        // This would be the format shown for: let add = {x, y} -> x + y
        val result = printTypeWithConstraints(addFunctionType, constraints)
        assertEquals("{ x: 'a, y: 'a } -> 'a with 'a : NumAdd", result)
    }

    @Test
    fun `test constraint grouping and sorting`() {
        // Test that constraints are properly grouped by type variable and sorted
        val constraints = listOf(
            InspectAbilityConstraint("'c", "Ord"),
            InspectAbilityConstraint("'a", "NumAdd"),
            InspectAbilityConstraint("'a", "Eq"),
            InspectAbilityConstraint("'b", "Eq"),
            InspectAbilityConstraint("'a", "Ord")
        )

        val result = printConstraints(constraints)
        // Should group by variable and sort abilities within each group
        // Note: groupBy preserves insertion order for keys, so 'c comes first
        assertEquals("'c : Ord, 'a : Eq + NumAdd + Ord, 'b : Eq", result)
    }
}
