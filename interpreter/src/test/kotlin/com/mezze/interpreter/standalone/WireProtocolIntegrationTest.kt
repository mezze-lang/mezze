package com.mezze.interpreter.standalone

import com.mezze.wire.Bye
import com.mezze.wire.Hello
import com.mezze.wire.MessageType
import com.mezze.wire.Quit
import com.mezze.wire.Welcome
import com.mezze.wire.WireFrame
import com.mezze.wire.WireFrameCodec
import com.mezze.wire.WireProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end test: spawn RuntimeServer in a fresh JVM, exchange
 * HELLO/WELCOME on its real stdin/stdout, send QUIT, expect BYE +
 * clean exit. Validates the wire protocol on a real process boundary
 * (codec + state machine + handshake — but not LOAD, which needs real
 * compiled IR).
 */
class WireProtocolIntegrationTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun handshakeAndQuit() {
        val runtimeJar = locateRuntimeJar()
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath

        val proc = ProcessBuilder(
            javaBin,
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/sun.misc=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
            "--add-modules=jdk.incubator.vector",
            "-cp", runtimeJar.absolutePath,
            "com.mezze.interpreter.standalone.RuntimeServer",
        )
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        try {
            val out = proc.outputStream  // child's stdin
            val inp = proc.inputStream   // child's stdout

            // Send HELLO
            WireFrameCodec.writeFlush(out, WireFrame.of(MessageType.HELLO, Hello(
                client = "wire-protocol-integration-test",
                capabilities = listOf("core", "exit_code"),
            )))

            // Expect WELCOME
            val welcomeFrame = WireFrameCodec.read(inp)
            assertNotNull(welcomeFrame, "expected WELCOME frame, got null (EOF)")
            assertEquals(MessageType.WELCOME, welcomeFrame!!.type, "first reply must be WELCOME")
            val welcome = welcomeFrame.decode<Welcome>()
            assertEquals(WireProtocol.PROTOCOL_VERSION_MAJOR, welcome.agreedVersionMajor)
            assertTrue(welcome.agreedCapabilities.contains("core"),
                "server should agree on `core` cap; got ${welcome.agreedCapabilities}")
            assertTrue(welcome.runtimeInfo.jvmVersion.isNotBlank(),
                "runtime_info.jvm_version must be populated")

            // Send QUIT
            WireFrameCodec.writeFlush(out, WireFrame.of(MessageType.QUIT, Quit(reason = "test-done")))

            // Expect BYE
            val byeFrame = WireFrameCodec.read(inp)
            assertNotNull(byeFrame, "expected BYE frame, got null (EOF)")
            assertEquals(MessageType.BYE, byeFrame!!.type, "after QUIT the server must send BYE")

            // Child must terminate cleanly within the timeout
            assertTrue(proc.waitFor(10, TimeUnit.SECONDS), "RuntimeServer did not exit within 10s of BYE")
            assertEquals(0, proc.exitValue(), "RuntimeServer exit code must be 0 on clean QUIT")
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    private fun locateRuntimeJar(): File {
        // Tests run from the project root; the slim runtime jar lives at
        // interpreter/build/libs/mezze-runtime.jar after :interpreter:runtimeShadowJar.
        val root = File(System.getProperty("mezze.project.root")
            ?: error("mezze.project.root system property not set"))
        val jar = File(root, "interpreter/build/libs/mezze-runtime.jar")
        check(jar.exists()) {
            "mezze-runtime.jar not found at ${jar.absolutePath}. " +
            "Run `./gradlew :interpreter:runtimeShadowJar` before this test."
        }
        return jar
    }
}
