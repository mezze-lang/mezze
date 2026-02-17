package com.mezze.language.ast

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TypePrinterConstraintTest {

    @Test
    fun `test printTypeWithConstraints with no constraints`() {
        val intType = MezzeType.IntType
        val result = printTypeWithConstraints(intType, emptyList())
        assertEquals("Int", result)
    }

    @Test
    fun `test printTypeWithConstraints with single constraint`() {
        val typeVar = TypeVar("'a")
        val varType = MezzeType.VarType(typeVar)
        val constraints = listOf(
            InspectAbilityConstraint("'a", "NumAdd")
        )

        val result = printTypeWithConstraints(varType, constraints)
        assertEquals("'a with 'a : NumAdd", result)
    }
}
