package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive ability-dispatch coverage: a call `x.op {}` (or `Ability.op {…}`)
 * must reach the ONE impl whose target matches the receiver — by STRUCTURE /
 * hash, never by matching the op name to an impl name. Covers multiple impls of
 * one ability, static vs method syntax, generic and two-parameter abilities,
 * and same-op-name collisions across abilities.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AbilityDispatchExhaustiveTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    private String run(String src) {
        return MezzeTestHelper.run(src).toString();
    }

    @Test void dispatchesToTheMatchingImplByReceiver() {
        assertEquals("Dog Rex", run(
            "ability Describe for 'a where { describe: { it: 'a } -> Str }\n" +
            "type Dog = { name: Str }\n" +
            "type Cat = { name: Str, lives: Int }\n" +
            "impl Describe for Dog where { describe = { it } -> \"Dog $(it.name)\" }\n" +
            "impl Describe for Cat where { describe = { it } -> \"Cat $(it.name)\" }\n" +
            "let d: Dog = { name = \"Rex\" }\n" +
            "d.describe {}"));
    }

    @Test void twoDifferentImplsInOneScope() {
        // The second call must not collapse onto the first impl.
        assertEquals("Dog Rex/Cat W", run(
            "ability Describe for 'a where { describe: { it: 'a } -> Str }\n" +
            "type Dog = { name: Str }\n" +
            "type Cat = { name: Str, lives: Int }\n" +
            "impl Describe for Dog where { describe = { it } -> \"Dog $(it.name)\" }\n" +
            "impl Describe for Cat where { describe = { it } -> \"Cat $(it.name)\" }\n" +
            "let main = {} -> do\n" +
            "  let d: Dog = { name = \"Rex\" }\n" +
            "  let c: Cat = { name = \"W\", lives = 9 }\n" +
            "  \"$(d.describe {})/$(c.describe {})\"\n" +
            "main {}"));
    }

    @Test void staticAbilityCallSyntax() {
        assertEquals("Dog Rex", run(
            "ability Describe for 'a where { describe: { it: 'a } -> Str }\n" +
            "type Dog = { name: Str }\n" +
            "impl Describe for Dog where { describe = { it } -> \"Dog $(it.name)\" }\n" +
            "let d: Dog = { name = \"Rex\" }\n" +
            "Describe.describe { it = d }"));
    }

    @Test void implBodyCallingAnotherAbilityMethod() {
        // An impl body dispatches through a DIFFERENT user ability method.
        assertEquals("W!", run(
            "ability Describe for 'a where { describe: { it: 'a } -> Str }\n" +
            "ability Shout for 'a where { shout: { it: 'a } -> Str }\n" +
            "type Cat = { name: Str }\n" +
            "impl Shout for Str where { shout = { it } -> \"$(it)!\" }\n" +
            "impl Describe for Cat where { describe = { it } -> it.name.shout {} }\n" +
            "let c: Cat = { name = \"W\" }\n" +
            "c.describe {}"));
    }

    @Test void twoParameterAbility() {
        // `Combine 'b for 'a` — dispatch on receiver, co-operand pins 'b.
        assertEquals("z", run(
            "ability Combine 'b for 'a where { combine: { it: 'a, other: 'b } -> 'b }\n" +
            "type A = { x: Int }\n" +
            "impl Combine Str for A where { combine = { it, other } -> other }\n" +
            "let a: A = { x = 1 }\n" +
            "a.combine { other = \"z\" }"));
    }

    @Test void sameOpNameAcrossTwoAbilitiesDispatchesByReceiver() {
        // Both abilities declare `render`; the receiver's type picks the impl.
        assertEquals("num:1|word:hi", run(
            "ability RenderNum for 'a where { render: { it: 'a } -> Str }\n" +
            "ability RenderWord for 'a where { render: { it: 'a } -> Str }\n" +
            "type Num = { n: Str }\n" +
            "type Word = { w: Str }\n" +
            "impl RenderNum for Num where { render = { it } -> \"num:$(it.n)\" }\n" +
            "impl RenderWord for Word where { render = { it } -> \"word:$(it.w)\" }\n" +
            "let main = {} -> do\n" +
            "  let a: Num = { n = \"1\" }\n" +
            "  let b: Word = { w = \"hi\" }\n" +
            "  \"$(a.render {})|$(b.render {})\"\n" +
            "main {}"));
    }

    @Test void abilityWithMultipleOps() {
        assertEquals("hi/HI", run(
            "ability Text for 'a where {\n" +
            "  lower: { it: 'a } -> Str,\n" +
            "  upper: { it: 'a } -> Str,\n" +
            "}\n" +
            "type S = { v: Str }\n" +
            "impl Text for S where {\n" +
            "  lower = { it } -> it.v,\n" +
            "  upper = { it } -> \"HI\",\n" +
            "}\n" +
            "let s: S = { v = \"hi\" }\n" +
            "\"$(s.lower {})/$(s.upper {})\""));
    }

    @Test void toStrOnFieldInsideImplBody() {
        // Fixed: `to_str`/`to_dbg` on a field inside an impl body no longer
        // demand a phantom `__dict_to_str` (they are InspectIR/receiver-typed,
        // never dict-dispatched), so the impl runs instead of returning the op
        // function uncalled.
        assertEquals("5", run(
            "ability D for 'a where { d: { it: 'a } -> Str }\n" +
            "type N = { v: Int }\n" +
            "impl D for N where { d = { it } -> it.v.to_str {} }\n" +
            "let x: N = { v = 5 }\n" +
            "x.d {}"));
    }

    @Test void toDbgOnFieldInsideImplBody() {
        assertEquals("5", run(
            "ability D for 'a where { d: { it: 'a } -> Str }\n" +
            "type N = { v: Int }\n" +
            "impl D for N where { d = { it } -> it.v.to_dbg {} }\n" +
            "let x: N = { v = 5 }\n" +
            "x.d {}"));
    }

}
