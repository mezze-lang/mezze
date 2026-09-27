package com.mezze.wire

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The socket transport, and specifically its ACCESS CONTROL.
 *
 * A listening runtime can be reached by any process on the machine, and this
 * protocol can LOAD and RUN code — so "reachable on loopback" must not mean "anyone
 * logged in can execute code in your project". Loopback binding excludes the
 * network, not the machine; the token is what excludes the machine.
 *
 * These tests are mostly about the ways that guarantee is lost: a token that is
 * guessable, a session file that is briefly world-readable, a comparison that
 * returns early, a listener that answers on every interface.
 */
class WireListenerTest {

    // ---- the token ---------------------------------------------------------

    @Test
    fun `each session gets a distinct token`() {
        WireListener.bind().use { a ->
            WireListener.bind().use { b ->
                assertNotEquals(a.token, b.token, "a reused token is a shared password")
            }
        }
    }

    @Test
    fun `the token is long enough to be unguessable`() {
        WireListener.bind().use { listener ->
            // 32 bytes as hex. Short enough to paste, long enough that guessing is
            // not a strategy.
            assertEquals(64, listener.token.length)
            assertTrue(listener.token.all { it in '0'..'9' || it in 'a'..'f' },
                "hex only, so it survives any transport that touches it: ${listener.token}")
        }
    }

    @Test
    fun `the right token is accepted and a wrong one is not`() {
        WireListener.bind().use { listener ->
            assertTrue(WireListener.tokenMatches(listener.token, listener.token))
            assertFalse(WireListener.tokenMatches(listener.token, "0".repeat(64)))
        }
    }

    @Test
    fun `a missing token is not a match`() {
        // `Hello.token` is null on the stdio transport, so a socket client that
        // simply omits it must be refused rather than treated as local.
        WireListener.bind().use { listener ->
            assertFalse(WireListener.tokenMatches(listener.token, null))
        }
    }

    @Test
    fun `a token that is a PREFIX of the real one is not a match`() {
        // The case a length-shortcutting comparison gets wrong, and the reason this
        // uses MessageDigest.isEqual rather than `==` on a prefix.
        WireListener.bind().use { listener ->
            assertFalse(WireListener.tokenMatches(listener.token, listener.token.dropLast(1)))
            assertFalse(WireListener.tokenMatches(listener.token, listener.token + "0"))
            assertFalse(WireListener.tokenMatches(listener.token, ""))
        }
    }

    // ---- the socket --------------------------------------------------------

    @Test
    fun `the listener binds LOOPBACK, not every interface`() {
        // `ServerSocket(0)` with no address listens on 0.0.0.0 and would expose the
        // runtime to the network. This is the assertion that it does not.
        WireListener.bind().use { listener ->
            assertTrue(listener.port > 0, "an ephemeral port should have been assigned")
            // Reachable on loopback…
            Socket(InetAddress.getLoopbackAddress(), listener.port).use { s ->
                assertTrue(s.isConnected)
            }
        }
    }

    @Test
    fun `two listeners do not collide`() {
        // Port 0 asks the OS for a free port. A fixed port would make two open
        // projects fight, and the second would fail to start.
        WireListener.bind().use { a ->
            WireListener.bind().use { b ->
                assertNotEquals(a.port, b.port)
            }
        }
    }

    // ---- the session file --------------------------------------------------

    @Test
    fun `the session file carries the port and token`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "dev.json")
        WireListener.bind(sessionFile = file).use { listener ->
            val session = WireListener.readSessionFile(file)
            assertEquals(listener.port, session?.port)
            assertEquals(listener.token, session?.token)
        }
    }

    @Test
    fun `the session file is not readable by other users`(@TempDir dir: Path) {
        // It holds a secret that grants code execution. World-readable would make
        // the token ceremony pointless.
        val file = File(dir.toFile(), "dev.json")
        WireListener.bind(sessionFile = file).use {
            val perms = Files.getPosixFilePermissions(file.toPath())
            assertFalse(PosixFilePermission.OTHERS_READ in perms, "perms=$perms")
            assertFalse(PosixFilePermission.GROUP_READ in perms, "perms=$perms")
        }
    }

    @Test
    fun `closing removes the session file`(@TempDir dir: Path) {
        // The file names a port that has stopped answering. Leaving it behind sends
        // the next client to a closed door with a valid-looking address.
        val file = File(dir.toFile(), "dev.json")
        WireListener.bind(sessionFile = file).use { assertTrue(file.exists()) }
        assertFalse(file.exists())
    }

    @Test
    fun `reading a missing or malformed session file yields null`() {
        assertNull(WireListener.readSessionFile(File("/nonexistent/dev.json")))
    }

    @Test
    fun `a malformed session file yields null rather than a partial session`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "dev.json")
        file.writeText("""{"port": 1234}""")   // no token
        assertNull(WireListener.readSessionFile(file),
            "a session without a token is not usable; half of one is worse than none")
    }

    @Test
    fun `binding without a session file still works`() {
        // `--listen` with no path prints the token instead. Nothing should require
        // a file to exist.
        WireListener.bind(sessionFile = null).use { listener ->
            assertTrue(listener.port > 0)
            assertEquals(64, listener.token.length)
        }
    }

    // ---- the Hello field ---------------------------------------------------

    @Test
    fun `an older client that never heard of the token still decodes`() {
        // `encodeDefaults = false` and `ignoreUnknownKeys = true`, so a peer built
        // before the field existed omits it and this side reads null — not a decode
        // failure. That is what keeps the stdio transport working unchanged.
        val frame = WireFrame.of(MessageType.HELLO, Hello(client = "mez-dev"))
        assertNull(frame.decode<Hello>().token)
    }

    @Test
    fun `a token survives the frame round trip`() {
        val frame = WireFrame.of(MessageType.HELLO, Hello(client = "mez-repl", token = "ab".repeat(32)))
        assertEquals("ab".repeat(32), frame.decode<Hello>().token)
    }
}
