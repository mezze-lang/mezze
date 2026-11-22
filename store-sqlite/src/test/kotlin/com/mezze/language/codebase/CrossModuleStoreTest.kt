package com.mezze.language.codebase

import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.store.MezzeStore
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.module.ModuleTypeChecker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A store asked what it has already seen, after indexing a program that is
 * really several files importing each other.
 *
 * `CodeHashContract` indexes modules one at a time, each alone. Here one type
 * check loads the whole program and one `populate` writes it — imports, their
 * declarations, the functions lowered for all of them — which is how a build
 * fills a store, and the only way a caller and the function it imports are
 * hashed together.
 */
abstract class CrossModuleStoreContract {

    abstract fun open(): MezzeStore

    @TempDir lateinit var root: Path
    private lateinit var store: MezzeStore

    @BeforeEach fun setUp() { store = open() }
    @AfterEach fun tearDown() { store.close() }

    private fun write(module: String, body: String) {
        val file = root.resolve("src").resolve("$module.mz")
        Files.createDirectories(file.parent)
        Files.writeString(file, body.trimIndent())
    }

    private fun index(main: String) {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        ModuleTypeChecker.clearCache()
        ModuleTypeChecker.clearTypeCheckingStack()
        write("main", main)
        val file = root.resolve("src/main.mz")
        val parsed = MezzeParser().parseParsedModule(main.trimIndent(), file.toString())
        val checked = TypeChecker().typeCheck(parsed, root.resolve("src"), true, null)
        StorePopulator.populate(store, checked, "main", file.toString(), "srchash",
            entrySource = main.trimIndent(), parsedModule = parsed)
    }

    private fun identity(module: String, name: String) =
        store.resolveName(module, name) ?: error("$module::$name was not indexed; names: ${store.getModuleExports(module).map { it.name }}")

    private fun code(module: String, name: String) =
        store.getCodeHash(identity(module, name)) ?: error("$module::$name has no code hash recorded")

    @Test
    fun `a function two imported modules both declare is seen once, under both names`() {
        val sum = "pub let sum = { a: Int, b: Int } -> a + b"
        write("a", sum)
        write("b", "$sum\npub let add = { a: Int, b: Int } -> a + b")
        index("""
            use a
            use b

            let main = {} -> a::sum { a = 1, b = 2 } + b::sum { a = 3, b = 4 } + b::add { a = 5, b = 6 }
        """)

        assertEquals(identity("a", "sum"), identity("b", "sum"))
        assertEquals(
            setOf("a::sum", "b::sum"),
            store.getNamesForHash(identity("a", "sum")).map { "${it.module}::${it.name}" }.toSet(),
        )
        assertNotEquals(identity("a", "sum"), identity("b", "add"))
        assertEquals(code("a", "sum"), code("b", "add"), "the same code under another name")
        assertEquals(
            setOf(identity("a", "sum"), identity("b", "add")),
            store.getHashesForCode(code("a", "sum")).toSet(),
        )
        assertNotNull(store.getIRForCode(code("b", "add")))
    }

    @Test
    fun `a caller's code hash is made of the code it imports`() {
        fun lib(step: String) = "pub let count = { n: Int, acc: Int } -> if n <= 0 then acc else count { n = n - 1, acc = acc + $step }"
        write("up", lib("n"))
        write("ones", lib("1"))
        write("a", "use up::{ count }\n\npub let total = { n: Int } -> count { n = n, acc = 0 }")
        write("b", "use up::{ count as tally }\n\npub let total = { n: Int } -> tally { n = n, acc = 0 }")
        write("c", "use ones::{ count }\n\npub let total = { n: Int } -> count { n = n, acc = 0 }")
        // `up` and `ones` are imported here too: a populate writes the modules the
        // entry module loads, and their names are asked for below.
        index("""
            use a
            use b
            use c
            use up
            use ones

            let main = {} -> a::total { n = 1 } + b::total { n = 1 } + c::total { n = 1 } +
              up::count { n = 1, acc = 0 } + ones::count { n = 1, acc = 0 }
        """)

        assertEquals(code("a", "total"), code("b", "total"), "the same function imported under two names")
        assertNotEquals(code("a", "total"), code("c", "total"), "the same text calling different code")
        assertNotEquals(code("up", "count"), code("ones", "count"))
    }
}

class SQLiteCrossModuleStoreTest : CrossModuleStoreContract() {
    override fun open(): MezzeStore = SQLiteMezzeStore.inMemory()
}

class InMemoryCrossModuleStoreTest : CrossModuleStoreContract() {
    override fun open(): MezzeStore = InMemoryMezzeStore()
}
