package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `to_dbg` / `to_str` on a receiver whose type is still a type VARIABLE.
 *
 * Inspect is derived for every type rather than implemented, so no dictionary
 * slot is ever populated for it. Lowering used to send a VarType receiver down
 * the mono/dict path anyway, where dispatch found the argument record sitting
 * where the impl function should be and died inside CallIndirect with
 *
 *     UnsupportedSpecializationException … [MezzeRecord, Object[]]
 *
 * A polymorphic receiver is ordinary: a match arm binding on an undeclared
 * variant leaves the payload as `'a` with an `Inspect 'a` constraint, so
 * `n.to_dbg {}` in that arm crashed while `n.to_str {}` beside it worked.
 *
 * The fix routes these to InspectIR regardless, as PrintType.Dynamic — the
 * printer reads the shape from the runtime value, which PrintTypeBuilder
 * already did for VarType. These pin that a polymorphic receiver prints
 * rather than throwing, and that the concrete paths still resolve as before.
 */
@Execution(ExecutionMode.SAME_THREAD)
class InspectPolymorphicReceiverTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    @Test
    void toDbgOnMatchArmBindingOfUndeclaredVariant() {
        // The minimal case. `a` is never declared, so the arms define it and
        // `n` stays 'a with an Inspect constraint.
        Object r = MezzeTestHelper.run("""
            let show = { a } -> match a is
              Grams { n } -> n.to_dbg {}
            show { a = Grams { n = 4 } }
            """);
        assertEquals("4", r.toString());
    }

    @Test
    void toDbgOnCatchAllBindingOfUndeclaredVariant() {
        // The catch-all binds the whole value, which is likewise polymorphic.
        Object r = MezzeTestHelper.run("""
            let show = { a } -> match a is
              Grams { n } -> "g"
              other -> other.to_dbg {}
            show { a = ToTaste }
            """);
        assertEquals("ToTaste", r.toString());
    }

    @Test
    void toStrOnMatchArmBindingStillWorks() {
        // Never broken — to_str produced no Inspect constraint — but it shares
        // the lowering branch now, so it is pinned alongside.
        Object r = MezzeTestHelper.run("""
            let show = { a } -> match a is
              Grams { n } -> n.to_str {}
            show { a = Grams { n = 4 } }
            """);
        assertEquals("4", r.toString());
    }

    @Test
    void toDbgOnDeclaredVariantStillWorks() {
        // The concrete path: declaring the type gave a real PrintType and
        // always worked. It must keep working.
        Object r = MezzeTestHelper.run("""
            type Amount = | Grams { n: Int }, Pinch, ToTaste |
            let show = { a : Amount } -> match a is
              Grams { n } -> "g"
              other -> other.to_dbg {}
            show { a = ToTaste }
            """);
        assertEquals("ToTaste", r.toString());
    }

    @Test
    void toDbgOnAPlainPolymorphicParameter() {
        // No match involved: a bare generic parameter is polymorphic too.
        Object r = MezzeTestHelper.run("""
            let show = { a } -> a.to_dbg {}
            show { a = 42 }
            """);
        assertEquals("42", r.toString());
    }

    @Test
    void userDeclaredToStrAbilityStillDispatchesToTheImpl() {
        // The gate is opHash == null. A user ability whose method is NAMED
        // to_str must still win over the derived Inspect.
        Object r = MezzeTestHelper.run("""
            ability Stringify for 'a where { to_str: { it: 'a } -> Str }
            type Tag = { name: Str }
            impl Stringify for Tag where { to_str = { it } -> "tag:$(it.name)" }
            { name = "x" }.to_str {}
            """);
        assertEquals("tag:x", r.toString());
    }
}
