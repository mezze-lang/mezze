package com.mezze.interpreter.standalone

import com.mezze.wire.Hello
import com.mezze.wire.MessageType
import com.mezze.wire.Reject
import com.mezze.wire.WireFrame
import com.mezze.wire.WireFrameCodec
import com.mezze.wire.WireListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/**
 * A client that cannot present the session token gets REJECTED, and reaches nothing.
 *
 * `WireListenerTest` proves the comparison is right. This proves the SERVER applies
 * it — the distinction that matters, because a correct check nobody calls protects
 * nothing. The protocol can LOAD and RUN code, so the failure being tested here is
 * arbitrary local code execution.
 *
 * Driven through the handshake by reflection rather than by launching a runtime: the
 * question is purely whether the auth gate precedes everything else, and booting a
 * Truffle context to ask it would make the test slow and the answer no clearer.
 */
class SocketTransportAuthTest {

    /** Feed [hello] to the server's handshake, returning the frames it sends back. */
    private fun handshakeWith(hello: Hello, requiredToken: String?): List<WireFrame> {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, 64 * 1024)
        WireFrameCodec.writeFlush(toServer, WireFrame.of(MessageType.HELLO, hello))

        val sent = mutableListOf<WireFrame>()
        val handshake = RuntimeServer::class.java.getDeclaredMethod(
            "handshake",
            java.io.InputStream::class.java,
            Function1::class.java,
            java.io.PrintStream::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        val send: (WireFrame) -> Unit = { sent.add(it) }
        val diag = java.io.PrintStream(ByteArrayOutputStream())
        try {
            handshake.invoke(RuntimeServer, serverIn, send, diag, requiredToken)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // A rejected handshake throws after sending REJECT; the frames are what
            // this test is about.
        }
        return sent
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    fun `a client with no token is rejected when a token is required`() {
        val frames = handshakeWith(Hello(client = "attacker"), requiredToken = "a".repeat(64))

        val reject = frames.firstOrNull { it.type == MessageType.REJECT }
        assertNotNull(reject, "expected REJECT, got ${frames.map { it.type }}")
        assertEquals("unauthorized", reject!!.decode<Reject>().reason)
        assertTrue(frames.none { it.type == MessageType.WELCOME },
            "a rejected client must never be welcomed")
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    fun `a client with the WRONG token is rejected`() {
        val frames = handshakeWith(
            Hello(client = "attacker", token = "b".repeat(64)),
            requiredToken = "a".repeat(64),
        )
        assertEquals(
            "unauthorized",
            frames.first { it.type == MessageType.REJECT }.decode<Reject>().reason,
        )
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    fun `the rejection does not describe the expected token`() {
        // An error that reports the expected length, or whether a token was supplied,
        // hands an attacker exactly the feedback a guessing loop needs.
        val secret = "c".repeat(64)
        val frames = handshakeWith(Hello(client = "attacker", token = "wrong"), requiredToken = secret)
        val detail = frames.first { it.type == MessageType.REJECT }.decode<Reject>().detail

        assertTrue(secret !in detail, "the reply leaks the token: $detail")
        assertTrue("64" !in detail, "the reply leaks the token's length: $detail")
        assertTrue("wrong" !in detail, "the reply echoes the attempt: $detail")
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    fun `a client WITH the right token is not rejected`() {
        // The boundary: the gate must open for a legitimate client, or it is just a
        // closed door.
        val secret = "d".repeat(64)
        val frames = handshakeWith(Hello(client = "mez-repl", token = secret), requiredToken = secret)

        assertTrue(frames.none { it.type == MessageType.REJECT },
            "a client presenting the session token must pass: ${frames.map { it.type }}")
        assertTrue(frames.any { it.type == MessageType.WELCOME },
            "expected WELCOME, got ${frames.map { it.type }}")
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    fun `stdio requires no token`() {
        // The default transport is inherited pipes, where the OS already decided who
        // may write. Demanding a secret there would break every existing client for
        // no gain.
        val frames = handshakeWith(Hello(client = "mez-dev"), requiredToken = null)
        assertTrue(frames.any { it.type == MessageType.WELCOME },
            "stdio must handshake unchanged: ${frames.map { it.type }}")
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    fun `a stray token on stdio is harmless`() {
        // A client that always sets the field should not be punished for it.
        val frames = handshakeWith(Hello(client = "mez-dev", token = "whatever"), requiredToken = null)
        assertTrue(frames.any { it.type == MessageType.WELCOME })
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    fun `auth is checked before version negotiation`() {
        // Order matters: an unauthenticated client must not be able to learn what
        // versions or capabilities this runtime speaks, nor reach any behaviour a
        // HELLO field drives (the terminal snapshot, for one).
        val frames = handshakeWith(
            Hello(client = "attacker", versionMajor = 99, token = null),
            requiredToken = "e".repeat(64),
        )
        val reject = frames.first { it.type == MessageType.REJECT }.decode<Reject>()
        assertEquals("unauthorized", reject.reason,
            "a bad version on an unauthenticated client must still report unauthorized, " +
                "not version_unsupported — otherwise the version check ran first")
    }
}
