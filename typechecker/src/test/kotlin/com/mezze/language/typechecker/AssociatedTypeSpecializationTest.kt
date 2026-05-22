package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedStatement
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression tests for the constraint solver's associated-type resolution.
 *
 * When an ability declares `having 'inner` and an impl binds it to one of the
 * impl's own type parameters (e.g. `impl Tryable for Result 'a 'e having 'inner = 'a`),
 * a call site like `(make_ok {}).try {}` should specialize the call's return type
 * by substituting the impl's type parameters with the receiver's concrete type-args.
 *
 * Bug history: `solveAssociatedTypeResolution` previously discarded the
 * impl-matching's type-parameter bindings and converted the annotation `'a`
 * to a literal `TypeApplication("'a", [])`, leaving the call's return type as
 * a free `'a` instead of the receiver's concrete type. Fix routes the impl
 * bindings through `findMatchingAbilityImplWithBindings`. Affects any ability
 * method with `having 'X = 'param` style bindings (Tryable.try, custom abilities).
 */
class AssociatedTypeSpecializationTest {
    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun typeCheckAndGetLetType(source: String, letName: String): MezzeType? {
        val parsedModule = parser.parseParsedModule(source.trimIndent(), "test.mz")
        val result = typeChecker.typeCheck(parsedModule, java.nio.file.Paths.get("."))
        val typed = result.typedModule ?: return null
        // Search top-level Let decls AND nested Let statements inside a top-level Let.
        for (decl in typed.decls) {
            if (decl is TypedLetDecl) {
                if (decl.name == letName) return decl.scheme.type
                findNestedLetType(decl.value, letName)?.let { return it }
            }
        }
        return null
    }

    private fun findNestedLetType(expr: com.mezze.language.typedast.TypedExpr, name: String): MezzeType? {
        return when (expr) {
            is TypedBlock -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is TypedStatement.Let -> {
                            if (stmt.name == name) return stmt.type
                            findNestedLetType(stmt.value, name)?.let { return it }
                        }
                        is TypedStatement.Expr -> findNestedLetType(stmt.expr, name)?.let { return it }
                    }
                }
                findNestedLetType(expr.result, name)
            }
            is com.mezze.language.typedast.TypedLambda -> findNestedLetType(expr.body, name)
            is com.mezze.language.typedast.TypedPerform -> findNestedLetType(expr.body, name)
            is com.mezze.language.typedast.TypedIf -> {
                findNestedLetType(expr.condition, name)
                    ?: findNestedLetType(expr.thenBranch, name)
                    ?: findNestedLetType(expr.elseBranch, name)
            }
            else -> null
        }
    }

    @Test
    fun `Tryable_try on Result Int Str specializes inner to Int`() {
        val type = typeCheckAndGetLetType(
            """
                use std::abilities::tryable::{Tryable}

                let make_ok : {} -> Result Int Str = {} -> Ok {val = 42}

                let v = (make_ok {}).try {}
            """,
            "v",
        )
        assertNotNull(type, "expected `v` to typecheck")
        val rendered = prettyPrintTypeWithAliases(type!!, emptyMap())
        assertTrue(rendered.contains("Int"),
            "expected `v`'s type to specialize `'inner` to `Int`, got: $rendered")
        assertTrue(!rendered.matches(Regex(".*\\b'[a-z]+\\b.*")),
            "expected `v`'s type to have no unbound type variables, got: $rendered")
    }

    @Test
    fun `Tryable_try on Option Str specializes inner to Str`() {
        val type = typeCheckAndGetLetType(
            """
                use std::abilities::tryable::{Tryable}

                let make_some : {} -> Option Str = {} -> Some {val = "hi"}

                let v = (make_some {}).try {}
            """,
            "v",
        )
        assertNotNull(type, "expected `v` to typecheck")
        val rendered = prettyPrintTypeWithAliases(type!!, emptyMap())
        assertTrue(rendered.contains("Str"),
            "expected `v`'s type to specialize `'inner` to `Str`, got: $rendered")
    }

    @Test
    fun `custom ability with having binding specializes through impl`() {
        // Custom ability with the same `having 'inner = 'param` pattern.
        // Confirms the fix isn't specific to Tryable.
        val type = typeCheckAndGetLetType(
            """
                ability Unwrap for 'w having 'inner where {
                    unwrap : { it: 'w } -> 'inner
                }

                type Wrap 'a = | Wrap {val: 'a} |

                impl Unwrap for Wrap 'a having 'inner = 'a where {
                    unwrap = { it } -> match it is
                        Wrap {val} -> val
                }

                let make : {} -> Wrap Int = {} -> Wrap {val = 7}

                let v = (make {}).unwrap {}
            """,
            "v",
        )
        assertNotNull(type, "expected `v` to typecheck")
        val rendered = prettyPrintTypeWithAliases(type!!, emptyMap())
        assertTrue(rendered.contains("Int"),
            "expected `v`'s type to specialize `'inner` to `Int`, got: $rendered")
    }
}
