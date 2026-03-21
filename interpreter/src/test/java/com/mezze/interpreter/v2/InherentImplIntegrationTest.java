package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.values.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for inherent impls (impl Type where { ... }).
 *
 * Tests that inherent method calls are correctly dispatched at runtime.
 */
public class InherentImplIntegrationTest {

    @Test
    void testInherentImplBasicMethodCall() {
        Object result = MezzeTestHelper.run("""
            type Counter = {value: Int}

            impl Counter where {
                get_value: {it: Counter} -> Int = {it} -> it.value
            }

            let c: Counter = {value = 42}
            c.get_value {}
            """);
        assertEquals(42L, result);
    }

    @Test
    void testInherentImplWithArguments() {
        Object result = MezzeTestHelper.run("""
            type Point = {x: Int, y: Int}

            impl Point where {
                translate: {dx: Int, dy: Int} -> Point = {dx, dy} -> {x = it.x + dx, y = it.y + dy}
            }

            let p: Point = {x = 1, y = 2}
            let p2 = p.translate {dx = 10, dy = 20}
            p2.x + p2.y
            """);
        assertEquals(33L, result);
    }

    @Test
    void testInherentImplOnVariantType() {
        Object result = MezzeTestHelper.run("""
            type Color = | Red, Green, Blue |

            impl Color where {
                is_red: {} -> Bool = {} ->
                    match it is
                        Red -> True
                        _ -> False
            }

            let c: Color = Red
            c.is_red {}
            """);
        assertEquals(true, result);
    }

    @Test
    void testInherentImplMultipleMethods() {
        Object result = MezzeTestHelper.run("""
            type Pair = {first: Int, second: Int}

            impl Pair where {
                sum: {it: Pair} -> Int = {it} -> it.first + it.second,
                product: {it: Pair} -> Int = {it} -> it.first * it.second
            }

            let p: Pair = {first = 3, second = 7}
            p.sum {} + p.product {}
            """);
        // sum = 10, product = 21, total = 31
        assertEquals(31L, result);
    }

    @Test
    void testInherentImplCoexistsWithAbilityImpl() {
        Object result = MezzeTestHelper.run("""
            ability Describable for 'a where {
                describe: {} -> Str
            }

            type Item = {name: Str, count: Int}

            impl Describable for Item where {
                describe = {it} -> it.name
            }

            impl Item where {
                total: {it: Widget} -> Int = {it} -> it.count * 2
            }

            let item: Item = {name = "widget", count = 5}
            item.total {}
            """);
        assertEquals(10L, result);
    }

    @Test
    void testInherentImplReturningRecord() {
        Object result = MezzeTestHelper.run("""
            type Box = {width: Int, height: Int}

            impl Box where {
                area: {it: Rect} -> Int = {it} -> it.width * it.height,
                scale: {factor: Int} -> Box = {factor} -> {width = it.width * factor, height = it.height * factor}
            }

            let b: Box = {width = 3, height = 4}
            let b2 = b.scale {factor = 2}
            b2.area {}
            """);
        // scaled to 6x8, area = 48
        assertEquals(48L, result);
    }

    @Test
    void testInherentImplSingleChain() {
        // Single method chain: n.double {} returns a Num, then .get {} reads from it
        Object result = MezzeTestHelper.run("""
            type Num = {val: Int}

            impl Num where {
                double: {it: Num} -> Num = {it} -> {val = it.val * 2},
                get: {it: Num} -> Int = {it} -> it.val
            }

            let n: Num = {val = 5}
            let n2 = n.double {}
            n2.get {}
            """);
        // 5 * 2 = 10
        assertEquals(10L, result);
    }
}
