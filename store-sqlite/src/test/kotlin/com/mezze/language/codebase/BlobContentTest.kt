package com.mezze.language.codebase

import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.store.MezzeStore
import com.mezze.language.store.StoreSerialization
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What is stored under a content hash is the declaration, not where or when it
 * was written.
 *
 * A blob's file and line numbers used to run through its bytes, and so did the
 * process-wide counter that numbers type variables: one hash held different
 * bytes in every checkout, after every edit above the declaration, and after
 * every run that had checked something else first.
 */
class BlobContentTest {

    private val source = """
        use std::effects::log::{ Console, println }

        type Point = { x: Int, y: Int }
        ability Label for 'a where { label: { it: 'a } -> Str }
        impl Label for Point where { label = { it } -> "p" }
        effect Audit where { record: { line: Str } -> {} <Audit> }
        impl Audit for Quiet where { record = { line } -> {} }

        let twice = { x: Int } -> x * 2
        let pick : { a: 'a, b: 'a } -> 'a = { a, b } -> a
        let first = { o: { x: Int, ... } } -> o.x
        let apply : { x: Int, f: { v: Int } -> Int <...> } -> Int <...> = { x, f } -> f { v = x }
        let shout = { msg: Str } -> println { msg = msg }
        let main = {} -> perform Console in shout { msg = { x = 1, y = 2 }.label {} }
    """.trimIndent()

    /** A function whose type variables the compiler names itself. */
    private val inferred = "\nlet double = { x } -> x * 2\nlet four = {} -> double { x = 2 }"

    private fun indexed(source: String, parsedAs: String): MezzeStore =
        InMemoryMezzeStore().also { indexSource(it, source, parsedAs = parsedAs) }

    /** Every blob's content, typed and lowered, by `hash/part`. */
    private fun contents(store: MezzeStore): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (hash in store.getAllBlobHashes()) {
            val blob = store.getBlob(hash)!!
            blob.typed?.let { out["${hash.hex}/typed"] = StoreSerialization.contentOf(it) }
            blob.ir?.let { out["${hash.hex}/ir"] = StoreSerialization.contentOf(it) }
        }
        return out
    }

    private fun assertSameContents(a: MezzeStore, b: MezzeStore, part: String = "") {
        val left = contents(a).filterKeys { it.endsWith(part) }
        val right = contents(b).filterKeys { it.endsWith(part) }
        assertEquals(left.keys, right.keys, "the two stores hold different declarations")
        assertTrue(left.keys.any { it.endsWith("/ir") } && (part == "/ir" || left.keys.any { it.endsWith("/typed") }),
            "nothing was compared: ${left.keys}")
        for ((key, bytes) in left) assertArrayEquals(bytes, right[key], "content differs for $key")
    }

    @Test
    fun `a declaration's content does not depend on the file it came from`() {
        assertSameContents(
            indexed(source, "/home/one/project/src/app.mz"),
            indexed(source, "/srv/build/another-checkout/src/app.mz"),
        )
    }

    @Test
    fun `a declaration's content does not depend on the line it is on`() {
        assertSameContents(
            indexed(source, "app.mz"),
            indexed("# moved down\n\n\n$source", "app.mz"),
        )
    }

    @Test
    fun `a declaration's content does not depend on what was checked before it`() {
        val first = indexed(source, "app.mz")
        // Checking anything in between advances the counter type variables are numbered from.
        indexed("let unrelated = { a, b, c } -> a", "other.mz")
        assertSameContents(first, indexed(source, "app.mz"))
    }

    @Test
    fun `a lowered function's content is the same however its types were named`() {
        assertSameContents(
            indexed(source + inferred, "/home/one/project/src/app.mz"),
            indexed("# moved down\n$source$inferred", "/srv/build/another-checkout/src/app.mz"),
            part = "/ir",
        )
    }

    /**
     * OPEN. A type variable the compiler names itself is named from a per-thread
     * counter that is never reset (`'x882` in one run, `'c887` in the next), and
     * the name is how a declaration's type parameters bind — so it runs through
     * the typed content. This asserts the break; it fails the day emission names
     * such variables by their place in the declaration, and is then to be folded
     * into the tests above.
     */
    @Test
    fun `OPEN - a compiler-named type variable still runs through typed content`() {
        fun double(store: MezzeStore) =
            StoreSerialization.deserializeTypedLetDecl(store.getBlob(store.resolveName("app", "double")!!)!!.typed!!)
        val a = double(indexed(source + inferred, "app.mz"))
        val b = double(indexed(source + inferred, "app.mz"))
        assertEquals(a.hash, b.hash)
        assertTrue(a.typeParams.isNotEmpty(), "`double` is no longer generic: ${a.scheme.type}")
        assertFalse(
            a.typeParams.map { it.name } == b.typeParams.map { it.name },
            "`double`'s type parameters are now named the same in every run (${a.typeParams.map { it.name }}) — " +
                "fold `inferred` into `source`",
        )
    }

    @Test
    fun `no file name runs through a declaration's content`() {
        val marker = "/a-path-that-must-not-be-stored/app.mz"
        val needle = marker.toByteArray()
        for ((key, bytes) in contents(indexed(source + inferred, marker))) {
            val found = (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
            assertFalse(found, "$key carries the file it was written in")
        }
    }

    @Test
    fun `a function read back knows where it was written`() {
        val file = "/home/one/project/src/app.mz"
        val store = indexed("\n\n$source", file)
        val blob = store.getBlob(store.resolveName("app", "twice")!!)!!

        val typed = StoreSerialization.deserializeTypedLetDecl(blob.typed!!)
        assertEquals(file, typed.loc.file)
        assertEquals(11, typed.loc.startLine, "`twice` is on line 11 of the shifted source")
        assertEquals(1, typed.loc.startCol, "and starts in column 1")
        assertEquals(11, typed.loc.endLine)
        assertTrue(typed.loc.endCol > 20, "and ends where its line does, not at column ${typed.loc.endCol}")

        val ir = StoreSerialization.deserializeFunctionIR(blob.ir!!)
        assertEquals(file, ir.sourceFile)
        assertEquals(11, ir.sourceSection.startLine)
        assertEquals(11, ir.body.src.startLine, "a node inside the body kept its own line")
    }

    @Test
    fun `what is read back is what was written, every location and variable of it`() {
        val parsed = com.mezze.language.parser.MezzeParser().parseParsedModule(source + inferred, "/home/one/app.mz")
        val checked = com.mezze.language.typechecker.TypeChecker()
            .checkParsedModule(parsed, java.nio.file.Paths.get("."), enablePrelude = true)
            as com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success

        val lets = checked.typedModule!!.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
        assertTrue(lets.any { it.name == "first" } && lets.any { it.name == "double" }, "${lets.map { it.name }}")
        for (written in lets) {
            val read = StoreSerialization.deserializeTypedLetDecl(StoreSerialization.serializeTypedLetDecl(written))
            assertEquals(written, read, "`${written.name}` came back a different declaration")
        }
        val lowered = checked.loweredFunctions!!
        assertTrue(lowered.isNotEmpty())
        for (written in lowered) {
            val read = StoreSerialization.deserializeFunctionIR(StoreSerialization.serializeFunctionIR(written))
            assertEquals(written, read, "`${written.qualifiedName}` came back a different function")
        }
    }

    @Test
    fun `reading back gives the bytes that were written`() {
        val store = indexed(source + inferred, "/home/one/project/src/app.mz")
        for (hash in store.getAllBlobHashes()) {
            val ir = store.getBlob(hash)!!.ir ?: continue
            val fn = StoreSerialization.deserializeFunctionIR(ir)
            assertArrayEquals(ir, StoreSerialization.serializeFunctionIR(fn), "a lowered function did not survive the round trip")
        }
    }
}
