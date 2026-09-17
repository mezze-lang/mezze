package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * A primitive's `Eq` and `Hash` belong to the compiler, inside the primitive's own
 * module too.
 *
 * The orphan rule already stops a user module from writing `impl Eq for Int` — it
 * declares neither the ability nor the type. The one place such an impl CAN be
 * written is the type's home module, `std::int`, which is exactly where the seven
 * hand-written ones used to live. And there it was worse than a duplicate: `==` on
 * a primitive receiver takes the inline comparison node BEFORE ability resolution
 * runs, so the impl was accepted and then never reached by the operator it was
 * written for. Nothing told the author their equality was not the one being used.
 *
 * So the duplicate-instance rule is extended to primitives, and these are the tests
 * for the half no user module can reach.
 *
 * The distinction that keeps it honest: SATISFYING an ability is not the same as
 * having its instance DERIVED, and only the second makes a hand-written impl a
 * duplicate. `Bool` satisfies `LogicalAnd` through an inline node with no instance
 * behind it, so `impl LogicalAnd for Bool` is the only instance there is and stays
 * legal — as `std::bool` still writes it.
 */
class PrimitiveHomeModuleCoherenceTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        ModuleResolver.clearCache()
    }

    /** Type-check [source] as if it WERE the given stdlib module; null when it passes. */
    private fun checkAsModule(source: String, modulePath: String): String? {
        val parsed = parser.parseParsedModule(source, "test.mz")
        return runCatching {
            typeChecker.typeCheck(parsed, Paths.get("."), modulePath = modulePath)
            null
        }.getOrElse { it.message }
    }

    @Test
    fun `Int's home module cannot re-add a hand-written Eq`() {
        // `Eq` and `Hash` written TOGETHER, so the pairing rule is satisfied and the
        // duplicate rule is the one that answers. (Writing `Eq` alone is rejected
        // too, one rule earlier — see the test below.)
        val errors = checkAsModule(
            """
            use std::abilities::compare::{ Eq, Hash, structural_hash }

            impl Eq for Int where {
              eq = { it, r } -> __native_int_eq { l = it, r = r },
              neq = { it, r } -> __native_int_neq { l = it, r = r },
            }

            impl Hash for Int where {
              hash: { it: Int } -> Int = { it } -> structural_hash { it = it }
            }
            """.trimIndent(),
            modulePath = "std::int",
        )
        assertTrue(
            errors?.contains("is derived by the compiler for `Int`") == true ||
                errors?.contains("cannot be hand-written for `Int`") == true,
            "expected a duplicate-instance error naming Int; got: $errors",
        )
    }

    @Test
    fun `an Eq alone is rejected by the pairing rule, naming the type properly`() {
        val errors = checkAsModule(
            """
            use std::abilities::compare::{ Eq }

            impl Eq for Int where {
              eq = { it, r } -> __native_int_eq { l = it, r = r },
              neq = { it, r } -> __native_int_neq { l = it, r = r },
            }
            """.trimIndent(),
            modulePath = "std::int",
        )
        assertTrue(
            errors?.contains("`Int` writes its own `Eq` but not a `Hash`") == true,
            "expected the pairing error naming `Int` (not its Kotlin toString); got: $errors",
        )
    }

    @Test
    fun `Str's home module cannot re-add a hand-written Hash`() {
        // Rejected by the Eq/Hash pairing arm rather than the duplicate arm: `Hash`
        // derives for every type, so the question that discriminates is whether the
        // type's EQUALITY is the derived one. For `Str` it now is.
        val errors = checkAsModule(
            """
            use std::abilities::compare::{ Hash, structural_hash }

            impl Hash for Str where {
              hash: { it: Str } -> Int = { it } -> structural_hash { it = it }
            }
            """.trimIndent(),
            modulePath = "std::str",
        )
        assertTrue(
            errors?.contains("`Hash` cannot be hand-written for `Str`") == true,
            "expected the Eq/Hash pairing error; got: $errors",
        )
    }

    @Test
    fun `an ability a primitive merely satisfies is still implementable at home`() {
        // `std::bool` writes this one today and must keep being able to: the rule
        // is about instances the compiler OWNS, not about impls on primitives.
        val errors = checkAsModule(
            """
            use std::abilities::logic::{ LogicalAnd }

            impl LogicalAnd for Bool where {
              and = { it, r } -> __native_bool_and { l = it, r = r }
            }
            """.trimIndent(),
            modulePath = "std::bool",
        )
        assertTrue(
            errors.isNullOrEmpty() || !errors.contains("Duplicate ability impl"),
            "LogicalAnd on Bool must not be read as a duplicate; got: $errors",
        )
    }
}
