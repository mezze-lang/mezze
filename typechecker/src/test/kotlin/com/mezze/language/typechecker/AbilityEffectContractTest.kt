package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests the three rules governing effects on ability operation signatures
 * and how impl bodies must match:
 *
 * Rule 1: Ability declares concrete effect <E> → every impl must carry <E>
 * Rule 2: Ability declares no effect (pure) → every impl must be pure
 * Rule 3: Ability declares <...> → each impl chooses its own effect(s)
 *
 * All effects and abilities here are self-contained — no stdlib reuse.
 */
class AbilityEffectContractTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ═══════════════════════════════════════════════════════════════
    // Rule 1: concrete effect — impl must match
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `concrete effect - impl inherits effect when no annotation given`() {
        val source = """
            effect Storage where {
                save: {data: Str} -> {}
            }

            ability Persist for 'a where {
                store: {it: 'a} -> Bool <Storage>
            }

            type Doc = { body: Str }

            impl Persist for Doc where {
                store = {it} -> True
            }

            let d = { body = "hello" }
            d.store {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `concrete effect - impl with explicit matching annotation passes`() {
        val source = """
            effect Storage where {
                save: {data: Str} -> {}
            }

            ability Persist for 'a where {
                store: {it: 'a} -> Bool <Storage>
            }

            type Doc = { body: Str }

            impl Persist for Doc where {
                store: {it: Doc} -> Bool <Storage> = {it} -> True
            }

            let d = { body = "hello" }
            d.store {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `concrete effect - impl body uses undeclared effect is rejected`() {
        val source = """
            effect Storage where {
                save: {data: Str} -> {}
            }

            effect Logging where {
                append: {msg: Str} -> {}
            }

            ability Persist for 'a where {
                store: {it: 'a} -> Bool <Storage>
            }

            type Doc = { body: Str }

            impl Persist for Doc where {
                store = {it} -> do
                    Logging.append {msg = "saving"}
                    True
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val ex = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(ex.message!!.contains("Logging"), "Error should mention the undeclared effect, got: ${ex.message}")
    }

    @Test
    fun `concrete effect - impl body is pure when ability declares effect is ok`() {
        val source = """
            effect Storage where {
                save: {data: Str} -> {}
            }

            ability Persist for 'a where {
                store: {it: 'a} -> Bool <Storage>
            }

            type Doc = { body: Str }

            impl Persist for Doc where {
                store = {it} -> True
            }

            let d = { body = "hello" }
            d.store {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `concrete effect - ability with multiple effects on one op`() {
        val source = """
            effect Network where {
                fetch: {url: Str} -> Str
            }

            effect Logging where {
                append: {msg: Str} -> {}
            }

            ability Downloader for 'a where {
                download: {it: 'a} -> Str <Network, Logging>
            }

            type Client = { base_url: Str }

            impl Downloader for Client where {
                download = {it} -> do
                    Logging.append {msg = "downloading"}
                    Network.fetch {url = it.base_url}
            }

            let c = { base_url = "example.com" }
            c.download {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // Rule 2: pure ability — impl must also be pure
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `pure ability - pure impl passes`() {
        val source = """
            ability Label for 'a where {
                label: {it: 'a} -> Str
            }

            type Color = { r: Int, g: Int, b: Int }

            impl Label for Color where {
                label = {it} -> "color"
            }

            let c = { r = 255, g = 0, b = 0 }
            c.label {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `pure ability - impl body using effect is rejected`() {
        val source = """
            effect Logging where {
                append: {msg: Str} -> {}
            }

            ability Label for 'a where {
                label: {it: 'a} -> Str
            }

            type Color = { r: Int, g: Int, b: Int }

            impl Label for Color where {
                label = {it} -> do
                    Logging.append {msg = "labeling"}
                    "color"
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val ex = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(ex.message!!.contains("Logging"), "Error should mention the undeclared effect, got: ${ex.message}")
        assertTrue(ex.message!!.contains("pure"), "Error should mention the op is pure, got: ${ex.message}")
    }

    // ═══════════════════════════════════════════════════════════════
    // Rule 3: polymorphic <...> — impl chooses its own effect(s)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `polymorphic - impl with no effect (pure) passes`() {
        val source = """
            ability Produce for 'a having 'elem where {
                next: {it: 'a} -> Option 'elem <...>
            }

            type Counter = { n: Int }

            impl Produce for Counter having 'elem = Int where {
                next = {it} -> Some {val = it.n}
            }

            let c = { n = 0 }
            c.next {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `polymorphic - impl with single effect passes`() {
        val source = """
            effect Sensor where {
                read_value: {} -> Int
            }

            ability Produce for 'a having 'elem where {
                next: {it: 'a} -> Option 'elem <...>
            }

            type Probe = { id: Int }

            impl Produce for Probe having 'elem = Int where {
                next = {it} -> Some {val = Sensor.read_value {}}
            }

            let p = { id = 1 }
            p.next {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `polymorphic - impl with multiple effects passes`() {
        val source = """
            effect Sensor where {
                read_value: {} -> Int
            }

            effect Logging where {
                append: {msg: Str} -> {}
            }

            ability Produce for 'a having 'elem where {
                next: {it: 'a} -> Option 'elem <...>
            }

            type LoggedProbe = { id: Int }

            impl Produce for LoggedProbe having 'elem = Int where {
                next = {it} -> do
                    Logging.append {msg = "reading"}
                    Some {val = Sensor.read_value {}}
            }

            let p = { id = 1 }
            p.next {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `polymorphic - two impls with different effects coexist`() {
        val source = """
            effect Sensor where {
                read_value: {} -> Int
            }

            ability Produce for 'a having 'elem where {
                next: {it: 'a} -> Option 'elem <...>
            }

            type PureCounter = { n: Int }

            impl Produce for PureCounter having 'elem = Int where {
                next = {it} -> Some {val = it.n}
            }

            type SensorReader = { channel: Int }

            impl Produce for SensorReader having 'elem = Int where {
                next = {it} -> Some {val = Sensor.read_value {}}
            }

            let pure = { n = 42 }.next {}
            let effectful = { channel = 1 }.next {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `polymorphic - mixed pure and polymorphic ops in same ability`() {
        val source = """
            ability Iterable for 'c having 'elem where {
                next: {it: 'c} -> Option 'elem <...>,
                count: {it: 'c} -> Int
            }

            type Pair = { a: Int, b: Int }

            impl Iterable for Pair having 'elem = Int where {
                next = {it} -> Some {val = it.a},
                count = {it} -> 2
            }

            let p = { a = 1, b = 2 }
            let n = p.count {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `polymorphic - effectful impl of mixed ability calls effectful op`() {
        val source = """
            effect Sensor where {
                read_value: {} -> Int
            }

            ability Iterable for 'c having 'elem where {
                next: {it: 'c} -> Option 'elem <...>,
                count: {it: 'c} -> Int
            }

            type Stream = { channel: Int }

            impl Iterable for Stream having 'elem = Int where {
                next = {it} -> Some {val = Sensor.read_value {}},
                count = {it} -> 0
            }

            let s = { channel = 7 }
            s.next {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // Edge: concrete + polymorphic tail (<E, ...>)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `concrete plus polymorphic tail - impl with just the concrete effect`() {
        val source = """
            effect Storage where {
                save: {data: Str} -> {}
            }

            ability Sink for 'a where {
                drain: {it: 'a} -> {} <Storage, ...>
            }

            type Bucket = { name: Str }

            impl Sink for Bucket where {
                drain = {it} -> Storage.save {data = it.name}
            }

            let b = { name = "test" }
            b.drain {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `concrete plus polymorphic tail - impl with extra effect beyond the concrete`() {
        val source = """
            effect Storage where {
                save: {data: Str} -> {}
            }

            effect Logging where {
                append: {msg: Str} -> {}
            }

            ability Sink for 'a where {
                drain: {it: 'a} -> {} <Storage, ...>
            }

            type LoggedBucket = { name: Str }

            impl Sink for LoggedBucket where {
                drain = {it} -> do
                    Logging.append {msg = "draining"}
                    Storage.save {data = it.name}
            }

            let b = { name = "test" }
            b.drain {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }
}
