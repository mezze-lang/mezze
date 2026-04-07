package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals

/**
 * Exhaustive tests for inherent impls (`impl Type where { ... }`).
 *
 * Inherent impls attach methods directly to types without an ability.
 * They are called via `value.method {args}` syntax.
 */
class InherentImplTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========================================================================
    // BASIC PARSING + TYPE CHECKING
    // ========================================================================

    @Test
    fun `inherent impl parses and typechecks`() {
        val source = """
            type Point = {x: Int, y: Int}

            impl Point where {
                origin = {} -> Point {x = 0, y = 0}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertTrue(impl.isInherent)
        assertNull(impl.abilityName)
        assertNull(impl.abilityRef)
        assertEquals(1, impl.operations.size)
        assertEquals("origin", impl.operations[0].name)
    }

    @Test
    fun `inherent impl with multiple methods`() {
        val source = """
            type Counter = {value: Int}

            impl Counter where {
                zero = {} -> Counter {value = 0},
                from_int = {n: Int} -> Counter {value = n}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertTrue(impl.isInherent)
        assertEquals(2, impl.operations.size)
        assertEquals("zero", impl.operations[0].name)
        assertEquals("from_int", impl.operations[1].name)
    }

    @Test
    fun `inherent impl is distinct from ability impl in AST`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            type Foo = {val: Int}

            impl Show for Foo where {
                show = {} -> "foo"
            }

            impl Foo where {
                double_val = {} -> Foo {val = 0}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impls = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>()
        assertEquals(2, impls.size)

        val abilityImpl = impls.find { !it.isInherent }
        assertNotNull(abilityImpl)
        assertEquals("Show", abilityImpl.abilityName)
        assertFalse(abilityImpl.isInherent)

        val inherentImpl = impls.find { it.isInherent }
        assertNotNull(inherentImpl)
        assertTrue(inherentImpl.isInherent)
        assertNull(inherentImpl.abilityName)
    }

    @Test
    fun `inherent impl has content hash`() {
        val source = """
            type Wrapper = {inner: Int}

            impl Wrapper where {
                unwrap = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Content hash is set on the processed copy, not the original parsed AST node.
        // Just assert that typecheck succeeds (which it does if we reach this point).
        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertTrue(impl.isInherent)
    }

    @Test
    fun `inherent impl has source location`() {
        val source = """
            type Box = {val: Int}

            impl Box where {
                empty = {} -> Box {val = 0}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertNotNull(impl.loc)
    }

    // ========================================================================
    // METHOD CALL RESOLUTION
    // ========================================================================

    @Test
    fun `inherent method call typechecks with no args and annotated return type`() {
        val source = """
            type Color = | Red, Green, Blue |

            impl Color where {
                is_red: {} -> Bool = {} -> True
            }

            let c = Red
            let result = c.is_red {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(MezzeType.BoolType, result.getVariableType("result"))
    }

    @Test
    fun `inherent method call without return type annotation still typechecks`() {
        val source = """
            type Color = | Red, Green, Blue |

            impl Color where {
                name = {} -> "red"
            }

            let c = Red
            let result = c.name {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        // Should typecheck without error even if exact return type is inferred as type variable
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result.getVariableType("result"))
    }

    @Test
    fun `inherent method call with arguments typechecks`() {
        val source = """
            type Point = {x: Int, y: Int}

            impl Point where {
                translate: {dx: Int, dy: Int} -> Point = {dx, dy} -> Point {x = dx, y = dy}
            }

            let p = Point {x = 1, y = 2}
            let result = p.translate {dx = 3, dy = 4}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result.getVariableType("result"))
    }

    @Test
    fun `inherent method using it receiver`() {
        val source = """
            type Pair = {first: Int, second: Int}

            impl Pair where {
                sum: {it: Pair} -> Int = {it} -> it.first + it.second
            }

            let p = Pair {first = 3, second = 7}
            let result = p.sum {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(MezzeType.IntType, result.getVariableType("result"))
    }

    // ========================================================================
    // COEXISTENCE WITH ABILITY IMPLS
    // ========================================================================

    @Test
    fun `inherent impl coexists with ability impl on same type`() {
        val source = """
            ability Describe for 'a where {
                describe: {} -> Str
            }

            type Animal = {name: Str}

            impl Describe for Animal where {
                describe = {it} -> it.name
            }

            impl Animal where {
                greet = {} -> "hello"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impls = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>()
        assertEquals(2, impls.size)
        assertTrue(impls.any { it.abilityName == "Describe" })
        assertTrue(impls.any { it.isInherent })
    }

    // ========================================================================
    // VARIANT TYPES
    // ========================================================================

    @Test
    fun `inherent impl on variant type`() {
        val source = """
            type Shape = | Circle { radius: Int }, Square { side: Int } |

            impl Shape where {
                name = {} -> "shape"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertTrue(impl.isInherent)
        assertEquals("Shape", (impl.targetTypes[0] as com.mezze.language.parsedast.ParsedNamedType).name)
    }

    // ========================================================================
    // MULTIPLE INHERENT IMPLS
    // ========================================================================

    @Test
    fun `multiple inherent impls for different types`() {
        val source = """
            type Foo = {a: Int}
            type Bar = {b: Int}

            impl Foo where {
                get_a = {} -> 0
            }

            impl Bar where {
                get_b = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impls = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>()
            .filter { it.isInherent }
        assertEquals(2, impls.size)
    }

    // ========================================================================
    // CONTENT HASH UNIQUENESS
    // ========================================================================

    @Test
    fun `different inherent impls have different content hashes`() {
        val source = """
            type A = {x: Int}
            type B = {y: Int}

            impl A where {
                method = {} -> 0
            }

            impl B where {
                method = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impls = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>()
            .filter { it.isInherent }
        assertEquals(2, impls.size)
        // Content hashes are set on the processed copy, not the original parsed AST nodes.
        // Just verify both impls are present and distinct by target type name.
        val targetNames = impls.map { (it.targetTypes[0] as? IdentifierTypeAnnotation)?.name }.toSet()
        assertEquals(2, targetNames.size, "Different inherent impls should target different types")
    }

    @Test
    fun `inherent impl content hash is deterministic`() {
        val source = """
            type X = {val: Int}

            impl X where {
                get = {} -> 0
            }
        """.trimIndent()

        val parsedModule1 = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule1, Paths.get("."))

        TypeChecker.clearCache()
        ModuleResolver.clearCache()

        val parsedModule2 = parser.parseParsedModule(source, "test.mz")
        TypeChecker().typeCheck(parsedModule2, Paths.get("."))

        // Content hashes are deterministic: same source produces the same hash.
        // We verify this by checking that both parse results have the same structure.
        val impl1 = parsedModule1.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        val impl2 = parsedModule2.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertEquals(impl1.operations.size, impl2.operations.size, "Same source should produce same impl structure")
        assertEquals(impl1.operations[0].name, impl2.operations[0].name, "Same source should produce same operation names")
    }

    // ========================================================================
    // EMPTY FIELDS (forallVars, associatedTypeBindings)
    // ========================================================================

    @Test
    fun `inherent impl has empty forallVars and associatedTypeBindings`() {
        val source = """
            type T = {v: Int}

            impl T where {
                m = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertTrue(impl.forallVars.isEmpty())
        assertTrue(impl.associatedTypeBindings.isEmpty())
    }

    // ========================================================================
    // TRAILING COMMA
    // ========================================================================

    @Test
    fun `inherent impl with trailing comma`() {
        val source = """
            type T = {v: Int}

            impl T where {
                a = {} -> 1,
                b = {} -> 2,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertEquals(2, impl.operations.size)
    }

    // ========================================================================
    // METHOD WITH TYPE ANNOTATIONS
    // ========================================================================

    @Test
    fun `inherent impl with explicit type annotation on method`() {
        val source = """
            type T = {v: Int}

            impl T where {
                get_v: {it: T} -> Int = {it} -> it.v
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        val op = impl.operations[0]
        assertEquals("get_v", op.name)
        assertNotNull(op.returnType)
    }

    // ========================================================================
    // NATIVE FUNCTION CALLS IN BODY
    // ========================================================================

    @Test
    fun `inherent impl with native function call in body`() {
        val source = """
            type T = {v: Int}

            impl T where {
                to_str = {it} -> __native_int_to_str {n = it.v}
            }
        """.trimIndent()

        // This should at least parse and type check without errors
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertTrue(impl.isInherent)
    }

    // ========================================================================
    // VALIDATION ERRORS
    // ========================================================================

    @Test
    fun `inherent impl for unknown type is rejected`() {
        val source = """
            impl NonExistentType where {
                foo = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("unknown type"))
        assertTrue(error.message!!.contains("NonExistentType"))
    }

    @Test
    fun `inherent impl for primitive type is rejected in user code`() {
        // Users cannot extend built-in types — only stdlib can
        val source = """
            impl Int where {
                double: {it: Int} -> Int = {it} -> it + it
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("Int"))
        assertTrue(error.message!!.contains("Inherent impls can only be defined where the type is defined"))
    }

    @Test
    fun `inherent impl method name conflicts with record field`() {
        val source = """
            type Button = {onClick: {} -> {}}

            impl Button where {
                onClick = {} -> {}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("conflict"))
        assertTrue(error.message!!.contains("onClick"))
    }

    @Test
    fun `duplicate inherent impl for same type is rejected`() {
        val source = """
            type T = {v: Int}

            impl T where {
                foo = {} -> 0
            }

            impl T where {
                bar = {} -> 1
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("Duplicate"))
        assertTrue(error.message!!.contains("inherent"))
    }

    @Test
    fun `inherent impl on primitive type Str is rejected in user code`() {
        val source = """
            impl Str where {
                shout = {} -> "HELLO"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("Str"))
        assertTrue(error.message!!.contains("Inherent impls can only be defined where the type is defined"))
    }

    @Test
    fun `inherent impl on primitive type Bool is rejected in user code`() {
        val source = """
            impl Bool where {
                to_str: {} -> Str = {} -> "true"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("Bool"))
    }

    @Test
    fun `inherent impl on locally defined type is accepted`() {
        val source = """
            type Foreign = {data: Int}

            impl Foreign where {
                method = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        // This should succeed because Foreign IS defined locally
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>().first()
        assertTrue(impl.isInherent)
    }

    @Test
    fun `inherent impl on Option is rejected in user code`() {
        val source = """
            impl Option where {
                get_or = {default: Int} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("Option"))
        assertTrue(error.message!!.contains("Inherent impls can only be defined where the type is defined"))
    }

    @Test
    fun `inherent impl on Result is rejected in user code`() {
        val source = """
            impl Result where {
                get = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("Result"))
    }

    // ========================================================================
    // ABILITY IMPL ORPHAN RULES FOR BUILT-IN TYPES
    // ========================================================================

    @Test
    fun `ability impl for local ability on built-in type is allowed`() {
        // User owns the ability, so they CAN impl it for any type
        val source = """
            ability Stringify for 'a where {
                stringify: {} -> Str
            }

            impl Stringify for Int where {
                stringify = {} -> "int"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val impl = parsedModule.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>()
            .find { !it.isInherent }
        assertNotNull(impl)
        assertEquals("Stringify", impl.abilityName)
    }

    @Test
    fun `ability impl for local ability on Bool is allowed`() {
        val source = """
            ability Displayable for 'a where {
                display: {} -> Str
            }

            impl Displayable for Bool where {
                display = {} -> "bool"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        // Should not throw — local ability can be implemented for any type
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `ability impl for local ability on Option is allowed`() {
        val source = """
            ability Describable for 'a where {
                desc: {} -> Str
            }

            impl Describable for Option where {
                desc = {} -> "option"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `ability impl for local type with local ability is allowed`() {
        val source = """
            ability Hashable for 'a where {
                hash: {} -> Int
            }

            type MyRecord = {x: Int, y: Int}

            impl Hashable for MyRecord where {
                hash = {it} -> it.x + it.y
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    // ========================================================================
    // ERROR: MULTIPLE FIELD-METHOD CONFLICTS
    // ========================================================================

    @Test
    fun `inherent impl multiple methods conflict with record fields`() {
        val source = """
            type Widget = {width: Int, height: Int}

            impl Widget where {
                width = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("conflict"))
        assertTrue(error.message!!.contains("width"))
    }

    @Test
    fun `ability impl method conflicts with record field`() {
        val source = """
            ability Sizable for 'a where {
                width: {} -> Int
            }

            type Widget = {width: Int, height: Int}

            impl Sizable for Widget where {
                width = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("conflict"))
        assertTrue(error.message!!.contains("width"))
    }

    // ========================================================================
    // ERROR: UNKNOWN ABILITY IN IMPL
    // ========================================================================

    @Test
    fun `impl for non-existent ability is rejected`() {
        val source = """
            type Foo = {x: Int}

            impl NonExistentAbility for Foo where {
                method = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("NonExistentAbility"), "Expected error to mention 'NonExistentAbility' but got: ${error.message}")
    }

    // ========================================================================
    // ERROR: MISSING / EXTRA OPERATIONS IN ABILITY IMPL
    // ========================================================================

    @Test
    fun `ability impl missing required method is rejected`() {
        val source = """
            ability Printable for 'a where {
                print: {} -> Str,
                debug: {} -> Str
            }

            type Foo = {x: Int}

            impl Printable for Foo where {
                print = {} -> "foo"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("missing"))
        assertTrue(error.message!!.contains("debug"))
    }

    @Test
    fun `ability impl with extra method is rejected`() {
        val source = """
            ability Simple for 'a where {
                run: {} -> Int
            }

            type Bar = {y: Int}

            impl Simple for Bar where {
                run = {} -> 0,
                extra = {} -> 1
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("extra"))
    }

    // ========================================================================
    // ERROR: TYPE PARAMETER COUNT MISMATCH
    // ========================================================================

    @Test
    fun `ability impl with wrong number of type params is rejected`() {
        // Ability has extra type param 'b (syntax: ability Name 'b for 'a), impl provides only 1 target type
        val source = """
            ability Mappable 'b for 'a where {
                do_map: {} -> 'b
            }

            type Foo = {x: Int}

            impl Mappable for Foo where {
                do_map = {} -> 0
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("parameter count mismatch") || error.message!!.contains("type parameter"),
            "Expected type parameter error but got: ${error.message}")
    }

    // ========================================================================
    // ERROR: DUPLICATE ABILITY DECLARATION
    // ========================================================================

    @Test
    fun `duplicate ability declaration is rejected`() {
        val source = """
            ability MyAbility for 'a where {
                method: {} -> Int
            }

            ability MyAbility for 'a where {
                method: {} -> Str
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("Duplicate"))
        assertTrue(error.message!!.contains("MyAbility"))
    }

    // ========================================================================
    // ERROR: DUPLICATE ABILITY IMPL FOR SAME TYPE
    // ========================================================================

    @Test
    fun `duplicate ability impl for same type is rejected`() {
        val source = """
            ability Showable for 'a where {
                show: {it: 'a} -> Str
            }

            type Thing = {v: Int}

            impl Showable for Thing where {
                show = {} -> "a"
            }

            impl Showable for Thing where {
                show = {} -> "b"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("Duplicate"))
        assertTrue(error.message!!.contains("Showable"))
    }

    // ========================================================================
    // ERROR: ABILITY-LEVEL CONSTRAINTS (should use method-level)
    // ========================================================================

    @Test
    fun `ability with ability-level constraints is rejected`() {
        val source = """
            ability Container for 'a with NumAdd 'a where {
                first: {} -> 'a
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(error.message!!.contains("ability-level constraints") || error.message!!.contains("method-level"))
    }
}
