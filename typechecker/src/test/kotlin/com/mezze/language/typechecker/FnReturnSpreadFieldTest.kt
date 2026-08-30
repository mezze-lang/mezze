package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertFailsWith

/**
 * #335: a spread whose source is still a type variable at generation time (the
 * `{ r } -> { ...r, x = ... }` lambda-param case) used to contribute NO fields to
 * the inferred result record, so the function's return type collapsed to just the
 * explicitly-written fields. A later access to any spread-through field then failed
 * to type-check with "Record type does not have field 'X'".
 */
class FnReturnSpreadFieldTest {
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
    fun `spread in a function return keeps every unmentioned field`() {
        // promote returns { host, port, tls } — the spread carries host+port even
        // though only tls is written — so `.port` on the result must resolve.
        check(
            """
            let base = { host = "localhost", port = 8080, tls = False }
            let promote = { r } -> { ...r, tls = True }
            let out = promote { r = base }.port
            """
        )
    }

    @Test
    fun `spread-through field keeps its concrete type across the call`() {
        // `.port` flows through as Int, so it unifies with an Int annotation.
        check(
            """
            let base = { host = "localhost", port = 8080, tls = False }
            let promote = { r } -> { ...r, tls = True }
            let out: Int = promote { r = base }.port
            """
        )
    }

    @Test
    fun `spread override that changes a fields type is still rejected`() {
        // #75 type-preservation must survive the #335 open-row change: overriding a
        // spread field with an incompatible type stays an error.
        assertFailsWith<TypeCheckError> {
            check(
                """
                let base = { host = "localhost", port = 8080, tls = False }
                let promote = { r } -> { ...r, port = "nope" }
                let out = promote { r = base }.port
                """
            )
        }
    }
}
