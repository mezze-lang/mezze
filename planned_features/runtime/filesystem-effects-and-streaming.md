# Filesystem effects, and the streaming abstraction underneath them

Two linked designs from one session (2026-07-23): a seven-effect split of
`FileSystem`, and the pull-based streaming abstraction that several of its ops
want.

**Status, 2026-10-20.** Part 1 is **complete** — seven effects, 34 ops, both
backends, ~130 tests (`std/effects/filesystem.mz`, `FilesystemNatives.java`,
`MemoryFilesystemNatives.java`), including the three signature changes that
were deferred on the first pass. Part 2's open question has been **answered**:
performing an effect from inside an `Iter` stage, source or sink works across
the native driver loop's `@TruffleBoundary`, with no change needed. What is
left is the runner effect.

**Provenance.** This reconstructs a discussion that produced
`docs/effect-improvement/02_FILESYSTEM_SEVEN_EFFECTS.md` (398 lines), lost when
`docs/` was dropped. Recovered from the session transcript; the FS half was
explicitly approved ("i like the 7 different effects"), the streaming half
reached an agreed shape with its name then still open.

---

## Part 1 — Why `FileSystem` splits into seven effects

### The tension that forced it

The question was whether a user could write an **S3** performer for
`FileSystem` in userland. Walking the then-13 ops against S3 one by one, the
answer is: not honestly. The ops are shaped around a POSIX file, and an S3 impl
must either return `FsNotSupported` from half of them or **lie** — fake locks,
fake symlinks, fake atomic moves. Both are bad, and the second is worse because
it fails silently at runtime.

The same applies to HTTP/zip-backed (read-mostly) and network FS (NFS/SMB,
where lock and fsync semantics can lie about durability).

### The organising principle

> **Each effect is the largest set of ops that a real backend either implements
> fully, or does not implement at all.** No effect where any backend has to stub
> half the ops.

Consequences that fall out of it:

- A backend declares which effects it implements. A program that never performs
  `FileDurability` cannot call `fsync`, and an S3 backend simply has no
  `FileLock` performer to offer — so the mismatch is a compile error, not a
  runtime `NotSupported`.
- A signature discloses what it needs: `<FileSystem, FileDurability>` tells the
  reader "this wants a real filesystem, not object storage."
- Growth is a new effect, not a bigger core.

### The seven, as built

| Effect | Ops | Local performer | Memory performer |
|---|---|---|---|
| `FileSystem` | 9 | `LocalFileSystem` | `MemoryFileSystem` |
| `FileStreaming` | 8 | `LocalFileStreaming` | `MemoryFileStreaming` |
| `FilePermissions` | 6 | `LocalFilePermissions` | `MemoryFilePermissions` |
| `FileSymlinks` | 4 | `LocalFileSymlinks` | `MemoryFileSymlinks` |
| `FileWatch` | 3 | `LocalFileWatch` | `MemoryFileWatch` |
| `FileDurability` | 2 | `LocalFileDurability` | `MemoryFileDurability` |
| `FileLock` | 2 | `LocalFileLock` | `MemoryFileLock` |

**34 ops across 7 effects**, against 13 in one effect before. For comparison:
Cats Effect's `fs2.io.file.Files` is ~40 methods on one class, ZIO's
`zio.nio.file.Files` ~60, fsspec ~80. The split supersets their POSIX coverage
while every backend implements only what it genuinely supports.

**Why fourteen performer names and not two.** A performer implements exactly
**one** effect — `validateDuplicatePerformers` keys on the performer's name and
rejects a second `impl` for it (V0701). So `impl FileDurability for LocalFileSystem`
is not expressible; each effect gets its own `Local*`/`Memory*` pair, and a
program composes them: `perform LocalFileSystem in perform LocalFileDurability in …`.
This is the shape the design wanted anyway — the set of performers in scope *is*
the declaration of what the backend supports.

#### 1. `FileSystem` — the universal core (9 ops)

`read_bytes`, `write_bytes`, `stat`, `list`, `list_with_stat`, `delete`,
`create_directory`, `copy`, `move`.

Deliberately **no streaming here** — that is what makes the S3 impl clean.

**`list` stays alongside `list_with_stat`.** It looks like a one-line derivation
and it was proposed for deletion, but measured against syscall count the gap is
**5–100×**: `list_with_stat` must stat every entry. That is not slack.

#### 2. `FileStreaming` — random access (8 ops)

`open`, `read_chunk`, `write_chunk`, `read_at`, `write_at`, `seek`, `truncate`,
`close`.

The four middle ops were missing from Mezze and are exactly what databases and
binary-format tools need.

Two backend divergences the tests pinned, both fixed, both worth remembering
because they are the shape of bug this split invites:

- `close` republished its buffered writes *over* a `write_at`, undoing it. A
  positional op now takes authority and clears the handle's write buffer.
- memfs `open(Write)` did not truncate, though its doc said it did —
  `LocalFileSystem` passes `TRUNCATE_EXISTING`. Writing both backends in the **same
  pass** is what surfaced this; neither backend alone looks wrong.

#### 3. `FileDurability` — commit semantics (2 ops)

`fsync`, `fsync_dir`.

Needed universally by WALs, queues and databases; unneeded by 90% of programs,
which is the argument for making it explicit rather than implicit. MemFS no-ops
it *honestly* — though it must still publish the write buffer, or `fsync`
followed by a read sees stale bytes on one backend and not the other. Every S3
`PutObject` is already durable. `fsync_dir` is subtle and POSIX-specific enough
that hiding it behind an explicit effect is the honest move.

#### 4. `FileWatch` — change notification (3 ops)

`watch`, `poll`, `unwatch`, with
`WatchEvent = | Created, Modified, Deleted, Overflow |`.

`poll` returns a **batch**, matching inotify's `read` semantics, and lets the
caller control cadence rather than imposing one. LocalFileSystem uses NIO
`WatchService`; memfs **synthesises** events from its own mutations (8 mutation
points call `notifyWatchers`), bounded at 1024 queued events before `Overflow`.

The event semantics are pinned on memfs, where they are deterministic. The
LocalFileSystem tests are deliberately weaker — a real `WatchService` may coalesce or
delay, so asserting an exact sequence there would be a flake generator.

#### 5. `FileLock` — coordinated access (2 ops)

`lock`, `unlock`, with `LockKind = | Shared, Exclusive |` and a
`newtype LockHandle`. Whole-file advisory locks only; **byte-range locks are
deliberately omitted** (POSIX-only, rarely used). The handle holds the channel
*and* the lock, or closing one leaks the other's fd.

#### 6. `FilePermissions` — POSIX metadata (6 ops)

`get_mode`, `set_mode`, `get_owner`, `set_owner`, `set_modified`, `real_path`,
with a `FileMode` record of nine booleans and `FileOwner = { user, group }`.

`real_path` lives here because symlink resolution touches the metadata layer
anyway. Windows implements what it can and returns `FsNotSupported` for
owner/group off NTFS ACLs — which is honest rather than aspirational.

#### 7. `FileSymlinks` — links (4 ops)

`create_symlink`, `create_hardlink`, `read_symlink`, `is_symlink`.

Matters for package managers (nix, npm), blue/green deploy swaps and monorepo
tooling. Windows needs admin or developer mode.

`FsError` gained `FsNotSupported { path, operation }` for exactly these cases:
a backend that genuinely cannot do the thing on this platform, as distinct from
a backend that does not offer the effect at all.

> **Open question, deliberately left open:** should `FilePermissions` and
> `FileSymlinks` be one `effect FileMetadata` (a POSIX-only umbrella) instead of
> two? Seven separate effects was approved as-is, so the default is two.

### Mixing backends is the point, and each one owns its own paths

Performers are chosen per effect, so

```mezze
perform LocalFileSystem in perform MemoryFilePermissions in …
```

is ordinary, supported composition — real file content with fake modes, or the
reverse. This is what "a backend is the set of performers in scope" means, and
it is the main reason to have split the effects: a test can swap one capability
without faking the other six, and a program can take real content with an
in-memory lock.

The one thing to know is that **a path is resolved by whichever performer
handles the op, so mixing two path-keyed effects means two namespaces.**
`MemoryFileSystem.write_bytes { path = "/x" }` puts bytes in the in-memory map;
a subsequent `LocalFilePermissions.get_mode { path = "/x" }` asks the real disk
and correctly reports that nothing is there. Neither performer is wrong — a
path is a plain string, not a handle scoped to a store, and each effect
interprets it against its own.

Where that is most likely to surprise: `MemoryFileSystem` writes do not wake a
`LocalFileWatch` watcher, because memfs synthesises its events from its own
mutations and the NIO `WatchService` is watching a real directory. Pair the
backends when you want them to see each other.

HANDLES, unlike paths, cannot be crossed at all: `MezzeFileHandle` and
`MemFileHandle` are distinct Java classes and the extractors `instanceof`-check
them, so passing one backend's handle to the other's op raises
`NativeTypeMismatchException` rather than reading the wrong file.

### The three signature changes — LANDED 2026-10-20

```
write_bytes { path, bytes, mode: WriteMode }
stat        { path, follow_symlinks }
copy_file   { src, dest, replace_existing, preserve_attrs, follow_symlinks }
move_file   { src, dest, replace_existing, require_atomic }
```

99 call sites migrated, each to the OLD behaviour, so a migrated call means
what it meant before. Three things worth keeping from the exercise:

**`WriteMode` is `| CreateOrTruncate, CreateNew, CreateOrAppend |`,** not
`Append`, because the typechecker rejected that: a constructor name is unique
per module and `OpenMode.Append` already has it. The rejection was right and
exposed the name doing two jobs — `OpenMode.Append` positions a HANDLE at the
end, while a write mode is a policy about content already there. Spelling all
three as Create-or-what also makes the one that does NOT create stand out,
which is the one with the sharp edge.

**`CreateNew` is the whole point.** It is the atomic lockfile primitive, and
nothing else in the effect could express it: `exists` then `write_bytes` has a
window, and two processes racing through it both believe they won.
`create_exclusive { path, bytes } -> Result Bool` is its userland face — `True`
if this caller created the file, `False` if somebody already had it. memfs
implements it with `putIfAbsent` rather than contains-then-put, for exactly the
same reason.

**`require_atomic` closed a silent downgrade.** `move_file` used to try
`ATOMIC_MOVE` and quietly fall back when the platform refused — fine for "put
this file over there", and exactly wrong for publish-by-rename, where a reader
seeing a partial file is the bug rename was chosen to avoid.

**`follow_symlinks` landed on two of the four ops the design named**, and the
omissions are deliberate rather than unfinished:

- **`delete`** — POSIX `unlink` and `Files.delete` BOTH always remove the link
  and never the target, with no option to change it. A flag here could only be
  implemented as "resolve, then delete", which is not what any filesystem means
  by deleting a path; it would be a Mezze invention wearing a POSIX name.
- **`move_file`** — `Files.move` has no follow option, because a rename moves
  the directory entry and there is no target to follow.

**`append_bytes`/`append_text` lost an effect**, which is the ergonomic win
hiding in this change. They opened a handle, so adding a log line required
disclosing `<FileStreaming>` — random-access I/O, the one capability an object
store cannot offer. They are one `CreateOrAppend` write now and need only
`<FileSystem>`.

Still wanted: a durable-write `OpenMode` (`WriteSync`), or `OpenMode` as a
record of `mode + sync + create_new + delete_on_close` flags.

### Stays derived, in userland `std::fs`

No backend implements any of these:

`read_text`/`write_text`, `exists`/`is_file`/`is_directory`/`file_size` (all
from `stat`), `create_directories` (fold over `create_directory`),
`delete_recursive` and `walk`/`find` (fold over `list_with_stat`),
`with_read`/`with_write` scope helpers, `read_lines`, `temp_file`/`temp_dir`
(path + random name + `write_bytes { mode = CreateNew }`), and all path
manipulation as pure functions.

Note that the helpers which touch a handle — `append_bytes`, `append_text`,
`with_read`, `with_write` — now perform `<FileStreaming>`, so their callers
disclose it.

### Punted to the polyglot escape hatch, not stdlib

Memory-mapped files (needs `MappedByteBuffer` interop; real but specialised),
extended attributes (too platform-specific to be anything but aspirational),
`FileStore` disk-space queries (could be a 4-op `effect Storage` later; truly
admin-tool niche), byte-range locks, sparse files, Windows specifics (junction
points, alternate data streams, share modes), and hosting a FUSE filesystem
*from* Mezze — a different layer entirely.

### What other languages do, and why this is unusual

Worth recording because it means there was no design to copy:

- **Koka, Eff, Effekt, OCaml 5** — the algebraic-effects languages — ship **no
  FS effect at all.** Files are `extern` calls under one catch-all `io` effect.
  Koka's rationale: separating FS gains nothing over `io` unless you have a mock
  backend to justify it, and research languages don't.
- **Cats Effect / fs2** is the closest analog and the most complete, but splits
  by async-vs-streaming rather than by backend capability — everything is a
  method on one `Files` typeclass.
- **ZIO** mirrors Java NIO ~1:1 with no minimalism pass.
- **polysemy** (Haskell) goes the other way: ~8 ops, separate
  `doesFileExist`/`doesDirectoryExist` rather than `stat`, streaming delegated
  to a `Resource` effect.
- **Rust `object_store`** is 8 methods — the S3-shaped core, which is roughly
  this design's `FileSystem`.

---

## Part 2 — The streaming abstraction

Asked immediately after the FS design: *"do you think a stream module is
important for it first?"*

**What has since happened:** `Iter` shipped — lazy, pull-based, one value type,
a descriptor tree with `Step = | Yield, Skip, Done |`, effect-polymorphic
`<...>` on every stage and sink, compile-time fusion for known sources plus JIT
fusion for `unfold`. That answers most of the questions this section left open.
The entries below are kept for the reasoning, and marked with what is settled.

### Sequencing: yes, but not FS-first — and that held

A stream type is wanted by ~6 callers, not one: network bodies and WebSocket
frames, `Time.every` ticks, `FileWatch.poll` events, DB cursors, subprocess
stdout, and token/log-line parsing. Designing it around bytes and lines for FS
means re-generalising it the moment Network arrives.

So the seven-effect FS shipped without it, as planned. A caller wanting lazy
line reads writes an explicit `open`/`read_chunk`/buffer/`close` loop; layering
`Iter` on top later *wraps* these ops and invalidates none of them.

### What it is not: an effect — SETTLED

The first instinct was `effect Stream`, with per-source impls and sync/async
variants as performers. Rejected on a concrete objection: **every source would
have to become a performer.** A file, an HTTP body and a timer are not
handlers — making them impls is the wrong shape, and composing two sources would
mean composing two handlers. `Iter` is a value type, as agreed.

### The requirement, as stated

> One abstraction with the same helper methods — `filter`, `map` and the rest —
> where whether it runs **synchronously (for-loop-ish), async, or parallel is
> swappable**.

### The agreed shape — all but one point now built

- **One value type**, not two (no separate sync and async types). ✔ `Iter`.
- **Pull-based** — the consumer asks for the next element. ✔
- **Lazy** — nothing happens until consumed. ✔
- **Effect-polymorphic consumers** — the callback's row propagates via `<...>`.
  ✔ advertised on every stage and sink.
- **Combinators are pure** — `map`, `filter`, `take` build a new value and
  perform nothing. ✔
- **A runner effect for the execution strategy** — Sync / Async / Parallel as
  *performers of the runner*, not different types. **Not built.** This is the
  one piece of the agreed shape still missing, and it is what would make the
  threading model swappable while the pipeline stays one shape.

### Scope

Covered: Rust `for`/`Iterator` (semantically), Rust `Stream` cleanly, Kotlin
`Flow` directly, the common pull-shaped Rx uses, and all the sources above.
Confirmed as "all we need".

**Not covered, deliberately:** Rx-style push, broadcast and multi-subscriber.
That is a separate `Channel` abstraction for later — a push model is a different
thing wearing the same word.

### The name — SETTLED as `Iter`

`Flow` was proposed and discarded: it reads as something being pushed at you
("go with the flow") when the mechanism is strictly pull. `Feed` was the
session's preference, `Iter` the recognisable option with minor
Rust-synchronous baggage. `Iter` is what shipped.

### Open questions, re-scored

1. ~~The name~~ — settled: `Iter`.
2. ~~Internal representation~~ — settled: a native descriptor tree, not the
   recursive-closure `newtype`.
3. ~~The effect-polymorphic signature, concretely~~ — **ANSWERED, it works.**
   13 tests in `IterEffectBoundaryTest`, no code change needed. A suspension
   does cross the native driver loop's `@TruffleBoundary`: sinks (`for_each`,
   `fold`), stages (`map` mid-walk, `filter` deciding control flow on a read,
   `any` short-circuiting, `flat_map`), a source (`unfold` whose step reads the
   filesystem to decide `Yield`/`Done`), all three composed in one walk, and
   both non-local exits — `Exception.throw` unwinds the loop and stops where it
   says, `Async.await` parks and resumes from a sink and from a stage.
4. **`IntoIter`/`FromIter` abilities** or plain `to_iter`/`to_seq` functions?
   `FromIter` depends on return-type-driven ability dispatch, itself open
   elsewhere in the codebase.
5. **Delete `Generator` and the narrow `Stream` in `generator.mz`?** Both still
   exist and were called "old and maybe wrong" — either replace them or keep
   them as a separate multi-shot-yield feature that happens to share a word.
6. **`for x in xs do body` sugar** desugaring to `for_each` — never discussed,
   and flagged as one of the highest-value ergonomic choices. Without it the API
   is fine but verbose.
7. ~~Perf target~~ — answered by building fusion up front.
8. **Single-shot or re-consumable?** Rust `Iterator` and Kotlin `Flow` chose
   differently; `Iter`'s descriptor tree makes re-consumption cheap, but nothing
   states the contract.
9. **Error propagation** — if the source raises `Exception`, does the pipeline
   halt immediately, or does the runner own a policy (skip, retry, log)? This is
   where Rx became baroque. Still open, and tied to question 3.

---

## The runner effect — the question dissolved

The agreed shape wanted "Sync / Async / Parallel as performers of one runner".
That runner **already exists**: `std::effects::parallel` has a `Parallel`
effect with five performers (`LoomParallel`, `LoomParallelWith`,
`OSThreadParallel`, `OSThreadParallelWith`, `DeterministicSequential`). What
was missing was not the runner but a lazy bounded-concurrency stage, and that
turned out not to be worth building. Measured
(`bench/cases/concurrent_map_compare`, identical checksums):

```
CPU-bound, 20000 items x 400 ops
  chunk_fold        63ms
  sequential       174ms
  par_map(16)      241ms     <- SLOWER than not forking

I/O-shaped, 160 items each parked 2ms
  sequential                           336ms
  chunk_fold, default min_chunk=1024   331ms   <- no parallelism at all
  par_map(16)                           21ms
  chunk_fold, min_chunk=1                6ms   <- fastest
```

Chunking expresses bounded concurrency over waits, and beats fork-per-element
at it. So an `iter::map_concurrent` has **no performance case** in either
regime. It has one remaining argument, which no benchmark can settle: a source
that cannot be materialised at all — an endless `watch_events`, a paginated
cursor, a 50GB file's lines — where `chunk_fold` and `par_map` are both
unusable because chunking needs the whole sequence up front. That is a
capability decision, not an optimisation.

**The trap worth carrying away:** `min_chunk = 1024` is tuned for compute, so
any workload under 1024 items is one chunk that never forks — 55x on the I/O
half, silently, and the module header used to claim `LoomParallel` was "great
for I/O-bound work". It now carries these numbers. There is deliberately no
single default that serves both, because the right threshold is a property of
the body the module cannot see.

## Remaining order of work

1. **HTTP streaming bodies** — the one substantial piece left, and a design
   pass rather than a retrofit. `Request.body` is `Option Bytes` and
   `Response.body` is `Bytes`, both fully materialised, so this is introducing
   a streaming body to the effect, the client AND the server. The earlier note
   that this would have to invent `ServerBody = | Whole, Streamed |` still
   stands.
2. **`iter::map_concurrent`, only if the capability is wanted** — see above. It
   buys unmaterialisable sources and nothing else.
3. **Whether `min_chunk`'s default should change**, or gain an I/O-shaped
   performer (`LoomParallelIO`?). Documented rather than decided, because
   changing it would slow every CPU-bound caller.
4. **`OpenMode` as a flags record** (`mode + sync + create_new +
   delete_on_close`) rather than four arms, if a fifth combination ever shows
   up.

Done: the seven effects, the three signature changes, the `Iter` boundary
question, `WriteSync`, the lazy FS sources (`lines_of` / `with_lines` /
`watch_events`), and the JVM-vs-native parity guard.
