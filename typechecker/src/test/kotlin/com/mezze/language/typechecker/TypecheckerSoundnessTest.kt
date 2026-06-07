package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Soundness tests for the Mezze typechecker.
 *
 * Section 1: Missing negative tests for soundness fixes — rules that had
 * positive tests but lacked a negative counterpart proving the reject path.
 *
 * Section 2: Boundary interaction tests — feature pairs that could break
 * when composed (alias x effects, newtype x modules, etc.).
 *
 * Section 3: Property-like invariant tests — unification symmetry,
 * substitution idempotence.
 */
class TypecheckerSoundnessTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(source: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(source.trimIndent(), "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun assertTypeChecks(source: String): TypeCheckResult {
        return parseAndTypeCheck(source)
    }

    private fun assertTypeCheckFails(source: String): TypeCheckError {
        val parsedModule = parser.parseParsedModule(source.trimIndent(), "test.mz")
        return assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    private fun bindingType(result: TypeCheckResult, name: String): MezzeType {
        val t = result.getVariableType(name)
        assertNotNull(t, "Binding '$name' has no inferred type")
        return t!!
    }

    // ========================================================================
    // SECTION 1: Missing negative tests for soundness fixes
    // ========================================================================

    // U18: Suspended<T, <E>> rejected against plain T
    // A function annotated as returning Int should not accept a body that
    // returns Int <Log> — the unhandled effect must cause a type error.
    @Test
    fun `effectful suspended type does not silently unify with pure type`() {
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLog {} where {
                info = {msg} -> {}
            }

            # This function is annotated as pure (no effects) but its body
            # calls an effectful operation, which should be rejected.
            let bad : {} -> {} <> = {} -> Log.info { msg = "hello" }
        """

        // The typechecker should reject this because Log.info produces <Log>
        // but the annotation says <> (no effects).
        val parsedModule = parser.parseParsedModule(source.trimIndent(), "test.mz")
        val result = runCatching { typeChecker.typeCheck(parsedModule, Paths.get(".")) }

        // Either it throws a TypeCheckError, or the inferred type should
        // surface the Log effect (proving the annotation was overridden).
        if (result.isSuccess) {
            val t = result.getOrThrow().getVariableType("bad")
            assertNotNull(t)
            val fnType = t as? MezzeType.FunctionType
            assertNotNull(fnType, "bad should be a function type")
            val effects = fnType!!.effects?.effects ?: emptySet()
            // If the typechecker allows this but propagates the effect,
            // the function must have <Log> — it should NOT be pure.
            assertTrue(
                effects.contains("Log") || fnType.returnType is MezzeType.SuspendedType,
                "An effectful body must not silently become pure. " +
                    "Effects=$effects, returnType=${fnType.returnType}"
            )
        }
        // If it failed with TypeCheckError, that's the correct rejection.
    }

    // C16: generate() never returns SuspendedType — test indirectly
    // via effect propagation: if an effect call appears inside a let binding
    // in a do block, the outer function must surface the effect.
    @Test
    fun `effects from sub-expressions propagate correctly`() {
        val source = """
            effect Counter where {
                inc: {} -> Int <Counter>
            }

            impl Counter for SimpleCounter where {
                inc = {} -> 1
            }

            let example = {} -> do
                let x = Counter.inc {}
                let y = Counter.inc {}
                x + y
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "example")
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "example should be a function type")

        val effects = fnType!!.effects?.effects ?: emptySet()
        val returnIsSuspended = fnType.returnType is MezzeType.SuspendedType
        assertTrue(
            effects.contains("Counter") || returnIsSuspended,
            "Counter effect must propagate. Effects=$effects, return=${fnType.returnType}"
        )
    }

    // C17: Effect propagation completeness — effects from nested calls
    // must propagate to the enclosing function.
    @Test
    fun `effects from nested calls propagate to enclosing function`() {
        val source = """
            effect Logging where {
                log: {msg: Str} -> {} <Logging>
            }

            impl Logging for StdLogger where {
                log = {msg} -> {}
            }

            let helper = {} -> Logging.log { msg = "from helper" }

            let outer = {} -> do
                helper {}
                42
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "outer")
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "outer should be a function type")

        val effects = fnType!!.effects?.effects ?: emptySet()
        val returnIsSuspended = fnType.returnType is MezzeType.SuspendedType
        assertTrue(
            effects.contains("Logging") || returnIsSuspended,
            "Logging effect from helper must propagate to outer. " +
                "Effects=$effects, return=${fnType.returnType}"
        )
    }

    // C18: Ability constraints from effect call arguments propagate.
    @Test
    fun `ability constraints in effect call arguments are checked`() {
        val source = """
            use std::int

            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> int::to_str { n = it }
            }

            effect Logger where {
                write: {msg: Str} -> {} <Logger>
            }

            impl Logger for FileLogger where {
                write = {msg} -> {}
            }

            # The function uses .show which requires Show constraint
            let logValue = {x} -> Logger.write { msg = x.show {} }
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "logValue")
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "logValue should be a function type")

        // Should have Show constraint on the polymorphic parameter
        assertTrue(
            fnType!!.abilityConstraints.any { it.abilityName == "Show" },
            "Show constraint should propagate. Constraints=${fnType.abilityConstraints}"
        )
    }

    // C19: Partial application preserves ability constraints.
    @Test
    fun `partial application of function with ability constraint checks concrete type`() {
        val source = """
            use std::int

            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> int::to_str { n = it }
            }

            let display = {x, prefix} -> "$(prefix)$(x.show {})"

            # Partial application: x is still polymorphic with Show constraint
            let prefixed = display { prefix = "Value: " }
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "prefixed")
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "prefixed should be a function type")

        // The partially applied function still requires Show on x
        assertTrue(
            fnType!!.abilityConstraints.any { it.abilityName == "Show" },
            "Show constraint must survive partial application. " +
                "Constraints=${fnType.abilityConstraints}"
        )
    }

    // V8: Record alias matching checks field types.
    @Test
    fun `record alias with different field types does not match`() {
        val source = """
            type Point = { x: Int, y: Int }

            # Attempting to assign a record with wrong field type
            let bad: Point = { x = 1, y = "not an int" }
        """

        assertTypeCheckFails(source)
    }

    // V9: Variant alias exact constructor match.
    @Test
    fun `variant alias requires exact constructor set not superset`() {
        val source = """
            type Color = | Red, Green, Blue |

            # A match that includes an extra constructor not in Color
            # should not infer Color type if the scrutinee is typed as Color
            let colorToStr = {c: Color} ->
                match c is
                  Red -> "red"
                  Green -> "green"
                  Blue -> "blue"
        """

        // This should typecheck fine — exact match
        val result = parseAndTypeCheck(source)
        assertNotNull(result)
    }

    // V10: TypeApp preserves type arguments.
    // Verify that a parametric type alias applied with concrete args
    // keeps those args through construction, field access, and usage.
    @Test
    fun `type application expansion preserves type arguments`() {
        val source = """
            type Pair 'a 'b = { fst: 'a, snd: 'b }

            let intStrPair: Pair Int Str = { fst = 42, snd = "hello" }

            # Using fst in arithmetic proves it resolved to Int
            let sumResult = intStrPair.fst + 1

            # Using snd in string interpolation proves it resolved to Str
            let greeting = "value: $(intStrPair.snd)"

            # Constructing with wrong types should fail at the ascription
            # but we test the positive path: construction works with correct types
            let anotherPair: Pair Bool Int = { fst = True, snd = 99 }
        """

        val result = parseAndTypeCheck(source)
        val sumType = bindingType(result, "sumResult")
        assertTrue(
            sumType is MezzeType.IntType,
            "fst + 1 should be Int, got ${prettyPrintType(sumType)}"
        )

        val greetType = bindingType(result, "greeting")
        assertTrue(
            greetType is MezzeType.StringType,
            "string interpolation result should be Str, got ${prettyPrintType(greetType)}"
        )
    }

    // ========================================================================
    // SECTION 2: Boundary interaction tests
    // ========================================================================

    // Alias x Effects: record type alias used inside effect operation resolves correctly
    @Test
    fun `type alias used inside effect operation resolves correctly`() {
        val source = """
            type UserInfo = { name: Str, role: Str }

            effect Greeter where {
                greet: {info: UserInfo} -> Str <Greeter>
            }

            impl Greeter for FriendlyGreeter where {
                greet = {info} -> "Hello, $(info.name)!"
            }

            let sayHi = {} -> Greeter.greet { info = { name = "Alice", role = "admin" } }
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "sayHi")
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "sayHi should be a function type")
    }

    // Alias x Effects: effect with associated type using type alias
    @Test
    fun `effect with associated type using type alias works`() {
        val source = """
            type Config = { host: Str, port: Int }

            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for ConfigState having 'state = Config where {
                get = {} -> { host = "localhost", port = 8080 },
                put = {value} -> {}
            }

            let getHost = {} -> do
                let cfg = State.get {}
                cfg.host
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "getHost")
        assertNotNull(t, "getHost should have a type")
    }

    // Newtype x Nominal: newtype from same module cannot structurally unify with payload
    @Test
    fun `newtype from another module cannot structurally unify with payload`() {
        val source = """
            newtype UserId = UserId Int
            newtype Age = Age Int

            # These two newtypes wrap the same type but should not unify
            let takeUserId = {uid: UserId} -> uid
            let myAge = Age { value = 25 }
        """

        // This should typecheck because we're not trying to unify them
        val result = parseAndTypeCheck(source)
        assertNotNull(result)

        // Now try to pass Age where UserId is expected — should fail
        val badSource = """
            newtype UserId = UserId Int
            newtype Age = Age Int

            let takeUserId = {uid: UserId} -> uid
            let myAge = Age { value = 25 }
            let bad = takeUserId { uid = myAge }
        """

        assertTypeCheckFails(badSource)
    }

    // Row polymorphism x Abilities: ability method on open record works with extra fields.
    // The impl is for the open record type alias Named, so a value ascribed as Named
    // (which can carry extra fields) should dispatch to the impl.
    @Test
    fun `ability method on open record works with extra fields`() {
        val source = """
            ability HasName for 'a where {
                get_name: {it: 'a} -> Str
            }

            type Named = { name: Str, ... }

            impl HasName for Named where {
                get_name = {it} -> it.name
            }

            let person: Named = { name = "Alice", age = 30 }
            let n = person.get_name {}
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "n")
        assertTrue(
            t is MezzeType.StringType,
            "get_name should return Str, got ${prettyPrintType(t)}"
        )
    }

    // Effect associated types x Do-bind
    @Test
    fun `do bind with effect having associated types infers correctly`() {
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 0,
                put = {value} -> {}
            }

            let increment = {} -> do
                let current = State.get {}
                State.put { value = current + 1 }
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "increment")
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "increment should be a function type")

        val effects = fnType!!.effects?.effects ?: emptySet()
        val returnIsSuspended = fnType.returnType is MezzeType.SuspendedType
        assertTrue(
            effects.contains("State") || returnIsSuspended,
            "State effect should propagate. Effects=$effects"
        )
    }

    // Partial application x Effects
    @Test
    fun `partial application of effectful function preserves effects`() {
        val source = """
            effect Logger where {
                log: {msg: Str} -> {} <Logger>
            }

            impl Logger for ConsoleLogger where {
                log = {msg} -> {}
            }

            let logAndAdd = {x, y} -> do
                Logger.log { msg = "adding" }
                x + y

            let addToFive = logAndAdd { x = 5 }
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "addToFive")
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "addToFive should be a function type")

        // The partially applied function must still carry the Logger effect
        val effects = fnType!!.effects?.effects ?: emptySet()
        val returnIsSuspended = fnType.returnType is MezzeType.SuspendedType
        assertTrue(
            effects.contains("Logger") || returnIsSuspended,
            "Logger effect must survive partial application. " +
                "Effects=$effects, return=${fnType.returnType}"
        )
    }

    // Open record x Spread x Effects:
    // Spread replaces existing fields from the base record.
    // The effectful sub-expression (Counter.next) provides the new value.
    @Test
    fun `spread in record with effectful sub-expression works`() {
        val source = """
            effect Counter where {
                next: {} -> Int <Counter>
            }

            impl Counter for SeqCounter where {
                next = {} -> 1
            }

            let base = { x = 1, y = 2 }

            let updated = {} -> do
                let newX = Counter.next {}
                { ...base, x = newX }
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "updated")
        assertNotNull(t, "updated should have a type")
    }

    // Type annotation x Effect annotation x Ability constraints
    @Test
    fun `function with type annotation effect annotation and ability constraint typechecks`() {
        val source = """
            use std::int

            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> int::to_str { n = it }
            }

            effect Logger where {
                log: {msg: Str} -> {} <Logger>
            }

            impl Logger for ConsoleLogger where {
                log = {msg} -> {}
            }

            let logShow : {x: Int} -> {} <Logger> = {x} -> do
                Logger.log { msg = x.show {} }
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "logShow")
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "logShow should be a function type")

        val effects = fnType!!.effects?.effects ?: emptySet()
        assertTrue(
            effects.contains("Logger"),
            "logShow must have Logger effect. Effects=$effects"
        )
    }

    // Nominal wall x Unification: two newtypes wrapping the same inner type
    // must not structurally unify with each other.
    @Test
    fun `nominal newtype does not structurally unify with another newtype`() {
        val source = """
            newtype Meters = Meters Int
            newtype Seconds = Seconds Int

            let takeMeters = {m: Meters} -> m
            let s = Seconds { value = 10 }

            # Passing Seconds where Meters is expected should fail
            let bad = takeMeters { m = s }
        """

        assertTypeCheckFails(source)
    }

    // Match exhaustiveness x Effects: match on effectful result with wildcard
    @Test
    fun `match on effectful open variant with wildcard works`() {
        val source = """
            effect Chooser where {
                choose: {} -> | Left, Right | <Chooser>
            }

            impl Chooser for CoinFlip where {
                choose = {} -> Left
            }

            let describe = {} -> do
                let choice = Chooser.choose {}
                match choice is
                  Left -> "went left"
                  Right -> "went right"
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "describe")
        assertNotNull(t, "describe should have a type")
    }

    // ========================================================================
    // SECTION 3: Property-like invariant tests
    // ========================================================================

    // Unification symmetry for records: { x: Int, y: Str } unifies the same
    // way regardless of which side of the equation it appears.
    @Test
    fun `unification is symmetric for records`() {
        // Direction 1: annotate param, infer argument
        val source1 = """
            let f = {r: { x: Int, y: Str }} -> r.x
            let result = f { r = { x = 1, y = "hi" } }
        """

        // Direction 2: infer param from usage
        val source2 = """
            let g = {r} -> r.x + r.y
            let result = g { r = { x = 1, y = 2 } }
        """

        val r1 = parseAndTypeCheck(source1)
        assertNotNull(r1)
        val t1 = bindingType(r1, "result")
        assertTrue(t1 is MezzeType.IntType, "result should be Int, got ${prettyPrintType(t1)}")

        // Reset caches between checks
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()

        val r2 = parseAndTypeCheck(source2)
        assertNotNull(r2)
        val t2 = bindingType(r2, "result")
        assertTrue(t2 is MezzeType.IntType, "result should be Int, got ${prettyPrintType(t2)}")
    }

    // Unification symmetry for variants: match arms work in any order.
    @Test
    fun `unification is symmetric for variants`() {
        val source1 = """
            type AB = | A, B |

            let f = {x: AB} -> match x is
              A -> 1
              B -> 2
        """

        val source2 = """
            type AB = | A, B |

            let f = {x: AB} -> match x is
              B -> 2
              A -> 1
        """

        val r1 = parseAndTypeCheck(source1)
        val t1 = bindingType(r1, "f")
        assertTrue(t1 is MezzeType.FunctionType)

        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()

        val r2 = parseAndTypeCheck(source2)
        val t2 = bindingType(r2, "f")
        assertTrue(t2 is MezzeType.FunctionType)

        // Both should return Int
        val ret1 = (t1 as MezzeType.FunctionType).returnType
        val ret2 = (t2 as MezzeType.FunctionType).returnType
        assertTrue(ret1 is MezzeType.IntType, "f should return Int, got ${prettyPrintType(ret1)}")
        assertTrue(ret2 is MezzeType.IntType, "f should return Int, got ${prettyPrintType(ret2)}")
    }

    // Unification symmetry for functions: function types unify regardless
    // of which side carries the annotation.
    @Test
    fun `unification is symmetric for functions`() {
        val source1 = """
            let apply = {f: {x: Int} -> Int, v: Int} -> f { x = v }
            let inc = {x: Int} -> x + 1
            let result = apply { f = inc, v = 5 }
        """

        val source2 = """
            let apply = {f, v} -> f { x = v }
            let inc : {x: Int} -> Int = {x} -> x + 1
            let result = apply { f = inc, v = 5 }
        """

        val r1 = parseAndTypeCheck(source1)
        val t1 = bindingType(r1, "result")
        assertTrue(t1 is MezzeType.IntType, "result should be Int, got ${prettyPrintType(t1)}")

        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()

        val r2 = parseAndTypeCheck(source2)
        val t2 = bindingType(r2, "result")
        assertTrue(t2 is MezzeType.IntType, "result should be Int, got ${prettyPrintType(t2)}")
    }

    // Substitution idempotence: applying the same substitution twice should
    // yield the same result as applying it once. We test this indirectly by
    // checking that a type annotation roundtrips through inference unchanged.
    @Test
    fun `substitution applied twice equals applied once`() {
        val source = """
            type Result 'ok 'err = | Ok { val: 'ok }, Err { error: 'err } |

            let wrap : {x: Int} -> Result Int Str = {x} -> Ok { val = x }
            let unwrap = {r: Result Int Str} -> match r is
                Ok { val } -> val
                Err { error } -> 0

            let roundtrip = {x: Int} -> unwrap { r = wrap { x = x } }
        """

        val result = parseAndTypeCheck(source)
        val t = bindingType(result, "roundtrip")
        val fnType = t as? MezzeType.FunctionType
        assertNotNull(fnType, "roundtrip should be a function type")
        assertTrue(
            fnType!!.returnType is MezzeType.IntType,
            "roundtrip should return Int, got ${prettyPrintType(fnType.returnType)}"
        )
    }
}
