package com.mezze.language.ast

import org.junit.jupiter.api.Test

class DebugTypeDisplayTest {
    
    @Test
    fun `debug constraint grouping`() {
        val constraints = listOf(
            InspectAbilityConstraint("'c", "Ord"),
            InspectAbilityConstraint("'a", "NumAdd"),
            InspectAbilityConstraint("'a", "Eq"),
            InspectAbilityConstraint("'b", "Eq"),
            InspectAbilityConstraint("'a", "Ord")
        )

        val result = printConstraints(constraints)
        println("Actual result: '$result'")
        println("Expected: ''a : Eq + NumAdd + Ord, 'b : Eq, 'c : Ord'")
    }
    
    @Test
    fun `debug nested function type`() {
        val typeVarA = TypeVar("a")  // Remove quotes from TypeVar names
        val typeVarB = TypeVar("b")
        val typeVarC = TypeVar("c")
        val typeVarD = TypeVar("d")

        val innerFunction1 = MezzeType.FunctionType(
            MezzeType.VarType(typeVarA),
            MezzeType.VarType(typeVarB)
        )
        val innerFunction2 = MezzeType.FunctionType(
            MezzeType.VarType(typeVarC),
            MezzeType.VarType(typeVarD)
        )
        val outerFunction = MezzeType.FunctionType(innerFunction1, innerFunction2)

        val constraints = listOf(
            InspectAbilityConstraint("a", "NumAdd"),  // Use TypeVar names without quotes
            InspectAbilityConstraint("b", "Eq"),
            InspectAbilityConstraint("c", "Ord"),
            InspectAbilityConstraint("d", "Ord")
        )

        val result = printTypeWithConstraints(outerFunction, constraints)
        println("Actual result: '$result'")
        println("Expected: '('a -> 'b) -> ('c -> 'd) where a : NumAdd, b : Eq, c : Ord, d : Ord'")
    }
}