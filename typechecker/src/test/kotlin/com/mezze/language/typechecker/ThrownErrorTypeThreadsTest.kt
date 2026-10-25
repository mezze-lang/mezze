package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A declared `having 'error = 'e` must reach the `Err` arm that binds it.
 *
 *     let adapt
 *       : { f: {} -> 'r <Exception having 'error = 'e, ...> }
 *       with Into 'e
 *       -> Str
 *       = { f } -> do
 *         let outcome = perform ExceptionToResult in do
 *           let answered = f {}
 *           Ok { val = "ok" }
 *         match outcome is
 *           Ok { val } -> val
 *           Err { error } -> error.conv {}
 *
 * The signature says the thrown channel is `'e` and that `'e` converts. The
 * body handles the exception and converts whatever came out of the `Err` arm.
 * Those are the same type, and the checker does not know it:
 *
 *     the annotation constrains `with Into 'e`, but the body applies Into to a
 *     type the annotation does not name
 *
 * The `Err` arm binds a variable of its own, related to the signature's `'e`
 * only through the substitution, and the ability constraint lands on that one.
 *
 * Layer 4 is fixed here. Layer 5 is architectural and pinned below with the
 * two hypotheses that were measured and disproved, so the next attempt does
 * not pay for them again.
 *
 * This is the gap behind three separate things in std::effects::http_router:
 * `catching` can only accept a thrown `Response` rather than any convertible
 * error; a route cannot therefore let a handler throw its own domain error;
 * and the verbs cannot apply `catching` themselves, because naming the error
 * type is what makes the thrown type checkable.
 */
class ThrownErrorTypeThreadsTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    private val head = """
        use std::effects::exception::{ ExceptionToResult, throw }
        ability Into for 'a where { conv: { it: 'a } -> Str }
        type Denied = { who: Str }
        impl Into for Denied where { conv = { it } -> it.who }
    """.trimIndent()

    /**
     * STILL FAILS, and the chain is recorded so the next attempt starts at the
     * end of it. Each layer was measured, not reasoned:
     *
     *  1. the `Err` arm's binding and the signature's `'e` are two DISTINCT,
     *     UNRESOLVED variables (`'y48#3316` vs `'e#3277`) — the documented
     *     "one written typevar, two TypeVars" shape;
     *  2. the perform region is NOT at fault: it binds `'error -> 'e#3277`,
     *     the signature's own variable;
     *  3. nor is the pattern solver: by the time it runs, the scrutinee is
     *     already `Result Str 'z47#3285` — the PERFORMER's fresh variable — so
     *     the wrong type arrived before the match;
     *  4. the binding was keyed `'error` while the performer's field is keyed
     *     `error`, so `performerAssocTypes.fields[assocTypeName]` missed and the
     *     result was keyed by the wrong name. FIXED here (one `removePrefix`).
     *     Measured after: `resolved=Result('u47#3276, 'e#3277)` — the
     *     signature's own variable now reaches the suspension type, and `'e`
     *     resolves instead of staying free;
     *  5. and it is STILL not enough, for a reason none of the above explains.
     *     Two hypotheses were measured and BOTH are wrong, so do not re-try
     *     them: the performer does NOT split its variable (its suspension slot
     *     and its associated-type map are both `'e#3275`), and `outcome` is NOT
     *     generalized (`bindLet` generalizes only a lambda or a function type,
     *     and already subtracts `getContextFreeVars`).
     *
     *     It is the intermediate `let`. Two tests below settle it without any
     *     id archaeology: `an inlined perform threads the error type` is this
     *     same program with the perform INLINED into the match and it COMPILES,
     *     while the version that binds `let outcome = …` first does not. And
     *     `a match arm binds a constrained error type argument` is the same
     *     question with no effects at all, which also compiles — so neither the
     *     match arm nor the effect machinery is at fault.
     *
     *     Measured in ONE run, so the ids are comparable (they are per-run —
     *     comparing them across runs is meaningless and cost me a wrong
     *     conclusion):
     *
     *         PERFORM  resolved      = Result #3276 #3277   ← the signature's var
     *         PATTERN  scrutineeArgs = Result Str  #3285    ← a different one
     *         VALIDATE usage=#3316 unresolved, annotated #3277 -> #3320
     *
     *     So the perform's resolved type does not reach the binding. A do-block
     *     `let` runs its own LOCAL solve (`solveDoBlockBindingLocally`) and
     *     hands that substitution to `bindLet`; the source already records the
     *     consequence, in `doBlockLambdaCapturesFreeVarBinding` — "the
     *     do-block's LOCAL solve gives the binding a fresh var identity …
     *     different var instances across the local-solve boundary".
     *
     *     That boundary is where to pick this up: either the local solve keeps
     *     enclosing variable identity, or the block's variables are re-linked
     *     to the enclosing ones afterwards, the way
     *     `TypedAstBuilder.linkDeclaredVars` does for declared-vs-inferred.
     *
     * A CONCRETE `having 'error = Denied` works throughout, because the thrown
     * value unifies the slot from the call site; only a VARIABLE has nothing
     * else to tie it. That contrast is the control below.
     */
    @Test fun `a declared thrown error type does not yet thread into the Err arm`() {
        val e = err("""
            $head
            let adapt
              : { f: {} -> 'r <Exception having 'error = 'e, ...> }
              with Into 'e
              -> Str <*>
              = { f } -> do
                let outcome = perform ExceptionToResult in do
                  let answered = f {}
                  Ok { val = "ok" }
                match outcome is
                  Ok { val } -> val
                  Err { error } -> error.conv {}
            adapt { f = {} -> throw { error = { who = "bob" } } }
        """)
        assertNotNull(e, "when this starts passing, the do-block local solve shares identities — flip to assertNull")
    }

    /**
     * Annotating the intermediate binding does not help, and that is itself a
     * finding: the `'e` written in a BODY annotation is a different variable
     * from the `'e` in the enclosing signature. A function's type variables are
     * not in scope over its body, so this `Result Str 'e` names a fresh one.
     *
     * Worth separating from layer 5 above — it is the same shape in a different
     * place, and fixing one will not fix the other.
     */
    @Test fun `annotating the handled outcome does not thread the error type`() {
        val e = err("""
            $head
            let adapt
              : { f: {} -> 'r <Exception having 'error = 'e, ...> }
              with Into 'e
              -> Str <*>
              = { f } -> do
                let outcome: Result Str 'e = perform ExceptionToResult in do
                  let answered = f {}
                  Ok { val = "ok" }
                match outcome is
                  Ok { val } -> val
                  Err { error } -> error.conv {}
            adapt { f = {} -> throw { error = { who = "bob" } } }
        """)
        assertNotNull(e, "a body annotation's `'e` is its own variable — see the doc above")
    }

    /**
     * The SMALLEST form of the same question, with no effects at all: a match
     * on a `Result` whose error type is a constrained variable. If this fails,
     * the break is the match arm's binding against a type ARGUMENT and has
     * nothing to do with `perform` or associated types.
     */
    @Test fun `a match arm binds a constrained error type argument`() {
        val e = err("""
            $head
            let describe: { r: Result Str 'e } with Into 'e -> Str = { r } -> match r is
              Ok { val } -> val
              Err { error } -> error.conv {}
            describe { r = Err { error = { who = "bob" } } }
        """)
        assertNull(e, "the Err arm binds the `'e` of `Result Str 'e`; got: $e")
    }

    /**
     * The same program with the perform INLINED into the match, so there is no
     * intermediate `let` binding. If this compiles while the bound version does
     * not, the do-block binding is what loses the connection.
     */
    @Test fun `an inlined perform threads the error type`() {
        val e = err("""
            $head
            let adapt
              : { f: {} -> 'r <Exception having 'error = 'e, ...> }
              with Into 'e
              -> Str <*>
              = { f } -> match perform ExceptionToResult in do
                  let answered = f {}
                  Ok { val = "ok" }
                is
                  Ok { val } -> val
                  Err { error } -> error.conv {}
            adapt { f = {} -> throw { error = { who = "bob" } } }
        """)
        assertNull(e, "inlined perform; got: $e")
    }

    /** The control: the same shape with the thrown type written out works. */
    @Test fun `a concrete thrown error type works`() {
        assertNull(err("""
            $head
            let adapt
              : { f: {} -> 'r <Exception having 'error = Denied, ...> }
              -> Str <*>
              = { f } -> do
                let outcome = perform ExceptionToResult in do
                  let answered = f {}
                  Ok { val = "ok" }
                match outcome is
                  Ok { val } -> val
                  Err { error } -> error.conv {}
            adapt { f = {} -> throw { error = { who = "bob" } } }
        """))
    }
}
