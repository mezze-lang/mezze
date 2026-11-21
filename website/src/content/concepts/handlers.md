---
title: Handlers
blurb: What a handler does with the call it answers, and who holds the rest.
---

The Effects page showed a handler supplying an operation. This page is about
the moment inside that handler. Something performed `Ask.ask`, the program
stopped there, and the handler has to decide what happens next.

It has three choices. It can answer, and the program carries on. It can end
the whole `perform` block with a value of its own. Or it can wait, and let
other work run until the answer exists. Everything effects do in Mezze, from
exceptions to async, is one of those three.

## Answering

A handler body's value is the answer. The call that performed the operation
receives it, and the program carries on from there.

<!--mezze-editor
use std::effects::log::{ Console, println }

effect Ask where {
  ask: { item: Str } -> Str <Ask>
}

# The body's value goes back to whoever asked. Nothing else to write.
impl Ask for Pantry where {
  ask = { item } -> "$(item) is on the second shelf"
}

let find = {} -> do
  let where_is = Ask.ask { item = "tahini" }
  "found it: $(where_is)"

let main = {} -> perform Console, Pantry in println { msg = find {} }
-->

`find` stopped at `Ask.ask`, the handler produced a string, and `find`
continued with that string as if `Ask.ask` were an ordinary function. That is
called resuming, and it is what nearly every handler does.

Spelled out, the same handler is `ask = { item } -> cont.resume { val = … }`.
Both forms mean the same thing and compile to the same code. Write the short
one. The long one exists so that the three choices share one name, `cont`,
when a handler needs more than one of them.

## Ending the block

`cont.abort { val }` does not answer the operation. It ends the `perform`
block that installed the handler, and `val` becomes the whole block's value.
The rest of the computation never runs.

<!--mezze-editor
use std::effects::log::{ Console, println }

effect Check where {
  fail: { why: Str } -> 'a <Check>
}

# Stop everything, and make the reason the block's answer.
impl Check for FirstProblem where {
  fail = { why } -> cont.abort { val = "rejected: $(why)" }
}

let restock = { qty: Int } -> do
  if qty < 1 then Check.fail { why = "nothing to restock" } else {}
  "restocked $(qty.to_str {})"

let main = {} -> perform Console in do
  println { msg = perform FirstProblem in restock { qty = 3 } }
  println { msg = perform FirstProblem in restock { qty = 0 } }
-->

The second call never reaches its `"restocked"` line. `Check.fail` ended the
block, and the block's value is the reason instead.

This is all an exception is. The standard library's `Exception` is an effect
with one operation, and `ExceptionToResult` is a single line:

<!--mezze-code
impl Exception for ExceptionToResult having 'error = 'e where {
  throw = { error } -> cont.abort { val = Err { error } }
}
-->

There is no `try` and no `catch` in the language. `perform ExceptionToResult in`
is the try, and the handler is the catch. Swap it for `ExceptionToOption` and
the same code returns `None` instead of an `Err`.

## Last, or not at all

`cont.resume` and `cont.abort` must be the last thing a handler body does.
Anything after them would read as if it ran later, and it would not:

<!--mezze-code
impl Ask for Chatty where {
  ask = { item } -> do
    cont.resume { val = "shelf two" }
    println { msg = "answered" }          # V0503
}
-->

<!--mezze-error
error[V0503]: `cont.resume` must be the last thing operation 'ask' in impl 'Chatty' does.
-->

The rule follows from what a handler does not get. In some languages a handler
receives the rest of the program as a value, usually called `k`, which it can
store, call twice, or never call. A Mezze handler never holds that value. The
runtime does. `cont` is a set of instructions to the runtime, not a
continuation you own, so it cannot be stored or passed on either.

What that buys is that nothing leaks. A continuation that is never resumed and
never cleaned up cannot exist, because nobody but the runtime ever had one.

## Waiting

The third handle is for a handler that cannot answer yet. `cont.park` suspends
the task that performed the operation until a condition holds, and lets other
tasks run in the meantime. It returns how the wait ended, and the compiler
makes the handler say what it does in both cases:

<!--mezze-code
impl Async for CoopAsync where {
  await = { p } -> match inspect { p = p } is
    PromiseCompleted { val } -> Ok { val = val }
    PromiseFailed { err } -> Err { error = err }
    PromisePending -> match cont.park { on = OnPromises { ids = [promise_id { p = p }] }, set = WaitDefault } is
      ParkReady { which } -> settled_result { p = p }
      ParkCancelled { reason } -> Err { error = AsyncCancelled { reason = reason } },
  …
}
-->

That is the standard library's own scheduler, and it is ordinary Mezze. If the
promise has settled, `await` answers at once. If not, it parks on the promise,
and the task wakes when the promise settles or the task is cancelled.

## The scheduler is a library

Because waiting is a handler's decision, async is not built into the language
either. `Async` is an effect with five operations: `spawn`, `await`, `cancel`,
`park` and `park_until`. Which scheduler runs them is the caller's choice, the
same way the filesystem was.

<!--mezze-code
use std::effects::time::{ SystemTime }
use std::effects::async::{ Async, CoopAsync, LoomAsync, try_await }

let price_of = { item: Str } -> 450

let basket = {} -> do
  let a = Async.spawn { f = {} -> price_of { item = "tahini" } }
  let b = Async.spawn { f = {} -> price_of { item = "olive-oil" } }
  let total = [a, b].fold { init = 0, f = { acc, x } -> match try_await { p = x } is
    Some { val } -> acc + val
    None -> acc
  }
  total
# basket : {} -> Int <Async>

# One thread, tasks take turns. It waits on a clock, so it asks for one.
let one_thread = {} -> perform SystemTime, CoopAsync in basket {}

# A virtual thread per task.
let many_thread = {} -> perform LoomAsync in basket {}
-->

`basket` says only that it uses `Async`. `CoopAsync` runs every task on one
thread, in an order a test can reproduce. Its deadlines read the clock through
the `Time` effect, so the compiler asks for a `Time` performer beside it, and a
test can hand it `MockTime` instead of `SystemTime`. `LoomAsync` gives each
task a virtual thread of the JVM. The code that spawns and awaits is
identical in both, and a scheduler you write yourself is one more `impl Async for …`.

A scheduler that finds every task waiting and nothing able to wake them
reports a deadlock rather than hanging. It only says so when that is certain:
no task is runnable and none is waiting on anything that could still happen.

## A scope around the block

A handler can also do something once before the block runs and once after it.
Two optional fields, `initialize_scope` and `finalize_scope`, run around the
whole `perform`. Whatever `initialize_scope` returns is available to every
operation as `scope`, and `finalize_scope` gets it back at the end.

<!--mezze-code
impl Async for LoomAsync where {
  initialize_scope = {} -> __native_async_init_scope {},
  finalize_scope = {} -> __native_async_finalize_scope { tracker = scope },
  spawn = { f } -> __native_async_spawn_tracked { f = f, max_concurrent = 0, tracker = scope },
  …
}
-->

Here the scope is the tracker for every task spawned inside the block, and
`finalize_scope` releases it when the block ends. A task nobody awaited keeps
running and its result is dropped, as in Go and Rust. To wait for a task,
await it.

## Names a handler gets

Two names exist only inside a handler body, and neither can be used for
anything else:

| name | what it is |
|---|---|
| `cont` | the instructions for the rest of the program: `cont.resume`, `cont.abort`, `cont.park` |
| `scope` | the value `initialize_scope` returned for this `perform` |
