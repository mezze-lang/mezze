package com.mezze.wire

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A loopback socket a tool can ATTACH to, instead of having to spawn the runtime
 * itself.
 *
 * The stdio transport only serves the process that forked the runtime: the pipes
 * are inherited, so a parent and exactly one child can talk. An editor, a REPL, a
 * documentation browser or an agent that wants to evaluate against an ALREADY WARM
 * runtime has no way in — it can only start its own, paying the whole startup cost
 * and getting a separate world.
 *
 * ## Why this needs a secret
 *
 * The protocol can LOAD and RUN code. A socket anyone on the machine can reach is
 * therefore a way for any local process to execute code in the user's project.
 * Loopback binding is not access control — it excludes the network, not the
 * machine — so a session carries a token:
 *
 *  - 256 bits from [SecureRandom].
 *  - Written to a session file whose permissions are reduced to owner-only BEFORE
 *    the secret is written to it, so it is never briefly world-readable.
 *  - Compared with [MessageDigest.isEqual], which does not return early on the
 *    first differing byte.
 *
 * A client proves possession in [Hello.token]. A connection that does not is
 * rejected and closed without loading anything.
 *
 * ## One client
 *
 * [accept] serves a single connection, deliberately. Several clients sharing one
 * runtime raises questions this has no answer for yet — whose stdin is it, who sees
 * whose output, what does interrupting mean — and the protocol reserves
 * `0x20–0x2F` for that design when it has a driver. Until then, one.
 */
class WireListener private constructor(
    private val server: ServerSocket,
    /** The secret a client must present in [Hello.token]. */
    val token: String,
    private val sessionFile: File?,
) : AutoCloseable {

    val port: Int get() = server.localPort

    companion object {
        /** Bytes of entropy in a session token. */
        private const val TOKEN_BYTES = 32

        /**
         * Bind an ephemeral loopback port and, if [sessionFile] is given, record
         * `{port, token}` there for a client to read.
         *
         * Port 0 asks the OS for a free port rather than guessing one, so two
         * projects open at once cannot collide. Bound to the loopback ADDRESS
         * explicitly — `ServerSocket(0)` alone listens on every interface, which
         * would expose the runtime to the network.
         */
        fun bind(sessionFile: File? = null): WireListener {
            val loopback = InetAddress.getLoopbackAddress()
            val server = ServerSocket(0, 1, loopback)
            val token = newToken()
            sessionFile?.let { writeSessionFile(it, server.localPort, token) }
            return WireListener(server, token, sessionFile)
        }

        private fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Write the session file, restricting permissions BEFORE the secret lands
         * in it.
         *
         * Creating the file and then chmod-ing leaves a window in which the token is
         * world-readable. Creating it empty, reducing the mode, then writing closes
         * that window. On a filesystem without POSIX permissions the reduction is
         * skipped rather than failing — the file still has to exist for the session
         * to be reachable at all.
         */
        private fun writeSessionFile(file: File, port: Int, token: String) {
            file.parentFile?.mkdirs()
            file.writeText("")
            try {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            } catch (_: UnsupportedOperationException) {
                // Not a POSIX filesystem; nothing to narrow.
            }
            file.writeText("""{"port": $port, "token": "$token"}""" + "\n")
        }

        /** Read a session file written by [bind]. Null if it is absent or unreadable. */
        fun readSessionFile(file: File): Session? {
            if (!file.isFile) return null
            val text = try { file.readText() } catch (_: Exception) { return null }
            val port = Regex("\"port\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val token = Regex("\"token\"\\s*:\\s*\"([0-9a-f]+)\"").find(text)?.groupValues?.get(1)
            return if (port != null && token != null) Session(port, token) else null
        }

        /**
         * Whether [presented] is the session token.
         *
         * Length-independent comparison via [MessageDigest.isEqual]: a comparison
         * that returns on the first differing byte leaks how much of a guess was
         * right, which is enough to recover a secret one byte at a time.
         */
        fun tokenMatches(expected: String, presented: String?): Boolean {
            if (presented == null) return false
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                presented.toByteArray(Charsets.UTF_8),
            )
        }
    }

    /** Where a client connects and what it must present. */
    data class Session(val port: Int, val token: String)

    /**
     * Wait for one client.
     *
     * Blocks. The caller runs the normal session over the returned socket's streams
     * — the frame codec takes any stream, so nothing above the transport changes.
     */
    fun accept(): Socket = server.accept()

    /**
     * Stop listening and remove the session file.
     *
     * The file names a port that is about to stop answering; leaving it behind sends
     * the next client to a closed door with a valid-looking address.
     */
    override fun close() {
        try { server.close() } catch (_: Exception) {}
        sessionFile?.delete()
    }
}
