package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.solving.pruneVacuousResultRow
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A result row variable carrying no effects, that occurs nowhere else in the
 * signature, is dropped from the inferred type (BUG 34, the inference half).
 *
 *     pub let run_async = {} -> perform Console, SystemTime, LoomAsync in do …
 *
 * inferred `{} -> {} <..., *>` — `effects=[]`, `instances=[]`, `performed=true`,
 * and a surviving row variable. A written `...` mints one, and the stdlib's tails
 * are full of them (`park: { predicate: {} -> Bool <...> } -> {} <Async, ...>`).
 * Nothing closed it: the labeled-row pass is for records and variants and says
 * effects do not participate. The printer then rendered the leftover as `...`,
 * giving a type the parser rejected.
 *
 * THE TWO NARROWINGS ARE THE POINT, and both come from a first attempt that
 * broke `:cli:compileStdlib` with `Field 'xs' expects ['a] but got [Int] in call
 * to 'map'`:
 *
 *  1. The variable must occur EXACTLY ONCE in the whole type. One shared row
 *     variable is minted per signature, so absence from a FRAGMENT means
 *     nothing — the first attempt asked a fragment question and closed live
 *     variables, changing generalization.
 *  2. Only the OUTERMOST row. A variable on a nested arrow has call-site
 *     meaning, and pruning it would make an effectful-callback parameter PURE —
 *     a stricter language, not a tidier type.
 *
 * The guard cases below are therefore the load-bearing ones, not the headline.
 */
class VacuousResultRowTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        tc = TypeChecker()
    }

    private val head = """
        effect Tick where { tick: {} -> Int <Tick> }
        impl Tick for Ticker where { tick = {} -> 7 }
    """.trimIndent()

    /** The inferred, printed type of [name]. */
    private fun inferred(name: String, src: String): String {
        val result = tc.typeCheck(
            parser.parseParsedModule("$head\n${src.trimIndent()}", "a.mz"),
            Paths.get("."),
            enablePrelude = false,
        )
        val t = result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.firstOrNull { it.name == name }
            ?.scheme?.type
        return t?.let { com.mezze.language.ast.prettyPrintType(it) } ?: "?"
    }

    /** THE case: everything handled, reached through a callee with an open tail. */
    @Test fun `a handled body through an open-tailed callee loses the vacuous tail`() {
        val t = inferred("go", """
            let thru: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
            let go = {} -> perform Ticker in thru { f = {} -> Tick.tick {} }
        """)
        assert(!t.contains("...")) {
            "a vacuous result row variable must not reach the printed type; got: $t"
        }
    }

    /** null ⇒ accepted; else the rejection message. */
    private fun err(src: String): String? =
        try {
            tc.typeCheck(
                parser.parseParsedModule("$head\n${src.trimIndent()}", "a.mz"),
                Paths.get("."),
                enablePrelude = false,
            )
            null
        } catch (e: Throwable) {
            e.message ?: "?"
        }

    /**
     * GUARD 1, stated BEHAVIOURALLY: a row-polymorphic function still accepts an
     * effectful callback.
     *
     * Asserted by behaviour rather than by the printed type on purpose. The
     * printer OMITS a row that resolved to empty, so
     * `{ f: {} -> Int <...> } -> Int <...>` prints as `{ f: {} -> Int } -> Int`
     * whether or not anything prunes — measured with the prune disabled. A test
     * looking for `...` there would pass for the wrong reason and would not
     * notice the pruning bug it was written to catch. What must hold is that the
     * parameter has not become PURE.
     */
    @Test fun `a row-polymorphic function still takes an effectful callback`() {
        assertEquals(
            null,
            err("""
                let thru: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
                let go: {} -> Int <*> = {} -> perform Ticker in thru { f = {} -> Tick.tick {} }
            """),
        )
    }

    /**
     * GUARD 2: the same for a variable that appears ONLY on a nested arrow,
     * where the enclosing row is closed. Pruning it would make the parameter
     * pure and start refusing this program.
     */
    @Test fun `a nested-arrow row variable still takes an effectful callback`() {
        assertEquals(
            null,
            err("""
                let absorb: { f: {} -> Int <...> } -> Int = { f } -> f {}
                let go: {} -> Int <*> = {} -> perform Ticker in absorb { f = {} -> Tick.tick {} }
            """),
        )
    }

    /** A named effect means the tail is a lower bound on a real row. */
    @Test fun `a named effect beside the tail is kept`() {
        val t = inferred("needs", "let needs: { f: {} -> Int <Tick, ...> } -> Int <Tick> = { f } -> f {}")
        assert(t.contains("Tick")) { "the named effect must survive; got: $t" }
    }

    /** An ordinary unhandled effect still shows. */
    @Test fun `an unhandled effect still shows`() {
        assert(inferred("go", "let go = {} -> Tick.tick {}").contains("Tick"))
    }

    /** A pure function stays pure. */
    @Test fun `a pure function is unchanged`() {
        assertEquals("{} -> Int", inferred("go", "let go = {} -> 1"))
    }

    /**
     * The round-trip this bug is about: whatever is inferred must be writable as
     * an annotation.
     */
    @Test fun `the inferred row is writable as an annotation`() {
        val t = inferred("go", """
            let thru: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
            let go = {} -> perform Ticker in thru { f = {} -> Tick.tick {} }
        """)
        val row = if (t.contains("<")) t.substring(t.indexOf('<')) else ""
        val failure = try {
            tc.typeCheck(
                parser.parseParsedModule(
                    "$head\nlet thru: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}\n" +
                        "let go: {} -> Int $row = {} -> perform Ticker in thru { f = {} -> Tick.tick {} }",
                    "b.mz",
                ),
                Paths.get("."),
                enablePrelude = false,
            )
            null
        } catch (e: Throwable) {
            e.message
        }
        assert(failure == null) {
            "the inferred row '$row' must be writable back; got: $failure"
        }
    }
}

/**
 * The two narrowings in [pruneVacuousResultRow], called DIRECTLY.
 *
 * A SEPARATE CLASS ON PURPOSE, with no fixture. [VacuousResultRowTest] builds a
 * TypeChecker in `@BeforeEach`, and that reaches PrecompiledStdlib whether or
 * not the prelude is enabled — so when a narrowing is removed the stdlib stops
 * precompiling and every test in that class dies with
 * `NoClassDefFoundError: PrecompiledStdlib` before any assertion runs. Measured
 * twice: 7 of 7, then 11 of 11, all errors, none of them the actual claim. A
 * suite that reports everything broken tells you something is wrong but not
 * what, and `:cli:compileStdlib` is where you would go to read the real message
 * (`expected { n: Nat, call: {} -> 'a <Sample, ...> } -> 'a but got
 * { n: 'c, call: {} -> ['b] } -> 'd`, in `std::effects::sample`).
 *
 * These touch nothing but the function and hand-built types, so when a
 * narrowing goes, the one test that names it fails and says which.
 */
class PruneVacuousRowNarrowingTest {

// ---------------------------------------------------------------------
// The narrowings, tested DIRECTLY on the function.
//
// The whole-program tests above cannot carry these. Every test in this
// class builds a TypeChecker, and that reaches PrecompiledStdlib whether
// or not the prelude is enabled — so when a narrowing is removed the
// stdlib fails to precompile and ALL of them die with
// `NoClassDefFoundError: PrecompiledStdlib` before any assertion runs.
// Measured: removing narrowing 1 gives 7 failed, 7 of them that error.
// A suite that reports every test broken tells you something is wrong
// but not WHAT, and `:cli:compileStdlib` is where you would end up
// reading the real message (`expected { n: Nat, call: {} -> 'a
// <Sample, ...> } -> 'a but got { n: 'c, call: {} -> ['b] } -> 'd`).
//
// These four call the pure function on hand-built types instead. No
// typechecker, no stdlib, no prelude: when a narrowing goes, the one
// that names it fails and says so.
// ---------------------------------------------------------------------

private val v = RowVar("v")

/** `{} -> Int <v>` with an empty row. */
private fun fnWithOuterRow(
    row: MezzeType.EffectRow,
    param: MezzeType = MezzeType.RecordType(emptyMap()),
) = MezzeType.FunctionType(
    paramType = param,
    returnType = MezzeType.IntType,
    effects = row,
    abilityConstraints = emptyList(),
)

private fun emptyRow(rowVar: RowVar?) = MezzeType.EffectRow(
    effects = emptySet(),
    rowVar = rowVar,
    performed = true,
)

/** THE case, at the unit level: the variable occurs once, so it goes. */
@Test fun `prune drops a lone vacuous row variable`() {
    val pruned = pruneVacuousResultRow(fnWithOuterRow(emptyRow(v)))
    assertEquals(null, (pruned as MezzeType.FunctionType).effects?.rowVar)
}

/**
 * NARROWING 1: the same variable also sits on a nested callback arrow, so
 * it occurs TWICE and something can still instantiate it. Pruning here is
 * what broke `std::effects::sample`.
 */
@Test fun `prune keeps a row variable shared with a callback`() {
    val callback = MezzeType.FunctionType(
        paramType = MezzeType.RecordType(emptyMap()),
        returnType = MezzeType.IntType,
        effects = emptyRow(v),
        abilityConstraints = emptyList(),
    )
    val t = fnWithOuterRow(emptyRow(v), MezzeType.RecordType(mapOf("f" to callback)))
    assertEquals(v, (pruneVacuousResultRow(t) as MezzeType.FunctionType).effects?.rowVar)
}

/** NARROWING 2: a user-written `...` is a promise to the caller; it stays. */
@Test fun `prune keeps a variable the caller requires to be open`() {
    val t = fnWithOuterRow(emptyRow(v))
    assertEquals(v, (pruneVacuousResultRow(t, setOf(v)) as MezzeType.FunctionType).effects?.rowVar)
}

/**
 * NARROWING 2: the OUTERMOST row only. Here the outer row is closed and `v`
 * occurs exactly ONCE, on a nested callback arrow — so a version that recursed
 * would happily prune it, and the parameter `{ f: {} -> Int <v> }` would become
 * `{ f: {} -> Int }`: a function that no longer accepts an effectful callback.
 * That is a stricter language, not a tidier type. (Whether such a row should be
 * absorbed at all is #215; it is not this question.)
 */
@Test fun `prune leaves a nested callback row alone`() {
    val callback = MezzeType.FunctionType(
        paramType = MezzeType.RecordType(emptyMap()),
        returnType = MezzeType.IntType,
        effects = emptyRow(v),
        abilityConstraints = emptyList(),
    )
    val t = MezzeType.FunctionType(
        paramType = MezzeType.RecordType(mapOf("f" to callback)),
        returnType = MezzeType.IntType,
        effects = emptyRow(null),
        abilityConstraints = emptyList(),
    )
    val param = (pruneVacuousResultRow(t) as MezzeType.FunctionType).paramType
    val f = (param as MezzeType.RecordType).fields["f"] as MezzeType.FunctionType
    assertEquals(v, f.effects?.rowVar)
}

/** A named effect makes the tail a lower bound on a real row, not a leftover. */
@Test fun `prune keeps a tail beside a named effect`() {
    val t = fnWithOuterRow(
        MezzeType.EffectRow(effects = setOf("Tick"), rowVar = v, performed = false),
    )
    assertEquals(v, (pruneVacuousResultRow(t) as MezzeType.FunctionType).effects?.rowVar)
}
}
