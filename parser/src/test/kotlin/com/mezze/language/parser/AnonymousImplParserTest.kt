package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AnonymousImplParserTest {

    private val parser = MezzeParser()

    @Test
    fun `test simple anonymous impl`() {
        val source = """
            impl Int where {
                double = {} -> 42
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val impl = module.decls[0] as ParsedImplAbilityDecl
        assertTrue(impl.isInherent)
        assertEquals(1, impl.targetTypes.size)
        assertEquals("Int", (impl.targetTypes[0] as ParsedNamedType).name)
        assertEquals(1, impl.operations.size)
        assertEquals("double", impl.operations[0].name)
    }

    @Test
    fun `test anonymous impl with multiple methods`() {
        val source = """
            impl Int where {
                double = {} -> 42,
                triple = {} -> 63
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        assertEquals(2, impl.operations.size)
        assertEquals("double", impl.operations[0].name)
        assertEquals("triple", impl.operations[1].name)
    }

    @Test
    fun `test anonymous impl with method taking arguments`() {
        val source = """
            impl Str where {
                pad = {n: Int} -> "padded"
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        assertEquals("Str", (impl.targetTypes[0] as ParsedNamedType).name)
        assertEquals(1, impl.operations.size)
        assertEquals("pad", impl.operations[0].name)
    }

    @Test
    fun `test anonymous impl with type annotation on method`() {
        val source = """
            impl Int where {
                to_float: {} -> Float = {} -> 1.0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        assertEquals(1, impl.operations.size)
        val op = impl.operations[0]
        assertEquals("to_float", op.name)
        assertNotNull(op.returnType)
        assertEquals("Float", (op.returnType as ParsedNamedType).name)
    }

    @Test
    fun `test anonymous impl with trailing comma`() {
        val source = """
            impl Bool where {
                to_str = {} -> "true",
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        assertEquals(1, impl.operations.size)
        assertEquals("to_str", impl.operations[0].name)
    }

    @Test
    fun `test anonymous impl coexists with ability declarations`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> "int"
            }

            impl Int where {
                double = {} -> 42
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(3, module.decls.size)

        assertTrue(module.decls[0] is ParsedAbilityDecl)

        val namedImpl = module.decls[1] as ParsedImplAbilityDecl
        assertFalse(namedImpl.isInherent)
        assertEquals("Show", namedImpl.abilityName)

        val anonImpl = module.decls[2] as ParsedImplAbilityDecl
        assertTrue(anonImpl.isInherent)
    }

    @Test
    fun `test anonymous impl for user-defined type`() {
        val source = """
            type Point = {x: Int, y: Int}

            impl Point where {
                magnitude = {} -> 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(2, module.decls.size)

        val impl = module.decls[1] as ParsedImplAbilityDecl
        assertTrue(impl.isInherent)
        assertEquals("Point", (impl.targetTypes[0] as ParsedNamedType).name)
    }

    @Test
    fun `test anonymous impl has empty forallVars`() {
        val source = """
            impl Float where {
                to_int = {} -> 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertEquals(emptyList<String>(), impl.forallVars)
        assertTrue(impl.associatedTypeBindings.isEmpty())
    }

    @Test
    fun `test anonymous impl has source location`() {
        val source = """
            impl Int where {
                method = {} -> 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertNotNull(impl.loc)
    }

    @Test
    fun `test anonymous impl with complex method body`() {
        val source = """
            impl Int where {
                is_positive = {} -> it > 0,
                is_negative = {} -> it < 0,
                sign = {} -> if it > 0 then 1 else if it < 0 then -1 else 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        assertEquals(3, impl.operations.size)
        assertEquals("is_positive", impl.operations[0].name)
        assertEquals("is_negative", impl.operations[1].name)
        assertEquals("sign", impl.operations[2].name)
    }

    @Test
    fun `test multiple anonymous impls for different types`() {
        val source = """
            impl Int where {
                double = {} -> 42
            }

            impl Str where {
                shout = {} -> "HELLO"
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(2, module.decls.size)

        val intImpl = module.decls[0] as ParsedImplAbilityDecl
        assertTrue(intImpl.isInherent)
        assertEquals("Int", (intImpl.targetTypes[0] as ParsedNamedType).name)

        val strImpl = module.decls[1] as ParsedImplAbilityDecl
        assertTrue(strImpl.isInherent)
        assertEquals("Str", (strImpl.targetTypes[0] as ParsedNamedType).name)
    }

    @Test
    fun `test anonymous impl with native function call`() {
        val source = """
            impl Int where {
                to_str = {} -> __native_int_to_str {n = it}
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        assertEquals(1, impl.operations.size)
        assertEquals("to_str", impl.operations[0].name)
    }

    // ========================================================================
    // SEALED TYPE PROPERTIES
    // ========================================================================

    @Test
    fun `inherent impl target is ImplTarget Inherent`() {
        val source = """
            impl Int where {
                foo = {} -> 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.target is ParsedImplTarget.Inherent)
        assertNull(impl.abilityName)
        assertNull(impl.abilityRef)
    }

    @Test
    fun `named impl target is ImplTarget Named`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> "int"
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[1] as ParsedImplAbilityDecl

        assertTrue(impl.target is ParsedImplTarget.Named)
        assertEquals("Show", impl.abilityName)
        assertNotNull(impl.abilityRef)
        assertFalse(impl.isInherent)
    }

    @Test
    fun `inherent impl for variant type`() {
        val source = """
            type Color = | Red, Green, Blue |

            impl Color where {
                is_primary = {} -> True,
                name = {} -> "color"
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[1] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        assertEquals("Color", (impl.targetTypes[0] as ParsedNamedType).name)
        assertEquals(2, impl.operations.size)
    }

    @Test
    fun `inherent impl with method using it reference`() {
        val source = """
            impl Int where {
                doubled = {} -> it + it
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        val body = impl.operations[0].implementation
        assertTrue(body is ParsedLambda)
    }

    @Test
    fun `inherent impl mixed with let bindings and type declarations`() {
        val source = """
            type Meter = {value: Float}

            let pi = 3.14

            impl Meter where {
                zero = {} -> Meter {value = 0.0}
            }

            let x = 42
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(4, module.decls.size)

        assertTrue(module.decls[0] is ParsedTypeDecl)
        assertTrue(module.decls[1] is ParsedLetDecl)
        assertTrue(module.decls[2] is ParsedImplAbilityDecl)
        assertTrue(module.decls[3] is ParsedLetDecl)

        val impl = module.decls[2] as ParsedImplAbilityDecl
        assertTrue(impl.isInherent)
    }

    @Test
    fun `multiple inherent impls on same type`() {
        val source = """
            impl Int where {
                is_zero = {} -> it == 0
            }

            impl Int where {
                is_positive = {} -> it > 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(2, module.decls.size)

        val impl1 = module.decls[0] as ParsedImplAbilityDecl
        val impl2 = module.decls[1] as ParsedImplAbilityDecl

        assertTrue(impl1.isInherent)
        assertTrue(impl2.isInherent)
        assertEquals("is_zero", impl1.operations[0].name)
        assertEquals("is_positive", impl2.operations[0].name)
    }

    @Test
    fun `inherent impl with multi-line method body`() {
        val source = """
            impl Int where {
                classify = {} ->
                    if it > 0
                    then "positive"
                    else if it < 0
                    then "negative"
                    else "zero"
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        assertEquals("classify", impl.operations[0].name)
    }

    // ─── Seq (`[T]`) receivers ──────────────────────────────────────────
    //
    // Anonymous inherent impls on seq-shaped receivers. The parser
    // must accept `[T]` in the receiver position, dispatching through
    // implTargetTypeAnnotation rather than the identifier-only
    // parseQualifiedRef path.

    @Test
    fun `test anonymous impl on Seq of concrete type`() {
        val source = """
            impl [Int] where {
                sum_all = { it } -> 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val impl = module.decls[0] as ParsedImplAbilityDecl
        assertTrue(impl.isInherent, "expected inherent impl, got: ${impl.target}")
        assertEquals(1, impl.targetTypes.size)
        val target = impl.targetTypes[0] as ParsedSeqType
        assertEquals("Int", (target.elementType as ParsedNamedType).name)
        assertEquals(1, impl.operations.size)
        assertEquals("sum_all", impl.operations[0].name)
    }

    @Test
    fun `test anonymous impl on Seq of type variable`() {
        val source = """
            impl ['a] where {
                first_or = { it, default } -> default
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        val target = impl.targetTypes[0] as ParsedSeqType
        assertEquals("'a", (target.elementType as ParsedNamedType).name)
        assertEquals("first_or", impl.operations[0].name)
    }

    @Test
    fun `test anonymous impl on Seq of type application`() {
        // `impl [Option 'a] where { ... }` — the seq element is itself
        // a parametric type. Both must reach the parser correctly.
        val source = """
            impl [Option 'a] where {
                unwrap_all = { it } -> 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        val target = impl.targetTypes[0] as ParsedSeqType
        val elem = target.elementType as ParsedTypeApp
        assertEquals("Option", elem.constructor)
        assertEquals(1, elem.args.size)
        assertEquals("'a", (elem.args[0] as ParsedNamedType).name)
    }

    @Test
    fun `test anonymous impl on Seq with multiple methods`() {
        val source = """
            impl [Str] where {
                joined = { it } -> "",
                count_non_empty = { it } -> 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[0] as ParsedImplAbilityDecl

        assertTrue(impl.isInherent)
        assertEquals(2, impl.operations.size)
        assertEquals("joined", impl.operations[0].name)
        assertEquals("count_non_empty", impl.operations[1].name)
    }

    // ─── Ability impls on Seq (`impl Ability for [T]`) ──────────────────
    //
    // The `for [T]` form was already supported (seq.mz uses it for
    // impl Default for ['a] etc.). Cover it here so the anonymous-form
    // changes don't accidentally break the existing path.

    @Test
    fun `test ability impl on Seq of concrete type`() {
        val source = """
            ability MySum for 'a where {
                sum: { xs: ['a] } -> 'a
            }

            impl MySum for [Int] where {
                sum = { xs } -> 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls.filterIsInstance<ParsedImplAbilityDecl>().first()

        assertFalse(impl.isInherent)
        assertEquals("MySum", impl.abilityName)
        val target = impl.targetTypes[0] as ParsedSeqType
        assertEquals("Int", (target.elementType as ParsedNamedType).name)
    }

    @Test
    fun `test ability impl on Seq of type variable`() {
        val source = """
            ability Empty for 'a where {
                empty: {} -> 'a
            }

            impl Empty for ['a] where {
                empty = {} -> 0
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls.filterIsInstance<ParsedImplAbilityDecl>().first()

        assertFalse(impl.isInherent)
        val target = impl.targetTypes[0] as ParsedSeqType
        assertEquals("'a", (target.elementType as ParsedNamedType).name)
    }
}
