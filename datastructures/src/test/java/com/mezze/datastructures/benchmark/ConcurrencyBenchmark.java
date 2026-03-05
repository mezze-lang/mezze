package com.mezze.datastructures.benchmark;

import com.mezze.datastructures.concurrent.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Performance benchmark for concurrency primitives.
 * Run with: ./gradlew :datastructures:test --tests "*.ConcurrencyBenchmark" -i
 */
public class ConcurrencyBenchmark {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 5000;

    @Test
    public void runAllBenchmarks() {
        System.out.println("\n=== Concurrency Primitives Performance Benchmark ===\n");

        benchmarkAtom();
        benchmarkChannel();
        benchmarkMutex();
        benchmarkSemaphore();
        benchmarkLatch();
        benchmarkWaitGroup();
    }

    // ==================== Benchmark Helpers ====================

    private static void benchmark(String name, Runnable operation) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            operation.run();
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            operation.run();
        }
        long elapsed = System.nanoTime() - start;

        double usPerOp = (elapsed / 1000.0) / BENCHMARK_ITERATIONS;
        double totalMs = elapsed / 1_000_000.0;
        System.out.printf("      %-35s %6.2f us/op  (%6.2f ms total)%n", name, usPerOp, totalMs);
    }

    // ==================== MezzeAtom Benchmarks ====================

    private void benchmarkAtom() {
        System.out.println("    ============================================================");
        System.out.println("     MezzeAtom (Atomic Reference)");
        System.out.println("    ============================================================\n");

        MezzeAtom<Long> atom = new MezzeAtom<>(0L);

        // deref (lock-free read)
        benchmark("deref (lock-free read)", () -> {
            atom.deref();
        });

        // reset
        benchmark("reset", () -> {
            atom.reset(42L);
        });

        // swap with function (CAS loop)
        benchmark("swap (increment)", () -> {
            atom.swap(x -> x + 1);
        });

        // compareAndSet
        AtomicLong counter = new AtomicLong(0);
        benchmark("compareAndSet", () -> {
            long old = counter.get();
            atom.compareAndSet(old, old + 1);
        });

        // With validator
        MezzeAtom<Long> validatedAtom = new MezzeAtom<>(0L, x -> x >= 0);
        benchmark("swap with validator", () -> {
            validatedAtom.swap(x -> x + 1);
        });

        // With watches (1 watch)
        MezzeAtom<Long> watchedAtom = new MezzeAtom<>(0L);
        watchedAtom.addWatch("counter", e -> {});
        benchmark("swap with 1 watch", () -> {
            watchedAtom.swap(x -> x + 1);
        });

        System.out.println();
    }

    // ==================== MezzeChannel Benchmarks ====================

    private void benchmarkChannel() {
        System.out.println("    ============================================================");
        System.out.println("     MezzeChannel (Message Passing)");
        System.out.println("    ============================================================\n");

        // Unbounded channel - send/receive
        MezzeChannel<Long> unbounded = new MezzeChannel<>();
        benchmark("unbounded trySend", () -> {
            unbounded.trySend(42L);
            unbounded.tryReceive();
        });

        // Bounded channel
        MezzeChannel<Long> bounded = new MezzeChannel<>(100);
        benchmark("bounded trySend (capacity 100)", () -> {
            bounded.trySend(42L);
            bounded.tryReceive();
        });

        // Size check
        benchmark("size() check", () -> {
            unbounded.size();
        });

        // isEmpty check
        benchmark("isEmpty() check", () -> {
            unbounded.isEmpty();
        });

        // isClosed check (VarHandle optimized)
        benchmark("isClosed() check (VarHandle)", () -> {
            unbounded.isClosed();
        });

        System.out.println();
    }

    // ==================== MezzeMutex Benchmarks ====================

    private void benchmarkMutex() {
        System.out.println("    ============================================================");
        System.out.println("     MezzeMutex (Reentrant Lock)");
        System.out.println("    ============================================================\n");

        MezzeMutex mutex = new MezzeMutex();

        // Uncontended lock/unlock
        benchmark("lock/unlock (uncontended)", () -> {
            mutex.lock();
            mutex.unlock();
        });

        // tryLock (uncontended)
        benchmark("tryLock/unlock (uncontended)", () -> {
            if (mutex.tryLock()) {
                mutex.unlock();
            }
        });

        // withLock
        benchmark("withLock (simple action)", () -> {
            mutex.withLock(() -> 42);
        });

        // isHeldByCurrentThread
        mutex.lock();
        benchmark("isHeldByCurrentThread (held)", () -> {
            mutex.isHeldByCurrentThread();
        });
        mutex.unlock();

        System.out.println();
    }

    // ==================== MezzeSemaphore Benchmarks ====================

    private void benchmarkSemaphore() {
        System.out.println("    ============================================================");
        System.out.println("     MezzeSemaphore (Counting Semaphore)");
        System.out.println("    ============================================================\n");

        MezzeSemaphore semaphore = new MezzeSemaphore(100);

        // Acquire/release (uncontended)
        benchmark("acquire/release (uncontended)", () -> {
            try {
                semaphore.acquire();
                semaphore.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // tryAcquire (uncontended)
        benchmark("tryAcquire/release (uncontended)", () -> {
            if (semaphore.tryAcquire()) {
                semaphore.release();
            }
        });

        // withPermit
        benchmark("withPermit (simple action)", () -> {
            try {
                semaphore.withPermit(() -> 42);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // availablePermits
        benchmark("availablePermits()", () -> {
            semaphore.availablePermits();
        });

        System.out.println();
    }

    // ==================== MezzeLatch Benchmarks ====================

    private void benchmarkLatch() {
        System.out.println("    ============================================================");
        System.out.println("     MezzeLatch (CountDown Latch)");
        System.out.println("    ============================================================\n");

        // Create/countDown/check cycle
        benchmark("create + countDown + isDone cycle", () -> {
            MezzeLatch latch = new MezzeLatch(1);
            latch.countDown();
            latch.isDone();
        });

        // getCount
        MezzeLatch latch10 = new MezzeLatch(10);
        benchmark("getCount()", () -> {
            latch10.getCount();
        });

        // countDown
        benchmark("countDown (no await)", () -> {
            MezzeLatch l = new MezzeLatch(BENCHMARK_ITERATIONS + 1);
            l.countDown();
        });

        System.out.println();
    }

    // ==================== MezzeWaitGroup Benchmarks ====================

    private void benchmarkWaitGroup() {
        System.out.println("    ============================================================");
        System.out.println("     MezzeWaitGroup (Dynamic Counter)");
        System.out.println("    ============================================================\n");

        MezzeWaitGroup wg = new MezzeWaitGroup();

        // add/done cycle
        benchmark("add(1) + done() cycle", () -> {
            wg.add(1);
            wg.done();
        });

        // getCount
        benchmark("getCount()", () -> {
            wg.getCount();
        });

        // isDone
        benchmark("isDone()", () -> {
            wg.isDone();
        });

        System.out.println();
    }
}
