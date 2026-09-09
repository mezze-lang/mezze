package com.mezze.interpreter;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the universal Iter system:
 * unfold, from_list, once, empty, and all new combinators
 * (take_while, drop_while, enumerate, chain, zip, flat_map,
 * first, last, count).
 */
class IterUnfoldIntegrationTest {

    private Object eval(String src) {
        return MezzeTestHelper.run(src);
    }

    // ── unfold: basic ────────────────────────────────────────────────

    @Test
    void unfoldSquares() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }

            let main = {} -> do
              iter::unfold {
                state = 0,
                step = { s } ->
                  if s >= 5 then Done
                  else Yield { val = s * s, next = s + 1 }
              }.fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // 0 + 1 + 4 + 9 + 16 = 30
        assertEquals(30L, result);
    }

    @Test
    void unfoldCountdown() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }

            let main = {} -> do
              iter::unfold {
                state = 5,
                step = { s } ->
                  if s <= 0 then Done
                  else Yield { val = s, next = s - 1 }
              }.fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // 5 + 4 + 3 + 2 + 1 = 15
        assertEquals(15L, result);
    }

    // ── unfold + filter + map ────────────────────────────────────────

    @Test
    void unfoldFilterMap() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }

            let main = {} -> do
              iter::unfold {
                state = 0,
                step = { s } ->
                  if s >= 10 then Done
                  else Yield { val = s, next = s + 1 }
              }.filter { p = { x } -> x % 2 == 0 }
               .map { f = { x } -> x * x }
               .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // even: 0,2,4,6,8; squared: 0,4,16,36,64; sum = 120
        assertEquals(120L, result);
    }

    // ── unfold with Skip ─────────────────────────────────────────────

    @Test
    void unfoldWithSkip() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Skip, Done }

            let main = {} -> do
              iter::unfold {
                state = { n = 0, count = 0 },
                step = { s } ->
                  if s.count >= 5 then Done
                  else if s.n % 2 != 0 then Skip { next = { n = s.n + 1, count = s.count } }
                  else Yield { val = s.n, next = { n = s.n + 1, count = s.count + 1 } }
              }.fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // even numbers: 0, 2, 4, 6, 8 → sum = 20
        assertEquals(20L, result);
    }

    // ── from_list ────────────────────────────────────────────────────

    @Test
    void fromListMapFold() {
        var result = eval("""
            use std::collections::iter
            use std::collections::list

            let main = {} -> do
              let xs = list::cons { head = 10, tail = list::cons { head = 20, tail = list::cons { head = 30, tail = list::empty {} } } }
              iter::from_list { xs = xs }.map { f = { x } -> x + 1 }.fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // (10+1) + (20+1) + (30+1) = 63
        assertEquals(63L, result);
    }

    // ── once ─────────────────────────────────────────────────────────

    @Test
    void onceElement() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::once { val = 42 }.fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        assertEquals(42L, result);
    }

    // ── empty ────────────────────────────────────────────────────────

    @Test
    void emptyFold() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::empty {}.fold { init = 99, f = { acc, x } -> acc + x }
            main {}
            """);
        assertEquals(99L, result);
    }

    // ── chain ────────────────────────────────────────────────────────

    @Test
    void chainTwoIterators() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::once { val = 100 }.chain { b = iter::range { start = 1, end = 4 } }
                .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // 100 + 1 + 2 + 3 = 106
        assertEquals(106L, result);
    }

    // ── take_while ───────────────────────────────────────────────────

    @Test
    void takeWhile() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 1, end = 100 }
                .take_while { p = { x } -> x < 5 }
                .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // 1 + 2 + 3 + 4 = 10
        assertEquals(10L, result);
    }

    // ── drop_while ───────────────────────────────────────────────────

    @Test
    void dropWhile() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 1, end = 6 }
                .drop_while { p = { x } -> x < 3 }
                .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // drops 1,2; keeps 3,4,5; sum = 12
        assertEquals(12L, result);
    }

    // ── enumerate ────────────────────────────────────────────────────

    @Test
    void enumerateRange() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 10, end = 13 }
                .enumerate {}
                .fold { init = 0, f = { acc, x } -> acc + x.i + x.val }
            main {}
            """);
        // (0+10) + (1+11) + (2+12) = 10 + 12 + 14 = 36
        assertEquals(36L, result);
    }

    // ── zip ──────────────────────────────────────────────────────────

    @Test
    void zipTwoRanges() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 1, end = 4 }
                .zip { b = iter::range { start = 10, end = 13 } }
                .fold { init = 0, f = { acc, x } -> acc + x.fst + x.snd }
            main {}
            """);
        // (1+10) + (2+11) + (3+12) = 11 + 13 + 15 = 39
        assertEquals(39L, result);
    }

    // ── flat_map ─────────────────────────────────────────────────────

    @Test
    void flatMapExpand() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 1, end = 4 }
                .flat_map { f = { x } -> iter::range { start = 0, end = x } }
                .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // 1->[0]; 2->[0,1]; 3->[0,1,2]; elements: 0, 0,1, 0,1,2; sum = 4
        assertEquals(4L, result);
    }

    // ── first ────────────────────────────────────────────────────────

    @Test
    void firstOfRange() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              match iter::range { start = 10, end = 15 }.first {} is
                Some { val } -> val
                None -> -1
            main {}
            """);
        assertEquals(10L, result);
    }

    @Test
    void firstOfEmpty() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              match iter::empty {}.first {} is
                Some { val } -> 1
                None -> 0
            main {}
            """);
        assertEquals(0L, result);
    }

    // ── last ─────────────────────────────────────────────────────────

    @Test
    void lastOfRange() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              match iter::range { start = 10, end = 15 }.last {} is
                Some { val } -> val
                None -> -1
            main {}
            """);
        assertEquals(14L, result);
    }

    @Test
    void lastOfEmpty() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              match iter::empty {}.last {} is
                Some { val } -> 1
                None -> 0
            main {}
            """);
        assertEquals(0L, result);
    }

    // ── count ────────────────────────────────────────────────────────

    @Test
    void countRange() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 0, end = 100 }.count {}
            main {}
            """);
        assertEquals(100L, result);
    }

    @Test
    void countEmpty() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::empty {}.count {}
            main {}
            """);
        assertEquals(0L, result);
    }

    // ── to_seq materialization ───────────────────────────────────────

    @Test
    void unfoldToSeq() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }
            use std::collections::seq

            let main = {} -> do
              let xs = iter::unfold {
                state = 1,
                step = { s } ->
                  if s > 3 then Done
                  else Yield { val = s * 10, next = s + 1 }
              }.to_seq {}
              seq::size { xs = xs }
            main {}
            """);
        assertEquals(3L, result);
    }

    // ── complex pipeline: unfold → filter → map → take → fold ───────

    @Test
    void complexPipeline() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Skip, Done }

            let main = {} -> do
              iter::unfold {
                state = { a = 1, b = 1 },
                step = { s } ->
                  if s.a > 100 then Done
                  else Yield { val = s.a, next = { a = s.b, b = s.a + s.b } }
              }.filter { p = { x } -> x % 2 != 0 }
               .map { f = { x } -> x * 2 }
               .take { n = 5 }
               .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        // fibonacci: 1,1,2,3,5,8,13,21,34,55,89
        // odd fibs: 1,1,3,5,13,21,55,89
        // doubled: 2,2,6,10,26,...
        // take 5: 2,2,6,10,26
        // sum = 46
        assertEquals(46L, result);
    }

    // ── unfold + enumerate + take ────────────────────────────────────

    @Test
    void unfoldEnumerateTake() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }

            let main = {} -> do
              iter::unfold {
                state = 10,
                step = { s } ->
                  if s > 12 then Done
                  else Yield { val = s, next = s + 1 }
              }.enumerate {}
               .fold { init = 0, f = { acc, x } -> acc + x.i }
            main {}
            """);
        // indices: 0, 1, 2 → sum = 3
        assertEquals(3L, result);
    }

    // ── unfold + chain + count ───────────────────────────────────────

    @Test
    void unfoldChainCount() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }

            let main = {} -> do
              let a = iter::unfold {
                state = 0,
                step = { s } ->
                  if s >= 3 then Done
                  else Yield { val = s, next = s + 1 }
              }
              let b = iter::range { start = 0, end = 4 }
              a.chain { b = b }.count {}
            main {}
            """);
        // 3 from unfold + 4 from range = 7
        assertEquals(7L, result);
    }

    // ── range fold (existing, but verifies baseline) ─────────────────

    @Test
    void rangeFoldSum() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 1, end = 101 }.fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """);
        assertEquals(5050L, result);
    }

    // ── sum via Iter Int inherent ────────────────────────────────────

    @Test
    void rangeSum() {
        var result = eval("""
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 1, end = 11 }.sum {}
            main {}
            """);
        assertEquals(55L, result);
    }

    // ── to_list ──────────────────────────────────────────────────────

    @Test
    void unfoldToList() {
        var result = eval("""
            use std::collections::iter
            use std::collections::iter::{ Yield, Done }
            use std::collections::list

            let main = {} -> do
              let xs = iter::unfold {
                state = 1,
                step = { s } ->
                  if s > 3 then Done
                  else Yield { val = s, next = s + 1 }
              }.to_list {}
              list::size { xs = xs }
            main {}
            """);
        assertEquals(3L, result);
    }
}
