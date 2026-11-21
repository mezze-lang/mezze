package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Where do a callback's effects go? To exactly one place.
 *
 *  - the callee's open result row (`-> 'b <...>`): the shared tail carries them
 *    out of the call;
 *  - the returned DATA, when it has a function field with its own `<...>`
 *    (`Routes.run`, `RouteGroup`'s entries, `SocketRoute.handler`): the callback is
 *    stored and the data's row tracks it — the CALL performs nothing;
 *  - otherwise the callee runs the callback under the handlers in force at the
 *    call (`Async.spawn` → `Promise 'a`), so the effects are the call's.
 *
 * The method-call path used to charge the call unconditionally, so a top-level
 * `router { … }.get { handler = <effectful> }` "performed at module load"
 * (V0502) while the free `route { … }` passed.
 *
 * And a bare `<...>` on a callback that NOTHING carries — closed result row, no
 * rowed field in the result type — bound the callback's effects and dropped
 * them: `apply { f = {} -> Tick.tick {} }` compiled with no handler anywhere.
 * User rule: such a tail can only ever mean `<>`, so the declaration is legal,
 * the slot is pure, and an effectful callback is refused where it is passed
 * (`closeUncarriedCallbackTails`).
 */
class CallbackEffectsChargedOnlyWhenNotCarriedTest : VariantSpecBase() {

    private val tick = """
        use std::int
        effect Tick where { tick: {} -> Int <Tick> }
        impl Tick for Ticker where { tick = {} -> 1 }
    """.trimIndent()

    @Test fun `a storing method does not charge the call`() {
        accepts(
            """
            use std::effects::http::{ router, RouteGroup, not_found }
            $tick
            pub let g: {} -> RouteGroup = {} -> router { path = "" }.get { path = "/x", handler = { m } -> do
              let _ = Tick.tick {}
              not_found { body = "x" } }
            $MAIN
            """,
            "`RouteGroup` carries the handler in a field typed `… <...>`: the call stores it, and " +
                "the effects run later under the server's handlers. `g` itself performs nothing.",
        )
    }

    @Test fun `a storing websocket route does not charge the call`() {
        accepts(
            """
            use std::effects::http::{ router, RouteGroup, Message, WebSocket, Socket, Matched, messages }
            pub let g: {} -> RouteGroup = {} -> router { path = "" }.websocket { path = "/echo", handler = { m, socket } ->
              messages { socket = socket, init = {}, f = { acc, msg } -> do
                let _ = WebSocket.send { socket = socket, msg = msg }
                {} } }
            $MAIN
            """,
            "Same shape, the socket endpoint.",
        )
    }

    @Test fun `a method whose result cannot carry the callback charges the call`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync, await }
            $tick
            pub let p = {} -> perform LoomAsync in await { p = Async.spawn { f = {} -> Tick.tick {} } }
        """
        val t = printedType(src, "p")
        org.junit.jupiter.api.Assertions.assertTrue(
            "Tick" in t,
            "`Promise 'a` has no row to carry Tick, so spawning an effectful task performs Tick at the call; got: $t",
        )
    }

    @Test fun `an uncarried bare tail means pure - the declaration stands and a pure callback fits`() {
        accepts(
            """
            $tick
            pub let apply: { f: {} -> Int <...> } -> Int = { f } -> f {}
            pub let n: Int = apply { f = {} -> 1 }
            $MAIN
            """,
            "`<...>` under a closed `-> Int` can only mean `<>`; the body treats f as pure and that is consistent.",
        )
    }

    @Test fun `an effectful callback into that slot is refused with no handler in scope`() {
        rejects(
            """
            $tick
            pub let apply: { f: {} -> Int <...> } -> Int = { f } -> f {}
            pub let run: {} -> Int = {} -> apply { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "T0201",
            "THE LAUNDER: this compiled, the tail bound {Tick} and the closed result dropped it, so " +
                "nobody was ever asked for a Tick handler.",
        )
    }

    @Test fun `an effectful callback into that slot is refused even under a handler`() {
        rejects(
            """
            $tick
            pub let apply: { f: {} -> Int <...> } -> Int = { f } -> f {}
            pub let run: {} -> Int <*> = {} -> perform Ticker in apply { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "T0201",
            "the slot is pure; a handler in scope does not change what `apply`'s signature admits.",
        )
    }

    @Test fun `a callback that is never called is still a pure slot`() {
        rejects(
            """
            $tick
            pub let ignore: { f: {} -> Int <...> } -> Int = { f } -> 1
            pub let run: {} -> Int <*> = {} -> perform Ticker in ignore { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "T0201",
            "nothing carries the tail, so it is `<>` whether or not the body runs f.",
        )
    }

    @Test fun `the same body with an open result is accepted`() {
        accepts(
            """
            $tick
            pub let apply: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
            pub let run: {} -> Int <*> = {} -> perform Ticker in apply { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "The honest spelling: the result row carries the callback's effects to the caller.",
        )
    }

    @Test fun `a callback row that names an effect is reconciled per call, not refused at the declaration`() {
        accepts(
            """
            $tick
            pub let runs: { f: {} -> Int <Tick, ...> } -> Int <Tick> = { f } -> f {}
            pub let run: {} -> Int <*> = {} -> perform Ticker in runs { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "`<Tick, ...>` is a declared callback row: the tail is checked against the closed `<Tick>` at each " +
                "call site (CallbackTailEscapesClosedRowTest), so the declaration stands. Only a BARE `<...>` " +
                "has nothing for the call site to anchor on — that is the launder, not this.",
        )
    }

    @Test fun `storing the callback into a rowed field keeps the body's row closed`() {
        accepts(
            """
            $tick
            pub let keep: { f: {} -> Int <...> } -> { run: {} -> Int <...> } = { f } -> { run = f }
            $MAIN
            """,
            "Nothing is called: the record field's own `<...>` carries the row.",
        )
    }
}
