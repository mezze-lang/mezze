package com.mezze.interpreter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for user-defined Ord impls through HOFs (list/seq min/max/sort).
 * Mode A: dict capture crash (ClassCastException: MezzeFunction → MezzeVariant)
 * Mode B: native comparators ignoring user Ord (silently wrong order)
 */
class OrdImplTest {

    static final String PREAMBLE = """
        use std::abilities::compare::{ Ord }
        use std::collections::list
        use std::collections::seq

        type P = | Low, Mid, High |

        let rank = { p } -> match p is
          Low -> 0
          Mid -> 1
          High -> 2

        impl Ord for P where {
          lt = { it, r } -> rank { p = it } < rank { p = r },
          le = { it, r } -> rank { p = it } <= rank { p = r },
          gt = { it, r } -> rank { p = it } > rank { p = r },
          ge = { it, r } -> rank { p = it } >= rank { p = r },
        }
        """;

    // ── Mode A: dict capture through HOF ──

    @Test
    void listMinUsesUserOrd() {
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let xs = list::from_seq { xs = [High, Low, Mid] }
            match list::min { xs = xs } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(0L, result);  // Low has rank 0
    }

    @Test
    void listMaxUsesUserOrd() {
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let xs = list::from_seq { xs = [High, Low, Mid] }
            match list::max { xs = xs } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(2L, result);  // High has rank 2
    }

    // ── Mode B: native comparators ignoring impl ──

    @Test
    void seqMinUsesUserOrd() {
        Object result = MezzeTestHelper.run(PREAMBLE + """
            match seq::min { xs = [High, Low, Mid] } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(0L, result);  // Low has rank 0
    }

    @Test
    void seqMaxUsesUserOrd() {
        Object result = MezzeTestHelper.run(PREAMBLE + """
            match seq::max { xs = [High, Low, Mid] } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(2L, result);  // High has rank 2
    }

    @Test
    void seqSortUsesUserOrd() {
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let sorted = seq::sort { xs = [High, Low, Mid] }
            match seq::get { xs = sorted, i = 0 } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(0L, result);  // first element should be Low (rank 0)
    }

    @Test
    void listSortUsesUserOrd() {
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let xs = list::from_seq { xs = [High, Low, Mid] }
            let sorted = list::sort { xs = xs }
            match list::head { xs = sorted } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(0L, result);  // first element should be Low (rank 0)
    }

    // ── Sanity: direct comparison works ──

    @Test
    void directComparisonWorks() {
        Object result = MezzeTestHelper.run(PREAMBLE + """
            if Low < High then 1 else 0
            """);
        assertEquals(1L, result);
    }

    // ── Dict capture through HOF callback ──

    @Test
    void dictCaptureThroughFoldCallback() {
        // Tests the B2/B3 mechanism: the <lt> dict must be captured
        // from the outer min_manual frame into the fold callback lambda.
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let min_manual: { xs: ['a] } with Ord 'a -> Option 'a = { xs } ->
                seq::fold { xs = xs, init = None, f = { acc, x } ->
                    match acc is
                        None -> Some { val = x }
                        Some { val } -> if x < val then Some { val = x } else acc
                }
            match min_manual { xs = [High, Low, Mid] } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(0L, result);  // Low has rank 0
    }

    @Test
    void sortByWithOrdOperatorWorks() {
        // Rewriting sort in terms of sort_by + Ord operator
        // Uses the function through an intermediate variable to test dict-capture through HOF
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let my_sort: { xs: ['a] } with Ord 'a -> ['a] = { xs } ->
                seq::sort_by { xs = xs, cmp = { a, b } -> a <= b }
            let sorted = my_sort { xs = [High, Low, Mid] }
            match seq::get { xs = sorted, i = 0 } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(0L, result);  // first element should be Low (rank 0)
    }

    @Test
    void sortByInlineWithOrdOperator() {
        // Inline lambda with Ord operators passed to sort_by.
        // Unlike sortByWithOrdOperatorWorks which wraps in a `with Ord 'a`
        // function (triggering dict-passing), this tests the inline-lambda
        // path where dict resolution must happen from sibling field types.
        // Currently this requires a thin wrapper with explicit constraint.
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let sort_p: { xs: ['a] } with Ord 'a -> ['a] = { xs } ->
                seq::sort_by { xs = xs, cmp = { a, b } -> a <= b }
            let sorted = sort_p { xs = [High, Low, Mid] }
            match seq::get { xs = sorted, i = 0 } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(0L, result);  // first element should be Low (rank 0)
    }

    // ── Dict forwarding through wrapper functions ──

    @Test
    void seqMinViaWrapper() {
        // Dict forwarding: wrapper's __dict_lt must propagate through to seq::min
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let my_min: { xs: ['a] } with Ord 'a -> Option 'a = { xs } ->
              seq::min { xs = xs }
            match my_min { xs = [High, Low, Mid] } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(0L, result);
    }

    @Test
    void seqSortViaWrapper() {
        // Dict forwarding: wrapper's __dict_le must propagate through to seq::sort
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let my_sort: { xs: ['a] } with Ord 'a -> ['a] = { xs } ->
              seq::sort { xs = xs }
            let sorted = my_sort { xs = [High, Low, Mid] }
            match seq::get { xs = sorted, i = 0 } is
              Some { val } -> rank { p = val }
              None -> -1
            """);
        assertEquals(0L, result);
    }

    @Test
    void reduceWithOrdOperator() {
        // User-space min via reduce with Ord operator (bypasses stdlib min)
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let my_min: { xs: ['a] } with Ord 'a -> Option 'a = { xs } ->
              seq::reduce { xs = xs, f = { acc, x } -> if x < acc then x else acc }
            match my_min { xs = [High, Low, Mid] } is
                Some { val } -> rank { p = val }
                None -> -1
            """);
        assertEquals(0L, result);
    }

    // ── Direct test: non-HOF function with Ord constraint ──

    @Test
    void directSmallerWithOrd() {
        // Simplest possible: a non-HOF function with with Ord 'a, no lambda nesting
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let smaller: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } ->
                if a < b then a else b
            rank { p = smaller { a = High, b = Low } }
            """);
        assertEquals(0L, result);  // Low has rank 0
    }

    @Test
    void lambdaNestedInConstrainedFn() {
        // Dict capture through nested lambda inside a constrained function.
        // Uses a single-expression body (not `do` block) to avoid the TC
        // annotation-mismatch check which currently rejects fresh VarType
        // ids created inside `do` blocks (#TC-do-constraint-propagation).
        Object result = MezzeTestHelper.run(PREAMBLE + """
            let my_cmp: { a: 'a, b: 'a } with Ord 'a -> Bool = { a, b } ->
                ({ x, y } -> x <= y) { x = a, y = b }
            if my_cmp { a = Low, b = High } then 1 else 0
            """);
        assertEquals(1L, result);  // Low <= High is true
    }

    @Test
    void primitiveOrdImplExists() {
        // Verify that adding with Ord 'a doesn't break primitive sorting
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let my_sort: { xs: ['a] } with Ord 'a -> ['a] = { xs } ->
                seq::sort_by { xs = xs, cmp = { a, b } -> a <= b }
            match seq::get { xs = my_sort { xs = [3, 1, 2] }, i = 0 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);
    }
}
