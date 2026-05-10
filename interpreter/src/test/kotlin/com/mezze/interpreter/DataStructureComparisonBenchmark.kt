package com.mezze.interpreter

import com.mezze.datastructures.deque.MezzeDeque
import com.mezze.datastructures.list.MezzeList
import com.mezze.datastructures.map.MezzeMap
import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.datastructures.set.MezzeSet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Mezze data-structure performance vs pure Java baselines.
 *
 * Each test runs the SAME workload twice:
 *   - Java side: directly on java.util.ArrayList/HashMap/etc. — measures
 *     the JVM's hand-tuned reference implementations, no Mezze overhead.
 *   - Mezze Java side: directly on MezzeList/MezzeMap/etc. — measures
 *     the storage primitive only, no interpreter overhead.
 *
 * The gap shows the cost of (a) immutability + COW (vs Java mutable), and
 * (b) Object[]-with-Long-boxing (vs Java specialized primitive containers
 * like int-keyed maps which Java doesn't have either, so this comparison
 * is mostly about the structural overhead).
 *
 * A separate set of tests adds the Mezze-interpreter overhead on top so we
 * can decompose: storage cost vs language dispatch cost.
 *
 * All times are median of 5 runs (after 5 warmup runs). N is fixed per
 * bench so the absolute numbers are comparable across implementations.
 */
class DataStructureComparisonBenchmark {

    private fun bench(label: String, runs: Int = 5, warmups: Int = 5, body: () -> Unit): Long {
        repeat(warmups) { body() }
        val timings = LongArray(runs)
        for (i in 0 until runs) {
            System.gc()
            Thread.sleep(20)
            val start = System.nanoTime()
            body()
            timings[i] = System.nanoTime() - start  // keep in nanos
        }
        timings.sort()
        val median = timings[timings.size / 2]
        val medianMs = median / 1_000_000.0
        println("  %-50s %10.3f ms".format(label, medianMs))
        return median
    }

    private fun divider(title: String) {
        println("\n=== $title ===")
        println("  %-50s %6s".format("workload", "median ms"))
        println("  " + "-".repeat(58))
    }

    // ===== Seq / List random-access vs ArrayList =====

    @Test
    @Timeout(300)
    fun `seq vs ArrayList - build 100K elements`() {
        divider("Build 100K-element list")
        val n = 100_000
        bench("java.util.ArrayList<Long>.add (mutable)") {
            val a = java.util.ArrayList<Long>(n)
            for (i in 0 until n) a.add(i.toLong())
        }
        bench("MezzeSeq.appendLong (persistent + COW)") {
            var s = MezzeSeq.empty<Long>()
            for (i in 0 until n) s = s.append(i.toLong())
        }
        bench("MezzeList.cons (persistent cons list)") {
            var l: MezzeList<Long> = MezzeList.empty()
            for (i in 0 until n) l = MezzeList.cons(i.toLong(), l)
        }
    }

    @Test
    @Timeout(300)
    fun `seq vs ArrayList - random read 1M elements`() {
        divider("Random read 1M elements (10K-element list)")
        val size = 10_000
        val iters = 1_000_000

        val javaList = java.util.ArrayList<Long>(size).apply {
            for (i in 0 until size) add(i.toLong())
        }
        var seq = MezzeSeq.empty<Long>()
        for (i in 0 until size) seq = seq.append(i.toLong())

        bench("java.util.ArrayList<Long>.get") {
            var sum = 0L
            for (i in 0 until iters) sum += javaList[i % size]
        }
        bench("MezzeSeq.get") {
            var sum = 0L
            for (i in 0 until iters) sum += seq.get(i % size) as Long
        }
    }

    // ===== Map: build + lookup =====

    @Test
    @Timeout(300)
    fun `map vs HashMap - build 10K entries`() {
        divider("Build 10K-entry Long->Long map")
        val n = 10_000
        bench("java.util.HashMap<Long,Long>.put (mutable)") {
            val m = java.util.HashMap<Long, Long>(n * 2)
            for (i in 0 until n) m[i.toLong()] = (i * 2).toLong()
        }
        bench("MezzeMap.putObject (persistent + COW)") {
            @Suppress("UNCHECKED_CAST")
            var m = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
            for (i in 0 until n) m = m.putObject(i.toLong(), (i * 2).toLong())
        }
    }

    @Test
    @Timeout(300)
    fun `map vs HashMap - lookup 1M times in 10K-entry map`() {
        divider("Lookup 1M times in 10K-entry map")
        val size = 10_000
        val iters = 1_000_000

        val javaMap = java.util.HashMap<Long, Long>(size * 2).apply {
            for (i in 0 until size) put(i.toLong(), (i * 2).toLong())
        }
        @Suppress("UNCHECKED_CAST")
        var mezze = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
        for (i in 0 until size) mezze = mezze.putObject(i.toLong(), (i * 2).toLong())
        val mezzeFinal = mezze

        bench("java.util.HashMap.get") {
            var sum = 0L
            for (i in 0 until iters) {
                val v = javaMap[(i % size).toLong()] ?: 0L
                sum += v
            }
        }
        bench("MezzeMap.get") {
            var sum = 0L
            for (i in 0 until iters) {
                val v = (mezzeFinal.get((i % size).toLong()) as? Long) ?: 0L
                sum += v
            }
        }
    }

    // ===== Set: contains =====

    @Test
    @Timeout(300)
    fun `set vs HashSet - contains 1M times in 10K-element set`() {
        divider("Contains 1M times in 10K-element set")
        val size = 10_000
        val iters = 1_000_000

        val javaSet = java.util.HashSet<Long>(size * 2).apply {
            for (i in 0 until size) add(i.toLong())
        }
        @Suppress("UNCHECKED_CAST")
        var mezzeSet = MezzeSet.empty<Any?>() as MezzeSet<Any?>
        for (i in 0 until size) mezzeSet = mezzeSet.addObject(i.toLong())
        val mezzeFinal = mezzeSet

        bench("java.util.HashSet.contains") {
            var hits = 0
            for (i in 0 until iters) if (javaSet.contains((i % size).toLong())) hits++
        }
        bench("MezzeSet.contains") {
            var hits = 0
            for (i in 0 until iters) if (mezzeFinal.contains((i % size).toLong())) hits++
        }
    }

    // ===== Deque: push/pop =====

    @Test
    @Timeout(300)
    fun `deque vs ArrayDeque - push 100K then pop all`() {
        divider("Deque push 100K then pop all")
        val n = 100_000
        bench("java.util.ArrayDeque.addLast/removeFirst (mutable)") {
            val d = java.util.ArrayDeque<Long>(n)
            for (i in 0 until n) d.addLast(i.toLong())
            while (!d.isEmpty()) d.removeFirst()
        }
        bench("MezzeDeque.pushBack/popFront (persistent + COW)") {
            var d = MezzeDeque.empty<Long>()
            for (i in 0 until n) d = d.pushBack(i.toLong())
            while (!d.isEmpty()) {
                val r = d.popFront()
                d = r.rest()
            }
        }
    }

    // ===== Combined Mezze-from-interpreter overhead view =====

    @Test
    @Timeout(600)
    fun `interpreter overhead - Mezze map workload from inside Mezze code`() {
        divider("Mezze map workload INSIDE Mezze interpreter")
        val n = 10_000
        val code = """
            use std::collections::map

            let build: {m: Map Int Int, i: Int, n: Int} -> Map Int Int = {m, i, n} ->
              if i == n then m
              else build {m = map::insert {m = m, k = i, v = i * 2}, i = i + 1, n = n}

            let unwrap: {opt: Option Int} -> Int = {opt} ->
              match opt is
                Some {val} -> val
                None -> 0

            let lookups: {m: Map Int Int, i: Int, n: Int, acc: Int} -> Int =
              {m, i, n, acc} ->
                if i == n then acc
                else lookups {m = m, i = i + 1, n = n, acc = acc + unwrap {opt = map::get {m = m, k = i}}}

            do
              let m = build {m = map::empty {}, i = 0, n = $n}
              lookups {m = m, i = 0, n = $n, acc = 0}
        """.trimIndent()
        bench("Mezze: build + lookup 10K (interpreter)") {
            MezzeTestHelper.run(code)
        }
    }
}
