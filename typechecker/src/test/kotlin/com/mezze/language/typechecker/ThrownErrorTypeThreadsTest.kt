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
 * The `Err` arm bound a variable of its own, related to the signature's `'e`
 * only through the substitution, and the ability constraint landed on that one.
 *
 * FIXED, in two places: the performer's associated-type key (layer 4 below) and
 * the do-block binding's local solve (layer 5). The measured chain is kept on
 * the first test, because five of its six layers were dead ends and the next
 * person should not pay for them again.
 *
 * This was the gap behind three separate things in std::effects::http_router:
 * `catching` could only accept a thrown `Response` rather than any convertible
 * error; a route could not therefore let a handler throw its own domain error;
 * and the verbs could not apply `catching` themselves, because naming the error
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
     * The chain, as measured. Layers 1-3 are dead ends, recorded so they are
     * not re-walked; 4 and 5 are the two real defects.
     *
     *  1. the `Err` arm's binding and the signature's `'e` were two DISTINCT,
     *     UNRESOLVED variables — the documented "one written typevar, two
     *     TypeVars" shape, here in its ninth known instance;
     *  2. the perform region was not at fault, nor the pattern solver: by the
     *     time the match ran, the scrutinee was ALREADY wrong, so the wrong
     *     type arrived before the match;
     *  3. nor was it the performer splitting its own variable, nor `outcome`
     *     being generalized (it is bound MONOMORPHIC), nor the method-call
     *     path (`the Err arm through a free constrained function` fails
     *     identically), nor the arm binding (it IS the scrutinee's error slot),
     *     nor constraint merging (a match arm's ability constraints DO reach
     *     the enclosing collector). All measured, all negative.
     *
     *  4. the suspension binding was keyed `'error` while the performer's field
     *     is keyed `error`, so `performerAssocTypes.fields[assocTypeName]`
     *     missed and the result was keyed by the wrong name. One
     *     `removePrefix` in ExprPerformConstraints. After it, the signature's
     *     own variable reaches the suspension type.
     *
     *  5. and that was still not enough, because of the do-block `let`. A
     *     do-block binding runs its OWN local solve
     *     (`solveDoBlockBindingLocally`), and solving instantiates schemes — so
     *     the local solve minted fresh variables that the enclosing solve,
     *     instantiating the same schemes separately, never created. The type it
     *     handed back was written in those variables, and that type is what the
     *     binding carried, so the match on it, the arm's bindings and the arm
     *     body's obligations all spoke of variables the enclosing substitution
     *     had no equation for. Measured in ONE run (the ids are per-run;
     *     comparing them across runs is meaningless and cost a wrong
     *     conclusion):
     *
     *         perform's own type   = Result #3276 #3278   ← `'e` IS #3278
     *         `outcome` registered = Result Str  #3306    ← an orphan
     *         arm binding          = #3314, usage = #3316
     *
     *     Fixed by emitting the equation the fresh variables broke — see
     *     ExprControlFlowConstraints, where the binding's solved type is
     *     recorded. The source had already written down the consequence, in
     *     `doBlockLambdaCapturesFreeVarBinding`: "the do-block's LOCAL solve
     *     gives the binding a fresh var identity".
     *
     * A CONCRETE `having 'error = Denied` worked throughout, because the thrown
     * value unifies the slot from the call site; only a VARIABLE had nothing
     * else to tie it. That contrast is the control below — and note it is why
     * the two do-block controls pass even unfixed.
     */
    @Test fun `a declared thrown error type threads into the Err arm`() {
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
        assertNull(e, "the declared `'e` reaches the Err arm; got: $e")
    }

    /**
     * Annotating the intermediate binding works as well. The `'e` written in a
     * BODY annotation is NOT the `'e` of the enclosing signature — a function's
     * type variables are not in scope over its body, so this `Result Str 'e`
     * names a fresh variable — and that is fine: the bridge equation ties the
     * binding to the perform's own type, which carries the signature's `'e`, so
     * the body's fresh one unifies with it rather than competing.
     *
     * Before the layer-5 fix this was rejected, which is the better reason to
     * keep it: an annotation must not be able to break a body that compiles
     * without one. See [[no_annot]].
     */
    @Test fun `annotating the handled outcome also threads the error type`() {
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
        assertNull(e, "an annotation on the binding must not break it; got: $e")
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

    /**
     * The SMALLEST form: a do-block `let` whose value carries the enclosing
     * function's type variable, then a match on it. No effects, no perform, no
     * associated types — just a binding across the do-block's local solve.
     *
     * If this fails, the subject is "a do-block let loses the enclosing
     * function's type variable", which has nothing to do with effects and is a
     * far smaller thing to fix.
     */
    @Test fun `a do-block let keeps the enclosing type variable`() {
        val e = err("""
            $head
            let describe: { r: Result Str 'e } with Into 'e -> Str = { r } -> do
              let outcome = r
              match outcome is
                Ok { val } -> val
                Err { error } -> error.conv {}
            describe { r = Err { error = { who = "bob" } } }
        """)
        assertNull(e, "the binding keeps `'e`; got: $e")
    }

    /**
     * The same program with the Err arm's use routed through a FREE constrained
     * function instead of a method call. It failed identically before the
     * layer-5 fix, which is what cleared the method-call path: the obligation
     * was not being recorded against the receiver by mistake, the variable it
     * named was an orphan whichever way the arm used it.
     */
    @Test fun `the Err arm through a free constrained function`() {
        val e = err("""
            $head
            let describe_err: { e: 'x } with Into 'x -> Str = { e } -> e.conv {}
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
                  Err { error } -> describe_err { e = error }
            adapt { f = {} -> throw { error = { who = "bob" } } }
        """)
        assertNull(e, "free function in the Err arm; got: $e")
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
