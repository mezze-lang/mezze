export interface Lesson {
  slug: string
  title: string
  blurb: string
  /** Paragraphs of explanation. Supports backtick code and **bold**. */
  body: string[]
  code: string
}

/**
 * The tour, in order: primitives first, then one idea at a time.
 *
 * Every snippet has been typechecked and run by the real compiler, so what a
 * lesson claims and what the Run button prints agree.
 */
export const lessons: Lesson[] = [
  {
    slug: "primitives",
    title: "Values and types",
    blurb: "let, inference, and why interpolation asks for a Str.",
    body: [
      "Everything starts with **let**. Types are inferred, so none of these bindings say what they are — but they all have exact types, and the compiler knows them.",
      "String interpolation with `$(...)` accepts a `Str` and nothing else. That looks strict at first; it means a stray value can never quietly become text in a log line.",
      "Hover any name to see what it was inferred as.",
    ],
    code: `use std::effects::log::{ Console, println }

# let binds a value. Types are inferred — none are written here.
let name = "Mezze"
let year = 2026
let ready = True

let main = {} -> perform Console in do
  println { msg = "\$(name) — \$(year.to_str {})" }
  println { msg = "ready: \$(ready.to_str {})" }

  # Interpolation never guesses: a value has to become a Str first.
  println { msg = "7 / 2 = \$((7 / 2).to_str {})" }`,
  },
  {
    slug: "records",
    title: "Records",
    blurb: "Fields, copy-with, and destructuring.",
    body: [
      "A **record** is just named fields. There is no class to declare and no constructor to call — `{ x = 3, y = 4 }` is the value.",
      "`{ ...point, x = 10 }` copies with one field changed. The original is untouched; nothing mutates.",
      "`let { x, y } = point` destructures. The same shape works as a function parameter, which is why every Mezze function takes exactly one record.",
    ],
    code: `use std::effects::log::{ Console, println }

# A record is just fields. No class, no constructor.
let point = { x = 3, y = 4 }

let main = {} -> perform Console in do
  println { msg = "x = \$(point.x.to_str {})" }

  # Copy with a change. The original is untouched.
  let moved = { ...point, x = 10 }
  println { msg = "moved x = \$(moved.x.to_str {})" }
  println { msg = "original x = \$(point.x.to_str {})" }

  # Destructure in a let.
  let { x, y } = point
  println { msg = "\$(x.to_str {}), \$(y.to_str {})" }`,
  },
  {
    slug: "variants",
    title: "Variants",
    blurb: "Closed sets of cases, and exhaustive match.",
    body: [
      "A **variant** is a fixed set of alternatives, separated by pipes. Some carry payloads, some do not.",
      "`match` must cover every case. Delete the `Point` arm and press Check: the compiler names the case you forgot, before the program runs.",
      "That is the bargain — add a case later and every incomplete match becomes an error rather than a bug.",
    ],
    code: `use std::effects::log::{ Console, println }

# A variant is a closed set of cases. Pipes separate them.
type Shape = |
  Circle { radius: Int },
  Rect { w: Int, h: Int },
  Point
|

# match must cover every case — delete an arm and this stops compiling.
let area: { shape: Shape } -> Int = { shape } -> match shape is
  Circle { radius } -> radius * radius * 3
  Rect { w, h } -> w * h
  Point -> 0

let main = {} -> perform Console in do
  let circle = area { shape = Circle { radius = 2 } }
  let rect = area { shape = Rect { w = 3, h = 4 } }

  println { msg = "circle: \$(circle.to_str {})" }
  println { msg = "rect:   \$(rect.to_str {})" }
  println { msg = "point:  \$(area { shape = Point }.to_str {})" }`,
  },
  {
    slug: "aliases",
    title: "Type aliases",
    blurb: "Naming a shape you use more than once.",
    body: [
      "`type Reading = { city: Str, temp: Int }` gives a name to a shape.",
      "The alias is **transparent**: a `Reading` *is* that record, not a wrapper around it. Any value with the right fields fits, whether or not it was built with the name in mind.",
      "So an alias is documentation the compiler checks, not a new type to convert in and out of.",
    ],
    code: `use std::effects::log::{ Console, println }

# A type alias names a shape so you can say it once and reuse it.
type Reading = { city: Str, temp: Int }

# The name is documentation: a Reading IS that record, not a wrapper
# around it, so anything with the right fields fits.
let describe: { r: Reading } -> Str = { r } ->
  "\$(r.city): \$(r.temp.to_str {})°C"

let main = {} -> perform Console in do
  println { msg = describe { r = { city = "Lisbon", temp = 21 } } }

  # Built the plain way, still a Reading.
  let cairo = { city = "Cairo", temp = 35 }
  println { msg = describe { r = cairo } }`,
  },
  {
    slug: "open-records",
    title: "Open and closed records",
    blurb: "Deciding what a function will accept.",
    body: [
      "A closed parameter says exactly which fields it takes, and **rejects** anything wider. Try passing `big` to `closed`.",
      "Adding `...` opens it: `{ id: Int, ... }` means *at least* this field. Now a caller can hand over a record with more in it.",
      "Open where you want callers to grow their data without breaking you; closed where the exact shape is the point.",
    ],
    code: `use std::effects::log::{ Console, println }

# A closed record says exactly which fields it takes.
let closed: { r: { id: Int } } -> Int = { r } -> r.id

# \`...\` opens it: the caller may pass more fields than this needs.
let open: { r: { id: Int, ... } } -> Int = { r } -> r.id

let main = {} -> perform Console in do
  let small = { id = 1 }
  let big = { id = 2, name = "ada", active = True }

  println { msg = "closed: \$((closed { r = small }).to_str {})" }

  # Only the open one accepts the wider record.
  println { msg = "open:   \$((open { r = big }).to_str {})" }`,
  },
  {
    slug: "functions",
    title: "Functions",
    blurb: "One record in, and functions as values.",
    body: [
      "A function takes **one record**, which is why every argument is named at the call site. `clamp { val = 42, low = 0, high = 10 }` reads the way it is written.",
      "Reorder the fields and existing calls keep compiling. Positional arguments cannot offer that.",
      "Functions are values too: `twice` takes one as a field and calls it.",
    ],
    code: `use std::effects::log::{ Console, println }

# A function takes one record, so every argument is named at the call site.
let clamp: { val: Int, low: Int, high: Int } -> Int = { val, low, high } ->
  if val < low then low else if val > high then high else val

# Functions are values: pass one in, return one out.
let twice: { f: { n: Int } -> Int, n: Int } -> Int = { f, n } ->
  f { n = f { n = n } }

let inc = { n } -> n + 1

let main = {} -> perform Console in do
  println { msg = "\$((clamp { val = 42, low = 0, high = 10 }).to_str {})" }
  println { msg = "\$((twice { f = inc, n = 5 }).to_str {})" }`,
  },
  {
    slug: "effects",
    title: "Effects",
    blurb: "What a function does to the world, in its type.",
    body: [
      "`<Log>` on the return type is an **effect row**. It says this function talks to the outside world, and that travels up to everyone who calls it.",
      "A **handler** decides what the effect means. `Console` prints; `QuietConsole` discards. The function itself does not change.",
      "Run it: only one line appears. The second call was handled into silence.",
    ],
    code: `use std::effects::log::{ Log, Console, QuietConsole, println }

# <Log> is an effect row. It says this function talks to the outside world,
# and the type carries that all the way up to whoever calls it.
let audit: { order: Int } -> {} <Log> = { order } ->
  println { msg = "order \$(order.to_str {}) seen" }

let main = {} -> do
  # A handler decides what the effect means. Console prints it...
  perform Console in audit { order = 1 }

  # ...QuietConsole throws it away. Same function, no change to its code.
  perform QuietConsole in audit { order = 2 }

  perform Console in println { msg = "done" }`,
  },
  {
    slug: "abilities",
    title: "Abilities",
    blurb: "Dot-calling methods on any type.",
    body: [
      "`Inspect` is derived for every type, so `.to_dbg_str {}` works on anything — records, numbers, variants you just declared.",
      "A dot call resolves against the receiver's type. `Int` has its own inherent methods too, like `.abs {}`.",
      "This is how Mezze does ad-hoc polymorphism. The next lesson adds your own.",
    ],
    code: `use std::effects::log::{ Console, println }

# Inspect is built in and derived for every type, so to_dbg_str always works.
type User = { name: Str, age: Int }

let main = {} -> perform Console in do
  let u = { name = "ada", age = 36 }

  # A dot call finds the method for this value's type.
  println { msg = u.to_dbg_str {} }
  println { msg = (42).to_dbg_str {} }

  # Int has its own inherent methods too.
  println { msg = "abs: \$(((0 - 7).abs {}).to_str {})" }`,
  },
  {
    slug: "custom-abilities",
    title: "Your own abilities",
    blurb: "Interfaces you can implement for any type.",
    body: [
      "An **ability** declares an interface. The receiver is called `it`, and every method takes a record like any other function.",
      "`impl Priced for Book` supplies it for one type — including types you did not write.",
      "`with Priced 'a` on a signature means *any type that implements this*. Write the function once, and it works for every implementation.",
    ],
    code: `use std::effects::log::{ Console, println }

# An ability is an interface. \`it\` is the receiver.
ability Priced for 'a where {
  cents: { it: 'a } -> Int
}

type Book = { title: Str, price: Int }

impl Priced for Book where {
  cents = { it } -> it.price
}

# \`with Priced 'a\` means: any type that implements Priced.
let show_total: { it: 'a } with Priced 'a -> Str = { it } ->
  "\$((it.cents {}).to_str {}) cents"

let main = {} -> perform Console in do
  println { msg = show_total { it = { title = "Dune", price = 1299 } } }`,
  },
  {
    slug: "custom-effects",
    title: "Your own effects",
    blurb: "Effects are library code, not syntax.",
    body: [
      "Effects are not built in. `effect Error having 'e` declares one, and `having` gives it an associated type so each handler picks its own error type.",
      "The handler decides the meaning. `abort` stops the computation and returns a value instead — that is how this one turns a raise into an `Err`.",
      "Exceptions, early return and retries are all ordinary code here. There is no syntax to wait for.",
    ],
    code: `use std::effects::log::{ Console, println }

# Effects are not built in — you declare your own.
effect Error having 'e where {
  raise: { err: 'e } -> 'a <Error>
}

# A handler decides what raising means. This one stops and returns an Err.
impl Error for ErrorToResult having 'e = 'err where {
  raise = { err } -> abort { val = Err { error = err } }
}

let safe_div = { a, b } ->
  if b == 0 then Error.raise { err = "divide by zero" } else Ok { val = a / b }

let report = { r } -> match r is
  Ok { val } -> "ok: \$(val.to_str {})"
  Err { error } -> "failed: \$(error)"

let main = {} -> perform Console in do
  println { msg = report { r = perform ErrorToResult in safe_div { a = 10, b = 2 } } }
  println { msg = report { r = perform ErrorToResult in safe_div { a = 10, b = 0 } } }`,
  },
  {
    slug: "full-program",
    title: "Putting it together",
    blurb: "A record type, an ability, an effect, and a fold.",
    body: [
      "All of it at once: a record type for the data, an ability for behaviour, a `<Log>` row the compiler infers rather than you writing, and a fold over a sequence.",
      "`x.total {}` is the ability method. `seq::fold` is a plain stdlib function — nothing here is special-cased.",
      "Edit the cart and press Run.",
    ],
    code: `use std::effects::log::{ Log, Console, println }
use std::collections::seq

type Item = { name: Str, price: Int, qty: Int }

ability Priced for 'a where {
  total: { it: 'a } -> Int
}

impl Priced for Item where {
  total = { it } -> it.price * it.qty
}

let line: { it: Item } -> Str = { it } ->
  "  \$(it.name) x\$(it.qty.to_str {}) = \$((it.total {}).to_str {})"

let main = {} -> perform Console in do
  let cart = [
    { name = "olive oil", price = 899, qty = 2 },
    { name = "za'atar", price = 450, qty = 1 },
    { name = "pita", price = 199, qty = 3 },
  ]

  println { msg = "cart:" }
  let lines = seq::map { xs = cart, f = { x } -> line { it = x } }
  println { msg = seq::fold { xs = lines, init = "", f = { acc, x } -> "\$(acc)\$(x)\\n" } }

  let sum = seq::fold { xs = cart, init = 0, f = { acc, x } -> acc + x.total {} }
  println { msg = "total: \$(sum.to_str {}) cents" }`,
  },
]
