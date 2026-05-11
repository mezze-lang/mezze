package com.mezze.interpreter

import com.oracle.truffle.api.Truffle
import org.junit.jupiter.api.Test

class RuntimeProbeTest {
    @Test
    fun `report Truffle runtime in use`() {
        val rt = Truffle.getRuntime()
        println("=== Truffle runtime ===")
        println("  class: ${rt.javaClass.name}")
        println("  name : ${rt.name}")
        // Truffle's optimizing runtime is HotSpotTruffleRuntime (or its
        // subclasses) on the JVM; DefaultTruffleRuntime means PE/JIT is off.
        val isOptimizing = !rt.javaClass.name.contains("Default")
        println("  optimizing: $isOptimizing")
    }
}
