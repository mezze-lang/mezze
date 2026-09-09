package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying the Iterable ability impls for all built-in
 * collections: Seq, List, Set, SortedSet, Deque, Map, SortedMap.
 *
 * Each collection should gain `.to_iter{}` via its Iterable impl, enabling
 * the full Iter combinator surface (filter, map, fold, to_seq, etc.)
 * through dot-chaining.
 */
class IterableImplIntegrationTest {

    private Object eval(String src) {
        return MezzeTestHelper.run(src);
    }

    // ── Seq ─────────────────────────────────────────────────────────────

    @Test
    void seqToIterFoldSum() {
        var result = eval("""
            use std::collections::iter
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              [1, 2, 3, 4, 5].to_iter{}.fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        assertEquals(15L, result);
    }

    @Test
    void seqToIterFilterMapToSeq() {
        var result = eval("""
            use std::collections::iter
            use std::collections::seq
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              [1, 2, 3, 4, 5, 6]
                .to_iter{}
                .filter { p = { x } -> x % 2 == 0 }
                .map { f = { x } -> x * 10 }
                .to_seq{}
            main {}
            """);
        assertEquals(eval("[20, 40, 60]"), result);
    }

    @Test
    void seqToIterSum() {
        var result = eval("""
            use std::collections::iter
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              [10, 20, 30].to_iter{}.sum{}
            main {}
            """);
        assertEquals(60L, result);
    }

    // ── List ────────────────────────────────────────────────────────────

    @Test
    void listToIterFoldSum() {
        var result = eval("""
            use std::collections::iter
            use std::collections::list
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let xs = list::cons { head = 1, tail = list::cons { head = 2, tail = list::cons { head = 3, tail = list::empty {} } } }
              xs.to_iter{}.fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        assertEquals(6L, result);
    }

    @Test
    void listToIterFilterToList() {
        var result = eval("""
            use std::collections::iter
            use std::collections::list
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let xs = list::cons { head = 1, tail = list::cons { head = 2, tail = list::cons { head = 3, tail = list::cons { head = 4, tail = list::empty {} } } } }
              xs.to_iter{}.filter { p = { x } -> x > 2 }.to_list{}
            main {}
            """);
        var expected = eval("""
            use std::collections::list
            let main = {} -> list::cons { head = 3, tail = list::cons { head = 4, tail = list::empty {} } }
            main {}
            """);
        assertEquals(expected, result);
    }

    // ── Set ─────────────────────────────────────────────────────────────

    @Test
    void setToIterCount() {
        var result = eval("""
            use std::collections::iter
            use std::collections::set
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let s = set::from_seq { xs = [1, 2, 3, 4, 5] }
              s.to_iter{}.count{}
            main {}
            """);
        assertEquals(5L, result);
    }

    @Test
    void setToIterFilterCount() {
        var result = eval("""
            use std::collections::iter
            use std::collections::set
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let s = set::from_seq { xs = [1, 2, 3, 4, 5, 6, 7, 8] }
              s.to_iter{}.filter { p = { x } -> x % 2 == 0 }.count{}
            main {}
            """);
        assertEquals(4L, result);
    }

    // ── SortedSet ───────────────────────────────────────────────────────

    @Test
    void sortedSetToIterToSeq() {
        var result = eval("""
            use std::collections::iter
            use std::collections::sorted_set
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let s = sorted_set::from_seq { xs = [3, 1, 4, 1, 5, 9, 2, 6] }
              s.to_iter{}.to_seq{}
            main {}
            """);
        // SortedSet deduplicates and sorts: [1, 2, 3, 4, 5, 6, 9]
        assertEquals(eval("[1, 2, 3, 4, 5, 6, 9]"), result);
    }

    @Test
    void sortedSetToIterMapSum() {
        var result = eval("""
            use std::collections::iter
            use std::collections::sorted_set
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let s = sorted_set::from_seq { xs = [1, 2, 3] }
              s.to_iter{}.map { f = { x } -> x * x }.sum{}
            main {}
            """);
        // 1 + 4 + 9 = 14
        assertEquals(14L, result);
    }

    // ── Deque ───────────────────────────────────────────────────────────

    @Test
    void dequeToIterFold() {
        var result = eval("""
            use std::collections::iter
            use std::collections::deque
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let d = deque::from_seq { xs = [10, 20, 30] }
              d.to_iter{}.fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        assertEquals(60L, result);
    }

    @Test
    void dequeToIterTakeToSeq() {
        var result = eval("""
            use std::collections::iter
            use std::collections::deque
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
              d.to_iter{}.take { n = 3 }.to_seq{}
            main {}
            """);
        assertEquals(eval("[1, 2, 3]"), result);
    }

    // ── Map ─────────────────────────────────────────────────────────────

    @Test
    void mapToIterCount() {
        var result = eval("""
            use std::collections::iter
            use std::collections::map
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let m = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 1 }, k = "b", v = 2 }, k = "c", v = 3 }
              m.to_iter{}.count{}
            main {}
            """);
        assertEquals(3L, result);
    }

    @Test
    void mapToIterFoldValues() {
        var result = eval("""
            use std::collections::iter
            use std::collections::map
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let m = map::insert { m = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 10 }, k = "b", v = 20 }, k = "c", v = 30 }
              m.to_iter{}.fold { init = 0, f = { acc, x } -> acc + x.v }
            main {}
            """);
        assertEquals(60L, result);
    }

    // ── SortedMap ───────────────────────────────────────────────────────

    @Test
    void sortedMapToIterCount() {
        var result = eval("""
            use std::collections::iter
            use std::collections::sorted_map
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let m = sorted_map::insert { m = sorted_map::insert { m = sorted_map::empty {}, k = 2, v = "b" }, k = 1, v = "a" }
              m.to_iter{}.count{}
            main {}
            """);
        assertEquals(2L, result);
    }

    @Test
    void sortedMapToIterFoldKeys() {
        var result = eval("""
            use std::collections::iter
            use std::collections::sorted_map
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let m = sorted_map::insert { m = sorted_map::insert { m = sorted_map::insert { m = sorted_map::empty {}, k = 3, v = "c" }, k = 1, v = "a" }, k = 2, v = "b" }
              m.to_iter{}.fold { init = 0, f = { acc, x } -> acc + x.k }
            main {}
            """);
        // keys: 1, 2, 3 → sum = 6
        assertEquals(6L, result);
    }

    // ── Cross-collection pipeline ───────────────────────────────────────

    @Test
    void crossCollectionPipeline() {
        // Set → Iter → filter → map → collect to Seq
        var result = eval("""
            use std::collections::iter
            use std::collections::set
            use std::abilities::iter::{ Iterable }

            let main = {} -> do
              let s = set::from_seq { xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] }
              s.to_iter{}
                .filter { p = { x } -> x > 5 }
                .map { f = { x } -> x * 2 }
                .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // Elements > 5: 6,7,8,9,10 → doubled: 12,14,16,18,20 → sum = 80
        assertEquals(80L, result);
    }

    // ── User-defined Iterable impl ─────────────────────────────────────

    @Test
    void userDefinedIterableViaUnfold() {
        // Verify that iter::unfold + Iter combinators compose correctly
        // for user-authored iteration logic (the path a user Iterable impl takes)
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }

            let main = {} -> do
              iter::unfold {
                state = 1,
                step = { s } ->
                  if s > 5 then Done
                  else Yield { val = s * s, next = s + 1 }
              }.sum{}
            main {}
            """);
        // 1 + 4 + 9 + 16 + 25 = 55
        assertEquals(55L, result);
    }

    @Test
    void userDefinedIterableFilterToSeq() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }

            let main = {} -> do
              iter::unfold {
                state = 1,
                step = { s } ->
                  if s > 10 then Done
                  else Yield { val = s, next = s + 1 }
              }
                .filter { p = { x } -> x % 3 == 0 }
                .to_seq{}
            main {}
            """);
        assertEquals(eval("[3, 6, 9]"), result);
    }

    // ── Effectful user Iterable (async-streaming) ───────────────────────

    @Test
    void unfoldMapSum() {
        // Verify that unfold → map → sum works (the sum+map NPE fix)
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }

            let main = {} -> do
              iter::unfold {
                state = 0,
                step = { s } ->
                  if s >= 5 then Done
                  else Yield { val = s, next = s + 1 }
              }.map { f = { x } -> x * 2 }.sum{}
            main {}
            """);
        // (0+1+2+3+4)*2 = 20
        assertEquals(20L, result);
    }
}
