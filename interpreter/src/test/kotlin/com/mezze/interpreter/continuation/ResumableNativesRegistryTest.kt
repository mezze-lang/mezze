package com.mezze.interpreter.continuation

import com.mezze.interpreter.nodes.builtin.NativeDispatch
import com.mezze.language.ast.ResumableNatives
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** C4: the one list two readers share — every name the typechecker trusts as
 *  resumable (V0513 lets a parking callback through) is a registered native. */
class ResumableNativesRegistryTest {
    @Test
    fun `every resumable native is registered`() {
        for (name in ResumableNatives.NAMES) {
            assertNotNull(NativeDispatch.recordHandlerOf(name), "$name is listed as resumable but has no handler")
        }
    }
}
