package com.mezze.language.parser

/**
 * Represents a hint for fixing a parse error.
 *
 * Hints provide actionable suggestions to help users quickly resolve parse errors.
 */
data class ParseHint(
    val message: String,
    val code: String? = null,
    val docUrl: String? = null,
    val severity: HintSeverity = HintSeverity.SUGGESTION
)

/**
 * Severity level for hints.
 */
enum class HintSeverity {
    /** General information about the error */
    INFO,

    /** Suggested fix for the error */
    SUGGESTION,

    /** Warning about a potential issue */
    WARNING,

    /** Best practice or tip */
    TIP
}

/**
 * Collection of common parse hints for various error scenarios.
 */
object ParseHints {

    /**
     * Hints for missing delimiter errors.
     */
    fun missingDelimiter(delimiter: String, context: String): List<ParseHint> = listOf(
        ParseHint(
            message = "Add closing '$delimiter' to match the opening delimiter",
            code = when (delimiter) {
                "}" -> "{ field = value }"
                ")" -> "(expression)"
                "]" -> "[element1, element2]"
                else -> null
            }
        ),
        ParseHint(
            message = "Check for unbalanced $delimiter in your $context",
            severity = HintSeverity.TIP
        )
    )

    /**
     * Hints for invalid type variable syntax.
     */
    fun invalidTypeVariable(actual: String): List<ParseHint> = listOf(
        ParseHint(
            message = "Type variables must start with an apostrophe (')",
            code = "Try: '$actual instead of $actual"
        ),
        ParseHint(
            message = "Valid type variables: 'a, 'b, 'state, 'error",
            severity = HintSeverity.INFO
        ),
        ParseHint(
            message = "Type variables represent unknown types that will be inferred",
            docUrl = "https://mezze-lang.org/docs/types#type-variables",
            severity = HintSeverity.INFO
        )
    )

    /**
     * Hints for expected function type errors.
     */
    fun expectedFunctionType(context: String): List<ParseHint> = listOf(
        ParseHint(
            message = "Ability operations must have function types",
            code = "operation_name: {param: Type} -> ReturnType"
        ),
        ParseHint(
            message = "Effects require function signatures in their operations",
            docUrl = "https://mezze-lang.org/docs/effects#operations"
        )
    )

    /**
     * Hints for missing having type in effects.
     */
    fun missingHavingType(): List<ParseHint> = listOf(
        ParseHint(
            message = "Specify the type for the associated type binding",
            code = "effect Reader having 'state = Int where { ... }"
        ),
        ParseHint(
            message = "The type after '=' must be a valid type annotation",
            severity = HintSeverity.INFO
        ),
        ParseHint(
            message = "Associated types must be bound to concrete types in implementations",
            docUrl = "https://mezze-lang.org/docs/effects#associated-types",
            severity = HintSeverity.INFO
        )
    )

    /**
     * Hints for missing type annotation after '=' in having clause.
     */
    fun missingTypeAfterEquals(): List<ParseHint> = listOf(
        ParseHint(
            message = "Add a type after '=': having 'state = Int",
            code = "effect Reader having 'state = Int where { ... }"
        ),
        ParseHint(
            message = "The type can be Int, String, {field: Type}, or any valid type annotation",
            severity = HintSeverity.INFO
        )
    )

    /**
     * Hints for ambiguous lambda vs record syntax.
     */
    fun ambiguousLambdaRecord(): List<ParseHint> = listOf(
        ParseHint(
            message = "This could be a lambda or record literal",
            code = """
                Lambda:  {x} -> x + 1
                Record:  {x = 1}
            """.trimIndent()
        ),
        ParseHint(
            message = "Use '->' for lambda, '=' for record fields",
            severity = HintSeverity.TIP
        )
    )

    /**
     * Hints for deprecated syntax.
     */
    fun deprecatedSyntax(old: String, new: String, reason: String? = null): List<ParseHint> = listOf(
        ParseHint(
            message = "Deprecated syntax: '$old' is no longer supported",
            code = "Use: $new",
            severity = HintSeverity.WARNING
        )
    ) + if (reason != null) {
        listOf(ParseHint(message = reason, severity = HintSeverity.INFO))
    } else {
        emptyList()
    }

    /**
     * Hints for invalid literal values.
     */
    fun invalidLiteral(literalType: String, value: String, reason: String): List<ParseHint> = listOf(
        ParseHint(
            message = "The value '$value' is not a valid $literalType",
            code = when (literalType) {
                "integer" -> "Valid: 123, -456, 1_000_000"
                "float" -> "Valid: 1.5, -3.14, 2.5e10"
                "nat" -> "Valid: 0n, 123n, 1_000n"
                else -> null
            }
        ),
        ParseHint(
            message = reason,
            severity = HintSeverity.INFO
        )
    )

    /**
     * Hints for missing required token.
     */
    fun missingToken(expected: String, context: String): List<ParseHint> = listOf(
        ParseHint(
            message = "Add the missing '$expected' token",
            code = when (expected) {
                "->" -> "{ params } -> body"
                "=" -> "let name = value"
                ":" -> "param: Type"
                else -> null
            }
        ),
        ParseHint(
            message = "Expected $expected in $context",
            severity = HintSeverity.INFO
        )
    )

    /**
     * Hints for invalid constructor pattern.
     */
    fun invalidConstructor(name: String, issue: String): List<ParseHint> = listOf(
        ParseHint(
            message = "Constructor names must start with an uppercase letter",
            code = "Try: ${name.capitalize()} instead of $name"
        ),
        ParseHint(
            message = issue,
            severity = HintSeverity.INFO
        )
    )

    /**
     * Hints for string interpolation errors.
     */
    fun interpolationError(issue: String): List<ParseHint> = listOf(
        ParseHint(
            message = "String interpolation syntax: \$(expression)",
            code = """\"Hello \$(name)!\""""
        ),
        ParseHint(
            message = issue,
            severity = HintSeverity.INFO
        ),
        ParseHint(
            message = "Escape \$ with backslash if you don't want interpolation: \\\$",
            severity = HintSeverity.TIP
        )
    )

    /**
     * Hints for unexpected end of file.
     */
    fun unexpectedEOF(expected: String): List<ParseHint> = listOf(
        ParseHint(
            message = "File ended unexpectedly while looking for '$expected'",
            severity = HintSeverity.WARNING
        ),
        ParseHint(
            message = "Check for unclosed brackets, braces, or parentheses",
            severity = HintSeverity.TIP
        )
    )

    /**
     * Hints for ability/effect constraint syntax.
     */
    fun abilityConstraintSyntax(): List<ParseHint> = listOf(
        ParseHint(
            message = "Ability constraints use 'with' keyword",
            code = "{a} -> Int with Eq 'a"
        ),
        ParseHint(
            message = "Multiple constraints separated by commas: with Eq 'a, Ord 'a",
            severity = HintSeverity.TIP
        ),
        ParseHint(
            message = "See docs for ability constraint syntax",
            docUrl = "https://mezze-lang.org/docs/abilities",
            severity = HintSeverity.INFO
        )
    )
}
