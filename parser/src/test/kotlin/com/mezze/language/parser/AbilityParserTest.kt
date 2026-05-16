package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AbilityParserTest {

    private val parser = MezzeParser()

    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            ability Encoder for 'a where {
              encode : {val: 'a} -> String
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val ability = module.decls[0] as ParsedAbilityDecl
        assertEquals("Encoder", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(1, ability.operations.size)

        val operation = ability.operations[0]
        assertEquals("encode", operation.name)
        assertEquals("String", (operation.returnType as ParsedNamedType).name)
    }

    @Test
    fun testAbilityWithMultipleOperations() {
        val source = """
            ability Show for 'a where {
              show : {val: 'a} -> String,
              debug : {val: 'a} -> String,
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val ability = module.decls[0] as ParsedAbilityDecl

        assertEquals("Show", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(2, ability.operations.size)

        assertEquals("show", ability.operations[0].name)
        assertEquals("debug", ability.operations[1].name)
    }

    @Test
    fun testSimpleImplAbilityDeclaration() {
        val source = """
            ability Encoder for 'a where {
              encode : {val: 'a} -> String
            }

            impl Encoder for Int where {
              encode : {val: Int} -> String = {val} -> "number"
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(2, module.decls.size)

        val impl = module.decls[1] as ParsedImplAbilityDecl
        assertEquals("Encoder", impl.abilityName)
        assertEquals(1, impl.targetTypes.size)
        assertEquals("Int", (impl.targetTypes[0] as ParsedNamedType).name)
        assertEquals(1, impl.operations.size)

        val operation = impl.operations[0]
        assertEquals("encode", operation.name)
        assertEquals("String", (operation.returnType as ParsedNamedType).name)
        assertTrue(operation.implementation is ParsedLambda)
    }

    @Test
    fun testImplAbilityWithMultipleOperations() {
        val source = """
            ability Show for 'a where {
              show : {val: 'a} -> String,
              debug : {val: 'a} -> String,
            }

            impl Show for String where {
              show : {val: String} -> String = {val} -> val,
              debug : {val: String} -> String = {val} -> "\"$(val)\"",
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val impl = module.decls[1] as ParsedImplAbilityDecl

        assertEquals("Show", impl.abilityName)
        assertEquals("String", (impl.targetTypes[0] as ParsedNamedType).name)
        assertEquals(2, impl.operations.size)

        assertEquals("show", impl.operations[0].name)
        assertEquals("debug", impl.operations[1].name)
    }

    @Test
    fun testAbilityAndImplTogether() {
        val source = """
            ability Encoder for 'a where {
              encode : {val: 'a} -> String
            }

            impl Encoder for Int where {
              encode : {val: Int} -> String = {val} -> intToString {val = val}
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(2, module.decls.size)

        val ability = module.decls[0] as ParsedAbilityDecl
        assertEquals("Encoder", ability.name)

        val impl = module.decls[1] as ParsedImplAbilityDecl
        assertEquals("Encoder", impl.abilityName)
        assertEquals("Int", (impl.targetTypes[0] as ParsedNamedType).name)
    }

    @Test
    fun testComplexAbilityExample() {
        val source = """
            ability Serializer for 'a where {
              serialize : {val: 'a} -> String,
              deserialize : {str: String} -> 'a,
            }

            impl Serializer for Int where {
              serialize : {val: Int} -> String = {val} -> intToString {val = val},
              deserialize : {str: String} -> Int = {str} -> stringToInt {str = str},
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(2, module.decls.size)

        val ability = module.decls[0] as ParsedAbilityDecl
        assertEquals("Serializer", ability.name)
        assertEquals(2, ability.operations.size)
        assertEquals("serialize", ability.operations[0].name)
        assertEquals("deserialize", ability.operations[1].name)

        val impl = module.decls[1] as ParsedImplAbilityDecl
        assertEquals("Serializer", impl.abilityName)
        assertEquals(2, impl.operations.size)
    }

    @Test
    fun testAbilityWithMultipleTypeParameters() {
        val source = """
            ability KVStore 'b for 'a where {
              store : {key: 'a, value: 'b} -> {}
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val ability = module.decls[0] as ParsedAbilityDecl
        assertEquals("KVStore", ability.name)
        assertEquals(listOf("'b", "'a"), ability.typeParams)
        assertEquals("'a", ability.receiverTypeParam)
        assertEquals(1, ability.operations.size)

        val operation = ability.operations[0]
        assertEquals("store", operation.name)
    }

    @Test
    fun testImplWithMultipleTypes() {
        val source = """
            impl KVStore for String Int where {
              store : {key: String, value: Int} -> {} = {key, value} -> {}
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val impl = module.decls[0] as ParsedImplAbilityDecl
        assertEquals("KVStore", impl.abilityName)
        assertEquals(2, impl.targetTypes.size)
        assertEquals("String", (impl.targetTypes[0] as ParsedNamedType).name)
        assertEquals("Int", (impl.targetTypes[1] as ParsedNamedType).name)
        assertEquals(1, impl.operations.size)
    }

    // ========================================================================
    // ABILITY CONSTRAINT ASSOCIATED TYPE ANNOTATION TESTS
    // ========================================================================

    @Test
    fun testAbilityConstraintWithSingleAssociatedType() {
        val source = """
            let f: {c: 'c} -> 'item with Container 'c having 'item = Int = {c} -> 42
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        assertEquals("Container", constraint.abilityName)
        assertEquals(1, constraint.typeArgs.size)
        assertTrue(constraint.associatedTypeBindings.isNotEmpty())
        assertEquals(1, constraint.associatedTypeBindings.size)
        assertTrue(constraint.associatedTypeBindings.containsKey("'item"))
        assertEquals("Int", (constraint.associatedTypeBindings["'item"] as ParsedNamedType).name)
    }

    @Test
    fun testAbilityConstraintWithMultipleAssociatedTypes() {
        val source = """
            let f: {iter: 'i} -> {} with Iterator 'i having 'item = Int, 'state = Boolean = {iter} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        assertEquals("Iterator", constraint.abilityName)
        assertEquals(2, constraint.associatedTypeBindings.size)
        assertTrue(constraint.associatedTypeBindings.containsKey("'item"))
        assertTrue(constraint.associatedTypeBindings.containsKey("'state"))
        assertEquals("Int", (constraint.associatedTypeBindings["'item"] as ParsedNamedType).name)
        assertEquals("Boolean", (constraint.associatedTypeBindings["'state"] as ParsedNamedType).name)
    }

    @Test
    fun testMultipleAbilityConstraintsWithAssociatedTypes() {
        val source = """
            let f: {a: 'a, b: 'b} -> {} with Container 'a having 'item = Int, Iterator 'b having 'elem = String = {a, b} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(2, typeAnnotation.abilityConstraints.size)

        val containerConstraint = typeAnnotation.abilityConstraints[0]
        assertEquals("Container", containerConstraint.abilityName)
        assertEquals(1, containerConstraint.associatedTypeBindings.size)
        assertEquals("Int", (containerConstraint.associatedTypeBindings["'item"] as ParsedNamedType).name)

        val iteratorConstraint = typeAnnotation.abilityConstraints[1]
        assertEquals("Iterator", iteratorConstraint.abilityName)
        assertEquals(1, iteratorConstraint.associatedTypeBindings.size)
        assertEquals("String", (iteratorConstraint.associatedTypeBindings["'elem"] as ParsedNamedType).name)
    }

    @Test
    fun testMixedAbilityConstraintsWithAndWithoutAssociatedTypes() {
        val source = """
            let f: {a: 'a, b: 'b} -> {} with Eq 'a, Container 'b having 'item = Int = {a, b} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(2, typeAnnotation.abilityConstraints.size)

        // First constraint: Eq 'a (no associated types)
        val eqConstraint = typeAnnotation.abilityConstraints[0]
        assertEquals("Eq", eqConstraint.abilityName)
        assertFalse(eqConstraint.associatedTypeBindings.isNotEmpty())
        assertTrue(eqConstraint.associatedTypeBindings.isEmpty())

        // Second constraint: Container 'b having 'item = Int
        val containerConstraint = typeAnnotation.abilityConstraints[1]
        assertEquals("Container", containerConstraint.abilityName)
        assertTrue(containerConstraint.associatedTypeBindings.isNotEmpty())
        assertEquals("Int", (containerConstraint.associatedTypeBindings["'item"] as ParsedNamedType).name)
    }

    @Test
    fun testAbilityConstraintWithComplexAssociatedType() {
        val source = """
            let f: {c: 'c} -> {} with Container 'c having 'item = (List String) = {c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        val itemType = constraint.associatedTypeBindings["'item"]
        assertTrue(itemType is ParsedTypeApp)
        assertEquals("List", (itemType as ParsedTypeApp).constructor)
    }

    @Test
    fun testAbilityConstraintWithRecordAssociatedType() {
        val source = """
            let f: {c: 'c} -> {} with Container 'c having 'item = {x: Int, y: Int} = {c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        val itemType = constraint.associatedTypeBindings["'item"]
        assertTrue(itemType is ParsedRecordType)
        assertEquals(2, (itemType as ParsedRecordType).entries.size)
    }

    @Test
    fun testAbilityConstraintBeforeArrow() {
        val source = """
            let f: {a: 'a} with Eq 'a having 'ordering = Int -> Boolean = {a} -> true
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        assertEquals("Eq", constraint.abilityName)
        assertEquals(1, constraint.associatedTypeBindings.size)
        assertEquals("Int", (constraint.associatedTypeBindings["'ordering"] as ParsedNamedType).name)
    }

    @Test
    fun testAbilityConstraintWithQualifiedAbilityName() {
        val source = """
            let f: {a: 'a} -> {} with std::collections::Container 'a having 'item = Int = {a} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        assertEquals("std::collections::Container", constraint.abilityName)
        assertEquals(1, constraint.associatedTypeBindings.size)
    }

    @Test
    fun testAbilityConstraintWithMultipleTypeArgs() {
        val source = """
            let f: {a: 'a, b: 'b} -> {} with BiContainer 'a 'b having 'joined = String = {a, b} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        assertEquals("BiContainer", constraint.abilityName)
        assertEquals(2, constraint.typeArgs.size)
        assertEquals("'a", (constraint.typeArgs[0] as ParsedNamedType).name)
        assertEquals("'b", (constraint.typeArgs[1] as ParsedNamedType).name)
        assertEquals(1, constraint.associatedTypeBindings.size)
        assertEquals("String", (constraint.associatedTypeBindings["'joined"] as ParsedNamedType).name)
    }

    // ========================================================================
    // LOOKAHEAD DISAMBIGUATION TESTS
    // ========================================================================

    @Test
    fun testDisambiguateAssociatedTypeFromNextConstraint() {
        // This tests that the parser correctly distinguishes between:
        // - "Container 'c having 'item = Int, OtherAbility 'x" (two constraints)
        // - "Container 'c having 'item = Int, 'other = String" (one constraint with two associated types)
        val source = """
            let f: {c: 'c, x: 'x} -> {} with Container 'c having 'item = Int, Show 'x = {c, x} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        // Should have two constraints: Container and Show
        assertEquals(2, typeAnnotation.abilityConstraints.size)

        val containerConstraint = typeAnnotation.abilityConstraints[0]
        assertEquals("Container", containerConstraint.abilityName)
        assertEquals(1, containerConstraint.associatedTypeBindings.size)
        assertTrue(containerConstraint.associatedTypeBindings.containsKey("'item"))

        val showConstraint = typeAnnotation.abilityConstraints[1]
        assertEquals("Show", showConstraint.abilityName)
        assertTrue(showConstraint.associatedTypeBindings.isEmpty())
    }

    @Test
    fun testMultipleAssociatedTypesInSingleConstraint() {
        // This tests that multiple associated types are correctly parsed as part of one constraint
        val source = """
            let f: {c: 'c} -> {} with Container 'c having 'item = Int, 'index = String = {c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        // Should have one constraint with two associated types
        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        assertEquals("Container", constraint.abilityName)
        assertEquals(2, constraint.associatedTypeBindings.size)
        assertTrue(constraint.associatedTypeBindings.containsKey("'item"))
        assertTrue(constraint.associatedTypeBindings.containsKey("'index"))
    }

    @Test
    fun testDisambiguateMultipleConstraintsWithMixedAnnotations() {
        // Complex test: Ability1 with 'a = X, Ability2 (no associated), Ability3 with 'b = Y
        val source = """
            let f: {a: 'a, b: 'b, c: 'c} -> {} with Container 'a having 'item = Int, Eq 'b, Iterator 'c having 'elem = String = {a, b, c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        // Should have three constraints
        assertEquals(3, typeAnnotation.abilityConstraints.size)

        assertEquals("Container", typeAnnotation.abilityConstraints[0].abilityName)
        assertTrue(typeAnnotation.abilityConstraints[0].associatedTypeBindings.isNotEmpty())

        assertEquals("Eq", typeAnnotation.abilityConstraints[1].abilityName)
        assertFalse(typeAnnotation.abilityConstraints[1].associatedTypeBindings.isNotEmpty())

        assertEquals("Iterator", typeAnnotation.abilityConstraints[2].abilityName)
        assertTrue(typeAnnotation.abilityConstraints[2].associatedTypeBindings.isNotEmpty())
    }

    // ========================================================================
    // COMPLEX TYPE ARGUMENT TESTS
    // ========================================================================

    @Test
    fun testNestedOptionArrayType() {
        val source = """
            let f: {c: 'c} -> {} with Container 'c having 'item = (Option [Int]) = {c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val itemType = typeAnnotation.abilityConstraints[0].associatedTypeBindings["'item"]
        assertTrue(itemType is ParsedTypeApp)
        val optionType = itemType as ParsedTypeApp
        assertEquals("Option", optionType.constructor)
        assertEquals(1, optionType.args.size)
        assertTrue(optionType.args[0] is ParsedSeqType)
    }

    @Test
    fun testDeeplyNestedGenericType() {
        val source = """
            let f: {c: 'c} -> {} with Container 'c having 'item = (Result (Option String) Int) = {c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val itemType = typeAnnotation.abilityConstraints[0].associatedTypeBindings["'item"]
        assertTrue(itemType is ParsedTypeApp)
        val resultType = itemType as ParsedTypeApp
        assertEquals("Result", resultType.constructor)
        assertEquals(2, resultType.args.size)
        assertTrue(resultType.args[0] is ParsedTypeApp)
        assertEquals("Option", (resultType.args[0] as ParsedTypeApp).constructor)
    }

    @Test
    fun testFunctionTypeAsAssociatedType() {
        val source = """
            let f: {c: 'c} -> {} with Container 'c having 'item = ({x: Int} -> String) = {c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val itemType = typeAnnotation.abilityConstraints[0].associatedTypeBindings["'item"]
        assertTrue(itemType is ParsedFunctionType)
        val funcType = itemType as ParsedFunctionType
        assertTrue(funcType.paramType is ParsedRecordType)
        assertTrue(funcType.returnType is ParsedNamedType)
    }

    @Test
    fun testArrayOfRecordsAsAssociatedType() {
        val source = """
            let f: {c: 'c} -> {} with Container 'c having 'item = [{id: Int, name: String}] = {c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val itemType = typeAnnotation.abilityConstraints[0].associatedTypeBindings["'item"]
        assertTrue(itemType is ParsedSeqType)
        val seqType = itemType as ParsedSeqType
        assertTrue(seqType.elementType is ParsedRecordType)
        assertEquals(2, (seqType.elementType as ParsedRecordType).entries.size)
    }

    @Test
    fun testMultipleComplexAssociatedTypes() {
        val source = """
            let f: {iter: 'i} -> {} with Iterator 'i having 'item = (Option String), 'state = (Result Int String) = {iter} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        assertEquals("Iterator", constraint.abilityName)
        assertEquals(2, constraint.associatedTypeBindings.size)

        val itemType = constraint.associatedTypeBindings["'item"]
        assertTrue(itemType is ParsedTypeApp)
        assertEquals("Option", (itemType as ParsedTypeApp).constructor)

        val stateType = constraint.associatedTypeBindings["'state"]
        assertTrue(stateType is ParsedTypeApp)
        assertEquals("Result", (stateType as ParsedTypeApp).constructor)
    }

    // ========================================================================
    // PARTIAL ASSOCIATED TYPE ANNOTATION TESTS
    // ========================================================================

    @Test
    fun testAbilityConstraintWithoutHavingClause() {
        // Ability with associated types declared but used without `having` annotation
        val source = """
            let f: {c: 'c} -> {} with Container 'c = {c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(1, typeAnnotation.abilityConstraints.size)
        val constraint = typeAnnotation.abilityConstraints[0]
        assertEquals("Container", constraint.abilityName)
        assertFalse(constraint.associatedTypeBindings.isNotEmpty())
        assertTrue(constraint.associatedTypeBindings.isEmpty())
    }

    @Test
    fun testPartialAnnotationInMiddle() {
        // Constraints: annotated, simple, annotated
        val source = """
            let f: {a: 'a, b: 'b, c: 'c} -> {} with Container 'a having 'item = Int, Show 'b, Iterator 'c having 'elem = String = {a, b, c} -> {}
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertEquals(1, module.decls.size)

        val letBinding = module.decls[0] as ParsedLetDecl
        val typeAnnotation = letBinding.typeAnnotation as ParsedFunctionType

        assertEquals(3, typeAnnotation.abilityConstraints.size)

        assertTrue(typeAnnotation.abilityConstraints[0].associatedTypeBindings.isNotEmpty())
        assertFalse(typeAnnotation.abilityConstraints[1].associatedTypeBindings.isNotEmpty())
        assertTrue(typeAnnotation.abilityConstraints[2].associatedTypeBindings.isNotEmpty())
    }
}
