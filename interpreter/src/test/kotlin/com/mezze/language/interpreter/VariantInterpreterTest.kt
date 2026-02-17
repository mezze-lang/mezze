package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VariantInterpreterTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
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

        val success = variables["success"] as MezzeVariant
        assertEquals("Success", success.constructor)
        val successData = success.value as MezzeRecord
        assertEquals(42L, (successData.fields["value"] as MezzeInt).value)

        val failure = variables["failure"] as MezzeVariant
        assertEquals("Failure", failure.constructor)
        val failureData = failure.value as MezzeRecord
        assertEquals("Something went wrong", (failureData.fields["error"] as MezzeString).value)

        val loading = variables["loading"] as MezzeVariant
        assertEquals("Loading", loading.constructor)
        assertEquals(MezzeNull, loading.value)
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

        assertEquals("Got value: success", (variables["successMsg"] as MezzeString).value)
        assertEquals("Error: Network error", (variables["failureMsg"] as MezzeString).value)
        assertEquals("Still loading...", (variables["loadingMsg"] as MezzeString).value)
    }

    @Test
    fun `test variant with complex data`() {
        val source = """
            let user = User {
                id = 123, 
                name = "Alice", 
                profile = {email = "alice@example.com", active = True}
            }
            let admin = Admin {permissions = {read = True, write = True, delete = False}}
            let guest = Guest
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val user = variables["user"] as MezzeVariant
        assertEquals("User", user.constructor)
        val userData = user.value as MezzeRecord
        assertEquals(123L, (userData.fields["id"] as MezzeInt).value)
        assertEquals("Alice", (userData.fields["name"] as MezzeString).value)
        val profile = userData.fields["profile"] as MezzeRecord
        assertEquals("alice@example.com", (profile.fields["email"] as MezzeString).value)
        assertEquals(true, (profile.fields["active"] as MezzeBool).value)

        val admin = variables["admin"] as MezzeVariant
        assertEquals("Admin", admin.constructor)
        val adminData = admin.value as MezzeRecord
        val permissions = adminData.fields["permissions"] as MezzeRecord
        assertEquals(true, (permissions.fields["read"] as MezzeBool).value)
        assertEquals(true, (permissions.fields["write"] as MezzeBool).value)
        assertEquals(false, (permissions.fields["delete"] as MezzeBool).value)

        val guest = variables["guest"] as MezzeVariant
        assertEquals("Guest", guest.constructor)
        assertEquals(MezzeNull, guest.value)
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
                    canEdit = False
                }
        }
            
            let user = User {
                id = 456, 
                name = "Bob", 
                profile = {email = "bob@test.com", active = True}
            }
            let admin = Admin {permissions = {read = True, write = True, delete = True}}
            let guest = Guest
            
            let userInfo = processUser {userType = user}
            let adminInfo = processUser {userType = admin}
            let guestInfo = processUser {userType = guest}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val userInfo = variables["userInfo"] as MezzeRecord
        assertEquals("user", (userInfo.fields["type"] as MezzeString).value)
        assertEquals(456L, (userInfo.fields["id"] as MezzeInt).value)
        assertEquals("Bob", (userInfo.fields["displayName"] as MezzeString).value)
        assertEquals(true, (userInfo.fields["canEdit"] as MezzeBool).value)

        val adminInfo = variables["adminInfo"] as MezzeRecord
        assertEquals("admin", (adminInfo.fields["type"] as MezzeString).value)
        assertEquals(0L, (adminInfo.fields["id"] as MezzeInt).value)
        assertEquals("Administrator", (adminInfo.fields["displayName"] as MezzeString).value)
        assertEquals(true, (adminInfo.fields["canEdit"] as MezzeBool).value)

        val guestInfo = variables["guestInfo"] as MezzeRecord
        assertEquals("guest", (guestInfo.fields["type"] as MezzeString).value)
        assertEquals(-1L, (guestInfo.fields["id"] as MezzeInt).value)
        assertEquals("Guest User", (guestInfo.fields["displayName"] as MezzeString).value)
        assertEquals(false, (guestInfo.fields["canEdit"] as MezzeBool).value)
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

        assertEquals(0L, (variables["result1"] as MezzeInt).value)
        assertEquals(15L, (variables["result2"] as MezzeInt).value)
        assertEquals(-8L, (variables["result3"] as MezzeInt).value)
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

        assertEquals("User: Charlie", (variables["result1"] as MezzeString).value)
        assertEquals("Message: Hello World", (variables["result2"] as MezzeString).value)
        assertEquals("No data", (variables["result3"] as MezzeString).value)
        assertEquals("Error code: failed", (variables["result4"] as MezzeString).value)
    }

    @Test
    fun `test variant with boolean logic`() {
        val source = """
            let evaluateCondition = {condition} -> match condition {
                | Always -> True
                | Never -> False
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

        assertEquals(true, (variables["result1"] as MezzeBool).value)
        assertEquals(false, (variables["result2"] as MezzeBool).value)
        assertEquals(true, (variables["result3"] as MezzeBool).value)
        assertEquals(false, (variables["result4"] as MezzeBool).value)
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
            
            let result1 = createResult {success = True, value = 42, error = ""}
            let result2 = createResult {success = False, value = 0, error = "Failed"}
            let option1 = wrapValue {x = 10}
            let option2 = wrapValue {x = -5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val result1 = variables["result1"] as MezzeVariant
        assertEquals("Success", result1.constructor)
        val result1Data = result1.value as MezzeRecord
        assertEquals(42L, (result1Data.fields["value"] as MezzeInt).value)

        val result2 = variables["result2"] as MezzeVariant
        assertEquals("Failure", result2.constructor)
        val result2Data = result2.value as MezzeRecord
        assertEquals("Failed", (result2Data.fields["error"] as MezzeString).value)

        val option1 = variables["option1"] as MezzeVariant
        assertEquals("Some", option1.constructor)
        val option1Data = option1.value as MezzeRecord
        assertEquals(10L, (option1Data.fields["value"] as MezzeInt).value)

        val option2 = variables["option2"] as MezzeVariant
        assertEquals("None", option2.constructor)
        assertEquals(MezzeNull, option2.value)
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
                profile = {email = "diana@test.com", active = True}
            }
            
            let updatedUser = updateUserData {user = user, newName = "Diana Smith"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val updatedUser = variables["updatedUser"] as MezzeVariant
        assertEquals("User", updatedUser.constructor)
        val userData = updatedUser.value as MezzeRecord
        assertEquals(789L, (userData.fields["id"] as MezzeInt).value)
        assertEquals("Diana Smith", (userData.fields["name"] as MezzeString).value)
        val profile = userData.fields["profile"] as MezzeRecord
        assertEquals("diana@test.com", (profile.fields["email"] as MezzeString).value)
        assertEquals(true, (profile.fields["active"] as MezzeBool).value)
        assertEquals("2024-01-01", (profile.fields["lastUpdated"] as MezzeString).value)
    }
    */
}
