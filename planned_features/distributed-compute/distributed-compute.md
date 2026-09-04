# Distributed compute in Mezze — the authoritative design

Status: design (consolidated). Supersedes the thesis in
`distributed-hash-transport.md`, `sending-hashes-explained.md`, and
`distributed-compute-feasibility.md` where they conflict; those remain useful for
the transport/discovery mechanics and the plain-language intro. Implementation
detail for the value serializer lives in `native-value-serializer.md`.

This is the single source of truth for what distributed compute *is* in Mezze,
what serializes and how, and the type-level contract.

---

## 0. The one-sentence model

**Distributed compute in Mezze is a typed async call to a remote node.** It is not
a new subsystem — it is a special case of two things the language already has:
**content-addressed code** (so *what runs there* is settled by hash) and
**`Async` + `Result`** (so *did it come back, and did it succeed* is settled by
types). Everything else falls out.

```
ship code by hash  +  ship data by a built-in serializer  →  Async → Result 'a RemoteError
```

---

## 1. Why it collapses into async + types

A remote call is an `Async.spawn` whose task runs on another node. You get a
`Promise`; you `await` it into a `Result`. Every distributed concern is then an
existing `std::effects::async` primitive:

| Concern | Mechanism (already exists) |
|---|---|
| request / reply | `spawn` → `await` → match the `Result` |
| fire-and-forget | `Async.go` the call, drop the handle |
| peer death / no response | a `Promise` that never resolves → `with_timeout` → `Err { Timeout }` |
| fan-out over N nodes | `all { ps }` → `[Result 'a AsyncError]` |
| remote autonomy | the node holds code + data; you hold a promise — its scheduling is its own |

**No new fault-tolerance runtime is needed.** Erlang built supervision trees
because it is untyped: an unexpected shape crashes a process, and a supervisor
*reacts*. Mezze *prevents* the unhandled crash — the failure is a typed `Err`
variant the compiler forces you to match. Recovery is ordinary typed control flow
(retry / fail over / degrade) at the call site. The one thing a `Result` can't see
— a peer physically vanishing — is caught by `with_timeout`, which converts it back
into a typed `Err`.

---

## 2. What serializes — the uniform rule

> **Anything serializes if its parts serialize.** A record ships iff its fields
> ship. A variant ships iff its payload ships. A **closure ships iff its code (a
> hash) ships AND its captured environment (values) ships.** The recursion bottoms
> out only at things with no value or hash representation.

Concretely:

| In the shipped value | How it travels | If the receiver can't resolve it |
|---|---|---|
| **Data** — record / variant / collection / primitive | serialized structurally (built-in) | — always works |
| **Function / closure** | `[code hash] + [recursively-serialized captures]` | fetch the code blob on miss (Transport) |
| **Ability impl** | it's a dictionary of functions → hashes | fetch on miss |
| **Effectful code** | it's a function → hash; the *effect row* is its capability contract | fetch on miss; **handler supplied by receiver** |
| **Native / host function** | **NOT serialized** — must pre-exist on the receiver | handshake catches it; else typed `Err { MissingNative }` |
| **Live continuation / open OS handle** | not sendable (no value form) | you don't send these — you send code to *run* |

### 2.1 The whole function ships — body AND captures

The earlier belief that "closures are excluded" was wrong. A closure is
`code hash + captured environment`. The code half is a hash (ships). Each captured
value is *a value* → serialize it recursively. So on the wire:

```
serialize(closure) = [FUNCTION] [code hash #H] [N] [ serialize(cap[0]) … serialize(cap[N-1]) ]
```

The receiver fetches `#H`'s blob if missing, deserializes the captures, and rebuilds
the identical closure. A captured `Int`/record/`function` all travel; only a
captured **native handle** stops it.

### 2.2 Effect handlers do NOT travel — and that's the point

Effectful code ships as a hash. Its **effect row** (`<Log, Http>`) is visible in
its type. The **handler is supplied by the receiving node**, not sent. This is a
feature, not a gap:

- the receiver decides *how* `Log`/`FileSystem`/`Http` are handled (or refuses),
- the effect row tells the receiver *what the code will demand* before it runs,
- untrusted code runs under a **restricted handler set** (no `FileSystem`, no
  `Http`) → sandboxed by construction.

This is a capability-honest distributed model that Erlang/Spark cannot express:
"this remote code will touch the filesystem" is *in the type*, and the receiver
grants or denies.

### 2.3 The native boundary — handshake + typed error

Native/host functions (`__native_*`) are the one irreducible non-hash, non-value
thing — they are JVM/Truffle code, not Mezze. They must **already exist** on the
receiver. This is handled in three layers, all typed:

1. **Handshake** (once per peer): nodes exchange a native/ABI + stdlib-version
   fingerprint. Each side knows what the other can run.
2. **Pre-flight** (per call): the sender knows the closure's transitive native set
   (a static IR walk). If the peer lacks one → return `Err { MissingNative }`
   locally, no wasted round-trip.
3. **Runtime backstop**: if a version skews mid-flight, the receiver hits an unknown
   native and returns `Err { MissingNative }` over the wire — never a crash.

---

## 3. The type-level contract

The un-shippable cases become **type errors at the `Remote` boundary**, not runtime
surprises.

```mezze
type Node = { addr: Str, id: Str }

type RemoteError = |
  Unreachable     { node: Node },
  Timeout         { node: Node, ms: Int },
  MissingNative   { name: Str, node: Node },
  RemoteThrew     { message: Str },
  IntegrityFailed { requested: Str, got: Str },
  Refused         { reason: Str },
|

## fire-and-forget: run an effectful thunk over there, don't wait
pub let go   : { node: Node, fn: {} -> {} <*> } -> {} <Async, Transport>

## request/reply: run it, get a SERIALIZABLE result back
pub let call : { node: Node, fn: {} -> 'a <*> } -> Result 'a RemoteError <Async, Transport>

## convenience: map a fn over serializable-record args
pub let apply : { node: Node, fn: { r } -> 'a <*>, args: 'r } -> Result 'a RemoteError <Async, Transport>
```

Design points enforced by these signatures:
- `fn` is a **function value → ships by hash** (with captures, per §2.1).
- `Remote.call` sends a hash and gets back **only data** (`'a`, serializable).
- `Remote.go` sends **only a hash** — nothing to serialize on the way in.
- effects (`<*>`) are handled by the **receiver's** handlers.

**Caveat — arity does not stop captures.** A `{} -> 'a <*>` thunk can still be an
inline closure over locals; that's fine now (captures serialize, §2.1). The only
hard failure is a captured native handle → surfaces as a serialize error / typed
`Err`. Prefer passing data as `args` (via `Remote.apply`) over capturing large
locals, for efficiency, not correctness.

---

## 4. The wire format for values

**Fixed, built-in, runtime-owned — like `Inspect`, not like `EncodeRepr`.** Both
ends are the same Mezze, so the receiver rebuilds the bit-identical value; the
format must be a runtime invariant, exactly like the content hash. Users control
the **transport** (how bytes move); the runtime controls the **format** (what the
bytes are).

- **Encoding: CBOR** (already a dependency via `StoreSerialization`). Do NOT
  hand-roll a binary format; do NOT point kotlinx-CBOR at the raw Java value classes
  (their `int` variant tag is registry-local — would ship machine-local state).
- **Approach:** a hand-written structural walk from the runtime value classes
  (`MezzeRecord` / `MezzeVariant` / `Mutable*`) into one small `@Serializable
  WireValue` sealed type, which CBOR encodes/decodes. The walk is a near-mechanical
  twin of `InspectNatives.toStr`.
- **Two settled decisions:** variant tags travel by **constructor name**
  (registry-local → receiver re-interns via `getOrCreateTag`); records travel with
  **sorted field names** (self-describing shape, rebuilt via `fromFields`).
- **Orthogonal to the codec:** this path does NOT touch `EncodeRepr` / the
  `NewtypeType -> false` gap / the #86 redesign. It is **not blocked** by the
  deferred codec work. `EncodeRepr`/JSON stays for interop with the outside world.

Full byte layout, natives (`__native_serialize` / `__native_deserialize`), and the
round-trip test plan are in `native-value-serializer.md`. Estimate: **~1–2 days /
~600 LOC.**

---

## 5. Difficulty & implementation plan

Overall: **the hard part was pre-paid by content-addressing.** Code is already
portable and hash-identified; the store's fetch-on-miss seam already exists.

| Step | Cost |
|---|---|
| 1. `RemoteStore` behind the existing pluggable-store seam (fetch blobs over the network) | weekend |
| 2. `Transport` + `Discovery` effects & handlers (HTTP / in-memory), `Node`/`Cluster` | weekend–week |
| 3. Built-in value serializer (`__native_serialize`/`deserialize`, CBOR walk) — **unblocked by the codec** | ~1–2 days |
| 4. `Remote.go` / `Remote.call` / `Remote.apply` (thin wrappers over `Async.spawn` + Transport + serializer) | days |
| 5. Native/version handshake + `MissingNative`; sandbox posture (restricted handlers) | week |
| 6. Live-closure captures serialize (fold into step 3's recursion) | included / small |

**Critical path to a working `Remote.call`: steps 1–4** — a few focused weeks,
dominated by the (small) serializer. Fault handling, fire-and-forget, remote
autonomy, and fan-out need **zero** new work — already expressible via `Async` +
`Result`.

---

## 6. Why this is a strong foundation (vs industry)

- **Code transports by content hash** — the receiver runs exactly the bytes you
  meant, with free integrity and dedup. (Spark ships closures + fat jars and fights
  version skew.)
- **Failure lives in the type** — `Result 'a RemoteError`; no remote call can
  silently go unhandled. (gRPC bolts contracts on the side; Erlang can't type
  failure at all.)
- **Capabilities are typed** — the effect row is the contract; the receiver grants
  or denies handlers. A sandbox by construction.
- **Distribution = async + types**, not a bolted-on runtime.

Honest caveats (greenfield, not blockers): delivery semantics (exactly-once,
ordering, backpressure) and operational tooling (placement, tracing) are not built.
But the *bones* — moving the code, serializing any value including closures, and
handling every failure as a typed `Err` — are more principled than the incumbents,
because the two hardest problems were solved at the compiler, not patched at the
runtime.

---

## 7. Worked example — a worker pool

```mezze
let workers : [Node] = [ Node { addr = "http://w1:9000" }, … ]

## unit of work: a top-level fn + serializable record args
let score : { chunk: Chunk } -> Score = { chunk } ->
  seq::fold { xs = chunk.rows, init = 0, f = { acc, row } -> acc + weigh { row = row } }

let run_job : { chunks: [Chunk] } -> Report <Async, Transport> = { chunks } -> do
  let tasks = seq::map_indexed { xs = chunks, f = { i, chunk } ->
    Async.spawn { f = {} -> Remote.apply {
      node = seq::at { xs = workers, i = i % seq::len { xs = workers } },
      fn   = score,
      args = { chunk = chunk },
      timeout_ms = 5000,          -- peer death → Err { Timeout }
    } }
  }
  let results = Async.all { ps = tasks }        -- [Result Score RemoteError]
  let scores  = seq::filter_map { xs = results, f = { r } -> match r is
    Ok  { val = s }   -> Some { val = s }
    Err { error = _ } -> None }                 -- a dead worker doesn't kill the job
  Report { total = seq::sum { xs = scores }, ok = seq::len { xs = scores } }
```

`Remote.apply`, `Node`, `Transport`, and the value serializer are the proposed
surface; `Async.spawn/all/go/with_timeout` and content-addressed code are real
today.
