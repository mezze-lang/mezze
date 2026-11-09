package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.interpreter.values.MezzeRecord

/**
 * Tests for newtype functionality.
 * Migrated from V1 to V2 with unboxed values.
 */
class NewtypeInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========== Basic Construction Tests ==========

    @Test
    fun `test simple newtype with Str`() {
        val code = """
            newtype Email = Str
            let email = Email.("test@example.com")
            match email is
                Email.(value) -> value
        """
        assertEquals("test@example.com", eval(code))
    }

    @Test
    fun `test simple newtype with Int`() {
        val code = """
            newtype UserId = Int
            let userId = UserId.(42)
            match userId is
                UserId.(value) -> value
        """
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test simple newtype with Float`() {
        val code = """
            newtype Temperature = Float
            let temp = Temperature.(98.6)
            match temp is
                Temperature.(value) -> value
        """
        assertEquals(98.6, eval(code) as Double, 0.001)
    }

    // ========== Newtype with Records ==========

    @Test
    fun `test newtype with record type - x coordinate`() {
        val code = """
            newtype Point = { x: Float, y: Float }
            let point = Point.({x = 3.0, y = 4.0})
            match point is
                Point.({x, y}) -> x
        """
        assertEquals(3.0, eval(code) as Double, 0.001)
    }

    @Test
    fun `test newtype with record type - y coordinate`() {
        val code = """
            newtype Point = { x: Float, y: Float }
            let point = Point.({x = 3.0, y = 4.0})
            match point is
                Point.({x, y}) -> y
        """
        assertEquals(4.0, eval(code) as Double, 0.001)
    }

    @Test
    fun `test newtype origin point`() {
        val code = """
            newtype Point = { x: Float, y: Float }
            let origin = Point.({x = 0.0, y = 0.0})
            match origin is
                Point.({x, y}) -> x + y
        """
        assertEquals(0.0, eval(code) as Double, 0.001)
    }

    // ========== Pattern Matching Tests ==========

    @Test
    fun `test newtype pattern matching`() {
        val code = """
            newtype Email = Str
            let extractEmail = {e} -> match e is
                Email.(value) -> value
            let email = Email.("alice@example.com")
            extractEmail {e = email}
        """
        assertEquals("alice@example.com", eval(code))
    }

    @Test
    fun `test newtype pattern matching with record - x`() {
        val code = """
            newtype Point = { x: Float, y: Float }
            let getX = {p} -> match p is
                Point.({x, y}) -> x
            let point = Point.({x = 5.0, y = 12.0})
            getX {p = point}
        """
        assertEquals(5.0, eval(code) as Double, 0.001)
    }

    @Test
    fun `test newtype pattern matching with record - y`() {
        val code = """
            newtype Point = { x: Float, y: Float }
            let getY = {p} -> match p is
                Point.({x, y}) -> y
            let point = Point.({x = 5.0, y = 12.0})
            getY {p = point}
        """
        assertEquals(12.0, eval(code) as Double, 0.001)
    }

    // ========== Helper Function Tests ==========

    @Test
    fun `test newtype with constructor helper function`() {
        val code = """
            newtype Email = Str
            let mkEmail = {s} -> Email.(s)
            let unwrapEmail = {e} -> match e is
                Email.(value) -> value
            let email = mkEmail {s = "bob@test.com"}
            unwrapEmail {e = email}
        """
        assertEquals("bob@test.com", eval(code))
    }

    @Test
    fun `test newtype with validation function - valid age`() {
        val code = """
            newtype Age = Int
            let mkAge = {n} ->
                if n >= 0
                then Age.(n)
                else Age.(0)
            let validAge = mkAge {n = 25}
            match validAge is
                Age.(value) -> value
        """
        assertEquals(25L, eval(code))
    }

    @Test
    fun `test newtype with validation function - invalid age`() {
        val code = """
            newtype Age = Int
            let mkAge = {n} ->
                if n >= 0
                then Age.(n)
                else Age.(0)
            let invalidAge = mkAge {n = -5}
            match invalidAge is
                Age.(value) -> value
        """
        assertEquals(0L, eval(code))
    }

    // ========== Multiple Newtypes Tests ==========

    @Test
    fun `test multiple distinct newtypes - email`() {
        val code = """
            newtype Email = Str
            newtype UserId = Int
            newtype Name = Str
            let email = Email.("user@test.com")
            match email is
                Email.(value) -> value
        """
        assertEquals("user@test.com", eval(code))
    }

    @Test
    fun `test multiple distinct newtypes - userId`() {
        val code = """
            newtype Email = Str
            newtype UserId = Int
            newtype Name = Str
            let userId = UserId.(123)
            match userId is
                UserId.(value) -> value
        """
        assertEquals(123L, eval(code))
    }

    @Test
    fun `test multiple distinct newtypes - name`() {
        val code = """
            newtype Email = Str
            newtype UserId = Int
            newtype Name = Str
            let name = Name.("John Doe")
            match name is
                Name.(value) -> value
        """
        assertEquals("John Doe", eval(code))
    }

    @Test
    fun `test newtype type safety with distinct wrappers - email`() {
        val code = """
            newtype Email = Str
            newtype Name = Str
            let processEmail = {e} -> match e is
                Email.(value) -> value
            let email = Email.("test@example.com")
            processEmail {e = email}
        """
        assertEquals("test@example.com", eval(code))
    }

    @Test
    fun `test newtype type safety with distinct wrappers - name`() {
        val code = """
            newtype Email = Str
            newtype Name = Str
            let processName = {n} -> match n is
                Name.(value) -> value
            let name = Name.("Alice")
            processName {n = name}
        """
        assertEquals("Alice", eval(code))
    }

    // ========== Nested Newtypes Tests ==========

    @Test
    fun `test nested newtype pattern matching`() {
        val code = """
            newtype UserId = Int
            newtype User = { id: UserId, name: Str }
            let unwrapUserId = {uid} -> match uid is
                UserId.(value) -> value
            let getUserId = {u} -> match u is
                User.({id, name}) -> unwrapUserId {uid = id}
            let userId = UserId.(789)
            let user = User.({id = userId, name = "Charlie"})
            getUserId {u = user}
        """
        assertEquals(789L, eval(code))
    }

    // ========== Function Parameter/Return Tests ==========

    @Test
    fun `test newtype as function parameter`() {
        val code = """
            newtype Distance = Float
            let double = {d} -> match d is
                Distance.(value) -> Distance.(value * 2.0)
            let dist = Distance.(10.0)
            let doubled = double {d = dist}
            match doubled is
                Distance.(value) -> value
        """
        assertEquals(20.0, eval(code) as Double, 0.001)
    }

    @Test
    fun `test newtype as function return value - success`() {
        val code = """
            newtype MyResult = Str
            let createResult = {msg} -> MyResult.(msg)
            let result1 = createResult {msg = "Success"}
            match result1 is
                MyResult.(value) -> value
        """
        assertEquals("Success", eval(code))
    }

    @Test
    fun `test newtype as function return value - failure`() {
        val code = """
            newtype MyResult = Str
            let createResult = {msg} -> MyResult.(msg)
            let result2 = createResult {msg = "Failure"}
            match result2 is
                MyResult.(value) -> value
        """
        assertEquals("Failure", eval(code))
    }

    // ========== Complex Use Cases ==========

    @Test
    fun `test newtype with complex business logic`() {
        val code = """
            newtype Price = Float
            newtype Quantity = Float
            newtype Total = Float
            let calculateTotal = {price, qty} ->
                match price is
                    Price.(p) -> match qty is
                        Quantity.(q) -> Total.(p * q)
            let price = Price.(19.99)
            let qty = Quantity.(3.0)
            let total = calculateTotal {price = price, qty = qty}
            match total is
                Total.(value) -> value
        """
        assertEquals(59.97, eval(code) as Double, 0.01)
    }

    @Test
    fun `test newtype composition`() {
        val code = """
            newtype FirstName = Str
            newtype LastName = Str
            newtype FullName = Str
            let combineNames = {first, last} ->
                match first is
                    FirstName.(f) -> match last is
                        LastName.(l) -> FullName.(f)
            let firstName = FirstName.("John")
            let lastName = LastName.("Smith")
            let fullName = combineNames {first = firstName, last = lastName}
            match fullName is
                FullName.(value) -> value
        """
        assertEquals("John", eval(code))
    }

    @Test
    fun `test newtype with conditional logic - grade A`() {
        val code = """
            newtype Score = Int
            newtype Grade = Str
            let calculateGrade = {s} -> match s is
                Score.(value) ->
                    if value >= 90
                    then Grade.("A")
                    else if value >= 80
                    then Grade.("B")
                    else if value >= 70
                    then Grade.("C")
                    else Grade.("F")
            let score1 = Score.(95)
            let grade1 = calculateGrade {s = score1}
            match grade1 is
                Grade.(value) -> value
        """
        assertEquals("A", eval(code))
    }

    @Test
    fun `test newtype with conditional logic - grade B`() {
        val code = """
            newtype Score = Int
            newtype Grade = Str
            let calculateGrade = {s} -> match s is
                Score.(value) ->
                    if value >= 90
                    then Grade.("A")
                    else if value >= 80
                    then Grade.("B")
                    else if value >= 70
                    then Grade.("C")
                    else Grade.("F")
            let score2 = Score.(85)
            let grade2 = calculateGrade {s = score2}
            match grade2 is
                Grade.(value) -> value
        """
        assertEquals("B", eval(code))
    }

    @Test
    fun `test newtype with conditional logic - grade C`() {
        val code = """
            newtype Score = Int
            newtype Grade = Str
            let calculateGrade = {s} -> match s is
                Score.(value) ->
                    if value >= 90
                    then Grade.("A")
                    else if value >= 80
                    then Grade.("B")
                    else if value >= 70
                    then Grade.("C")
                    else Grade.("F")
            let score3 = Score.(75)
            let grade3 = calculateGrade {s = score3}
            match grade3 is
                Grade.(value) -> value
        """
        assertEquals("C", eval(code))
    }

    @Test
    fun `test newtype with conditional logic - grade F`() {
        val code = """
            newtype Score = Int
            newtype Grade = Str
            let calculateGrade = {s} -> match s is
                Score.(value) ->
                    if value >= 90
                    then Grade.("A")
                    else if value >= 80
                    then Grade.("B")
                    else if value >= 70
                    then Grade.("C")
                    else Grade.("F")
            let score4 = Score.(65)
            let grade4 = calculateGrade {s = score4}
            match grade4 is
                Grade.(value) -> value
        """
        assertEquals("F", eval(code))
    }

    // ========== Derived Inspect: what opacity does NOT cover ==========

    /**
     * `to_dbg` on a newtype SHOWS the payload. Decided, not accidental.
     *
     * Opacity is complete against code and absent against inspection: from
     * outside the defining module a value can be neither constructed nor
     * destructured (M0803, pinned in NewtypeOpacitySpecTest), but `to_dbg`
     * renders it in full — and a derived impl cannot know the caller's module,
     * so what it shows here it shows everywhere.
     *
     * The alternative was Roc's, where an opaque type inspects to `<opaque>` by
     * default "so it doesn't leak implementation details", with the
     * payload-showing version opt-in via `implements [Inspect]`. Rust goes the
     * other way: `#[derive(Debug)]` on a newtype prints `Secret(7)`.
     *
     * Mezze takes Rust's: opacity here means TYPE ABSTRACTION — no code may
     * depend on the representation — not secrecy. Hiding the payload would cost
     * every one of the 39 stdlib newtypes its debug output, and for `Nat`,
     * `Duration` or `MutInt` there is nothing to hide.
     *
     * And it cannot be overridden at all. `impl Inspect for Secret` is V0407,
     * "`Inspect` is derived by the compiler for `Secret`" — true for a locally
     * declared ability of that name too — and an inherent `to_dbg` is V0422, so
     * neither route exists. There is therefore NO way to hide a newtype's
     * payload from `to_dbg`, which is the decision rather than an oversight: an
     * author who needs a value kept out of debug output should not reach for
     * opacity, because opacity here is about what code may depend on, not about
     * what a reader may see.
     *
     * Pinned because NewtypeDeriveSpecTest only asserts that `to_dbg`
     * TYPECHECKS; nothing asserted what it rendered, so a change to `<opaque>`
     * would have been silent.
     */
    @Test
    fun `derived Inspect renders the newtype's payload`() {
        assertEquals("Secret.(7)", eval("newtype Secret = Int\nSecret.(7).to_dbg {}"))
        assertEquals("Token.(\"abc\")", eval("newtype Token = Str\nToken.(\"abc\").to_dbg {}"))
        assertEquals(
            "Ids.([1, 2, 3])",
            eval("newtype Ids = [Int]\nIds.([1, 2, 3]).to_dbg {}"),
        )
    }

    /** The wrapper is visible in the rendering — the type name is not hidden either. */
    @Test
    fun `derived Inspect names the newtype, not just the payload`() {
        val wrapped = eval("newtype Secret = Int\nSecret.(7).to_dbg {}")
        val bare = eval("(7).to_dbg {}")
        assertEquals("7", bare)
        assertNotEquals(bare, wrapped, "a wrapped value must not render as its payload alone")
    }

    /**
     * An inherent `to_dbg` is REFUSED, because it would silently replace the
     * derived `Inspect` rather than add anything.
     *
     * This was accepted until V0422, and it WON: measured, the program below
     * rendered `<hidden>` where the derived `Inspect` renders `Secret.(7)`. So
     * an inherent method quietly displaced a compiler-derived ability op, while
     * the ability spelling of the same intent — `impl Inspect for Secret` — was
     * refused outright by V0407, "derived by the compiler for `Secret`". Two
     * routes to one thing, one refused loudly and one taken silently.
     *
     * `Inspect` and `Hash` are in scope for every type whether imported or not,
     * so this collision was one an author could not notice. It is the mirror of
     * V0420, which stops an ABILITY impl whose method an inherent one would
     * displace; this direction had no check.
     */
    @Test
    fun `an inherent to_dbg is refused rather than silently replacing Inspect`() {
        val e = assertThrows(Throwable::class.java) {
            eval(
                """
                newtype Secret = Int
                impl Secret where { to_dbg = { it: Secret } -> "<hidden>" }
                Secret.(7).to_dbg {}
                """.trimIndent(),
            )
        }
        val msg = e.message ?: ""
        assertTrue(
            "answers for you" in msg || "V0422" in msg,
            "expected the derived-op collision to be reported; got: $msg",
        )
        // And the derived rendering is what a newtype still gets.
        assertEquals("Secret.(7)", eval("newtype Secret = Int\nSecret.(7).to_dbg {}"))
    }

    /**
     * The whole reserved set, and each name is here because it produced a WRONG
     * ANSWER rather than an error — measured, before V0422:
     *
     *   to_dbg  rendered `<hidden>`, silently replacing the derived `Inspect`
     *   hash    the other ability derived for every type
     *   eq      made `a == b` False for two equal records, while a `Map` keyed
     *           on them still treated them as ONE key — and on a newtype made
     *           `==` work with no `Eq` impl, bypassing the matching-`Hash` rule
     *   lt      made `a < b` answer the inherent method, bypassing `Ord`
     */
    @Test
    fun `every reserved op name is refused on an inherent impl`() {
        val cases = mapOf(
            "to_dbg" to "{ it: P } -> \"x\"",
            "hash" to "{ it: P } -> 1",
            "eq" to "{ it: P, r: P } -> True",
            "neq" to "{ it: P, r: P } -> False",
            "lt" to "{ it: P, r: P } -> True",
            "le" to "{ it: P, r: P } -> True",
            "gt" to "{ it: P, r: P } -> False",
            "ge" to "{ it: P, r: P } -> False",
        )
        for ((name, body) in cases) {
            val e = assertThrows(Throwable::class.java) {
                eval(
                    """
                    type P = { x: Int }
                    impl P where { $name = $body }
                    1
                    """.trimIndent(),
                )
            }
            val msg = e.message ?: ""
            assertTrue(
                "answers for you" in msg || "V0422" in msg,
                "`$name` must be refused on an inherent impl; got: $msg",
            )
        }
    }

    /**
     * The ARITHMETIC names are NOT reserved, and that is measured rather than
     * assumed: an inherent `add` does not make `+` work — `+` still demands the
     * `NumAdd` impl — so `add` is an ordinary method name. The stdlib depends on
     * this in `Seq`, `SortedSet` and `WaitGroup`, where `add` appends an element.
     */
    @Test
    fun `an inherent add is allowed and does not become the plus operator`() {
        assertEquals(
            6L,
            MezzeTestHelper.asLong(
                eval(
                    """
                    use std::int
                    type Bag = { n: Int }
                    impl Bag where { add = { it: Bag, x: Int } -> { n = it.n + x } }
                    let a: Bag = { n = 1 }
                    a.add { x = 5 } .n
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** A name that is NOT reserved stays fine — the rule is narrow. */
    @Test
    fun `an inherent method with an ordinary name is still allowed`() {
        assertEquals(
            "<shown>",
            eval(
                """
                newtype Secret = Int
                impl Secret where { describe = { it: Secret } -> "<shown>" }
                Secret.(7).describe {}
                """.trimIndent(),
            ),
        )
    }
}
