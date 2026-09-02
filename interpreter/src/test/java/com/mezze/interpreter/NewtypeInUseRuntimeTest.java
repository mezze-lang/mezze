package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #402 — newtype IN USE, runtime gaps not covered elsewhere.
 *
 * The pre-existing newtype runtime suites cover Eq-impl dispatch, NumAdd/NumMul
 * via match-unwrap, erasure identity, and construct/unwrap end to end. This file
 * fills the remaining IN-USE runtime holes flagged by #402:
 *
 *   A. `impl Ord for` a newtype — `<` / `<=` dispatch resolves to the newtype's
 *      Ord impl (NOT the underlying Int's), and the comparison result is correct.
 *   E. Generic `newtype Box 'a = 'a` used at runtime — construct at Int and Str,
 *      unwrap via match, AND drive an ability impl over the generic newtype.
 *   H. `to_dbg` renders a newtype as `Name.(inner)` (rule 5): primitive, string
 *      (quoted under to_dbg), record, and generic newtype all render as the
 *      wrapper form, and the erased runtime value is unchanged.
 *
 * These run through the Truffle interpreter (MezzeTestHelper.run), so they pin
 * real dispatch + render, not just types.
 */
@Execution(ExecutionMode.SAME_THREAD)
class NewtypeInUseRuntimeTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    // ========================================================================
    // A. impl Ord for a newtype — dispatch must resolve to the Money impl.
    //    Money wraps Int, but its Ord is user-defined via match-unwrap; the
    //    `<` / `<=` operators must fire the Money impl, not Int's built-in Ord.
    // ========================================================================

    @Nested
    class NewtypeOrdDispatch {

        private static final String ORD_MONEY = """
            use std::abilities::compare::{ Ord }

            newtype Money = Int
            impl Ord for Money where {
              lt = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> a < b,
              le = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> a <= b,
              gt = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> a > b,
              ge = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> a >= b
            }
            """;

        @Test
        void moneyLessThanTrue() {
            Object r = MezzeTestHelper.run(ORD_MONEY + """
                let a = Money.(350)
                let b = Money.(475)
                a < b
                """);
            assertEquals(true, r);
        }

        @Test
        void moneyLessThanFalse() {
            Object r = MezzeTestHelper.run(ORD_MONEY + """
                let a = Money.(475)
                let b = Money.(350)
                a < b
                """);
            assertEquals(false, r);
        }

        @Test
        void moneyLessEqualBoundary() {
            Object r = MezzeTestHelper.run(ORD_MONEY + """
                let a = Money.(350)
                let b = Money.(350)
                a <= b
                """);
            assertEquals(true, r);
        }

        @Test
        void moneyGreaterThan() {
            Object r = MezzeTestHelper.run(ORD_MONEY + """
                let a = Money.(475)
                let b = Money.(350)
                a > b
                """);
            assertEquals(true, r);
        }

        // The Money Ord impl runs the user body (match-unwrap), proven by making
        // the impl deliberately REVERSE the comparison: if `<` dispatched to
        // Int's built-in Ord this would be false, but the Money impl inverts it.
        @Test
        void moneyOrdUsesTheMoneyImplNotIntBuiltin() {
            Object r = MezzeTestHelper.run("""
                use std::abilities::compare::{ Ord }
                newtype Money = Int
                ## Deliberately INVERTED body: lt returns a > b. If dispatch wrongly
                ## fell through to Int's Ord, 350 < 475 would be true; the Money impl
                ## makes it false — proving the Money impl is the one that fires.
                impl Ord for Money where {
                  lt = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> a > b,
                  le = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> a >= b,
                  gt = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> a < b,
                  ge = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> a <= b
                }
                let a = Money.(350)
                let b = Money.(475)
                a < b
                """);
            assertEquals(false, r,
                "`<` on Money must dispatch to the (inverted) Money impl, not Int's built-in Ord");
        }
    }

    // ========================================================================
    // E. Generic newtype at runtime: construct at Int and Str, unwrap via
    //    match, and drive an ability impl over the generic wrapper.
    // ========================================================================

    @Nested
    class GenericNewtypeRuntime {

        @Test
        void boxOverIntUnwraps() {
            Object r = MezzeTestHelper.run("""
                newtype Box 'a = 'a
                let b = Box.(5)
                match b is Box.(v) -> v
                """);
            assertEquals(5L, r);
        }

        @Test
        void boxOverStrUnwraps() {
            Object r = MezzeTestHelper.run("""
                newtype Box 'a = 'a
                let b = Box.("hi")
                match b is Box.(v) -> v
                """);
            assertEquals("hi", r);
        }

        @Test
        void boxOverIntErasesToBareLong() {
            Object r = MezzeTestHelper.run("""
                newtype Box 'a = 'a
                Box.(7)
                """);
            assertSame(Long.class, r.getClass(),
                "generic newtype must erase to the bare inner value at runtime");
            assertEquals(7L, r);
        }

        @Test
        void genericNewtypeThroughFunction() {
            Object r = MezzeTestHelper.run("""
                newtype Box 'a = 'a
                let wrap = {x} -> Box.(x)
                let unwrap = {b} -> match b is Box.(v) -> v
                unwrap {b = wrap {x = 99}}
                """);
            assertEquals(99L, r);
        }

        // Ability impl over the generic newtype: a `Peek` ability whose method
        // unwraps the Box via match. A generic `impl Peek for Box 'a` fires for
        // any Box, running the match-unwrap body.
        @Test
        void genericAbilityImplOverGenericNewtype() {
            Object r = MezzeTestHelper.run("""
                newtype Box 'a = 'a

                ability Peek for 'a where {
                  peek : { it: 'a } -> Int
                }

                impl Peek for Box 'a where {
                  peek = { it } -> match it is Box.(_) -> 7
                }

                let b = Box.(41)
                b.peek {}
                """);
            assertEquals(7L, r);
        }

        // Ability impl over the generic newtype at a CONCRETE argument
        // (`impl Peek for (Box Int)`): dispatch fires when the receiver is
        // annotated `Box Int` and runs the match-unwrap body.
        @Test
        void concreteAbilityImplOverGenericNewtype() {
            Object r = MezzeTestHelper.run("""
                newtype Box 'a = 'a

                ability Peek for 'a where {
                  peek : { it: 'a } -> Int
                }

                impl Peek for (Box Int) where {
                  peek = { it } -> match it is Box.(v) -> v + 1
                }

                let b : Box Int = Box.(41)
                b.peek {}
                """);
            assertEquals(42L, r);
        }
    }

    // ========================================================================
    // H. to_dbg renders a newtype as `Name.(inner)` (rule 5). The runtime value
    //    is erased, but the static PrintType drives the wrapper render.
    // ========================================================================

    @Nested
    class NewtypeToDbgRender {

        @Test
        void primitiveNewtypeRendersWrapped() {
            Object r = MezzeTestHelper.run("""
                newtype Money = Int
                let m = Money.(42)
                m.to_dbg {}
                """);
            assertEquals("Money.(42)", r);
        }

        @Test
        void stringNewtypeRendersWrappedAndQuoted() {
            // to_dbg quotes string leaves, so the inner "hi" renders as "hi".
            Object r = MezzeTestHelper.run("""
                newtype Tag = Str
                let t = Tag.("hi")
                t.to_dbg {}
                """);
            assertEquals("Tag.(\"hi\")", r);
        }

        @Test
        void genericNewtypeRendersWrapped() {
            Object r = MezzeTestHelper.run("""
                newtype Box 'a = 'a
                let b = Box.("hi")
                b.to_dbg {}
                """);
            assertEquals("Box.(\"hi\")", r);
        }

        @Test
        void recordNewtypeRendersWrapped() {
            Object r = MezzeTestHelper.run("""
                newtype Point = { x: Int, y: Int }
                let p = Point.({ x = 3, y = 4 })
                p.to_dbg {}
                """);
            assertEquals("Point.({ x = 3, y = 4 })", r);
        }

        // Render does not disturb the erased runtime value: the same Money still
        // unwraps to its bare Int after being rendered.
        @Test
        void renderLeavesErasedValueIntact() {
            Object r = MezzeTestHelper.run("""
                newtype Money = Int
                let m = Money.(42)
                let _ = m.to_dbg {}
                match m is Money.(v) -> v
                """);
            assertEquals(42L, r);
        }
    }
}
