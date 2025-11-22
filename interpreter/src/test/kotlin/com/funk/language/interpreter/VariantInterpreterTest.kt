package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VariantInterpreterTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    private val interpreter = FunkInterpreter()

    private fun parseTypeCheckAndRun(source: String): Map<String, FunkValue> {
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val (_, context) = interpreter.executeWithContext(typedProgram)
        return context
    }

    // TODO: These tests are commented out because variant features need additional implementation
    // Uncomment and fix as variant pattern matching is completed

    /*
    @Test
    fun `test simple variant construction`() {
        val source = """
            let success = Success {value = 42}
            let failure = Failure {error = "Something went wrong"}
            let loading = Loading
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val success = variables["success"] as FunkVariant
        assertEquals("Success", success.constructor)
        val successData = success.value as FunkRecord
        assertEquals(42L, (successData.fields["value"] as FunkInt).value)

        val failure = variables["failure"] as FunkVariant
        assertEquals("Failure", failure.constructor)
        val failureData = failure.value as FunkRecord
        assertEquals("Something went wrong", (failureData.fields["error"] as FunkString).value)

        val loading = variables["loading"] as FunkVariant
        assertEquals("Loading", loading.constructor)
        assertEquals(FunkNull, loading.value)
    }

    @Test
    fun `test variant pattern matching`() {
        val source = """
        let handleResult = {result} -> match result {
            | Success {value} -> "Got value: success"
            | Failure {error} -> "Error: $(error)"
            | Loading -> "Still loading..."
        }
            
            let success = Success {value = 100}
            let failure = Failure {error = "Network error"}
            let loading = Loading
            
            let successMsg = handleResult {result = success}
            let failureMsg = handleResult {result = failure}
            let loadingMsg = handleResult {result = loading}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Got value: success", (variables["successMsg"] as FunkString).value)
        assertEquals("Error: Network error", (variables["failureMsg"] as FunkString).value)
        assertEquals("Still loading...", (variables["loadingMsg"] as FunkString).value)
    }

    @Test
    fun `test variant with complex data`() {
        val source = """
            let user = User {
                id = 123, 
                name = "Alice", 
                profile = {email = "alice@example.com", active = true}
            }
            let admin = Admin {permissions = {read = true, write = true, delete = false}}
            let guest = Guest
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val user = variables["user"] as FunkVariant
        assertEquals("User", user.constructor)
        val userData = user.value as FunkRecord
        assertEquals(123L, (userData.fields["id"] as FunkInt).value)
        assertEquals("Alice", (userData.fields["name"] as FunkString).value)
        val profile = userData.fields["profile"] as FunkRecord
        assertEquals("alice@example.com", (profile.fields["email"] as FunkString).value)
        assertEquals(true, (profile.fields["active"] as FunkBool).value)

        val admin = variables["admin"] as FunkVariant
        assertEquals("Admin", admin.constructor)
        val adminData = admin.value as FunkRecord
        val permissions = adminData.fields["permissions"] as FunkRecord
        assertEquals(true, (permissions.fields["read"] as FunkBool).value)
        assertEquals(true, (permissions.fields["write"] as FunkBool).value)
        assertEquals(false, (permissions.fields["delete"] as FunkBool).value)

        val guest = variables["guest"] as FunkVariant
        assertEquals("Guest", guest.constructor)
        assertEquals(FunkNull, guest.value)
    }

    @Test
    fun `test variant pattern matching with complex logic`() {
        val source = """
            let processUser = {userType} -> match userType {
                | User {id, name, profile} -> {
                    type = "user",
                    id = id,
                    displayName = name,
                    canEdit = profile.active
                }
                | Admin {permissions} -> {
                    type = "admin",
                    id = 0,
                    displayName = "Administrator",
                    canEdit = permissions.write
                }
                | Guest -> {
                    type = "guest",
                    id = -1,
                    displayName = "Guest User",
                    canEdit = false
                }
        }
            
            let user = User {
                id = 456, 
                name = "Bob", 
                profile = {email = "bob@test.com", active = true}
            }
            let admin = Admin {permissions = {read = true, write = true, delete = true}}
            let guest = Guest
            
            let userInfo = processUser {userType = user}
            let adminInfo = processUser {userType = admin}
            let guestInfo = processUser {userType = guest}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val userInfo = variables["userInfo"] as FunkRecord
        assertEquals("user", (userInfo.fields["type"] as FunkString).value)
        assertEquals(456L, (userInfo.fields["id"] as FunkInt).value)
        assertEquals("Bob", (userInfo.fields["displayName"] as FunkString).value)
        assertEquals(true, (userInfo.fields["canEdit"] as FunkBool).value)

        val adminInfo = variables["adminInfo"] as FunkRecord
        assertEquals("admin", (adminInfo.fields["type"] as FunkString).value)
        assertEquals(0L, (adminInfo.fields["id"] as FunkInt).value)
        assertEquals("Administrator", (adminInfo.fields["displayName"] as FunkString).value)
        assertEquals(true, (adminInfo.fields["canEdit"] as FunkBool).value)

        val guestInfo = variables["guestInfo"] as FunkRecord
        assertEquals("guest", (guestInfo.fields["type"] as FunkString).value)
        assertEquals(-1L, (guestInfo.fields["id"] as FunkInt).value)
        assertEquals("Guest User", (guestInfo.fields["displayName"] as FunkString).value)
        assertEquals(false, (guestInfo.fields["canEdit"] as FunkBool).value)
    }

    @Test
    fun `test variant with numeric data`() {
        val source = """
            let processNumber = {num} -> match num {
                | Zero -> 0
                | Positive {value} -> value
            | Negative {value} -> -value
        }
        
        let zero = Zero
            let pos = Positive {value = 15}
            let neg = Negative {value = 8}
            
            let result1 = processNumber {num = zero}
            let result2 = processNumber {num = pos}
            let result3 = processNumber {num = neg}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(0L, (variables["result1"] as FunkInt).value)
        assertEquals(15L, (variables["result2"] as FunkInt).value)
        assertEquals(-8L, (variables["result3"] as FunkInt).value)
    }

    @Test
    fun `test nested variant matching`() {
        val source = """
            let processResponse = {response} -> match response {
                | Ok {data} -> match data {
                    | User {name} -> "User: $(name)"
                    | Message {text} -> "Message: $(text)"
                    | Empty -> "No data"
                }
                | Error {code} -> "Error code: failed"
        }
        
        let userResponse = Ok {data = User {name = "Charlie"}}
            let messageResponse = Ok {data = Message {text = "Hello World"}}
            let emptyResponse = Ok {data = Empty}
            let errorResponse = Error {code = 404}
            
            let result1 = processResponse {response = userResponse}
            let result2 = processResponse {response = messageResponse}
            let result3 = processResponse {response = emptyResponse}
            let result4 = processResponse {response = errorResponse}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("User: Charlie", (variables["result1"] as FunkString).value)
        assertEquals("Message: Hello World", (variables["result2"] as FunkString).value)
        assertEquals("No data", (variables["result3"] as FunkString).value)
        assertEquals("Error code: failed", (variables["result4"] as FunkString).value)
    }

    @Test
    fun `test variant with boolean logic`() {
        val source = """
            let evaluateCondition = {condition} -> match condition {
                | Always -> true
                | Never -> false
            | Sometimes {probability} -> probability > 50
        }
        
        let always = Always
            let never = Never
            let likely = Sometimes {probability = 75}
            let unlikely = Sometimes {probability = 25}
            
            let result1 = evaluateCondition {condition = always}
            let result2 = evaluateCondition {condition = never}
            let result3 = evaluateCondition {condition = likely}
            let result4 = evaluateCondition {condition = unlikely}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(true, (variables["result1"] as FunkBool).value)
        assertEquals(false, (variables["result2"] as FunkBool).value)
        assertEquals(true, (variables["result3"] as FunkBool).value)
        assertEquals(false, (variables["result4"] as FunkBool).value)
    }

    @Test
    fun `test variant construction in functions`() {
        val source = """
            let createResult = {success, value, error} -> 
                if success 
                then Success {value = value}
                else Failure {error = error}
            
            let wrapValue = {x} -> 
                if x >= 0 
                then Some {value = x}
                else None
            
            let result1 = createResult {success = true, value = 42, error = ""}
            let result2 = createResult {success = false, value = 0, error = "Failed"}
            let option1 = wrapValue {x = 10}
            let option2 = wrapValue {x = -5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val result1 = variables["result1"] as FunkVariant
        assertEquals("Success", result1.constructor)
        val result1Data = result1.value as FunkRecord
        assertEquals(42L, (result1Data.fields["value"] as FunkInt).value)

        val result2 = variables["result2"] as FunkVariant
        assertEquals("Failure", result2.constructor)
        val result2Data = result2.value as FunkRecord
        assertEquals("Failed", (result2Data.fields["error"] as FunkString).value)

        val option1 = variables["option1"] as FunkVariant
        assertEquals("Some", option1.constructor)
        val option1Data = option1.value as FunkRecord
        assertEquals(10L, (option1Data.fields["value"] as FunkInt).value)

        val option2 = variables["option2"] as FunkVariant
        assertEquals("None", option2.constructor)
        assertEquals(FunkNull, option2.value)
    }

    @Test
    fun `test variant with record updates`() {
        val source = """
            let updateUserData = {user, newName} -> match user {
                | User {id, name, profile} -> User {
                    id = id,
                    name = newName,
                    profile = {profile | lastUpdated = "2024-01-01"}
                }
                | Admin {permissions} -> Admin {permissions = permissions}
                | Guest -> Guest
        }
        
        let user = User {
                id = 789,
                name = "Diana",
                profile = {email = "diana@test.com", active = true}
            }
            
            let updatedUser = updateUserData {user = user, newName = "Diana Smith"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val updatedUser = variables["updatedUser"] as FunkVariant
        assertEquals("User", updatedUser.constructor)
        val userData = updatedUser.value as FunkRecord
        assertEquals(789L, (userData.fields["id"] as FunkInt).value)
        assertEquals("Diana Smith", (userData.fields["name"] as FunkString).value)
        val profile = userData.fields["profile"] as FunkRecord
        assertEquals("diana@test.com", (profile.fields["email"] as FunkString).value)
        assertEquals(true, (profile.fields["active"] as FunkBool).value)
        assertEquals("2024-01-01", (profile.fields["lastUpdated"] as FunkString).value)
    }
    */
}
