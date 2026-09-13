package com.mezze.interpreter.spi

import com.mezze.interpreter.nodes.builtin.NativeDispatch
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class NativePluginTest {
    @Test
    fun `test plugin registered via ServiceLoader is present in HANDLERS`() {
        // Static block already ran when NativeDispatch was class-loaded.
        assertTrue(NativeDispatch.isNativeFunction("__native_test_plugin_ping"),
            "TestNativePlugin should have registered __native_test_plugin_ping via ServiceLoader")
    }
}
