package com.funk.language.ast

/**
 * Shared mapping between operators and their corresponding abilities.
 * This is used by the parser, typechecker, and interpreter to maintain consistency.
 */
object OperatorAbilityMapping {
    /**
     * Map from operator names (as they appear in the AST, e.g., "(+)") to ability names (e.g., "NumAdd")
     */
    val OPERATOR_TO_ABILITY = mapOf(
        "(+)" to "NumAdd",
        "(-)" to "NumSub",
        "(*)" to "NumMul",
        "(/)" to "NumDiv",
        "(%)" to "NumMod",
        "(//)" to "NumDiv",  // Integer division also uses NumDiv
        "(==)" to "Eq",
        "(!=)" to "Eq",
        "(<)" to "Ord",
        "(>)" to "Ord",
        "(<=)" to "Ord",
        "(>=)" to "Ord",
        "(&&)" to "LogicalAnd",
        "(||)" to "LogicalOr",
        "(!)" to "LogicalNot",
        "(~)" to "NumNeg"
    )

    /**
     * Map from ability names to their primary operator
     */
    val ABILITY_TO_OPERATOR = mapOf(
        "NumAdd" to "(+)",
        "NumSub" to "(-)",
        "NumMul" to "(*)",
        "NumDiv" to "(/)",
        "NumMod" to "(%)",
        "Eq" to "(==)",
        "Ord" to "(<)",
        "LogicalAnd" to "(&&)",
        "LogicalOr" to "(||)",
        "LogicalNot" to "(!)",
        "NumNeg" to "(~)"
    )

    /**
     * Set of arithmetic operators that require type validation
     */
    val ARITHMETIC_OPERATORS = setOf("(+)", "(-)", "(*)", "(/)", "(%)", "(//)")

    /**
     * Set of comparison operators
     */
    val COMPARISON_OPERATORS = setOf("(==)", "(!=)", "(<)", "(>)", "(<=)", "(>=)")

    /**
     * Set of logical operators
     */
    val LOGICAL_OPERATORS = setOf("(&&)", "(||)", "(!)")

    /**
     * Check if an operator is an arithmetic operator
     */
    fun isArithmeticOperator(operator: String): Boolean = operator in ARITHMETIC_OPERATORS

    /**
     * Check if an operator is a comparison operator
     */
    fun isComparisonOperator(operator: String): Boolean = operator in COMPARISON_OPERATORS

    /**
     * Check if an operator is a logical operator
     */
    fun isLogicalOperator(operator: String): Boolean = operator in LOGICAL_OPERATORS

    /**
     * Get the ability name for an operator
     */
    fun getAbilityForOperator(operator: String): String? = OPERATOR_TO_ABILITY[operator]

    /**
     * Get the operator for an ability name
     */
    fun getOperatorForAbility(ability: String): String? = ABILITY_TO_OPERATOR[ability]
}
