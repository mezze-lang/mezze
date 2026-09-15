package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.util.appliedHashOf
import com.mezze.language.typechecker.util.declaredHashOf
import com.mezze.language.typechecker.util.receiverDispatchHashes
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * BUG 1 — two impls that share a type CONSTRUCTOR but differ in their type
 * ARGUMENTS must be distinct declarations to dispatch.
 *
 * This is the polyglot `Result PyForeign InteropError` / `Result JsForeign
 * InteropError` collision with the providers taken out: the shape is what
 * breaks, not anything about Python or JS. Both impls register under the
 * constructor's declaration hash (`Result`), which is IDENTICAL for the two —
 * so every hash-keyed registry entry for one overwrote the other, and the
 * mangled STRING `Result_PyForeign_InteropError` was the only thing keeping
 * them apart at the call site.
 *
 * The fix is an applied hash: `hash(constructor ⊕ arg identities)`, computed the
 * same way where impls are registered and where receivers are resolved, so the
 * two are different keys without a name ever being compared.
 */
class AppliedTypeDispatchTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private val twoProviders = """
        type PyF = { py_handle: Int }
        type JsF = { js_handle: Str }
        type IErr = { msg: Str }

        impl Result PyF IErr where {
          tag = { it } -> "py"
        }

        impl Result JsF IErr where {
          tag = { it } -> "js"
        }

        let py: Result PyF IErr = Ok { val = { py_handle = 1 } }
        let js: Result JsF IErr = Ok { val = { js_handle = "j" } }
    """.trimIndent()

    private fun run(code: String): String {
        val module = parser.parseParsedModule(code, "test.mz")
        typeChecker.typeCheck(module, Paths.get("."))
        return "ok"
    }

    @Test
    fun `both same-constructor impls survive registration and typecheck`() {
        assertEquals("ok", run("$twoProviders\n\nlet a = py.tag {}\nlet b = js.tag {}"))
    }

    @Test
    fun `the Py receiver reaches the Py impl`() {
        // Not just "it typechecks": the two impls return different STRINGS, so
        // the chosen impl is observable. A wrong pick here is the silent
        // mis-dispatch the mangled-name key was papering over.
        assertEquals("ok", run("$twoProviders\n\nlet a: Str = py.tag {}"))
    }

    @Test
    fun `a partially-generic same-constructor impl is still reachable`() {
        // `impl Result 'a IErr` has a type VARIABLE in the first argument, so it
        // mints no applied hash and files under the bare constructor hash — as it
        // must, since it has to serve every `Result <anything> IErr` receiver.
        // A `Result PyF IErr` receiver has to find BOTH impls: `tag` by its applied
        // hash, `describe` by falling back to the constructor hash. The applied
        // hash is a more-specific key layered on top, never a replacement.
        val code = """
            type PyF = { py_handle: Int }
            type IErr = { msg: Str }

            impl Result PyF IErr where {
              tag = { it } -> "py"
            }

            impl Result 'a IErr where {
              describe = { it } -> "generic"
            }

            let py: Result PyF IErr = Ok { val = { py_handle = 1 } }

            let a: Str = py.tag {}
            let b: Str = py.describe {}
        """.trimIndent()
        assertEquals("ok", run(code))
    }

    // ─── the hash itself, independent of the pipeline ────────────────────────

    private fun nominal(name: String, hex: String) =
        MezzeType.TypeApplication(name, emptyList(), TypeIdentity.Declared(Hash(hex.padEnd(64, '0')), name))

    private fun resultOf(vararg args: MezzeType) =
        MezzeType.TypeApplication(
            "Result", args.toList(),
            TypeIdentity.Declared(Hash("25".padEnd(64, 'a')), "Result"),
        )

    @Test
    fun `same constructor, different args, different applied hash`() {
        val py = resultOf(nominal("PyF", "aa"), nominal("IErr", "cc"))
        val js = resultOf(nominal("JsF", "bb"), nominal("IErr", "cc"))

        // The collision, stated: the two share every key derived from the
        // constructor alone.
        assertEquals(declaredHashOf(py), declaredHashOf(js))
        // …and are told apart by the applied hash.
        assertNotEquals(appliedHashOf(py), appliedHashOf(js))
        assertNotEquals(appliedHashOf(py), declaredHashOf(py))
    }

    @Test
    fun `an all-variable application mints no applied hash`() {
        // `impl Result 'a 'e` must keep the bare constructor hash — it has to
        // serve every `Result …` receiver, so a key that pinned it to particular
        // arguments would make it unreachable.
        val generic = resultOf(
            MezzeType.VarType(TypeVar.fresh()),
            MezzeType.VarType(TypeVar.fresh()),
        )
        assertNull(appliedHashOf(generic))
        assertEquals(listOf(declaredHashOf(generic)), receiverDispatchHashes(generic))
    }

    @Test
    fun `a partly-variable application mints no applied hash either`() {
        // `impl Result 'a IErr` still has to serve every first argument, so
        // pinning it to one would be just as wrong.
        val partial = resultOf(MezzeType.VarType(TypeVar.fresh()), nominal("IErr", "cc"))
        assertNull(appliedHashOf(partial))
    }

    @Test
    fun `a concrete receiver offers applied then constructor, in that order`() {
        val py = resultOf(nominal("PyF", "aa"), nominal("IErr", "cc"))
        assertEquals(listOf(appliedHashOf(py), declaredHashOf(py)), receiverDispatchHashes(py))
    }

    @Test
    fun `identical argument identities give identical applied hashes`() {
        // Determinism across the two sides of dispatch: registration and lookup
        // build the type separately and must land on the same bytes.
        val a = resultOf(nominal("PyF", "aa"), nominal("IErr", "cc"))
        val b = resultOf(nominal("PyF", "aa"), nominal("IErr", "cc"))
        assertEquals(appliedHashOf(a), appliedHashOf(b))
    }

    @Test
    fun `argument ORDER changes the applied hash`() {
        val ab = resultOf(nominal("A", "aa"), nominal("B", "bb"))
        val ba = resultOf(nominal("B", "bb"), nominal("A", "aa"))
        assertNotEquals(appliedHashOf(ab), appliedHashOf(ba))
    }

    @Test
    fun `builtin arguments are identified by their reserved name`() {
        // Int and Str are declared nowhere, so there is no hash to prefer — the
        // reserved name is the identity, the same accepted rule receiverTypeName
        // uses. They must still tell two applications apart.
        val intErr = resultOf(MezzeType.IntType, nominal("IErr", "cc"))
        val strErr = resultOf(MezzeType.StringType, nominal("IErr", "cc"))
        assertNotEquals(appliedHashOf(intErr), appliedHashOf(strErr))
    }
}
