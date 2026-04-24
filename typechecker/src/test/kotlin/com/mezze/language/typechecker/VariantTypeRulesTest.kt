package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertContains

/**
 * Comprehensive tests for variant type rules.
 *
 * These tests cover:
 * - A. With Type Declaration + With Type Annotation
 * - B. With Type Declaration + Without Type Annotation
 * - C. Without Type Declaration + With Inline Annotation
 * - D. Without Type Declaration + Without Annotation
 * - E. Function Parameter Inference
 * - F. Exhaustiveness & Unreachable Patterns
 * - G. Constructor Ambiguity
 * - H. Cross-Type Assignment
 * - I. Mixing Declared + Anonymous
 * - J. Variants with Payloads
 * - K. Payload Mismatches
 * - L. Guard Conditions
 */
class VariantTypeRulesTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun assertTypeCheckFails(code: String, expectedMessagePart: String? = null): TypeCheckError {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        if (expectedMessagePart != null) {
            assertContains(error.message ?: "", expectedMessagePart, ignoreCase = true)
        }
        return error
    }

    private fun assertTypeCheckPasses(code: String): TypeCheckResult {
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
        return result
    }

    // ========================================================================
    // A. WITH TYPE DECLARATION + WITH TYPE ANNOTATION
    // ========================================================================

    @Test
    fun `A1 - closed variant with annotation accepts valid constructor`() {
        val code = """
            type Color = | Red, Blue |
            let x: Color = Red
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `A2 - closed variant with annotation rejects unknown constructor`() {
        val code = """
            type Color = | Red, Blue |
            let y: Color = Green
        """.trimIndent()
        assertTypeCheckFails(code, "Green")
    }

    @Test
    fun `A3 - open variant with annotation accepts valid constructor`() {
        val code = """
            type Color = | Red, Blue, ... |
            let x: Color = Red
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `A4 - open variant with annotation accepts unknown constructor`() {
        val code = """
            type Color = | Red, Blue, ... |
            let y: Color = Green
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    // ========================================================================
    // B. WITH TYPE DECLARATION + WITHOUT TYPE ANNOTATION
    // ========================================================================

    @Test
    fun `B1 - constructor from closed type infers that type`() {
        val code = """
            type Color = | Red, Blue |
            let x = Red
        """.trimIndent()
        assertTypeCheckPasses(code)
        // x should have type Color
    }

    @Test
    fun `B2 - unknown constructor creates anonymous variant`() {
        val code = """
            type Color = | Red, Blue |
            let y = Green
        """.trimIndent()
        assertTypeCheckPasses(code)
        // y should have type | Green | (anonymous)
    }

    @Test
    fun `B3 - constructor from open type infers that type`() {
        val code = """
            type Color = | Red, Blue, ... |
            let x = Red
        """.trimIndent()
        assertTypeCheckPasses(code)
        // x should have type Color
    }

    @Test
    fun `B4 - unknown constructor with open type in scope creates anonymous`() {
        val code = """
            type Color = | Red, Blue, ... |
            let y = Green
        """.trimIndent()
        assertTypeCheckPasses(code)
        // y should have type | Green | (anonymous, not Color)
    }

    // ========================================================================
    // C. WITHOUT TYPE DECLARATION + WITH INLINE ANNOTATION
    // ========================================================================

    @Test
    fun `C1 - inline closed variant accepts valid constructor`() {
        val code = """
            let x: | Red, Blue | = Red
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `C2 - inline closed variant rejects unknown constructor`() {
        // Inline variant annotations now have synthetic typeName "<inline>" set,
        // so the unification code validates constructors properly.
        val code = """
            let y: | Red, Blue | = Green
        """.trimIndent()
        assertTypeCheckFails(code, "Green")
    }

    @Test
    fun `C3 - inline open variant accepts valid constructor`() {
        val code = """
            let x: | Red, Blue, ... | = Red
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `C4 - inline open variant accepts unknown constructor`() {
        val code = """
            let y: | Red, Blue, ... | = Green
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    // ========================================================================
    // D. WITHOUT TYPE DECLARATION + WITHOUT ANNOTATION
    // ========================================================================

    @Test
    fun `D1 - bare constructor creates anonymous closed variant`() {
        val code = """
            let x = Red
        """.trimIndent()
        assertTypeCheckPasses(code)
        // x should have type | Red |
    }

    @Test
    fun `D2 - different bare constructor creates its own variant`() {
        val code = """
            let y = Green
        """.trimIndent()
        assertTypeCheckPasses(code)
        // y should have type | Green |
    }

    @Test
    fun `D3 - anonymous variants merge in branches`() {
        val code = """
            let z = { flag } -> if flag then Red else Green
        """.trimIndent()
        assertTypeCheckPasses(code)
        // z should have type { flag: Bool } -> | Red, Green |
    }

    @Test
    fun `D4 - multiple anonymous variants merge`() {
        val code = """
            let x = { n } -> match n is
                1 -> Red
                2 -> Green
                _ -> Blue
        """.trimIndent()
        assertTypeCheckPasses(code)
        // Result type should be | Red, Green, Blue |
    }

    // ========================================================================
    // E. FUNCTION PARAMETER INFERENCE
    // ========================================================================

    // E1: With type declaration, exhaustive match
    @Test
    fun `E1a - function with declared type match accepts valid constructor`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red -> 1
                Blue -> 2

            let x = f { c = Red }
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `E1b - function with declared type match rejects unknown constructor`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red -> 1
                Blue -> 2

            let x = f { c = Green }
        """.trimIndent()
        assertTypeCheckFails(code, "Green")
    }

    // E2: With type declaration, partial match with wildcard
    @Test
    fun `E2a - function with declared type and wildcard accepts valid constructor`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red -> 1
                _ -> 0

            let x = f { c = Red }
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `E2b - function with declared type and wildcard still rejects unknown constructor`() {
        // Wildcard doesn't make the declared type open
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red -> 1
                _ -> 0

            let x = f { c = Green }
        """.trimIndent()
        assertTypeCheckFails(code, "Green")
    }

    // E3: Without type declaration, exhaustive match
    @Test
    fun `E3a - function with inferred closed type accepts valid constructor`() {
        val code = """
            let f = { c } -> match c is
                Red -> 1
                Blue -> 2

            let x = f { c = Red }
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `E3b - function with inferred closed type rejects unknown constructor`() {
        // Inferred closed variants now have synthetic typeName "<inferred>" set,
        // so the unification code validates constructors properly.
        val code = """
            let f = { c } -> match c is
                Red -> 1
                Blue -> 2

            let x = f { c = Green }
        """.trimIndent()
        assertTypeCheckFails(code, "Green")
    }

    // E4: Without type declaration, with wildcard
    @Test
    fun `E4a - function with wildcard infers open type accepts known constructor`() {
        val code = """
            let f = { c } -> match c is
                Red -> 1
                _ -> 0

            let x = f { c = Red }
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `E4b - function with wildcard infers open type accepts unknown constructor`() {
        // Wildcard makes inferred type open: | Red, ... |
        val code = """
            let f = { c } -> match c is
                Red -> 1
                _ -> 0

            let x = f { c = Green }
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    // ========================================================================
    // F. EXHAUSTIVENESS & UNREACHABLE PATTERNS
    // ========================================================================

    @Test
    fun `F1 - non-exhaustive match on closed variant fails`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c: Color } -> match c is
                Red -> 1
        """.trimIndent()
        assertTypeCheckFails(code, "Missing case")
    }

    @Test
    fun `F2 - wildcard after all constructors of closed variant is unreachable`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red -> 1
                Blue -> 2
                _ -> 0
        """.trimIndent()
        assertTypeCheckFails(code, "unreachable")
    }

    @Test
    fun `F3 - inferred closed variant without wildcard is exhaustive`() {
        val code = """
            let f = { c } -> match c is
                Red -> 1
        """.trimIndent()
        assertTypeCheckPasses(code)
        // c inferred as | Red | which is exhaustively matched
    }

    @Test
    fun `F4 - wildcard makes inferred type open`() {
        val code = """
            let f = { c } -> match c is
                Red -> 1
                _ -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
        // c inferred as | Red, ... | which needs wildcard
    }

    @Test
    fun `F5 - open type with wildcard is not unreachable`() {
        val code = """
            type Event = | Click, Hover, ... |

            let f = { e } -> match e is
                Click -> 1
                Hover -> 2
                _ -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `F5b - open type without wildcard fails`() {
        // Open variants MUST have a wildcard to handle unknown constructors
        val code = """
            type Color = | Blue, Red, ... |

            let my_func = { x, y, c } -> match c is
                Red -> x + y
                Blue -> x - y
        """.trimIndent()
        assertTypeCheckFails(code, "wildcard")
    }

    @Test
    fun `F6 - variable pattern after all constructors is unreachable`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red -> 1
                Blue -> 2
                other -> 3
        """.trimIndent()
        assertTypeCheckFails(code, "unreachable")
    }

    // ========================================================================
    // G. CONSTRUCTOR AMBIGUITY
    // ========================================================================

    @Test
    fun `G1 - duplicate constructor in multiple types fails`() {
        // You can't define the same constructor name in multiple types
        val code = """
            type Color = | Red, Blue |
            type Status = | Red, Green |

            let x = Red
        """.trimIndent()
        assertTypeCheckFails(code, "Duplicate constructor")
    }

    @Test
    fun `G2 - non-ambiguous constructors from different types work`() {
        val code = """
            type Color = | Red, Blue |
            type Status = | Active, Inactive |

            let x = Red
            let y = Active
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `G3 - constructor with type annotation works`() {
        // When there's only one type defining the constructor, annotation is optional
        val code = """
            type Color = | Red, Blue |

            let x: Color = Red
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    // ========================================================================
    // H. CROSS-TYPE ASSIGNMENT
    // ========================================================================

    @Test
    fun `H1 - constructor from different type cannot be assigned to closed variant`() {
        val code = """
            type Color = | Red, Blue |
            type Animal = | Cat, Dog |

            let x: Color = Cat
        """.trimIndent()
        assertTypeCheckFails(code, "Cat")
    }

    @Test
    fun `H2 - constructor from different type cannot be assigned to open variant`() {
        // Even though Color is open, Cat belongs to Animal
        val code = """
            type Color = | Red, Blue, ... |
            type Animal = | Cat, Dog |

            let x: Color = Cat
        """.trimIndent()
        assertTypeCheckFails(code, "Cat")
    }

    @Test
    fun `H3 - same constructor name from wrong type fails`() {
        val code = """
            type Color = | Red, Blue |
            type Signal = | Red, Yellow, Green |

            let c: Color = Red
            let s: Signal = c
        """.trimIndent()
        // c is Color, cannot assign to Signal even though both have Red
        assertTypeCheckFails(code)
    }

    // ========================================================================
    // I. MIXING DECLARED + ANONYMOUS
    // ========================================================================

    @Test
    fun `I1 - cannot merge declared type with anonymous constructor`() {
        val code = """
            type Color = | Red, Blue |

            let x = { flag } -> if flag then Red else Green
        """.trimIndent()
        // Red is Color, Green is anonymous - can't merge
        assertTypeCheckFails(code)
    }

    @Test
    fun `I2 - anonymous constructors without type declaration merge fine`() {
        val code = """
            let x = { flag } -> if flag then Red else Green
        """.trimIndent()
        // No type declaration, both are anonymous
        assertTypeCheckPasses(code)
    }

    @Test
    fun `I3 - all branches from same declared type work`() {
        val code = """
            type Color = | Red, Blue, Green |

            let x = { flag } -> if flag then Red else Green
        """.trimIndent()
        // Both Red and Green are from Color
        assertTypeCheckPasses(code)
    }

    @Test
    fun `I4 - match branches from same declared type work`() {
        val code = """
            type Color = | Red, Blue, Green |

            let f = { n } -> match n is
                1 -> Red
                2 -> Blue
                _ -> Green
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `I5 - match branches mixing declared and anonymous fails`() {
        val code = """
            type Color = | Red, Blue |

            let f = { n } -> match n is
                1 -> Red
                2 -> Blue
                _ -> Pink
        """.trimIndent()
        // Red/Blue are Color, Pink is anonymous
        assertTypeCheckFails(code)
    }

    // ========================================================================
    // J. VARIANTS WITH PAYLOADS
    // ========================================================================

    @Test
    fun `J1 - constructor with payload in closed variant`() {
        val code = """
            type Result = | Ok { value: Int }, Err { message: Str } |

            let x: Result = Ok { value = 42 }
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `J2 - constructor with payload inferred correctly`() {
        val code = """
            type Result = | Ok { value: Int }, Err { message: Str } |

            let x = Ok { value = 42 }
            let y = Err { message = "failed" }
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `J3 - pattern match extracts payload`() {
        val code = """
            type Result = | Ok { value: Int }, Err { message: Str } |

            let f = { r } -> match r is
                Ok { value } -> value
                Err { message } -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `J4 - mixed payload and unit constructors`() {
        val code = """
            type Option = | Some { value: Int }, None |

            let f = { o } -> match o is
                Some { value } -> value
                None -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `J5 - open variant with payload accepts new constructor`() {
        val code = """
            type Response = | Success { data: Str }, Failure { code: Int }, ... |

            let x: Response = Unknown
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `J6 - anonymous variant with payload`() {
        val code = """
            let x = Wrap { value = 42 }
            let y = Empty
            let z = { flag } -> if flag then x else y
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    // ========================================================================
    // K. PAYLOAD MISMATCHES
    // ========================================================================

    @Test
    fun `K1 - missing required payload fails`() {
        val code = """
            type Option = | Some { value: Int }, None |

            let x: Option = Some
        """.trimIndent()
        assertTypeCheckFails(code, "payload")
    }

    @Test
    fun `K2 - extra payload on unit constructor fails`() {
        val code = """
            type Option = | Some { value: Int }, None |

            let x: Option = None { value = 42 }
        """.trimIndent()
        assertTypeCheckFails(code, "payload")
    }

    @Test
    fun `K3 - wrong payload field name fails`() {
        val code = """
            type Option = | Some { value: Int }, None |

            let x: Option = Some { wrong = 42 }
        """.trimIndent()
        assertTypeCheckFails(code)
    }

    @Test
    fun `K4 - wrong payload type fails`() {
        val code = """
            type Option = | Some { value: Int }, None |

            let x: Option = Some { value = "hello" }
        """.trimIndent()
        assertTypeCheckFails(code)
    }

    @Test
    fun `K5 - pattern without destructuring on payload constructor fails`() {
        val code = """
            type Option = | Some { value: Int }, None |

            let f = { o } -> match o is
                Some -> 1
                None -> 0
        """.trimIndent()
        assertTypeCheckFails(code, "payload")
    }

    @Test
    fun `K6 - pattern with destructuring on unit constructor fails`() {
        val code = """
            type Option = | Some { value: Int }, None |

            let f = { o } -> match o is
                Some { value } -> value
                None { x } -> 0
        """.trimIndent()
        assertTypeCheckFails(code, "payload")
    }

    @Test
    fun `K7 - payload type mismatch in different branches`() {
        val code = """
            let x = { flag } ->
                if flag
                then Some { value = 42 }
                else Some { value = "hello" }
        """.trimIndent()
        assertTypeCheckFails(code)
    }

    // ========================================================================
    // L. GUARD CONDITIONS
    // ========================================================================

    @Test
    fun `L1 - guard makes pattern non-exhaustive`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c, x } -> match c is
                Red if x > 0 -> 1
                Blue -> 2
        """.trimIndent()
        // Red with guard doesn't fully cover Red
        assertTypeCheckFails(code, "Missing case")
    }

    @Test
    fun `L2 - wildcard after guarded patterns is reachable`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c, x } -> match c is
                Red if x > 0 -> 1
                Blue -> 2
                _ -> 0
        """.trimIndent()
        // Wildcard catches Red when x <= 0
        assertTypeCheckPasses(code)
    }

    @Test
    fun `L3 - all constructors with guards need wildcard`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c, x } -> match c is
                Red if x > 0 -> 1
                Blue if x > 0 -> 2
                _ -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `L4 - unguarded pattern after guarded same constructor is reachable`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c, x } -> match c is
                Red if x > 0 -> 1
                Red -> 2
                Blue -> 3
        """.trimIndent()
        // Second Red catches when guard fails
        assertTypeCheckPasses(code)
    }

    @Test
    fun `L5 - guard with payload extraction`() {
        val code = """
            type Option = | Some { value: Int }, None |

            let f = { o } -> match o is
                Some { value } if value > 0 -> value
                Some { value } -> 0
                None -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `L6 - guard expression type must be Bool`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c, x } -> match c is
                Red if x -> 1
                Red -> 2
                Blue -> 3
        """.trimIndent()
        // x must be Bool for guard; second Red catches when guard fails
        assertTypeCheckPasses(code)
    }

    @Test
    fun `L7 - wildcard with guard is not exhaustive by itself`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c: Color, x } -> match c is
                _ if x > 0 -> 1
        """.trimIndent()
        // Guard could fail, so not exhaustive
        assertTypeCheckFails(code, "Missing case")
    }

    @Test
    fun `L8 - guarded wildcard followed by unguarded wildcard is ok`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c, x } -> match c is
                Red if x > 0 -> 1
                _ if x < 0 -> 2
                _ -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    // ========================================================================
    // ADDITIONAL EDGE CASES
    // ========================================================================

    @Test
    fun `nested variant types work correctly`() {
        val code = """
            type Inner = | A, B |
            type Outer = | X { inner: Inner }, Y |

            let f = { o } -> match o is
                X { inner } -> match inner is
                    A -> 1
                    B -> 2
                Y -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `polymorphic variant type works`() {
        val code = """
            type Option 'a = | Some { value: 'a }, None |

            let x: Option Int = Some { value = 42 }
            let y: Option Str = Some { value = "hello" }
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `or-pattern covers multiple constructors`() {
        val code = """
            type Color = | Red, Blue, Green |

            let f = { c } -> match c is
                Red | Blue -> 1
                Green -> 2
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `or-pattern followed by wildcard on closed variant is unreachable`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red | Blue -> 1
                _ -> 2
        """.trimIndent()
        assertTypeCheckFails(code, "unreachable")
    }

    // ========================================================================
    // M. BUILT-IN RESULT TYPE MATCHING
    // ========================================================================

    @Test
    fun `M1 - Result type Ok constructor infers full type with Err`() {
        // When using Ok, the full Result type should be inferred including Err with payload
        val code = """
            let f = {} -> do
                let r = Ok {val = 42}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `M2 - Result type Err constructor infers full type with Ok`() {
        // When using Err, the full Result type should be inferred including Ok with payload
        val code = """
            let f = {} -> do
                let r = Err {error = "oops"}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `M3 - Result type exhaustive match with both constructors`() {
        val code = """
            let handle_result = { r } -> match r is
                Ok {val = v} -> v
                Err {error = e} -> 0

            let test = {} -> do
                let x = handle_result {r = Ok {val = 42}}
                let y = handle_result {r = Err {error = "fail"}}
                x + y
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `M4 - Result type missing Err case is not exhaustive`() {
        val code = """
            let f = { r } -> match r is
                Ok {val} -> val
        """.trimIndent()
        assertTypeCheckFails(code, "Missing case")
    }

    @Test
    fun `M5 - Result type missing Ok case is not exhaustive`() {
        val code = """
            let f = { r } -> match r is
                Err {error} -> 0
        """.trimIndent()
        assertTypeCheckFails(code, "Missing case")
    }

    @Test
    fun `M6 - Result with wildcard is exhaustive`() {
        val code = """
            let f = {} -> do
                let r = Ok {val = 42}
                match r is
                    Ok {val} -> val
                    _ -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `M7 - Option type Some constructor infers full type with None`() {
        // When using Some, the full Option type should be inferred including None
        val code = """
            let f = {} -> do
                let o = Some {val = 42}
                match o is
                    Some {val} -> val
                    None -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }

    @Test
    fun `M8 - Option type None constructor infers full type with Some`() {
        val code = """
            let f = {} -> do
                let o: Option Int = None
                match o is
                    Some {val} -> val
                    None -> 0
        """.trimIndent()
        assertTypeCheckPasses(code)
    }
}
