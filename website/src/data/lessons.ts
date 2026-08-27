export interface Lesson {
  slug: string
  title: string
  blurb: string
  /** Paragraphs of explanation. */
  body: string[]
  code: string
}

/**
 * Lessons are derived from examples/*.mz in the Mezze repo — every snippet
 * has been run through the WASM runtime, so what the tour claims and what
 * the Run button produces agree.
 */
export const lessons: Lesson[] = [
  {
    slug: 'hello',
    title: 'Hello, Mezze',
    blurb: 'Bindings, functions, string interpolation.',
    body: [
      'Every Mezze program starts from `main`. Values bind with `let`, and there is no separate statement form — a program is a set of declarations.',
      'Functions always take a **record** argument. `{ who } -> ...` destructures a field named `who`, so calls are `greet { who = "world" }` — arguments are named at the call site, never positional.',
      'Strings interpolate with `$(...)`. Non-string values need `.to_dbg_str {}` first; Mezze will not silently stringify.',
    ],
    code: `use std::effects::log::{ Console, println }

let name = "Mezze"
let year = 2026
let flag = True

let greet = { who } -> "Hello, $(who)!"

let main = {} -> perform Console in do
  println { msg = greet { who = name } }
  println { msg = "year = $(year.to_dbg_str {})" }
  println { msg = "flag = $(flag.to_dbg_str {})" }
`,
  },
  {
    slug: 'types',
    title: 'Types and matching',
    blurb: 'Variants, exhaustive pattern matching.',
    body: [
      'A `type` declares a closed variant — a value is exactly one of its cases, each carrying its own named fields.',
      '`match ... is` must be **exhaustive** for a closed variant. Delete the `Triangle` arm below and press Check: the typechecker names the case you forgot, before the program ever runs.',
      'That is the whole bargain — the compiler tracks which shapes are possible, so adding a variant later turns every incomplete match into an error rather than a bug.',
    ],
    code: `use std::effects::log::{ Console, println }

type Shape = |
  Circle { radius: Int },
  Rect { w: Int, h: Int },
  Triangle { base: Int, height: Int },
|

let area = { shape } -> match shape is
  Circle { radius } -> radius * radius * 3
  Rect { w, h } -> w * h
  Triangle { base, height } -> base * height / 2

let main = {} -> perform Console in do
  println { msg = "circle = $(area { shape = Circle { radius = 2 } }.to_dbg_str {})" }
  println { msg = "rect   = $(area { shape = Rect { w = 3, h = 4 } }.to_dbg_str {})" }
`,
  },
  {
    slug: 'abilities',
    title: 'Abilities',
    blurb: 'Shared behaviour across types, plus dot-methods.',
    body: [
      'An `ability` names behaviour a type can provide — close to a typeclass or trait. `impl Printable for Vec2` supplies it for one type.',
      'An **inherent** `impl Vec2 where { ... }` is different: it attaches methods directly to the type, which is what makes `v.mag_sq {}` dot-syntax work.',
      'Both are resolved statically. There is no dynamic dispatch by name — the compiler knows exactly which implementation each call reaches.',
    ],
    code: `use std::effects::log::{ Console, println }

ability Printable for 'a where {
  display: { it: 'a } -> Str
}

type Vec2 = | Vec2 { x: Int, y: Int } |

impl Printable for Vec2 where {
  display = { it } -> match it is
    Vec2 { x, y } -> "($(x.to_dbg_str {}), $(y.to_dbg_str {}))"
}

impl Vec2 where {
  mag_sq = { it } -> match it is
    Vec2 { x, y } -> x * x + y * y,
}

let main = {} -> perform Console in do
  let v = Vec2 { x = 3, y = 4 }
  println { msg = "v       = $(Printable.display { it = v })" }
  println { msg = "mag_sq  = $(v.mag_sq {}.to_dbg_str {})" }
`,
  },
  {
    slug: 'effects',
    title: 'Effects',
    blurb: 'What a function does is part of its type.',
    body: [
      'This is the idea Mezze is built around. `process_order` calls `info` and `warn`, so it carries the `<Log>` effect in its type. A caller cannot ignore that — it must supply a handler with `perform`.',
      'The same function then runs twice below with **different handlers**: `Console` prints, `QuietConsole` stays silent. The function did not change; the interpretation of its effects did.',
      'That is what separates effects from ordinary side effects. Logging, filesystem, HTTP and randomness are all values you pass in, so a test swaps `MemoryFS` for `LocalDisk` and nothing else moves.',
    ],
    code: `use std::effects::log::{ Log, Console, QuietConsole, println, info, warn }

let process_order = { id, amount } -> do
  info { msg = "Processing order #$(id.to_dbg_str {})" }
  if amount > 1000
    then warn { msg = "Large order: $(amount.to_dbg_str {})" }
    else info { msg = "Normal order: $(amount.to_dbg_str {})" }
  amount

let main = {} -> perform Console in do
  println { msg = "--- Console handler ---" }
  perform Console in do
    process_order { id = 1, amount = 500 }
    process_order { id = 2, amount = 1500 }
  println { msg = "--- QuietConsole handler (silent) ---" }
  perform QuietConsole in do
    process_order { id = 3, amount = 9999 }
  println { msg = "done" }
`,
  },
  {
    slug: 'handlers',
    title: 'Swapping the world',
    blurb: 'Filesystem and randomness as values.',
    body: [
      'Because effects are handled rather than performed directly, the surrounding world is a parameter. `MemoryFS` is a complete filesystem that exists only in memory.',
      '`SeededRandom { seed = 42 }` is deterministic — this page and the `mez` CLI produce the identical number. That is what makes a shareable playground link mean something.',
      'Swap `MemoryFS` for `LocalDisk` and press Run: the browser refuses, naming the performer, because there is no real disk here. It does not quietly pretend to succeed.',
    ],
    code: `use std::effects::log::{ Console, println }
use std::effects::filesystem::{ FileSystem, MemoryFS, write_text, read_text_or }
use std::effects::random::{ Random, SeededRandom, random_int }

let main = {} -> perform Console in perform MemoryFS in
  perform SeededRandom { seed = 42 } in do
    write_text { path = "/notes.txt", text = "written in memory" }
    println { msg = "file  = $(read_text_or { path = "/notes.txt", default = "missing" })" }
    println { msg = "roll  = $(random_int { min = 1, max = 100 }.to_dbg_str {})" }
`,
  },
  {
    slug: 'http',
    title: 'Talking to the network',
    blurb: 'A real HTTPS request, from this tab.',
    body: [
      'The `Http` effect works the same way. `HttpClient` is the real performer; in this page it is bridged to the browser, so the request below genuinely leaves your machine.',
      '`Http.request` returns a `Result`, so failure is a value you match on rather than an exception you forget to catch.',
      'Browser rules still apply: only sites that send permissive CORS headers will answer. For examples that must always work, `MockHttpClient` scripts responses instead.',
    ],
    code: `use std::effects::log::{ Console, println }
use std::effects::http::{ Http, HttpClient, request, default_http_config, body_string }

let main = {} -> perform Console in perform HttpClient in do
  let resp = Http.request {
    req = request { url = "https://api.github.com/zen" },
    config = default_http_config,
  }
  match resp is
    Ok { val } -> match body_string { resp = val } is
      Ok { val = text } -> println { msg = "$(val.status.to_dbg_str {}): $(text)" }
      Err { error = _ } -> println { msg = "status $(val.status.to_dbg_str {}), no body" }
    Err { error = _ } -> println { msg = "request failed" }
`,
  },
]
