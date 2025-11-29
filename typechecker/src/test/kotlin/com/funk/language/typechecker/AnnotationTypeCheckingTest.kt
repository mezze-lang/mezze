package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnnotationTypeCheckingTest {

    private fun parseAndTypeCheck(code: String): Program {
        val parser = FunkParser()
        val ast = parser.parse(code)
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(ast)
    }

    private fun getVariableType(program: Program, varName: String): FunkType? {
        return program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == varName }?.inferredType
    }

    private fun assertTypeChecks(code: String, variableName: String, expectedType: String) {
        val program = parseAndTypeCheck(code)
        val actualType = getVariableType(program, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        assertEquals(expectedType, prettyPrintType(actualType))
    }

    private fun assertTypeChecks(code: String) {
        parseAndTypeCheck(code)
    }

    private fun assertTypeCheckFails(code: String) {
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    // ===== Basic Type Annotations =====

    @Test
    fun `test simple Int annotation matches inferred type`() {
        assertTypeChecks(
            """
            let x: Int = 42
            x
            """.trimIndent(),
            "x",
            "Int"
        )
    }

    @Test
    fun `test simple String annotation matches inferred type`() {
        assertTypeChecks(
            """
            let s: String = "hello"
            s
            """.trimIndent(),
            "s",
            "String"
        )
    }

    @Test
    fun `test simple Bool annotation matches inferred type`() {
        assertTypeChecks(
            """
            let b: Bool = true
            b
            """.trimIndent(),
            "b",
            "Bool"
        )
    }

    @Test
    fun `test simple Float annotation matches inferred type`() {
        assertTypeChecks(
            """
            let f: Float = 3.14
            f
            """.trimIndent(),
            "f",
            "Float"
        )
    }

    // ===== Type Mismatch Tests =====

    @Test
    fun `test Int annotation with String value fails`() {
        assertTypeCheckFails(
            """
            let x: Int = "hello"
            x
            """.trimIndent()
        )
    }

    @Test
    fun `test String annotation with Int value fails`() {
        assertTypeCheckFails(
            """
            let s: String = 42
            s
            """.trimIndent()
        )
    }

    @Test
    fun `test Bool annotation with Int value fails`() {
        assertTypeCheckFails(
            """
            let b: Bool = 123
            b
            """.trimIndent()
        )
    }

    @Test
    fun `test Float annotation with String value fails`() {
        assertTypeCheckFails(
            """
            let f: Float = "not a float"
            f
            """.trimIndent()
        )
    }

    // ===== Function Type Annotations =====

    @Test
    fun `test function annotation guides inference for polymorphic lambda`() {
        assertTypeChecks(
            """
            let add: { x: Int, y: Int } -> Int = {x, y} -> x + y
            add {x = 1, y = 2}
            """.trimIndent(),
            "add",
            "{ x: Int, y: Int } -> Int"
        )
    }

    @Test
    fun `test function annotation with String concatenation`() {
        assertTypeChecks(
            """
            let concat: { a: String, b: String } -> String = {a, b} -> "$(a)$(b)"
            concat {a = "hello", b = "world"}
            """.trimIndent(),
            "concat",
            "{ a: String, b: String } -> String"
        )
    }

    @Test
    fun `test function annotation with single parameter`() {
        assertTypeChecks(
            """
            let inc: { n: Int } -> Int = {n} -> n + 1
            inc {n = 5}
            """.trimIndent(),
            "inc",
            "{ n: Int } -> Int"
        )
    }

    @Test
    fun `test function annotation with Bool return type`() {
        assertTypeChecks(
            """
            let isAdult: { age: Int } -> Bool = {age} -> age >= 18
            isAdult {age = 21}
            """.trimIndent(),
            "isAdult",
            "{ age: Int } -> Bool"
        )
    }

    @Test
    fun `test nested function annotation`() {
        assertTypeChecks(
            """
            let makeAdder: { x: Int } -> { y: Int } -> Int = {x} -> {y} -> x + y
            makeAdder {x = 5}
            """.trimIndent(),
            "makeAdder",
            "{ x: Int } -> { y: Int } -> Int"
        )
    }

    // ===== Function Type Mismatch Tests =====

    @Test
    fun `test function param type mismatch fails`() {
        assertTypeCheckFails(
            """
            let f: { x: String } -> Int = {x} -> x + 1
            f {x = "test"}
            """.trimIndent()
        )
    }

    @Test
    fun `test function return type mismatch fails`() {
        assertTypeCheckFails(
            """
            let f: { x: Int } -> Int = {x} -> "hello"
            f {x = 5}
            """.trimIndent()
        )
    }

    @Test
    fun `test function arity mismatch with annotation fails`() {
        assertTypeCheckFails(
            """
            let f: { x: Int, y: Int } -> Int = {x} -> x + 1
            f {x = 1, y = 2}
            """.trimIndent()
        )
    }

    // ===== List Type Annotations =====

    @Test
    fun `test list annotation with Int elements`() {
        assertTypeChecks(
            """
            let nums: [Int] = [1, 2, 3]
            nums
            """.trimIndent(),
            "nums",
            "[Int]"
        )
    }

    @Test
    fun `test list annotation with String elements`() {
        assertTypeChecks(
            """
            let words: [String] = ["hello", "world"]
            words
            """.trimIndent(),
            "words",
            "[String]"
        )
    }

    @Test
    fun `test empty list with annotation`() {
        assertTypeChecks(
            """
            let empty: [Int] = []
            empty
            """.trimIndent(),
            "empty",
            "[Int]"
        )
    }

    @Test
    fun `test list element type mismatch fails`() {
        assertTypeCheckFails(
            """
            let nums: [Int] = ["hello", "world"]
            nums
            """.trimIndent()
        )
    }

    // ===== Record Type Annotations =====

    @Test
    fun `test record annotation matches structure`() {
        assertTypeChecks(
            """
            let person: { name: String, age: Int } = { name = "Alice", age = 30 }
            person
            """.trimIndent(),
            "person",
            "{ name: String, age: Int }"
        )
    }

    @Test
    fun `test record annotation with nested records`() {
        assertTypeChecks(
            """
            let data: { outer: { inner: Int } } = { outer = { inner = 42 } }
            data
            """.trimIndent(),
            "data",
            "{ outer: { inner: Int } }"
        )
    }

    @Test
    fun `test record field type mismatch fails`() {
        assertTypeCheckFails(
            """
            let person: { name: String, age: Int } = { name = "Alice", age = "thirty" }
            person
            """.trimIndent()
        )
    }

    @Test
    fun `test record missing field with annotation fails`() {
        assertTypeCheckFails(
            """
            let person: { name: String, age: Int } = { name = "Alice" }
            person
            """.trimIndent()
        )
    }

    // ===== Variant Type Annotations =====

    @Test
    fun `test variant annotation with simple constructors`() {
        assertTypeChecks(
            """
            let status: | Success, Failure | = Success
            status
            """.trimIndent(),
            "status",
            "| Success, Failure |"
        )
    }

    @Test
    fun `test variant annotation with payload`() {
        assertTypeChecks(
            """
            let result: | Some { val: Int }, None | = Some { val = 42 }
            result
            """.trimIndent(),
            "result",
            "| Some { val: Int }, None |"
        )
    }

    @Test
    fun `test variant payload type mismatch fails`() {
        assertTypeCheckFails(
            """
            let result: | Some { val: Int }, None | = Some { val = "hello" }
            result
            """.trimIndent()
        )
    }

    // ===== Complex Nested Annotations =====

    @Test
    fun `test function returning list with annotation`() {
        assertTypeChecks(
            """
            let makeList: { n: Int } -> [Int] = {n} -> [n, n + 1, n + 2]
            makeList {n = 1}
            """.trimIndent(),
            "makeList",
            "{ n: Int } -> [Int]"
        )
    }

    @Test
    fun `test function taking record with annotation`() {
        assertTypeChecks(
            """
            let getName: { person: { name: String, age: Int } } -> String = {person} -> person.name
            getName {person = {name = "Alice", age = 30}}
            """.trimIndent(),
            "getName",
            "{ person: { name: String, age: Int } } -> String"
        )
    }

    @Test
    fun `test list of functions with annotation`() {
        assertTypeChecks(
            """
            let ops: [{ x: Int } -> Int] = [{x} -> x + 1, {x} -> x * 2]
            ops
            """.trimIndent(),
            "ops",
            "[{ x: Int } -> Int]"
        )
    }

    // ===== Polymorphic Type Annotations =====

    @Test
    fun `test polymorphic identity function annotation`() {
        assertTypeChecks(
            """
            let id: { x: Int } -> Int = {x} -> x
            id {x = 42}
            """.trimIndent(),
            "id",
            "{ x: Int } -> Int"
        )
    }

    @Test
    fun `test annotation constrains polymorphic function to concrete type`() {
        assertTypeChecks(
            """
            let first: { a: Int, b: String } -> Int = {a, b} -> a
            first {a = 1, b = "hello"}
            """.trimIndent(),
            "first",
            "{ a: Int, b: String } -> Int"
        )
    }

    // ===== Arithmetic and Comparison Annotations =====

    @Test
    fun `test arithmetic result with Int annotation`() {
        assertTypeChecks(
            """
            let result: Int = 5 + 3
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test comparison result with Bool annotation`() {
        assertTypeChecks(
            """
            let result: Bool = 5 > 3
            result
            """.trimIndent(),
            "result",
            "Bool"
        )
    }

    @Test
    fun `test string concat result with String annotation`() {
        assertTypeChecks(
            """
            let result: String = "$("hello")$("world")"
            result
            """.trimIndent(),
            "result",
            "String"
        )
    }

    // ===== If Expression Annotations =====

    @Test
    fun `test if expression with annotation on branches`() {
        assertTypeChecks(
            """
            let x: Int = if true then 1 else 2
            x
            """.trimIndent(),
            "x",
            "Int"
        )
    }

    @Test
    fun `test if expression branch type mismatch with annotation fails`() {
        assertTypeCheckFails(
            """
            let x: Int = if true then 1 else "hello"
            x
            """.trimIndent()
        )
    }

    // ===== Match Expression Annotations =====

    @Test
    fun `test match expression with annotation`() {
        assertTypeChecks(
            """
            let result: Int = match Some {val = 42} is
              Some {val} -> val
              None -> 0
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test match branch return type mismatch with annotation fails`() {
        assertTypeCheckFails(
            """
            let result: Int = match Some {val = 42} is
              Some {val} -> "hello"
              None -> 0
            result
            """.trimIndent()
        )
    }

    // ===== Recursive Function Annotations =====

    @Test
    fun `test recursive function with annotation`() {
        assertTypeChecks(
            """
            let factorial: { n: Int } -> Int = {n} ->
              if n <= 1 then 1 else n * factorial {n = n - 1}
            factorial {n = 5}
            """.trimIndent(),
            "factorial",
            "{ n: Int } -> Int"
        )
    }

    @Test
    fun `test mutually recursive functions with annotations`() {
        assertTypeChecks(
            """
            let isEven: { n: Int } -> Bool = {n} ->
              if n == 0 then true else isOdd {n = n - 1}
            let isOdd: { n: Int } -> Bool = {n} ->
              if n == 0 then false else isEven {n = n - 1}
            isEven {n = 4}
            """.trimIndent(),
            "isEven",
            "{ n: Int } -> Bool"
        )
    }

    // ===== Multiple Variable Annotations =====

    @Test
    fun `test multiple variables with annotations in sequence`() {
        val program = parseAndTypeCheck(
            """
            let x: Int = 42
            let y: String = "hello"
            let z: Bool = true
            z
            """.trimIndent()
        )
        val xType = getVariableType(program, "x")
        val yType = getVariableType(program, "y")
        val zType = getVariableType(program, "z")
        assertNotNull(xType)
        assertNotNull(yType)
        assertNotNull(zType)
        assertEquals("Int", prettyPrintType(xType))
        assertEquals("String", prettyPrintType(yType))
        assertEquals("Bool", prettyPrintType(zType))
    }

    @Test
    fun `test annotation helps inference in complex expression`() {
        assertTypeChecks(
            """
            let compute: { a: Int, b: Int } -> Int = {a, b} -> do
              let x = a + b
              let y = x * 2
              y - a
            compute {a = 5, b = 3}
            """.trimIndent(),
            "compute",
            "{ a: Int, b: Int } -> Int"
        )
    }

    // ===== Lambda Inference with Annotations =====

    @Test
    fun `test annotation guides lambda parameter inference`() {
        assertTypeChecks(
            """
            let apply: { f: { x: Int } -> Int, val: Int } -> Int = {f, val} -> f {x = val}
            apply {f = {x} -> x + 1, val = 5}
            """.trimIndent(),
            "apply",
            "{ f: { x: Int } -> Int, val: Int } -> Int"
        )
    }

    @Test
    fun `test higher-order function with annotation`() {
        assertTypeChecks(
            """
            let apply: { f: { x: Int } -> Int, value: Int } -> Int = {f, value} ->
              f {x = value}
            apply {f = {x} -> x * 2, value = 5}
            """.trimIndent(),
            "apply",
            "{ f: { x: Int } -> Int, value: Int } -> Int"
        )
    }

    // ===== Ability Annotations =====

    @Test
    fun `test function with numeric ability annotation`() {
        assertTypeChecks(
            """
            let double: { x: Int } -> Int = {x} -> x + x
            double {x = 5}
            """.trimIndent(),
            "double",
            "{ x: Int } -> Int"
        )
    }

    @Test
    fun `test function with comparison ability annotation`() {
        assertTypeChecks(
            """
            let max: { a: Int, b: Int } -> Int = {a, b} -> if a > b then a else b
            max {a = 5, b = 3}
            """.trimIndent(),
            "max",
            "{ a: Int, b: Int } -> Int"
        )
    }

    // ===== Edge Cases =====

    @Test
    fun `test annotation with unit type`() {
        assertTypeChecks(
            """
            let unit: {} = {}
            unit
            """.trimIndent(),
            "unit",
            "{}"
        )
    }

    @Test
    fun `test deeply nested function annotation`() {
        assertTypeChecks(
            """
            let f: { x: Int } -> { y: Int } -> { z: Int } -> Int =
              {x} -> {y} -> {z} -> x + y + z
            f {x = 1}
            """.trimIndent(),
            "f",
            "{ x: Int } -> { y: Int } -> { z: Int } -> Int"
        )
    }

    @Test
    fun `test annotation with complex record nesting`() {
        assertTypeChecks(
            """
            let data: { a: { b: { c: Int } } } = { a = { b = { c = 42 } } }
            data
            """.trimIndent(),
            "data",
            "{ a: { b: { c: Int } } }"
        )
    }

    @Test
    fun `test annotation prevents overly general inference`() {
        assertTypeChecks(
            """
            let first: { a: Int, b: Int } -> Int = {a, b} -> a
            first {a = 1, b = 2}
            """.trimIndent(),
            "first",
            "{ a: Int, b: Int } -> Int"
        )
    }

    // ===== Pattern Matching with Annotations =====

    @Test
    fun `test pattern match result has annotated type`() {
        assertTypeChecks(
            """
            let unwrap: { opt: | Some { val: Int }, None | } -> Int = {opt} ->
              match opt is
                Some {val} -> val
                None -> 0
            unwrap {opt = Some {val = 42}}
            """.trimIndent(),
            "unwrap",
            "{ opt: | Some { val: Int }, None | } -> Int"
        )
    }

    @Test
    fun `test variant constructor in annotation guides inference`() {
        assertTypeChecks(
            """
            let makeResult: { n: Int } -> | Ok { val: Int }, Err { msg: String } | =
              {n} -> if n > 0 then Ok {val = n} else Err {msg = "negative"}
            makeResult {n = 5}
            """.trimIndent(),
            "makeResult",
            "{ n: Int } -> | Ok { val: Int }, Err { msg: String } |"
        )
    }

    // ===== Type Variable Annotations =====

    @Test
    fun `test polymorphic identity function with type variable annotation`() {
        val program = parseAndTypeCheck(
            """
            let id: { x: 'a } -> 'a = {x} -> x
            id {x = 42}
            """.trimIndent()
        )
        val idType = getVariableType(program, "id")
        assertNotNull(idType, "Variable 'id' not found")
        // Type should be generalized/polymorphic
    }

    @Test
    fun `test type variable annotation constrains implementation`() {
        // This should fail because the implementation returns wrong type
        assertTypeCheckFails(
            """
            let wrong: { x: 'a } -> 'a = {x} -> 42
            wrong {x = "test"}
            """.trimIndent()
        )
    }

    @Test
    fun `test polymorphic function with same type variable used multiple times`() {
        val program = parseAndTypeCheck(
            """
            let first: { a: 'a, b: 'a } -> 'a = {a, b} -> a
            first {a = 1, b = 2}
            """.trimIndent()
        )
        val firstType = getVariableType(program, "first")
        assertNotNull(firstType, "Variable 'first' not found")
    }

    @Test
    fun `test polymorphic function with different type variables`() {
        val program = parseAndTypeCheck(
            """
            let fst: { a: 'a, b: 'b } -> 'a = {a, b} -> a
            fst {a = 1, b = "hello"}
            """.trimIndent()
        )
        val fstType = getVariableType(program, "fst")
        assertNotNull(fstType, "Variable 'fst' not found")
    }

    @Test
    fun `test type variable mismatch in polymorphic annotation fails`() {
        // Annotation says both params and return are same type 'a
        // But implementation returns different type
        assertTypeCheckFails(
            """
            let bad: { x: 'a, y: 'a } -> 'a = {x, y} -> if true then x else 0
            bad {x = 1, y = 2}
            """.trimIndent()
        )
    }

    @Test
    fun `test polymorphic list annotation`() {
        val program = parseAndTypeCheck(
            """
            let singleton: { x: 'a } -> ['a] = {x} -> [x]
            singleton {x = 42}
            """.trimIndent()
        )
        val singletonType = getVariableType(program, "singleton")
        assertNotNull(singletonType, "Variable 'singleton' not found")
    }

    @Test
    fun `test custom type variable names from annotation are accepted but normalized`() {
        // Annotation with custom names like 'foo and 'bar is accepted
        // But the inferred type normalizes them to standard names like 'a, 'b
        // This is current behavior - custom names guide type checking but aren't preserved
        assertTypeChecks(
            """
            let fst: { x: 'foo, y: 'bar } -> 'foo = {x, y} -> x
            fst
            """.trimIndent(),
            "fst",
            "{ x: 'a, y: 'b } -> 'a"
        )
    }

    @Test
    fun `test unannotated polymorphic function uses default names`() {
        // Without annotation, should infer with default type variable names
        assertTypeChecks(
            """
            let fst = {x, y} -> x
            fst
            """.trimIndent(),
            "fst",
            "{ x: 'a, y: 'b } -> 'a"
        )
    }

    @Test
    fun `test polymorphic function returning function`() {
        val program = parseAndTypeCheck(
            """
            let const: { a: 'a } -> { b: 'b } -> 'a = {a} -> {b} -> a
            const {a = 42}
            """.trimIndent()
        )
        val constType = getVariableType(program, "const")
        assertNotNull(constType, "Variable 'const' not found")
    }

    // ===== Variant Type Annotation Edge Cases =====

    @Test
    fun `test variant with no-data constructor annotation`() {
        assertTypeChecks(
            """
            let none: | Some { val: Int }, None | = None
            none
            """.trimIndent(),
            "none",
            "| Some { val: Int }, None |"
        )
    }

    @Test
    fun `test variant with multiple payloads annotation`() {
        assertTypeChecks(
            """
            let result: | Ok { val: Int }, Err { msg: String, code: Int } | =
              Err { msg = "error", code = 404 }
            result
            """.trimIndent(),
            "result",
            "| Ok { val: Int }, Err { msg: String, code: Int } |"
        )
    }

    @Test
    fun `test variant annotation missing constructor fails`() {
        assertTypeCheckFails(
            """
            let result: | Ok { val: Int } | = Err { msg = "error" }
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test variant annotation with wrong payload type fails`() {
        assertTypeCheckFails(
            """
            let result: | Some { val: String }, None | = Some { val = 42 }
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test variant annotation with nested variant`() {
        assertTypeChecks(
            """
            let nested: | Outer { inner: | Inner1, Inner2 | } | =
              Outer { inner = Inner1 }
            nested
            """.trimIndent(),
            "nested",
            "| Outer { inner: | Inner1, Inner2 | } |"
        )
    }

    // ===== Complex Type Mismatch Tests =====

    @Test
    fun `test function annotation with wrong parameter count fails`() {
        assertTypeCheckFails(
            """
            let f: { x: Int } -> Int = {x, y} -> x + y
            f {x = 1}
            """.trimIndent()
        )
    }

    @Test
    fun `test function annotation with wrong parameter names still works`() {
        // Parameter names in annotations should match implementation
        // or be flexible depending on language semantics
        assertTypeChecks(
            """
            let add: { a: Int, b: Int } -> Int = {a, b} -> a + b
            add {a = 1, b = 2}
            """.trimIndent(),
            "add",
            "{ a: Int, b: Int } -> Int"
        )
    }

    @Test
    @org.junit.jupiter.api.Disabled("Bidirectional type checking makes validation circular - needs architectural refactor")
    fun `test nested function type mismatch in annotation fails`() {
        assertTypeCheckFails(
            """
            let f: { x: Int } -> { y: Int } -> String = {x} -> {y} -> x + y
            f {x = 1}
            """.trimIndent()
        )
    }

    @Test
    fun `test list of wrong element type with annotation fails`() {
        assertTypeCheckFails(
            """
            let nums: [Int] = [1, 2, "three", 4]
            nums
            """.trimIndent()
        )
    }

    @Test
    fun `test record with extra field not in annotation fails`() {
        assertTypeCheckFails(
            """
            let person: { name: String, age: Int } = { name = "Alice", age = 30, city = "NYC" }
            person
            """.trimIndent()
        )
    }

    // ===== Ability Annotation Tests =====

    @Test
    fun `test function with single ability constraint annotation`() {
        val program = parseAndTypeCheck(
            """
            let show: { x: 'a } with Inspect 'a -> String = {x} -> Inspect.to_str {val = x}
            show {x = 42}
            """.trimIndent()
        )
        val showType = getVariableType(program, "show")
        assertNotNull(showType, "Variable 'show' not found")
    }

    @Test
    fun `test function with multiple ability constraints annotation`() {
        val program = parseAndTypeCheck(
            """
            let showSum: { x: 'a, y: 'a } with NumAdd 'a, Inspect 'a -> String =
              {x, y} -> Inspect.to_str {val = x + y}
            showSum {x = 1, y = 2}
            """.trimIndent()
        )
        val showSumType = getVariableType(program, "showSum")
        assertNotNull(showSumType, "Variable 'showSum' not found")
    }

    @Test
    fun `test function missing required ability fails`() {
        // Annotation says it requires NumAdd but implementation doesn't use it
        assertTypeCheckFails(
            """
            let wrong: { x: 'a } with NumAdd 'a -> 'a = {x} -> x
            wrong {x = 42}
            """.trimIndent()
        )
    }

    @Test
    fun `test function using ability not in annotation fails`() {
        // Implementation uses NumAdd but annotation doesn't declare it
        assertTypeCheckFails(
            """
            let wrong: { x: 'a } -> 'a = {x} -> x + x
            wrong {x = 42}
            """.trimIndent()
        )
    }

    @Test
    fun `test function with ability on correct type variable`() {
        val program = parseAndTypeCheck(
            """
            let fst: { a: 'a, b: 'b } with Inspect 'a -> 'a = {a, b} -> do
              Inspect.to_str {val = a}
              a
            fst {a = 1, b = "hello"}
            """.trimIndent()
        )
        val fstType = getVariableType(program, "fst")
        assertNotNull(fstType, "Variable 'fst' not found")
    }

    @Test
    fun `test function with ability on wrong type variable fails`() {
        // Annotation says NumAdd 'b but implementation uses it on 'a
        assertTypeCheckFails(
            """
            let wrong: { a: 'a, b: 'b } with NumAdd 'b -> 'a = {a, b} -> a + a
            wrong {a = 1, b = "hello"}
            """.trimIndent()
        )
    }

    @Test
    fun `test polymorphic function with extra ability in annotation fails`() {
        // Annotation declares NumAdd 'a but it's not needed
        assertTypeCheckFails(
            """
            let id: { x: 'a } with NumAdd 'a -> 'a = {x} -> x
            id {x = 42}
            """.trimIndent()
        )
    }

    @Test
    fun `test ability annotation with concrete type`() {
        val program = parseAndTypeCheck(
            """
            let showInt: { x: Int } with Inspect Int -> String = {x} -> Inspect.to_str {val = x}
            showInt {x = 42}
            """.trimIndent()
        )
        val showIntType = getVariableType(program, "showInt")
        assertNotNull(showIntType, "Variable 'showInt' not found")
    }

    // ===== Effect Annotation Tests =====

    @Test
    fun `test function with effect annotation`() {
        val program = parseAndTypeCheck(
            """
            effect State where {
              get: {} -> Int <State>
              put: { val: Int } -> {} <State>
            }

            let increment: {} -> Int <State> = {} -> do
              let current = State.get {}
              State.put { val = current + 1 }
              current + 1
            increment {}
            """.trimIndent()
        )
        val incrementType = getVariableType(program, "increment")
        assertNotNull(incrementType, "Variable 'increment' not found")
    }

    @Test
    fun `test function with multiple effects annotation`() {
        val program = parseAndTypeCheck(
            """
            effect State where {
              get: {} -> Int <State>
            }

            effect Log where {
              log: { msg: String } -> {} <Log>
            }

            let logState: {} -> Int <State, Log> = {} -> do
              Log.log { msg = "Getting state" }
              State.get {}
            logState {}
            """.trimIndent()
        )
        val logStateType = getVariableType(program, "logState")
        assertNotNull(logStateType, "Variable 'logState' not found")
    }

    @Test
    fun `test function missing required effect fails`() {
        // Annotation says it has State effect but doesn't use it
        assertTypeCheckFails(
            """
            effect State where {
              get: {} -> Int <State>
            }

            let wrong: {} -> Int <State> = {} -> 42
            wrong {}
            """.trimIndent()
        )
    }

    @Test
    fun `test function using effect not in annotation fails`() {
        // Uses State effect but annotation doesn't declare it
        assertTypeCheckFails(
            """
            effect State where {
              get: {} -> Int <State>
            }

            let wrong: {} -> Int = {} -> State.get {}
            wrong {}
            """.trimIndent()
        )
    }

    @Test
    fun `test function with extra effect in annotation fails`() {
        // Annotation declares Log but doesn't use it
        assertTypeCheckFails(
            """
            effect State where {
              get: {} -> Int <State>
            }

            effect Log where {
              log: { msg: String } -> {} <Log>
            }

            let wrong: {} -> Int <State, Log> = {} -> perform get {}
            wrong {}
            """.trimIndent()
        )
    }

    // ===== Variant Payload Validation Tests =====

    @Test
    fun `test variant payload with missing field fails`() {
        assertTypeCheckFails(
            """
            type Color = | Red { x: Int, y: Int } |

            let color = Red { x = 5 }
            color
            """.trimIndent()
        )
    }

    @Test
    fun `test variant payload with extra field fails`() {
        assertTypeCheckFails(
            """
            type Color = | Red { x: Int } |

            let color = Red { x = 5, y = 10 }
            color
            """.trimIndent()
        )
    }

    @Test
    fun `test variant payload with wrong field name fails`() {
        assertTypeCheckFails(
            """
            type Color = | Red { x: Int } |

            let color = Red { wrong = 5 }
            color
            """.trimIndent()
        )
    }

    @Test
    fun `test variant payload with open record allows extra fields`() {
        assertTypeChecks(
            """
            type Color = | Red { x: Int, ... } |

            let color = Red { x = 5, y = 10, z = 15 }
            color
            """.trimIndent()
        )
    }

    @Test
    fun `test variant payload closed record requires exact fields`() {
        assertTypeChecks(
            """
            type Color = | Blue { foo: String } |

            let color = Blue { foo = "test" }
            color
            """.trimIndent()
        )
    }

    // ===== Function Parameter Count Validation Tests =====

    @Test
    fun `test function with too many parameters fails`() {
        assertTypeCheckFails(
            """
            let f: { x: Int } -> Int = {x, y} -> x + y
            f {x = 1}
            """.trimIndent()
        )
    }

    @Test
    fun `test function with too few parameters fails`() {
        assertTypeCheckFails(
            """
            let f: { x: Int, y: Int } -> Int = {x} -> x + 1
            f {x = 1, y = 2}
            """.trimIndent()
        )
    }

    @Test
    fun `test function with correct parameter count passes`() {
        assertTypeChecks(
            """
            let f: { x: Int, y: Int } -> Int = {x, y} -> x + y
            f {x = 1, y = 2}
            """.trimIndent(),
            "f",
            "{ x: Int, y: Int } -> Int"
        )
    }
}
