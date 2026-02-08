package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Test suite for exception type inference and handler validation
 *
 * This tests the critical exception type checking feature that ensures
 * handlers match the actual error types thrown by functions.
 */
class ExceptionTypeInferenceTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(source: String) {
        val program = parser.parse(source)
        typeChecker.typeCheck(program).program
    }

    // ========================================================================
    // Test 1: Basic String Error Type Inference
    // ========================================================================

    @Test
    fun `test infer String error type from throw`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            impl Exception for StringHandler having 'error = String where {
                throw = {error} -> error
            }

            pub let divide : {a: Int, b: Int} -> Int <Exception> = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    a / b

            let result = perform StringHandler in divide {a = 10, b = 0}
        """.trimIndent()

        // Should pass - handler matches error type
        typeCheck(code)
    }

    // ========================================================================
    // Test 2: Handler Type Mismatch Caught
    // ========================================================================

    @Test
    fun `test handler type mismatch is caught`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type ValidationError = | FieldRequired {field: String} |

            impl Exception for StringHandler having 'error = String where {
                throw = {error} -> error
            }

            pub let validate : {} -> {} <Exception> = {} ->
                Exception.throw {error = FieldRequired {field = "x"}}

            let bad = perform StringHandler in validate {}
        """.trimIndent()

        // Should fail - handler expects String but function throws ValidationError
        val exception = assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(exception.message?.contains("Handler type mismatch") == true)
        assertTrue(exception.message?.contains("StringHandler") == true)
        assertTrue(exception.message?.contains("String") == true)
    }

    // ========================================================================
    // Test 3: Correct Handler Works
    // ========================================================================

    @Test
    fun `test correct handler type works`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type ValidationError = | FieldRequired {field: String} |

            impl Exception for ValidationHandler having 'error = ValidationError where {
                throw = {error} ->
                    match error is
                        FieldRequired {field} -> None
            }

            pub let validate : {} -> {} <Exception> = {} ->
                Exception.throw {error = FieldRequired {field = "x"}}

            let good = perform ValidationHandler in validate {}
        """.trimIndent()

        // Should pass - handler matches error type
        typeCheck(code)
    }

    // ========================================================================
    // Test 4: Multiple Variant Error Types (Unification)
    // ========================================================================

    @Test
    fun `test multiple variant error types unify correctly`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type ApiError = | BadRequest {msg: String}, NotFound {id: String}, ServerError {reason: String} |

            impl Exception for ApiHandler having 'error = ApiError where {
                throw = {error} ->
                    match error is
                        BadRequest {msg} -> {status = 400}
                        NotFound {id} -> {status = 404}
                        ServerError {reason} -> {status = 500}
            }

            pub let api_call : {id: String} -> {} <Exception> = {id} ->
                if id == "" then
                    Exception.throw {error = BadRequest {msg = "Empty ID"}}
                else if id == "404" then
                    Exception.throw {error = NotFound {id = id}}
                else
                    Exception.throw {error = ServerError {reason = "Unknown"}}

            let result = perform ApiHandler in api_call {id = ""}
        """.trimIndent()

        // Should pass - all variants unify to ApiError
        typeCheck(code)
    }

    // ========================================================================
    // Test 5: Handler with Subset of Variants Fails
    // ========================================================================

    @Test
    fun `test handler with subset of variants fails`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type PartialError = | BadRequest {msg: String} |

            impl Exception for PartialHandler having 'error = PartialError where {
                throw = {error} ->
                    match error is
                        BadRequest {msg} -> {status = 400}
            }

            pub let api_call : {id: String} -> {} <Exception> = {id} ->
                if id == "" then
                    Exception.throw {error = BadRequest {msg = "Empty ID"}}
                else if id == "404" then
                    Exception.throw {error = NotFound {id = id}}
                else
                    Exception.throw {error = ServerError {reason = "Unknown"}}

            let result = perform PartialHandler in api_call {id = ""}
        """.trimIndent()

        // Should fail - handler doesn't handle NotFound and ServerError
        val exception = assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(exception.message?.contains("Handler type mismatch") == true)
    }

    // ========================================================================
    // Test 6: Single Variant in Expression
    // ========================================================================

    @Test
    fun `test single variant error type works`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type ValidationError = | FieldRequired {field: String}, FieldTooShort {field: String} |

            impl Exception for ValidationHandler having 'error = ValidationError where {
                throw = {error} ->
                    match error is
                        FieldRequired {field} -> None
                        FieldTooShort {field} -> None
            }

            pub let validate : {} -> {} <Exception> = {} ->
                Exception.throw {error = FieldRequired {field = "username"}}

            let result = perform ValidationHandler in validate {}
        """.trimIndent()

        // Should pass - handler accepts the variant
        typeCheck(code)
    }

    // ========================================================================
    // Test 7: Effect Without Associated Types (Should Not Check)
    // ========================================================================

    @Test
    fun `test effect without associated types is not checked`() {
        val code = """
            effect Log where {
                log: {msg: String} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                log = {msg} -> {}
            }

            pub let test : {} -> {} <Log> = {} ->
                Log.log {msg = "test"}

            let result = perform ConsoleLogger in test {}
        """.trimIndent()

        // Should pass - no associated types to check
        typeCheck(code)
    }

    // ========================================================================
    // Test 8: Nested Perform Blocks
    // ========================================================================

    @Test
    fun `test nested perform blocks work correctly`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type OuterError = | OuterBad |
            type InnerError = | InnerBad |

            impl Exception for OuterHandler having 'error = OuterError where {
                throw = {error} ->
                    match error is
                        OuterBad -> None
            }

            impl Exception for InnerHandler having 'error = InnerError where {
                throw = {error} ->
                    match error is
                        InnerBad -> None
            }

            pub let inner_fn : {} -> {} <Exception> = {} ->
                Exception.throw {error = InnerBad}

            pub let outer_fn : {} -> {} <Exception> = {} -> do
                let result = perform InnerHandler in inner_fn {}
                Exception.throw {error = OuterBad}

            let final = perform OuterHandler in outer_fn {}
        """.trimIndent()

        // Should pass - each perform has correct handler
        typeCheck(code)
    }

    // ========================================================================
    // Test 9: Direct Effect Call (Not in Function)
    // ========================================================================

    @Test
    fun `test direct effect call in perform block`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type MyError = | ErrorA |

            impl Exception for MyHandler having 'error = MyError where {
                throw = {error} ->
                    match error is
                        ErrorA -> None
            }

            let result = perform MyHandler in Exception.throw {error = ErrorA}
        """.trimIndent()

        // Should pass - direct call matches handler
        typeCheck(code)
    }

    // ========================================================================
    // Test 10: Do Block with Multiple Throws
    // ========================================================================

    @Test
    fun `test do block with multiple throws of same type`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type MyError = | ErrorA, ErrorB |

            impl Exception for MyHandler having 'error = MyError where {
                throw = {error} ->
                    match error is
                        ErrorA -> None
                        ErrorB -> None
            }

            pub let multi_throw : {} -> {} <Exception> = {} -> do
                Exception.throw {error = ErrorA}
                Exception.throw {error = ErrorB}

            let result = perform MyHandler in multi_throw {}
        """.trimIndent()

        // Should pass - all variants handled
        typeCheck(code)
    }

    // ========================================================================
    // Test 11: If Expression with Different Error Types in Branches
    // ========================================================================

    @Test
    fun `test if expression with different variant types in branches`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type MyError = | ErrorA, ErrorB |

            impl Exception for MyHandler having 'error = MyError where {
                throw = {error} ->
                    match error is
                        ErrorA -> None
                        ErrorB -> None
            }

            pub let conditional : {flag: Bool} -> {} <Exception> = {flag} ->
                if flag then
                    Exception.throw {error = ErrorA}
                else
                    Exception.throw {error = ErrorB}

            let result = perform MyHandler in conditional {flag = True}
        """.trimIndent()

        // Should pass - both variants unified and handled
        typeCheck(code)
    }

    // ========================================================================
    // Test 12: Match Expression with Throws in Different Cases
    // ========================================================================

    @Test
    fun `test match expression with throws in different cases`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type Color = | Red, Blue, Green |
            type MyError = | ErrorRed, ErrorBlue, ErrorGreen |

            impl Exception for MyHandler having 'error = MyError where {
                throw = {error} ->
                    match error is
                        ErrorRed -> None
                        ErrorBlue -> None
                        ErrorGreen -> None
            }

            pub let process_color : {c: Color} -> {} <Exception> = {c} ->
                match c is
                    Red -> Exception.throw {error = ErrorRed}
                    Blue -> Exception.throw {error = ErrorBlue}
                    Green -> Exception.throw {error = ErrorGreen}

            let result = perform MyHandler in process_color {c = Red}
        """.trimIndent()

        // Should pass - all error variants covered
        typeCheck(code)
    }

    // ========================================================================
    // Test 13: Wrong Handler Type - Completely Incompatible
    // ========================================================================

    @Test
    fun `test wrong handler type fails with clear error`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type DatabaseError = | ConnectionFailed, QueryFailed {query: String} |
            type NetworkError = | Timeout, ConnectionRefused |

            impl Exception for DbHandler having 'error = DatabaseError where {
                throw = {error} ->
                    match error is
                        ConnectionFailed -> {status = "db_error"}
                        QueryFailed {query} -> {status = "query_error"}
            }

            pub let make_api_call : {} -> {} <Exception> = {} ->
                Exception.throw {error = Timeout}

            let result = perform DbHandler in make_api_call {}
        """.trimIndent()

        // Should fail - DbHandler expects DatabaseError but function throws NetworkError
        val exception = assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(exception.message?.contains("Handler type mismatch") == true)
        assertTrue(exception.message?.contains("DbHandler") == true)
    }

    // ========================================================================
    // Test 14: Four Variants Combine Correctly
    // ========================================================================

    @Test
    fun `test four variants combine into union type`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type ApiError = | BadRequest {msg: String}, NotFound {id: String}, ServerError {reason: String}, Unauthorized |

            impl Exception for ApiHandler having 'error = ApiError where {
                throw = {error} ->
                    match error is
                        BadRequest {msg} -> {status = 400}
                        NotFound {id} -> {status = 404}
                        ServerError {reason} -> {status = 500}
                        Unauthorized -> {status = 401}
            }

            pub let handle_request : {id: String, auth: Bool} -> {} <Exception> = {id, auth} ->
                if auth == False then
                    Exception.throw {error = Unauthorized}
                else if id == "" then
                    Exception.throw {error = BadRequest {msg = "Empty ID"}}
                else if id == "404" then
                    Exception.throw {error = NotFound {id = id}}
                else if id == "500" then
                    Exception.throw {error = ServerError {reason = "Database down"}}
                else
                    {}

            let result = perform ApiHandler in handle_request {id = "", auth = True}
        """.trimIndent()

        // Should pass - all four variants combine and are handled
        typeCheck(code)
    }

    // ========================================================================
    // Test 15: Partial Handler Missing Variants Fails
    // ========================================================================

    @Test
    fun `test partial handler missing variants fails`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type PartialError = | BadRequest {msg: String}, NotFound {id: String} |

            impl Exception for PartialHandler having 'error = PartialError where {
                throw = {error} ->
                    match error is
                        BadRequest {msg} -> {status = 400}
                        NotFound {id} -> {status = 404}
            }

            pub let handle_request : {id: String, auth: Bool} -> {} <Exception> = {id, auth} ->
                if auth == False then
                    Exception.throw {error = Unauthorized}
                else if id == "" then
                    Exception.throw {error = BadRequest {msg = "Empty ID"}}
                else if id == "404" then
                    Exception.throw {error = NotFound {id = id}}
                else if id == "500" then
                    Exception.throw {error = ServerError {reason = "Database down"}}
                else
                    {}

            let result = perform PartialHandler in handle_request {id = "", auth = True}
        """.trimIndent()

        // Should fail - PartialHandler can't handle Unauthorized or ServerError
        val exception = assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(exception.message?.contains("Handler type mismatch") == true)
        assertTrue(exception.message?.contains("PartialHandler") == true)
    }

    // ========================================================================
    // Test 16: Variant Combination in Sequential Branches
    // ========================================================================

    @Test
    fun `test variant combination step by step across branches`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type MyError = | Error1, Error2, Error3 |

            impl Exception for MyHandler having 'error = MyError where {
                throw = {error} ->
                    match error is
                        Error1 -> {}
                        Error2 -> {}
                        Error3 -> {}
            }

            pub let test_fn : {x: Int} -> {} <Exception> = {x} ->
                if x == 1 then
                    Exception.throw {error = Error1}
                else if x == 2 then
                    Exception.throw {error = Error2}
                else
                    Exception.throw {error = Error3}

            let result = perform MyHandler in test_fn {x = 1}
        """.trimIndent()

        // Should pass - all three variants combine: Error1 ∪ Error2 ∪ Error3 = MyError
        typeCheck(code)
    }

    // ========================================================================
    // Test 17: Nested Different Error Types
    // ========================================================================

    @Test
    fun `test nested perform blocks with different error types`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type DatabaseError = | ConnectionFailed, QueryFailed {query: String} |
            type ValidationError = | InvalidInput {field: String} |

            impl Exception for DbHandler having 'error = DatabaseError where {
                throw = {error} ->
                    match error is
                        ConnectionFailed -> {status = "db_error"}
                        QueryFailed {query} -> {status = "query_error"}
            }

            impl Exception for ValidationHandler having 'error = ValidationError where {
                throw = {error} ->
                    match error is
                        InvalidInput {field} -> {status = "validation_error"}
            }

            pub let validate_input : {input: String} -> {} <Exception> = {input} ->
                if input == "" then
                    Exception.throw {error = InvalidInput {field = "input"}}
                else
                    {}

            pub let save_to_db : {data: String} -> {} <Exception> = {data} -> do
                let validated = perform ValidationHandler in validate_input {input = data}
                if data == "fail" then
                    Exception.throw {error = QueryFailed {query = "INSERT"}}
                else
                    {}

            let result = perform DbHandler in save_to_db {data = "test"}
        """.trimIndent()

        // Should pass - each handler matches its respective error type
        typeCheck(code)
    }

    // ========================================================================
    // Test 18: Handler with Superset of Variants Works
    // ========================================================================

    @Test
    fun `test handler with superset of variants works`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            type AllErrors = | Error1, Error2, Error3, Error4, Error5 |

            impl Exception for AllHandler having 'error = AllErrors where {
                throw = {error} ->
                    match error is
                        Error1 -> {}
                        Error2 -> {}
                        Error3 -> {}
                        Error4 -> {}
                        Error5 -> {}
            }

            pub let test_fn : {x: Int} -> {} <Exception> = {x} ->
                if x == 1 then
                    Exception.throw {error = Error1}
                else
                    Exception.throw {error = Error2}

            let result = perform AllHandler in test_fn {x = 1}
        """.trimIndent()

        // Should pass - handler can handle Error1 and Error2 (subset of AllErrors)
        typeCheck(code)
    }
}
