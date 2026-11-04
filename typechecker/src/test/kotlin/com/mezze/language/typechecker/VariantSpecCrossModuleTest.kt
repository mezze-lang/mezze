package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Variant specification, section 7 and the cross-module claim rules.
 *
 * Groups travel inside signatures and inclusion works identically across a
 * boundary. Tags are anonymous, so there is nothing to import; importing a type
 * NAME lets you write it, and brings its claim into the importing module.
 *
 * The distinction that makes libraries compose — and the one this file exists to
 * pin — is that IMPORTING A FUNCTION CARRIES NO CLAIM. A claim comes from
 * importing the name; a group inside a signature travels as structure. Without
 * that, two libraries that happen to share a tag could not be used together at
 * all, and `Timeout`, `Cancelled`, `Failed` and `NotFound` are each claimed by
 * two stdlib modules today.
 *
 * Measured alongside these: the clash IS detected today, but globally and at
 * the first USE of the shared tag rather than at the import. Both differences
 * bite — too late (import both names, never use the tag: accepted) and too
 * broad (import only the functions: refused).
 */
class VariantSpecCrossModuleTest : VariantSpecBase() {

    // ------------------------------------------------- 7.1 / 7.4 / 7.5 crossing

    @Test fun `a group crosses inside a signature`() {
        writeModule("anon", "pub let f = { c: | Red, Pink | } -> 1")
        acceptsMain(
            "use anon::{ f }\npub let u = {} -> f { c = Red }\n$MAIN",
            "CONTROL for the file: an inline group in another module's signature is " +
                "usable from here",
        )
    }

    @Test fun `importing the type name`() {
        writeModule("ta", "pub type T = | Red, Pink |\npub let take_t = { c: T } -> 1")
        acceptsMain(
            "use ta::{ T, take_t }\npub let u = { c: T } -> take_t { c = c }\n$MAIN",
            "importing the name lets you write it",
        )
    }

    @Test fun `an imported open group accepts an unnamed tag`() {
        writeModule("ao", "pub let f_open = { c: | Red, Pink, ... | } -> 1")
        acceptsMain(
            """
            use ao::{ f_open }
            pub let u = {} -> f_open { c = Green }
            pub let a = {} -> f_open { c = Blue }
            pub let b = {} -> f_open { c = Yellow }
            $MAIN
            """,
            "the tail is unbounded, and two callers with different tags do not interfere",
        )
    }

    @Test fun `a public one-element producer, used wider`() {
        writeModule("m", "pub let mk = {} -> Red")
        writeModule("g1", "pub let f = { c: | Red, Pink | } -> 1")
        acceptsMain(
            "use m::{ mk }\nuse g1::{ f }\npub let u = {} -> f { c = mk {} }\n$MAIN",
            "inclusion, same as 2.1 — a fixed public signature needs no annotation and " +
                "no coercion rule",
        )
    }

    // ------------------------------------------------- 7.2 / 7.3 tags don't travel

    @Test fun `a tag cannot be imported`() {
        writeModule("mt", "pub type T = | Red, Pink |")
        rejectsMain(
            "use mt::{ T, Red }\n$MAIN",
            "M0801",
            "a tag belongs to no module, so there is nothing to import. `use mt::{ Red }` " +
                "names something the module does not export",
        )
    }

    // -------------------------------------- 2.3 / 7.6 / 7.7 checking must survive

    @Test fun `a non-subset does not fit across a boundary`() {
        writeModule(
            "tb",
            """
            pub type T = | Red, Pink |
            pub let handle = { c: T } -> match c is
              Red -> 1
              Pink -> 2
            """,
        )
        rejectsMain(
            "use tb::{ handle }\npub let u = {} -> handle { c = Blue }\n$MAIN",
            "V0704",
            "handle has no arm for Blue. This is the stuck call: accepted today, and it " +
                "reaches the interpreter as `no variant tag matched`",
        )
    }

    @Test fun `matching an imported producer's value with a missing arm`() {
        writeModule("mk", "pub let mk = { n: Int } -> if n > 0 then Red else Pink")
        rejectsMain(
            "use mk::{ mk }\npub let u = {} -> match mk { n = 1 } is\n  Red -> 1\n$MAIN",
            "V0601",
            "mk's return group is | Pink, Red |, so Pink is a missing case — the group " +
                "crossing a boundary must not lose its contents",
        )
    }

    @Test fun `matching an imported producer's value, fully covered`() {
        writeModule("mk2", "pub let mk = { n: Int } -> if n > 0 then Red else Pink")
        acceptsMain(
            "use mk2::{ mk }\npub let u = {} -> match mk { n = 1 } is\n" +
                "  Red -> 1\n  Pink -> 2\n$MAIN",
            "CONTROL: with both arms it must pass, else the case above is measuring a " +
                "broken import path rather than a coverage rule",
        )
    }

    @Test fun `coverage survives on an imported alias`() {
        writeModule("ta2", "pub type T = | Red, Pink |\npub let take = { c: T } -> 1")
        rejectsMain(
            "use ta2::{ T, take }\npub let f = { c: T } -> match c is\n  Red -> 1\n$MAIN",
            "V0601",
            "with the NAME imported the identity is intact, so coverage is checked",
        )
    }

    @Test fun `coverage survives when only the function is imported`() {
        writeModule("ta3", "pub type T = | Red, Pink |\npub let take = { c: T } -> 1")
        rejectsMain(
            """
            use ta3::{ take }
            pub let f = { c } -> do
              let n = match c is
                Red -> 1
              take { c = c }
            $MAIN
            """,
            "V0601",
            "7.7, the control experiment for identity-gated checking. The SAME value " +
                "is matched and then passed to `take`, so c's group is T — arriving " +
                "through take's signature, without its name. Coverage must still see " +
                "Pink.\n\nThe first version of this test matched one literal and passed " +
                "another (`take { c = Red } + f { c = Red }`), which links nothing: by 4.3 " +
                "each literal is fitted at its own site, so c was | Red | and one arm " +
                "covered it exactly as the spec says. The test was wrong, not the checker.",
        )
    }

    // ------------------------------------------- 8.3 / 9.3 claims cross imports

    @Test fun `importing two claimants of one tag`() {
        writeModule("ca", "pub type T = | Red, Pink |")
        writeModule("cb", "pub type U = | Red, Blue |")
        rejectsMain(
            "use ca::{ T }\nuse cb::{ U }\npub let u = {} -> 1\n$MAIN",
            "V0701",
            "two claimants is two claimants: an import is not a special case. Refused " +
                "at the IMPORT, not deferred to the first use of Red — the clash exists " +
                "whether or not the tag is ever written",
        )
    }

    @Test fun `one claimant declared, one imported`() {
        writeModule("cc", "pub type T = | Red, Pink |")
        rejectsMain(
            "use cc::{ T }\ntype U = | Red, Blue |\n$MAIN",
            "V0701",
            "same clash, half declared and half imported",
        )
    }

    @Test fun `importing a name then writing an inline group sharing a tag`() {
        writeModule("cd", "pub type T = | Red, Pink |")
        rejectsMain(
            "use cd::{ T }\npub let f = { c: | Red | } -> 1\n$MAIN",
            "V0701",
            "the imported name claims Red here, so the inline group contradicts it — " +
                "6.3 applied to an imported claim",
        )
    }

    @Test fun `two modules, disjoint groups, both names imported`() {
        writeModule("da", "pub type T = | Red, Pink |")
        writeModule("db2", "pub type U = | Up, Down |")
        acceptsMain(
            """
            use da::{ T }
            use db2::{ U }
            pub let f = { c: T, d: U } -> 1
            pub let u = {} -> f { c = Red, d = Up }
            $MAIN
            """,
            "CONTROL: disjoint groups never clash, so importing two names is normally " +
                "fine. Without this the rejections above could be refusing all " +
                "two-name imports",
        )
    }

    // ------------------------------------------------------- 8.4 composition

    @Test fun `two libraries sharing a tag compose when only functions are imported`() {
        writeModule(
            "dbe",
            "pub type DbErr = | DbDown, Timeout { ms: Int } |\n" +
                "pub let q = { e: DbErr } -> 1",
        )
        writeModule(
            "hte",
            "pub type HtErr = | Refused, Timeout { url: Str, ms: Int } |\n" +
                "pub let s = { e: HtErr } -> 2",
        )
        acceptsMain(
            """
            use dbe::{ q }
            use hte::{ s }
            pub let u = {} -> q { e = Timeout { ms = 5 } }
            $MAIN
            """,
            "THE composition case, and the reason `importing a function carries no " +
                "claim` has to hold. Nothing here claims Timeout, so the bare tag is " +
                "unclaimed and fits DbErr by inclusion. Four stdlib tags are in this " +
                "position today; if this fails, db and http cannot be used together",
        )
    }

    @Test fun `but importing both names is refused`() {
        writeModule("dbe2", "pub type DbErr = | DbDown, Timeout { ms: Int } |")
        writeModule("hte2", "pub type HtErr = | Refused, Timeout { url: Str, ms: Int } |")
        rejectsMain(
            "use dbe2::{ DbErr }\nuse hte2::{ HtErr }\npub let u = {} -> 1\n$MAIN",
            "V0701",
            "the other half: two names, two claims on Timeout. Refused even though the " +
                "payloads differ — identity is the name (9.2)",
        )
    }

    // --------------------------------------------------- 9.5 open into closed

    @Test fun `an open group does not flow into an imported closed one`() {
        writeModule("oc", "pub type Closed = | Red, Pink |\npub let f = { c: Closed } -> 1")
        writeModule("oo", "pub type Open = | Yellow, ... |")
        rejectsMain(
            """
            use oc::{ f }
            use oo::{ Open }
            pub let h = { c: Open } -> f { c = c }
            $MAIN
            """,
            // T0201, not V0704: no particular tag is named here. The fault is
            // that an unbounded group cannot stand where a bounded one is
            // expected — a type mismatch. V0704 is for a tag that is not a
            // member, which needs a tag to point at.
            "T0201",
            "h accepts any tag and f handles two, so h could hand f a Blue. Open is " +
                "permissive as a TARGET and maximally restrictive as a SOURCE",
        )
    }

    @Test fun `a closed group flows into an imported open one`() {
        writeModule("co", "pub let f_open = { c: | Red, ... | } -> 1")
        writeModule("cl", "pub type Closed = | Red, Pink |")
        acceptsMain(
            """
            use co::{ f_open }
            use cl::{ Closed }
            pub let h = { c: Closed } -> f_open { c = c }
            $MAIN
            """,
            "the mirror, and the one that proves the direction: closed into open fits",
        )
    }

    // ------------------------------------------------------- re-export, impls

    @Test fun `a re-exported group name is usable`() {
        writeModule("re1", "pub type T = | Red, Pink |")
        writeModule("re2", "pub use re1::{ T }")
        acceptsMain(
            "use re2::{ T }\npub let f = { c: T } -> match c is\n  Red -> 1\n  Pink -> 2\n$MAIN",
            "a re-export carries the group through unchanged",
        )
    }

    @Test fun `a re-exported name still claims`() {
        writeModule("re3", "pub type T = | Red, Pink |")
        writeModule("re4", "pub use re3::{ T }")
        rejectsMain(
            "use re4::{ T }\ntype L = | Red, Blue |\n$MAIN",
            "V0701",
            "the route a name arrived by does not change its claim",
        )
    }

    @Test fun `the same group reached by two routes is not a clash`() {
        writeModule("re5", "pub type T = | Red, Pink |")
        writeModule("re6", "pub use re5::{ T }")
        acceptsMain(
            "use re5::{ T }\nuse re6::{ T }\npub let f = { c: T } -> 1\n$MAIN",
            "8.3 refuses a claim from a group OTHER than itself; one group reached twice " +
                "is still itself",
        )
    }

    @Test fun `an impl reached through an imported group name`() {
        writeModule("col", "pub type Color = | Red, Green |")
        acceptsMain(
            """
            use col::{ Color }
            ability Describe for 'a where { describe: { it: 'a } -> Str }
            impl Describe for Color where { describe = { it } -> "c" }
            pub let u = {} -> do
              let v = Red
              v.describe {}
            $MAIN
            """,
            "importing the name is what brings the group into scope for adoption, which " +
                "is what lets a bare Red reach a nominal impl",
        )
    }

    @Test fun `a receiver narrower than the impl's group`() {
        writeModule("na", "pub type Color = | Red, Green |")
        writeModule("nb", "pub type Small = | Red |")
        rejectsMain(
            """
            use na::{ Color }
            use nb::{ Small }
            ability Describe for 'a where { describe: { it: 'a } -> Str }
            impl Describe for Color where { describe = { it } -> "c" }
            pub let q = { c: Small } -> c.describe {}
            $MAIN
            """,
            "V0701",
            "dispatch is equality on the group: Small is not Color. Note this program " +
                "should ALSO be refused one step earlier — Color and Small both claim " +
                "Red, so 8.3 refuses the two imports. Either diagnostic is correct; what " +
                "must not happen is acceptance",
        )
    }

    @Test fun `a generic payload crossing a boundary`() {
        writeModule(
            "gb",
            "pub type Box 'a = | Full { val: 'a }, Empty |\npub let take = { b: Box Int } -> 1",
        )
        acceptsMain(
            "use gb::{ Box, take }\npub let u = {} -> take { b = Full { val = 1 } }\n$MAIN",
            "the payload instantiates across the boundary like any type argument",
        )
    }

    @Test fun `a generic payload instantiated at the wrong type`() {
        writeModule(
            "gc",
            "pub type Box 'a = | Full { val: 'a }, Empty |\npub let take = { b: Box Int } -> 1",
        )
        rejectsMain(
            "use gc::{ Box, take }\npub let u = {} -> take { b = Full { val = \"x\" } }\n$MAIN",
            "T0201",
            "Box Str is not Box Int — the mirror of the case above",
        )
    }

    @Test fun `a recursive group imported, matched with a missing arm`() {
        writeModule("rg", "pub type Tree = | Leaf, Node { l: Tree, r: Tree } |")
        rejectsMain(
            "use rg::{ Tree }\npub let f = { t: Tree } -> match t is\n  Leaf -> 1\n$MAIN",
            "V0601",
            "recursion does not exempt an imported group from coverage",
        )
    }

    @Test fun `a nested payload group crossing, missing inner arm`() {
        writeModule("np", "pub type Outer = | Wrap { inner: | A, B | } |")
        rejectsMain(
            "use np::{ Outer }\npub let f = { o: Outer } -> match o is\n" +
                "  Wrap { inner = A } -> 1\n$MAIN",
            "V0601",
            "the inner group's B has no arm; nesting does not hide it",
        )
    }

    @Test fun `a three-module chain widens at each step`() {
        writeModule("c1", "pub let mk = {} -> Red")
        writeModule("c2", "pub let widen = { c: | Red, Pink | } -> c")
        writeModule("c3", "pub let eat = { c: | Red, Pink, Blue | } -> 1")
        acceptsMain(
            """
            use c1::{ mk }
            use c2::{ widen }
            use c3::{ eat }
            pub let u = {} -> eat { c = widen { c = mk {} } }
            $MAIN
            """,
            "| Red | into | Red, Pink | into | Red, Pink, Blue |. Each step is plain " +
                "inclusion, and the chain is where widening on a RETURN shows up — the " +
                "case that exposed inclusion being implemented as equality",
        )
    }

    @Test fun `a producer returning wider than the consumer accepts`() {
        writeModule("p1", "pub let mk = { n: Int } -> if n > 0 then Red else Pink")
        writeModule("r1", "pub let only_red = { c: | Red | } -> 1")
        rejectsMain(
            "use p1::{ mk }\nuse r1::{ only_red }\n" +
                "pub let u = {} -> only_red { c = mk { n = 1 } }\n$MAIN",
            "T0201",
            "the mirror of the chain above: the direction still holds across modules",
        )
    }

    @Test fun `an effect performer declared in another module`() {
        writeModule(
            "ef",
            "effect Beep where { beep: { n: Int } -> {} }\n" +
                "impl Beep for Loud where { beep = { n } -> {} }",
        )
        acceptsMain(
            "use ef::{ Beep, Loud }\n" +
                "pub let u = {} -> perform Loud in Beep.beep { n = 1 }\n$MAIN",
            "a performer tag and its effect cross a boundary like any other names",
        )
    }
}
