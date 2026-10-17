---
title: Effects
blurb: What a function reaches for, written on its arrow.
---

An effect is a property of a computation, not of a value. A record, a number,
a string: none of them carries an effect. A function arrow does, and it means
"calling me does this".

<!--mezze-code
{ url: Str } -> Str <HttpClient>
-->

Read that as: give me a URL, I give you a `Str`, and getting it reaches the
network. The effect sits on the arrow because it describes the act of calling,
not the answer.

## Values are bare

Bind an effectful call to a name and the name has the plain type:

<!--mezze-code
let page = fetch { url = "https://example.com" }   # page : Str
-->

`page` is a `Str`, not a `Str` carrying a network request. The `<HttpClient>` has
gone somewhere else, and where it goes is the whole model.

## Effects raise to the enclosing arrow

An unhandled effect rises to the nearest enclosing function, and each call
raises it again to its caller, until something handles it.

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::effects::http::{ MockHttpClient, mock_get, request }

# fetch reaches the network. Its arrow says so: <HttpClient>.
let fetch = { url } -> request { url }.send {}.text_or { def = "offline" }

# The effect raises to each enclosing arrow, and to every caller.
let inner = {} -> fetch { url = "https://wttr.in/Amsterdam?format=3" }
let outer = {} -> inner {}

# perform handles the computation, and the arrow gains a *.
let handled = {} ->
  perform MockHttpClient { rules = [mock_get { body = "sunny", url = "*" }] } in inner {}

let main = {} -> perform Console in println { msg = handled {} }
-->

Hover the four names in that editor. `inner` and `outer` both read
`{} -> Str <HttpClient>`, though neither mentions the network in its body: `inner`
inherited the effect by calling `fetch`, and `outer` by calling `inner`. That
is what "effects at the function boundary" means, and it is why an effect
cannot hide. It is on every arrow between the operation and its handler.

## perform handles a computation

`perform H in body` installs the handler `H` for the computation `body`. The
body has to be something that *does* something: a call, a `do` block, or the
body of a function.

A bare value does nothing, so handing one to `perform` is a compile error:

<!--mezze-code
let page = fetch { url = "https://example.com" }
perform MockHttpClient { rules = [] } in page      # V0510
-->

By the time `page` exists the request has already happened and the effect has
risen to the enclosing function. There is nothing left for a handler to catch,
and the compiler says so rather than silently doing nothing:

<!--mezze-error
error[V0510]: Handler 'MockHttpClient' handles 'HttpClient',
but the `perform` body performs no effect.
-->

## The star is a purity certificate

Handle an effect and the arrow keeps a mark: `<*>`. It means "effects were
performed here, and handled here".

<!--mezze-code
let fetch   = { url } -> …            # <HttpClient>    performs, unhandled
let handled = {} -> perform … in …    # <*>       performed and handled
let double  = { x } -> x * 2          #           no row at all
-->

The distinction is worth the symbol. An arrow with no row certifies that
nothing effectful runs inside, so the call can be memoised, reordered or run
in parallel. An arrow with `<*>` did real work and cleaned up after itself.
Most effect systems erase a handled effect and cannot tell you the difference.

### Handling one effect at a time

A handler discharges the effect it handles and leaves the rest alone. Read the
comments from the inside out: each `perform` removes one name from the row,
and the star appears the moment anything has been handled at all.

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::effects::http::{ MockHttpClient, mock_get, request }
use std::effects::filesystem::{ MemoryFS, write_text }
use std::effects::time::{ SystemTime, now }

# Reaches the network, writes a file, and reads the clock.
let snapshot = { url } -> do
  let body = request { url }.send {}.text_or { def = "offline" }
  write_text { path = "snapshot.txt", text = body }
  now {}
  body
# snapshot : { url: Str } -> Str <FileSystem, Http, Time>

let all_three = { url } ->
  # <*>                        nothing outstanding: every effect is handled
  perform MockHttpClient { rules = [mock_get { body = "sunny", url = "*" }] } in
    # <Http, *>                Time and FileSystem settled, Http still to go
    perform SystemTime in
      # <Http, Time, *>        only FileSystem settled so far
      perform MemoryFS in
        # <FileSystem, Http, Time>   what snapshot promised, nothing handled
        snapshot { url }

let main = {} -> perform Console in println { msg = all_three { url = "https://example.com" } }
-->

The innermost line is what `snapshot` promised: three effects, none handled.
Each handler above it takes one name away and puts the star there instead.
`MemoryFS` settles `FileSystem`, `SystemTime` settles `Time`, and only when
the outermost handler settles `Http` does the row become a bare `<*>`.

So the star is not "handled" — it is "handled, and nothing remains". A row
like `<Http, *>` is the useful middle: some effects are settled, `Http` is
still the caller's problem, and the type says which. Partially handled code is
ordinary code, and it stays honest about what is left.

## Swapping the handler

Because the handler is chosen by the caller and not by the function, the same
code runs against different worlds. `MockHttpClient` above answers from a
canned rule and never touches a socket, which is what makes the example
runnable on this page. Swap it for `PooledHttpClient` and the identical `fetch` goes
to the network.

That is the practical payoff: a test supplies a different handler, not
different code.

## More than one effect

An arrow lists everything calling it performs. A function that fetches and
then writes what it got carries both:

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::effects::http::{ MockHttpClient, mock_get, request }
use std::effects::filesystem::{ MemoryFS, write_text, read_text_or }

# Two effects on one arrow: it fetches, then writes what it got.
let cache = { url, path } -> do
  let body = request { url }.send {}.text_or { def = "offline" }
  write_text { path = path, text = body }
  body

let main = {} -> perform Console, MemoryFS in
  perform MockHttpClient { rules = [mock_get { body = "sunny", url = "*" }] } in do
    cache { url = "https://x.dev/weather", path = "weather.txt" }
    println { msg = read_text_or { path = "weather.txt", def = "nothing" } }
-->

`cache : { url: Str, path: Str } -> Str <FileSystem, Http>`. Neither effect
is handled inside it, so both raise to its arrow and both have to be supplied
by a caller.

Handlers can be listed together, `perform Console, MemoryFS in …`, or nested
when one takes arguments as `MockHttpClient` does. Both forms discharge what
they name and leave the rest to rise further.

Note what the mocks buy here. `MemoryFS` holds the file in memory, so the
example writes and reads a real file without touching your disk, and
`MockHttpClient` answers without a socket. The same `cache` runs against
`LocalDisk` and `PooledHttpClient` unchanged.

## Your own effects

Nothing above is built in. An effect declares operations and leaves them
unimplemented; a handler supplies them.

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::effects::filesystem::{ MemoryFS, append_text, read_text_or }

# Declaring an effect: the operations, with no implementation.
effect Audit where {
  record: { line: Str } -> {} <Audit>
}

# A handler gives them meaning. This one appends to a file.
impl Audit for ToFile where {
  record = { line } -> do
    append_text { path = "audit.log", text = "$(line)\n" }
    {}
}

# The caller performs Audit and knows nothing about files.
let checkout = {} -> do
  Audit.record { line = "cart opened" }
  Audit.record { line = "paid" }

let main = {} -> perform Console, MemoryFS, ToFile in do
  checkout {}
  println { msg = read_text_or { path = "audit.log", def = "empty" } }
-->

`checkout : {} -> {} <Audit>`, and that is the whole point. Its type says it
records an audit trail. It does not say a file is involved, because that is
the handler's business: `ToFile` chose a file, and a different handler could
collect the lines in memory, send them to a service, or drop them.

The declaration and the handler read like an ability and its impl, and that is
deliberate. What differs is who supplies the operations and when: a type
implements an ability and the compiler resolves the call, while a handler
supplies an effect and `perform` decides which one is in scope.

## The row can be a variable

Every row so far named its effects. A row can instead say "whatever the
caller's function performs", written `<...>`. This is how a higher-order
function stays usable with both pure and effectful callbacks.

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::effects::http::{ MockHttpClient, mock_get, request }

# apply performs nothing itself. Its row is whatever f's row is.
pub let apply: { x: Int, f: { v: Int } -> Int <...> } -> Int <...> = { x, f } -> f { v = x }

# A pure callback, so this whole call is pure.
let doubled = {} -> apply { x = 2, f = { v } -> v * 2 }
# doubled : {} -> Int

# An effectful one, and the effect comes straight back out.
let fetched = {} -> apply { x = 2, f = { v } -> do
  request { url = "http://example.com" }.send {}.text_or { def = "" }
  v * 2
}
# fetched : {} -> Int <Http, ...>

let main = {} -> perform Console in do
  println { msg = doubled {}.to_str {} }
  println { msg = perform MockHttpClient { rules = [mock_get { body = "b", url = "*" }] } in
    fetched {}.to_str {} }
-->

`doubled` has no row at all: a pure callback went in, so nothing effectful
happens and the certificate from the previous section still holds. `fetched`
carries `<Http, ...>`, the effect the callback performed. One definition of
`apply`, and it neither forces a caller to be effectful nor blocks one that is.

Both forms appear:

- `<...>` alone means "exactly what the callback performs, nothing added".
- `<Http, ...>` means "`Http`, plus whatever the callback performs". The
  function reaches the network itself and passes the rest through.

This is why `seq::map` works the same whether you map a pure function or one
that prints. Without it the standard library would need a pure copy and an
effectful copy of every combinator.

## Two rules about where effects may run

**The top level is for definitions.** A module-level binding whose value
performs an effect is rejected, because that would run at module-load time.
Defining an effectful *function* is fine, since an arrow is latent until
called.

<!--mezze-error
error[V0502]: Top-level `x` performs an effect at module-load time.
The top level is for definitions — effectful code must run inside a function.
-->

**The entry point must handle what it uses.** The runtime supplies the ambient
effects. An effect you declared yourself, left unhandled on the entry's arrow,
is a compile error rather than a surprise at run time:

<!--mezze-error
Entry 'main' performs Audit, but nothing discharges it — the entry has no
enclosing handler, so it leaks at runtime.
-->
