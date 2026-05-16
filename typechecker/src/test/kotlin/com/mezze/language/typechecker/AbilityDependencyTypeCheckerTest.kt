package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import kotlin.test.assertFailsWith
import kotlin.test.assertContains

/**
 * Tests for ability constraint validation.
 *
 * Key rule: Ability-level constraints are NOT allowed.
 * All constraints must be on individual methods (method-level constraints).
 *
 * OLD (rejected): ability Ord for 'a with Eq 'a where { compare: ... }
 * NEW (accepted): ability Ord for 'a where { compare: {...} with Eq 'a -> ... }
 */
class AbilityDependencyTypeCheckerTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    init {
        // Enable strict validation for typechecker tests
        System.setProperty("mezze.typecheck.strict", "true")
    }

    // ========================================================================
    // TESTS: Ability-level constraints are rejected
    // ========================================================================

    // Ability-level constraints used to be caught by the typechecker
    // (V0410). They're now rejected by the parser (P0119) before the
    // typechecker ever sees the module — see audit task #212. These
    // tests still pin the rejection behavior, but assert against the
    // parser exception thrown at parse time.

    @Test
    fun `ability-level constraint is rejected with helpful error`() {
        val source = """
            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Ord for 'a with Eq 'a where {
                compare : {left: 'a, right: 'a} -> Str
            }
        """.trimIndent()

        val ex = org.junit.jupiter.api.Assertions.assertThrows(Throwable::class.java) {
            parser.parseParsedModule(source, "test.mz")
        }
        val msg = ex.message ?: ""
        assertContains(msg, "ability-level 'with' constraint")
        // Per-method form is surfaced via hint lines in the rendered block.
        assertTrue(msg.contains("operation") || msg.contains("with"),
            "Expected per-operation suggestion in error, got: $msg")
    }

    @Test
    fun `multiple ability-level constraints are rejected`() {
        val source = """
            ability Show for 'a where {
                show : {val: 'a} -> Str
            }

            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Serializable for 'a with Show 'a, Eq 'a where {
                serialize : {val: 'a} -> Str
            }
        """.trimIndent()

        val ex = org.junit.jupiter.api.Assertions.assertThrows(Throwable::class.java) {
            parser.parseParsedModule(source, "test.mz")
        }
        assertContains(ex.message ?: "", "ability-level 'with' constraint")
    }

    @Test
    fun `circular ability-level constraints are rejected`() {
        // The legacy `ability A for 'a with B 'a` form is rejected by
        // the parser, so the "circular" case never reaches the
        // typechecker — the first ability decl fails to parse.
        val source = """
            ability A for 'a with B 'a where {
                opA : {val: 'a} -> Str
            }

            ability B for 'a with A 'a where {
                opB : {val: 'a} -> Str
            }
        """.trimIndent()

        org.junit.jupiter.api.Assertions.assertThrows(Throwable::class.java) {
            parser.parseParsedModule(source, "test.mz")
        }
    }

    @Test
    fun `self-referential ability-level constraint is rejected`() {
        val source = """
            ability SelfRef for 'a with SelfRef 'a where {
                op : {val: 'a} -> Str
            }
        """.trimIndent()

        org.junit.jupiter.api.Assertions.assertThrows(Throwable::class.java) {
            parser.parseParsedModule(source, "test.mz")
        }
    }

    // ========================================================================
    // TESTS: Method-level constraints work correctly
    // ========================================================================

    @Test
    fun `method-level constraint is accepted`() {
        val source = """
            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Ord for 'a where {
                compare : {left: 'a, right: 'a} with Eq 'a -> Str
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check via parsed AST that Eq and Ord were processed
        val eq = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>().find { it.name == "Eq" }
        assertNotNull(eq)

        val ord = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>().find { it.name == "Ord" }
        assertNotNull(ord)
        // Constraint lives on the method, not on the ability (#212).
        assertEquals(1, ord!!.operations[0].abilityConstraints.size)
        assertEquals("Eq", ord.operations[0].abilityConstraints[0].abilityName)

        // Verify type checking succeeds for both via TypedModule
        val eqDecl = typedProgram.typedModule?.abilityDecls?.find { it.name == "Eq" }
        assertNotNull(eqDecl)
        val ordDecl = typedProgram.typedModule?.abilityDecls?.find { it.name == "Ord" }
        assertNotNull(ordDecl)
    }

    @Test
    fun `multiple method-level constraints are accepted`() {
        val source = """
            ability Show for 'a where {
                show : {val: 'a} -> Str
            }

            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Serializable for 'a where {
                serialize : {val: 'a} with Show 'a, Eq 'a -> Str
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val serializable = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>().find { it.name == "Serializable" }
        assertNotNull(serializable)
        // Constraints live on operations only (#212).
        assertEquals(2, serializable!!.operations[0].abilityConstraints.size)

        // Verify type checking succeeds
        val serializableDecl = typedProgram.typedModule?.abilityDecls?.find { it.name == "Serializable" }
        assertNotNull(serializableDecl)
    }

    @Test
    fun `ability without constraints works`() {
        val source = """
            ability Show for 'a where {
                show : {val: 'a} -> Str
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val show = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>().find { it.name == "Show" }
        assertNotNull(show)
        assertEquals(0, show!!.operations[0].abilityConstraints.size)

        // Verify type checking succeeds
        val showDecl = typedProgram.typedModule?.abilityDecls?.find { it.name == "Show" }
        assertNotNull(showDecl)
    }

    @Test
    fun `method-level constraint on different type param works`() {
        val source = """
            ability Show for 'a where {
                show : {} -> Str
            }

            impl Show for Int where {
                show = {} -> "Int"
            }

            ability Printer for 'a where {
                print : {item: 'b} with Show 'b -> Str
            }

            impl Printer for Str where {
                print = {item} -> "printed"
            }

            let test = {} -> "Value".print {item = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Should type check without errors
        assertNotNull(typedProgram.getVariableType("test"))
    }

    @Test
    fun `method-level constraint fails when not satisfied`() {
        val source = """
            ability Show for 'a where {
                show : {} -> Str
            }

            impl Show for Int where {
                show = {} -> "Int"
            }

            ability Printer for 'a where {
                print : {item: 'b} with Show 'b -> Str
            }

            impl Printer for Str where {
                print = {item} -> "printed"
            }

            # Float does NOT implement Show - should fail
            let test = {} -> "Value".print {item = 3.14}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val error = assertFailsWith<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertContains(error.message!!, "Show")
        assertContains(error.message!!, "Float")
    }

    @Test
    fun `impl with method-level constraints works`() {
        val source = """
            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Ord for 'a where {
                compare : {left: 'a, right: 'a} with Eq 'a -> Str
            }

            impl Eq for Int where {
                equals = {left, right} -> True
            }

            impl Ord for Int where {
                compare = {left, right} -> "equal"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // All abilities and impls should type check successfully
        assertNotNull(typedProgram.typedModule?.abilityDecls?.find { it.name == "Eq" })
        assertNotNull(typedProgram.typedModule?.abilityDecls?.find { it.name == "Ord" })
        assertTrue(typedProgram.typedModule?.abilityImpls?.size == 2)
    }

    @Test
    fun `different methods can have different constraints`() {
        val source = """
            ability Show for 'a where {
                show : {val: 'a} -> Str
            }

            ability Eq for 'a where {
                equals : {left: 'a, right: 'a} -> Bool
            }

            ability Collection for 'c where {
                length : {col: 'c} -> Int,
                contains : {col: 'c, item: 'a} with Eq 'a -> Bool,
                show_all : {col: 'c, items: 'a} with Show 'a -> Str
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check via parsed AST that Collection operations have the right constraints
        val collection = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>().find { it.name == "Collection" }
        assertNotNull(collection)

        // length has no constraints
        assertEquals(0, collection!!.operations[0].abilityConstraints.size)
        // contains has Eq constraint
        assertEquals(1, collection.operations[1].abilityConstraints.size)
        assertEquals("Eq", collection.operations[1].abilityConstraints[0].abilityName)
        // show_all has Show constraint
        assertEquals(1, collection.operations[2].abilityConstraints.size)
        assertEquals("Show", collection.operations[2].abilityConstraints[0].abilityName)

        // Verify type checking succeeds
        assertNotNull(typedProgram.typedModule?.abilityDecls?.find { it.name == "Collection" })
    }
}
