<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Loom, async and parallel

**This surface is JDK-bound.** That is the finding, and it took a reference
measurement outside Mezze to establish.

Plain Java, same JDK (GraalVM CE 25.0.2), same machine, same shape — create a
virtual thread, run a trivial body, wait for its result:

| | ps/op |
|---|---|
| `Thread.ofVirtual().start()` + `join()` | 5 324 047 - 5 999 554 |
| `newVirtualThreadPerTaskExecutor().submit()` + `get()` | 3 694 968 - 4 866 857 |
| **Mezze `Async.spawn` + `await`** | **10 212 547 - 11 165 827** |

**Loom itself is 5.3 us and Mezze is 1.9x it.** `asyncsurface` reports the same
row as "x3314172/100 floor" because its floor is a counted loop — a true ratio
that compares creating a thread to adding two numbers, and tells you nothing.

Where the remaining ~5 us goes, from reading the spawn path: Truffle thread
registration (a guest thread MUST be registered with the polyglot context, so it
cannot be a bare `Thread.ofVirtual()`), `snapshotHandlerChainEntries` copying the
chain per spawn, `lookupScopeVar`, and a `MezzePromise` plus semaphore.
Amortising the first would mean a pool of pre-registered Truffle threads — a
design change, not a patch.

## Landed

Nothing, and deliberately: two thirds of the cost is the JDK's, and the iter
surface in the same session had 37x-122x cliffs of its own making.

## Open

- A reused-scheduler spawn (the executor variant is 30% cheaper than
  thread-per-task).
- **`parallel` has no numbers of its own** — no row separates it from `spawn`.
- Harness defects: with no row argument `asyncsurface` prints the header and NO
  rows; and row 1 at n=2000 does not finish in 400 s while n=800 takes under a
  second, though per-op cost FALLS with n (36.7 us at 100 to 10.2 at 400), so
  that is warmup amortising and the n=2000 behaviour is an anomaly.
