package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A method that only OTHER container types have is a type error on this one.
 *
 * `to_seq` is an inherent method of List, Set, Deque, Map, Bytes, … but not of
 * Seq. `seq.to_seq {}` type-checked and then died at run time with
 * "Expected record for field 'to_seq', got: MezzeSeq": the wrong-type check only
 * covered plain nominal receivers, because container impls register under
 * mangled names (`Seq_Promise`) a bare `Seq` never equals. Compared by the
 * type's CONSTRUCTOR instead, a List impl can never be a Seq, while a
 * `Seq_Promise` impl still matches a Seq receiver.
 */
class ContainerInherentMismatchTest {

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun check(code: String): TypeCheckResult =
        TypeChecker().typeCheck(MezzeParser().parseParsedModule(code.trimIndent(), "test.mz"), Paths.get("."), enablePrelude = true)

    @Test
    fun `to_seq on a Seq is rejected at check time`() {
        val e = assertThrows(Throwable::class.java) {
            check("""
                use std::collections::seq
                let xs: [Int] = [1, 2]
                let ys = xs.to_seq {}
            """)
        }
        assertTrue((e.message ?: "").contains("No inherent method 'to_seq'"), "got: ${e.message}")
    }

    @Test
    fun `to_seq on a List still resolves`() {
        assertDoesNotThrow {
            check("""
                use std::collections::list
                let l = list::from_seq { xs = [1, 2] }
                let ys = l.to_seq {}
            """)
        }
    }
}
