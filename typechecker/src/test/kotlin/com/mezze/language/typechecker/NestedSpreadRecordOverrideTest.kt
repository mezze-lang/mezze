package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertFailsWith

/**
 * H1: a spread override of a RECORD-typed field must stay type-preserving. Overriding
 * `inner : { x: Int }` with `{ x: "str" }` used to type-check (record-typed fields were
 * skipped by the #75 preservation check), then crash at runtime with
 * "Cannot add String and Long". A legitimate nested update (same field types, possibly
 * adding fields) must still be accepted.
 */
class NestedSpreadRecordOverrideTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun check(src: String) {
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
    }

    @Test
    fun `type-changing override of a record field is rejected`() {
        assertFailsWith<TypeCheckError> {
            check(
                """
                let base = { inner = { x = 1 } }
                let bad = { ...base, inner = { x = "str" } }
                let result = bad.inner.x + 1
                """
            )
        }
    }

    @Test
    fun `nested spread-update that keeps the exact field set is accepted`() {
        // The legitimate case the record-field skip used to protect: a nested
        // spread-update produces an inner record whose row openness differs from
        // the promised (closed) field type, so a strict Equality would spuriously
        // fail. It keeps x:Int, so it must be accepted.
        check(
            """
            let base = { inner = { x = 1 } }
            let ok = { ...base, inner = { ...base.inner, x = 2 } }
            let result = ok.inner.x + 1
            """
        )
    }

    @Test
    fun `same-type record override is accepted`() {
        check(
            """
            let base = { inner = { x = 1 } }
            let ok = { ...base, inner = { x = 42 } }
            let result = ok.inner.x + 1
            """
        )
    }
}
