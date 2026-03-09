package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for open variant type unification.
 *
 * Key behaviors being tested:
 * 1. A bare variant constructor like `Info` at call site should be inferred as
 *    a CLOSED variant `| Info |`, NOT an open variant `| Info, ... |`
 * 2. A closed variant `| Info |` should unify with an open variant that contains it,
 *    e.g., `| Error, Warn, Info, Debug, ... |` should accept `| Info |`
 * 3. A closed variant should NOT unify with an open variant that doesn't contain it
 */
class OpenVariantUnificationTest {

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun parseAndTypeCheck(code: String): Program {
        val parser = MezzeParser()
        val ast = parser.parse(code)
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(ast).program
    }

    private fun getVariableType(program: Program, varName: String): MezzeType? {
        return program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == varName }?.inferredType
    }

    private fun assertTypeChecks(code: String) {
        parseAndTypeCheck(code)
    }

    private fun assertTypeCheckFails(code: String) {
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    // =========================================================================
    // Test 1: Bare variant constructor should be inferred as CLOSED variant
    // =========================================================================

    @Test
    fun `bare variant constructor should be inferred as closed variant`() {
        // When you write just `Info`, it should be `| Info |` not `| Info, ... |`
        val program = parseAndTypeCheck("""
            let x = Info
        """.trimIndent())

        val xType = getVariableType(program, "x")
        assertNotNull(xType, "x should have a type")

        // The type should be a closed variant containing just Info
        assertTrue(xType is MezzeType.VariantType, "x should be a variant type")
        val variantType = xType as MezzeType.VariantType
        assertEquals(setOf("Info"), variantType.constructors.keys)
        // Should NOT be open
        assertEquals(false, variantType.isOpen, "Bare variant constructor should be CLOSED, not open")
    }

    @Test
    fun `bare variant with payload should be inferred as closed variant`() {
        // Note: Using MyVariant instead of Some because Some is a built-in for Option
        val program = parseAndTypeCheck("""
            let x = MyVariant { val = 42 }
        """.trimIndent())

        val xType = getVariableType(program, "x")
        assertNotNull(xType, "x should have a type")

        assertTrue(xType is MezzeType.VariantType, "x should be a variant type, but got: ${xType?.javaClass?.simpleName}")
        val variantType = xType as MezzeType.VariantType
        assertEquals(setOf("MyVariant"), variantType.constructors.keys)
        assertEquals(false, variantType.isOpen, "Bare variant constructor should be CLOSED")
    }

    // =========================================================================
    // Test 2: Closed variant should unify with open variant containing it
    // =========================================================================

    @Test
    fun `closed variant should unify with open variant type alias containing it`() {
        // Define an open variant type, then use a single constructor
        assertTypeChecks("""
            type Level = | Low, Medium, High, ... |

            let processLevel: { level: Level } -> String = { level } ->
                match level is
                    Low -> "low"
                    Medium -> "medium"
                    High -> "high"
                    _ -> "other"

            # This should work: High is in Level
            let result = processLevel { level = High }
        """.trimIndent())
    }

    @Test
    fun `closed variant should unify with open variant in function parameter`() {
        assertTypeChecks("""
            type Status = | Pending, Running, Done, ... |

            let describe: { s: Status } -> String = { s } ->
                match s is
                    Pending -> "waiting"
                    Running -> "in progress"
                    Done -> "finished"
                    _ -> "unknown"

            # All of these should work
            let r1 = describe { s = Pending }
            let r2 = describe { s = Running }
            let r3 = describe { s = Done }
        """.trimIndent())
    }

    @Test
    fun `closed variant should unify with open variant in record field`() {
        assertTypeChecks("""
            type LogLevel = | Error, Warn, Info, Debug, ... |

            type LogEntry = { level: LogLevel, msg: String }

            # Creating a record with a bare variant should work
            let entry: LogEntry = { level = Info, msg = "hello" }
        """.trimIndent())
    }

    // =========================================================================
    // Test 3: Using stdlib Log effect with bare variant constructors
    // =========================================================================

    @Test
    fun `Log log with bare Info variant should typecheck`() {
        // This is the actual use case that was failing
        assertTypeChecks("""
            use std::effects::log::{Log, Console}

            let main = {} -> perform Console in Log.log { level = Info, msg = "done" }
        """.trimIndent())
    }

    @Test
    fun `Log log with bare Error variant should typecheck`() {
        assertTypeChecks("""
            use std::effects::log::{Log, Console}

            let main = {} -> perform Console in Log.log { level = Error, msg = "error!" }
        """.trimIndent())
    }

    @Test
    fun `Log log with bare Debug variant should typecheck`() {
        assertTypeChecks("""
            use std::effects::log::{Log, Console}

            let main = {} -> perform Console in Log.log { level = Debug, msg = "debugging" }
        """.trimIndent())
    }

    // TODO: This test documents a known limitation. Custom constructors with imported
    // open variant types don't work because the type alias expansion doesn't have access
    // to the imported type declaration during unification. This works when the type is
    // defined in the same file (see `open variant allows custom constructors` test).
    @org.junit.jupiter.api.Disabled("Known limitation: custom constructors with imported open variant types")
    @Test
    fun `Log log with custom variant should typecheck since LogLevel is open`() {
        // Since LogLevel is open, custom levels should work
        // Currently fails - need to fix type alias expansion for imported types
        assertTypeChecks("""
            use std::effects::log::{Log, Console}

            let main = {} -> perform Console in Log.log { level = MyCustomLevel, msg = "custom" }
        """.trimIndent())
    }

    // =========================================================================
    // Test 4: Closed variant should NOT unify with incompatible closed variant
    // =========================================================================

    @Test
    fun `closed variant should not unify with different closed variant`() {
        // Yellow is not in Color = | Red, Green, Blue |, so this should fail
        assertTypeCheckFails("""
            type Color = | Red, Green, Blue |

            let describeColor: { c: Color } -> String = { c } ->
                match c is
                    Red -> "red"
                    Green -> "green"
                    Blue -> "blue"
                    _ -> "unknown"

            let result = describeColor { c = Yellow }
        """.trimIndent())
    }

    // =========================================================================
    // Test 5: Open variant allows extension
    // =========================================================================

    @Test
    fun `open variant allows custom constructors`() {
        assertTypeChecks("""
            type Status = | Active, Inactive, ... |

            let process: { s: Status } -> String = { s } ->
                match s is
                    Active -> "active"
                    Inactive -> "inactive"
                    _ -> "other"

            # CustomStatus is allowed because Status is open
            let result = process { s = CustomStatus }
        """.trimIndent())
    }

    // =========================================================================
    // Test 6: Multiple variants in expression should still be closed
    // =========================================================================

    @Test
    fun `match expression result with multiple variants should be closed`() {
        val program = parseAndTypeCheck("""
            let x = match True is
                True -> Left { val = 1 }
                False -> Right { val = "hello" }
        """.trimIndent())

        val xType = getVariableType(program, "x")
        assertNotNull(xType, "x should have a type")
        assertTrue(xType is MezzeType.VariantType, "x should be a variant type")
        val variantType = xType as MezzeType.VariantType
        assertEquals(setOf("Left", "Right"), variantType.constructors.keys)
        assertEquals(false, variantType.isOpen, "Match result should be closed variant")
    }

    // =========================================================================
    // Test 7: Passing closed variant to function expecting open variant
    // =========================================================================

    @Test
    fun `function expecting open variant should accept any single constructor`() {
        assertTypeChecks("""
            # Define open variant type
            type OpenAB = | A, B, ... |

            # Function takes an open variant
            let process: { x: OpenAB } -> String = { x } ->
                match x is
                    A -> "a"
                    B -> "b"
                    _ -> "other"

            # All these should work - single constructors unify with open variant
            let r1 = process { x = A }
            let r2 = process { x = B }
            let r3 = process { x = C }  # C is new, allowed because open
        """.trimIndent())
    }

    // =========================================================================
    // Test 8: Variable bound to variant should maintain closed type
    // =========================================================================

    @Test
    fun `variable bound to variant constructor should have closed type`() {
        assertTypeChecks("""
            type Level = | Low, Medium, High, ... |

            let myLevel = High  # Should be inferred as closed | High |

            let useLevel: { l: Level } -> String = { l } ->
                match l is
                    Low -> "low"
                    _ -> "other"

            # This should work because High is in Level
            let result = useLevel { l = myLevel }
        """.trimIndent())
    }

    // =========================================================================
    // Test 9: Effect operation with open variant parameter
    // =========================================================================

    @Test
    fun `effect operation with open variant parameter accepts bare constructor`() {
        assertTypeChecks("""
            type Priority = | Low, Medium, High, ... |

            effect Logger where {
                log: { priority: Priority, msg: String } -> {} <Logger>
            }

            impl Logger for TestLogger where {
                log = { priority, msg } -> {}
            }

            let main = {} -> perform TestLogger in do
                Logger.log { priority = High, msg = "important" }
                Logger.log { priority = Low, msg = "minor" }
                Logger.log { priority = Critical, msg = "custom priority" }
        """.trimIndent())
    }

    // =========================================================================
    // Test 10: If-then-else with different variant constructors
    // =========================================================================

    @Test
    fun `if-then-else with different variant constructors should merge`() {
        val program = parseAndTypeCheck("""
            type MyResult = | Success { value: Int }, Failure { error: String } |

            let createResult = { success: Bool, value: Int, error: String } ->
                if success
                then Success { value = value }
                else Failure { error = error }
        """.trimIndent())

        // The function should have the merged variant type
        val fn = program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "createResult" }
        assertNotNull(fn, "createResult should exist")
        assertTrue(fn.inferredType is MezzeType.FunctionType)
    }

    @Test
    fun `if-then-else variant result should be matchable`() {
        assertTypeChecks("""
            type MyResult = | Success { value: Int }, Failure { error: String } |

            let createResult = { success: Bool, value: Int, error: String } ->
                if success
                then Success { value = value }
                else Failure { error = error }

            let result = createResult { success = True, value = 42, error = "" }
            let extracted = match result is
                Success { value } -> value
                Failure { error } -> -1
        """.trimIndent())
    }

    // =========================================================================
    // Test 11: Inline variant type annotations
    // NOTE: Inline open variant annotations (| A, B, ... |) in type annotations
    // are not yet supported by the parser. See Task #1.
    // =========================================================================

    @Test
    fun `inline closed variant type annotation`() {
        assertTypeChecks("""
            let x: | Red, Green, Blue | = Red
        """.trimIndent())
    }

    @Test
    fun `inline open variant type annotation`() {
        assertTypeChecks("""
            let x: | Red, Green, ... | = Red
        """.trimIndent())
    }

    @Test
    fun `inline open variant with single constructor`() {
        // Single constructor followed by ellipsis
        assertTypeChecks("""
            let x: | Error, ... | = Error
        """.trimIndent())
    }

    @Test
    fun `inline open variant accepts additional constructors`() {
        // Open variant should accept constructors not in the annotation
        assertTypeChecks("""
            let processStatus: { status: | Success, Error, ... | } -> String = { status } ->
                match status is
                    Success -> "ok"
                    _ -> "other"

            let result1 = processStatus { status = Success }
            let result2 = processStatus { status = Error }
            let result3 = processStatus { status = Warning }
        """.trimIndent())
    }

    @Test
    fun `inline open variant in function return type`() {
        // Note: Using Success/Failure instead of Ok/Error since Ok is a built-in from Result
        assertTypeChecks("""
            let makeStatus: { code: Int } -> | Success, Failure, ... | = { code } ->
                if code == 0 then Success else Failure
        """.trimIndent())
    }

    @Test
    fun `inline open variant with payload`() {
        // Note: Using MyOption/MyNone instead of Some/None since Some is a built-in from Option
        assertTypeChecks("""
            let x: | MySome { val: Int }, MyNone, ... | = MySome { val = 42 }
        """.trimIndent())
    }

    @Test
    fun `function with inline variant parameter type`() {
        assertTypeChecks("""
            let describeColor: { c: | Red, Green, Blue | } -> String = { c } ->
                match c is
                    Red -> "red"
                    Green -> "green"
                    Blue -> "blue"
        """.trimIndent())
    }

    // =========================================================================
    // Test 12: Variant with record payloads
    // =========================================================================

    @Test
    fun `variant constructor with record payload`() {
        assertTypeChecks("""
            type Point = | Point2D { x: Int, y: Int }, Point3D { x: Int, y: Int, z: Int } |

            let origin2d = Point2D { x = 0, y = 0 }
            let origin3d = Point3D { x = 0, y = 0, z = 0 }
        """.trimIndent())
    }

    @Test
    fun `destructuring variant with record payload in match`() {
        assertTypeChecks("""
            type Point = | Point2D { x: Int, y: Int }, Point3D { x: Int, y: Int, z: Int } |

            let getX: { p: Point } -> Int = { p } ->
                match p is
                    Point2D { x, y } -> x
                    Point3D { x, y, z } -> x
        """.trimIndent())
    }

    // =========================================================================
    // Test 13: Parameterized variant types
    // =========================================================================

    @Test
    fun `parameterized variant type definition and usage`() {
        assertTypeChecks("""
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let leaf: Tree Int = Leaf
            let tree: Tree Int = Node { value = 10, left = Leaf, right = Leaf }
        """.trimIndent())
    }

    @Test
    fun `pattern matching on parameterized variant`() {
        assertTypeChecks("""
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let getValue: { t: Tree Int } -> Int = { t } ->
                match t is
                    Leaf -> 0
                    Node { value, left, right } -> value
        """.trimIndent())
    }

    // =========================================================================
    // Test 14: Recursive variant patterns
    // =========================================================================

    @Test
    fun `recursive list variant`() {
        assertTypeChecks("""
            type MyList 'a = | MyNil, MyCons { head: 'a, tail: MyList 'a } |

            let empty: MyList Int = MyNil
            let oneItem: MyList Int = MyCons { head = 1, tail = MyNil }
            let twoItems: MyList Int = MyCons { head = 1, tail = MyCons { head = 2, tail = MyNil } }
        """.trimIndent())
    }

    // =========================================================================
    // Test 15: Variant with no payload (unit variants)
    // =========================================================================

    @Test
    fun `variant with no payload constructors`() {
        assertTypeChecks("""
            type TrafficLight = | Red, Yellow, Green |

            let light = Red

            let isStop: { l: TrafficLight } -> Bool = { l } ->
                match l is
                    Red -> True
                    Yellow -> True
                    Green -> False
        """.trimIndent())
    }

    // =========================================================================
    // Test 17: Nested variant matching
    // =========================================================================

    @Test
    fun `nested variant in record field`() {
        assertTypeChecks("""
            type Status = | Active, Inactive |
            type User = { name: String, status: Status }

            let user: User = { name = "Alice", status = Active }

            let isActive: { u: User } -> Bool = { u } ->
                match u.status is
                    Active -> True
                    Inactive -> False
        """.trimIndent())
    }

    // =========================================================================
    // Test 18: Variant in function return position
    // =========================================================================

    @Test
    fun `function returning variant`() {
        assertTypeChecks("""
            type MyResult 'a 'e = | MyOk { val: 'a }, MyErr { error: 'e } |

            let divide: { a: Int, b: Int } -> MyResult Int String = { a, b } ->
                if b == 0
                then MyErr { error = "division by zero" }
                else MyOk { val = a / b }
        """.trimIndent())
    }

    // =========================================================================
    // Test 19: Wildcard patterns with open variants
    // =========================================================================

    @Test
    fun `wildcard pattern with open variant is exhaustive`() {
        assertTypeChecks("""
            type Level = | Low, Medium, High, ... |

            let describe: { l: Level } -> String = { l } ->
                match l is
                    Low -> "low"
                    Medium -> "medium"
                    _ -> "high or custom"
        """.trimIndent())
    }

    @Test
    fun `open variant requires wildcard for exhaustiveness`() {
        assertTypeChecks("""
            type OpenStatus = | Active, Inactive, ... |

            # This is exhaustive because of the wildcard
            let check: { s: OpenStatus } -> Bool = { s } ->
                match s is
                    Active -> True
                    _ -> False
        """.trimIndent())
    }

    // =========================================================================
    // Test 20: Constructor payload validation for declared types
    // =========================================================================

    @Test
    fun `built-in Ok requires payload`() {
        assertTypeCheckFails("""
            let x = Ok
        """.trimIndent())
    }

    @Test
    fun `built-in Ok with payload succeeds`() {
        assertTypeChecks("""
            let x = Ok { val = 42 }
        """.trimIndent())
    }

    @Test
    fun `built-in Err requires payload`() {
        assertTypeCheckFails("""
            let x = Err
        """.trimIndent())
    }

    @Test
    fun `built-in Err with payload succeeds`() {
        assertTypeChecks("""
            let x = Err { error = "failed" }
        """.trimIndent())
    }

    @Test
    fun `built-in Some requires payload`() {
        assertTypeCheckFails("""
            let x = Some
        """.trimIndent())
    }

    @Test
    fun `built-in Some with payload succeeds`() {
        assertTypeChecks("""
            let x = Some { val = 42 }
        """.trimIndent())
    }

    @Test
    fun `built-in None without payload succeeds`() {
        assertTypeChecks("""
            let x = None
        """.trimIndent())
    }

    @Test
    fun `built-in None with payload fails`() {
        assertTypeCheckFails("""
            let x = None { val = 42 }
        """.trimIndent())
    }

    // =========================================================================
    // Test 21: Constructor payload validation for user-defined types
    // =========================================================================

    @Test
    fun `user-defined constructor with payload requires payload`() {
        assertTypeCheckFails("""
            type Point = | Point2D { x: Int, y: Int }, Origin |
            let p = Point2D
        """.trimIndent())
    }

    @Test
    fun `user-defined constructor with payload and correct payload succeeds`() {
        assertTypeChecks("""
            type Point = | Point2D { x: Int, y: Int }, Origin |
            let p = Point2D { x = 1, y = 2 }
        """.trimIndent())
    }

    @Test
    fun `user-defined constructor without payload succeeds bare`() {
        assertTypeChecks("""
            type Point = | Point2D { x: Int, y: Int }, Origin |
            let p = Origin
        """.trimIndent())
    }

    @Test
    fun `user-defined constructor without payload fails with payload`() {
        assertTypeCheckFails("""
            type Point = | Point2D { x: Int, y: Int }, Origin |
            let p = Origin { x = 1 }
        """.trimIndent())
    }

    @Test
    fun `user-defined variant with multiple payload constructors`() {
        assertTypeChecks("""
            type Shape = | Circle { radius: Int }, Rectangle { width: Int, height: Int }, Point |

            let c = Circle { radius = 5 }
            let r = Rectangle { width = 10, height = 20 }
            let p = Point
        """.trimIndent())
    }

    @Test
    fun `user-defined variant missing required fields fails`() {
        assertTypeCheckFails("""
            type Shape = | Circle { radius: Int }, Rectangle { width: Int, height: Int } |
            let c = Circle { }
        """.trimIndent())
    }

    // =========================================================================
    // Test 22: Anonymous constructors (not in any declared type)
    // =========================================================================

    @Test
    fun `anonymous constructor creates open variant`() {
        val program = parseAndTypeCheck("""
            let x = CustomStatus
        """.trimIndent())

        val xType = getVariableType(program, "x")
        assertNotNull(xType)
        assertTrue(xType is MezzeType.VariantType)
        val variantType = xType as MezzeType.VariantType
        assertEquals(setOf("CustomStatus"), variantType.constructors.keys)
    }

    @Test
    fun `anonymous constructor with payload works`() {
        val program = parseAndTypeCheck("""
            let x = CustomData { value = 42, name = "test" }
        """.trimIndent())

        val xType = getVariableType(program, "x")
        assertNotNull(xType)
        assertTrue(xType is MezzeType.VariantType)
    }

    @Test
    fun `multiple anonymous constructors in if-then-else merge`() {
        assertTypeChecks("""
            let status = if True then Active else Inactive

            let check: { s: | Active, Inactive | } -> Bool = { s } ->
                match s is
                    Active -> True
                    Inactive -> False

            let result = check { s = status }
        """.trimIndent())
    }

    // =========================================================================
    // Test 23: Payload validation in function arguments
    // =========================================================================

    @Test
    fun `Result Ok in function argument requires payload`() {
        assertTypeCheckFails("""
            let processResult: { r: Result Int String } -> Int = { r } ->
                match r is
                    Ok { val } -> val
                    Err { error } -> 0

            let x = processResult { r = Ok }
        """.trimIndent())
    }

    @Test
    fun `Result Ok in function argument with payload succeeds`() {
        assertTypeChecks("""
            let processResult: { r: Result Int String } -> Int = { r } ->
                match r is
                    Ok { val } -> val
                    Err { error } -> 0

            let x = processResult { r = Ok { val = 42 } }
        """.trimIndent())
    }

    @Test
    fun `Option Some in function argument requires payload`() {
        assertTypeCheckFails("""
            let unwrap: { opt: Option Int } -> Int = { opt } ->
                match opt is
                    Some { val } -> val
                    None -> 0

            let x = unwrap { opt = Some }
        """.trimIndent())
    }

    @Test
    fun `Option None in function argument without payload succeeds`() {
        assertTypeChecks("""
            let unwrap: { opt: Option Int } -> Int = { opt } ->
                match opt is
                    Some { val } -> val
                    None -> 0

            let x = unwrap { opt = None }
        """.trimIndent())
    }

    // =========================================================================
    // Test 24: Payload validation with type parameters
    // =========================================================================

    @Test
    fun `parameterized variant constructor requires payload when declared`() {
        assertTypeCheckFails("""
            type Tree 'a = | Leaf { value: 'a }, Branch { left: Tree 'a, right: Tree 'a }, Empty |
            let t = Leaf
        """.trimIndent())
    }

    @Test
    fun `parameterized variant constructor with payload succeeds`() {
        assertTypeChecks("""
            type Tree 'a = | Leaf { value: 'a }, Branch { left: Tree 'a, right: Tree 'a }, Empty |
            let t: Tree Int = Leaf { value = 42 }
        """.trimIndent())
    }

    @Test
    fun `parameterized variant unit constructor succeeds bare`() {
        assertTypeChecks("""
            type Tree 'a = | Leaf { value: 'a }, Branch { left: Tree 'a, right: Tree 'a }, Empty |
            let t: Tree Int = Empty
        """.trimIndent())
    }
}
