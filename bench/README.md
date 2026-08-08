# bench/

Cross-language perf harness. Same benchmark in Mezze, C, Java, Node, Python.

## Layout

- `cases/` — one dir per benchmark; each holds a Mezze project (`Mezze.toml` + `src/app.mz`).
- `reference/` — same benchmark in C / Java / JS / Python.
- `scripts/run.sh` — runs everything, times user-cpu, writes a Markdown table.
- `results/` — timestamped Markdown tables. `latest.md` is the most recent.

## Cases

| id | what | why |
|---|---|---|
| `fib` | `fib(35) + fib(38)` recursion | measures function-call overhead |
| `sum_fold` | `range(0, 10M).fold((+))` | seq HOF hot loop — the workload #403 targets |
| `sum_loop` | native for-range loop `sum += i` | seq-free control; measures pure loop |

## Running

```bash
./bench/scripts/run.sh          # runs everything, prints table, updates results/latest.md
./bench/scripts/run.sh fib      # single case
./bench/scripts/run.sh -n 5     # 5 trials per (case, runtime)
```

## Timing convention

- `time` reports `user`. That's CPU time in the process, excludes wall-clock scheduling noise.
- Every case runs 3 trials by default; we report **min** (best-of-N is the standard perf-comparison protocol; median hides variance).
- Mezze native and C are "fresh process" costs. Java/Node/Python pay startup per run. To subtract startup, look at min-of-N — the warm run dominates.

## Baseline

`results/2026-08-08-baseline.md` is the pre-#403 baseline (interpreter-only was 32s, JIT-in-native is 1.13s for the combined bench).
