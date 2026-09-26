package com.mezze.language.store

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.SignatureNormalizer
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A signature stored as rows can be read back as a TYPE.
 *
 * This is the property the previous index could not have. It stored the
 * signature as a rendered string, and rendering is one-way: `{ xs: [Int] } -> Int`
 * cannot be turned back into something to unify against, so the column could only
 * be displayed or substring-matched. Round-tripping is therefore not a nice
 * property of the new shape — it is the entire reason for it, and these tests are
 * where that claim is checked rather than asserted.
 *
 * Types containing variables are compared through [SignatureNormalizer], because
 * rebuilding necessarily mints new [TypeVar]s: two variables are the same
 * variable by their place in the type, never by their identity across a store
 * boundary. Variable-free types are compared with `==`, which is stricter.
 */
class SignatureIndexRoundTripTest {

    private val hash = Hash("a".repeat(64))
    private fun roundTrip(type: MezzeType): MezzeType =
        SignatureIndex.rebuild(SignatureIndex.flatten(hash, type))

    /** Same shape, ignoring which variables were minted. */
    private fun assertSameShape(expected: MezzeType, actual: MezzeType, hint: String = "") {
        assertEquals(SignatureNormalizer.render(expected), SignatureNormalizer.render(actual), hint)
    }

    // ---- primitives: exact equality ----------------------------------------

    @Test
    fun `every primitive round-trips exactly`() {
        for (prim in listOf(
            MezzeType.IntType, MezzeType.FloatType, MezzeType.StringType,
            MezzeType.CharType, MezzeType.FracType, MezzeType.BytesType,
        )) {
            assertEquals(prim, roundTrip(prim), "$prim must survive the index unchanged")
        }
    }

    // ---- the shapes --------------------------------------------------------

    @Test
    fun `a concrete function round-trips exactly`() {
        // No variables anywhere, so this can be held to `==` rather than to
        // rendering equality.
        val type = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType, "y" to MezzeType.IntType)),
            returnType = MezzeType.IntType,
        )
        assertEquals(type, roundTrip(type))
    }

    @Test
    fun `a polymorphic function round-trips as the same shape`() {
        val a = MezzeType.VarType(TypeVar.freshWithName("'a"))
        val type = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("x" to a)),
            returnType = a,
        )
        assertSameShape(type, roundTrip(type))
    }

    @Test
    fun `one variable used twice stays ONE variable through the index`() {
        // The structural claim behind rendering equality: if the index stored
        // variables per occurrence, the identity function would come back as
        // `{x: 'a} -> 'b` and stop relating its argument to its result.
        val a = MezzeType.VarType(TypeVar.freshWithName("'a"))
        val rebuilt = roundTrip(
            MezzeType.FunctionType(
                paramType = MezzeType.RecordType(linkedMapOf("x" to a)),
                returnType = a,
            )
        ) as MezzeType.FunctionType

        val param = (rebuilt.paramType as MezzeType.RecordType).fields.getValue("x")
        // The TypeVar ITSELF, not its name. `TypeVar` carries an `id`, so two
        // `freshWithName("'a")` calls are two different variables that merely
        // print alike — comparing names made this assertion vacuous and let the
        // bug through (the rebuilt `{x: 'a} -> Deque 'a` then unified with
        // `{x: 'a} -> 'a`, which the occurs check should reject).
        assertEquals(
            (param as MezzeType.VarType).variable,
            (rebuilt.returnType as MezzeType.VarType).variable,
            "both occurrences were written `'a` and must rebuild as ONE variable",
        )
    }

    @Test
    fun `an open record stays open and a closed one stays closed`() {
        val open = MezzeType.RecordType(linkedMapOf("a" to MezzeType.IntType), rowVar = RowVar.userOpen())
        val closed = MezzeType.RecordType(linkedMapOf("a" to MezzeType.IntType))

        assertNotNull((roundTrip(open) as MezzeType.RecordType).rowVar,
            "`{ a: Int, ... }` is a different type from `{ a: Int }`, not a looser rendering of it")
        assertNull((roundTrip(closed) as MezzeType.RecordType).rowVar)
    }

    @Test
    fun `a variant round-trips with and without constructor payloads`() {
        val type = MezzeType.VariantType(
            linkedMapOf("None" to null, "Some" to MezzeType.IntType)
        )
        val rebuilt = roundTrip(type) as MezzeType.VariantType
        assertEquals(setOf("None", "Some"), rebuilt.constructors.keys)
        assertNull(rebuilt.constructors.getValue("None"),
            "`None` carries no payload and must not acquire one")
        assertEquals(MezzeType.IntType, rebuilt.constructors.getValue("Some"))
    }

    @Test
    fun `an applied type keeps its arguments in order`() {
        val type = MezzeType.TypeApplication(
            "Result", listOf(MezzeType.IntType, MezzeType.StringType)
        )
        val rebuilt = roundTrip(type) as MezzeType.TypeApplication
        assertEquals("Result", rebuilt.constructor)
        assertEquals(listOf(MezzeType.IntType, MezzeType.StringType), rebuilt.args,
            "`Result Int Str` and `Result Str Int` are different types")
    }

    @Test
    fun `nominal identity survives as a HASH, not as a name`() {
        // The point of indexing identity at all: two same-named types from
        // different modules must stay distinguishable.
        val declHash = Hash("b".repeat(64))
        val type = MezzeType.TypeApplication(
            "Color", emptyList(), identity = TypeIdentity.Declared(declHash, "Color")
        )
        val rebuilt = roundTrip(type) as MezzeType.TypeApplication
        assertEquals(declHash, (rebuilt.identity as TypeIdentity.Declared).hash,
            "the content hash is the identity; the name is how it is displayed")
    }

    @Test
    fun `a BUILTIN type keeps its identity, which has no hash`() {
        // Three identities exist, not two: a declaration has a content hash, a
        // builtin (`Bool`, `Seq`, `Map`) has only a reserved name and nothing to
        // hash, and anything else is anonymous. Reading only the hash collapsed the
        // first two, so `Bool` — which IS a variant, `|True, False|` — came back
        // anonymous and rendered as `?variant` in every signature mentioning it.
        val bool = com.mezze.language.ast.PrimitiveTypes.BOOL_TYPE
        val rebuilt = roundTrip(bool)

        assertEquals("Bool", (rebuilt as MezzeType.VariantType).identity.displayNameOrNull(),
            "a builtin's name is the only identity signal it has")
        assertEquals("Bool", SignatureNormalizer.render(rebuilt),
            "and it must render as `Bool`, not as its arms or a marker")
    }

    @Test
    fun `an ANONYMOUS variant renders its arms rather than a marker`() {
        // The fallback for a variant with no name at all: showing `?variant` lost
        // the type, and made two different anonymous variants render identically.
        val v = MezzeType.VariantType(linkedMapOf("Red" to null, "Green" to null))
        assertEquals("|Green, Red|", SignatureNormalizer.render(roundTrip(v)),
            "sorted, because an arm set is a set")
    }

    @Test
    fun `an effect-polymorphic signature does not render as pure`() {
        // `allEffectNames` excludes the open tail by design, so rendering only those
        // names turned `-> Int <...>` into `-> Int` — a different signature making
        // the opposite claim.
        val type = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(emptySet(), rowVar = RowVar.userOpen()),
        )
        assertEquals("{} -> Int <...>", SignatureNormalizer.render(roundTrip(type)))
    }

    @Test
    fun `the performed marker renders`() {
        val type = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(setOf("Log"), performed = true),
        )
        assertEquals("{} -> Int <Log, *>", SignatureNormalizer.render(roundTrip(type)),
            "`*` says the effect is handled HERE; dropping it changes what the " +
                "signature promises a caller")
    }

    @Test
    fun `a newtype round-trips with its wrapped type`() {
        val type = MezzeType.NewtypeType(
            name = "UserId", constructor = "UserId", wrappedType = MezzeType.IntType
        )
        val rebuilt = roundTrip(type) as MezzeType.NewtypeType
        assertEquals("UserId", rebuilt.name)
        assertEquals(MezzeType.IntType, rebuilt.wrappedType)
    }

    // ---- effects -----------------------------------------------------------

    @Test
    fun `an effect row round-trips with its names and hashes`() {
        val logHash = Hash("c".repeat(64))
        val type = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.RecordType(emptyMap()),
            effects = MezzeType.EffectRow.simple(
                setOf("Log"), effectHashes = mapOf("Log" to logHash)
            ),
        )
        val row = (roundTrip(type) as MezzeType.FunctionType).effects
        assertEquals(setOf("Log"), row?.effects)
        assertEquals(logHash, row?.effectHashes?.get("Log"),
            "an effect is identified by its declaration, same as any other type")
    }

    @Test
    fun `the performed marker survives`() {
        // `*` says the effect is handled HERE rather than propagated, which
        // changes what the signature means to a caller.
        val type = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.RecordType(emptyMap()),
            effects = MezzeType.EffectRow.simple(emptySet(), performed = true),
        )
        assertTrue((roundTrip(type) as MezzeType.FunctionType).effects?.performed == true,
            "the `*` marker is part of the signature, not decoration")
    }

    @Test
    fun `a polymorphic effect row stays polymorphic`() {
        val type = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(setOf("Log"), rowVar = RowVar.userOpen()),
        )
        assertNotNull((roundTrip(type) as MezzeType.FunctionType).effects?.rowVar,
            "`<Log, ...>` permits further effects; `<Log>` does not")
    }

    @Test
    fun `a pure function has no effect row after a round trip`() {
        val type = MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType)
        assertNull((roundTrip(type) as MezzeType.FunctionType).effects)
    }

    // ---- constraints -------------------------------------------------------

    @Test
    fun `ability constraints round-trip in written order`() {
        val ordHash = Hash("d".repeat(64))
        val a = MezzeType.VarType(TypeVar.freshWithName("'a"))
        val type = MezzeType.FunctionType(
            paramType = a,
            returnType = MezzeType.StringType,
            abilityConstraints = listOf(
                MezzeType.FunctionAbilityConstraint("Ord", abilityHash = ordHash),
                MezzeType.FunctionAbilityConstraint("Show"),
            ),
        )
        val rebuilt = (roundTrip(type) as MezzeType.FunctionType).abilityConstraints
        assertEquals(listOf("Ord", "Show"), rebuilt.map { it.abilityName })
        assertEquals(ordHash, rebuilt.first().abilityHash,
            "which `Ord` is keyed by hash, not by the name matching")
    }

    // ---- the residual arrow ------------------------------------------------

    @Test
    fun `the residual arrow of a curried signature is the INNER one`() {
        // `{a} -> {b} -> Int <Log>`: a caller who applies only the first argument
        // holds a pure function. The effects belong to the inner arrow, and that
        // is the row "which effects does this decl perform" must read — which is
        // why the writer marks it instead of a query guessing.
        val inner = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("b" to MezzeType.IntType)),
            returnType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(setOf("Log")),
        )
        val outer = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("a" to MezzeType.IntType)),
            returnType = inner,
        )

        val rows = SignatureIndex.flatten(hash, outer)
        val residual = rows.nodes.filter { it.isResidual }
        assertEquals(1, residual.size, "exactly one arrow is the residual one")
        assertEquals(
            listOf("Log"),
            rows.effects.filter { it.nodeId == residual.single().nodeId }.map { it.effectName },
            "the residual arrow is the one carrying <Log>",
        )
        assertTrue(residual.single().nodeId != rows.nodes.first().nodeId,
            "for a curried signature the residual arrow is not the root")
    }

    @Test
    fun `a single-arrow signature is its own residual`() {
        val rows = SignatureIndex.flatten(
            hash,
            MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType,
                effects = MezzeType.EffectRow.simple(setOf("Log")))
        )
        assertEquals(0, rows.nodes.single { it.isResidual }.nodeId)
    }

    @Test
    fun `a non-function signature has no residual arrow`() {
        val rows = SignatureIndex.flatten(hash, MezzeType.IntType)
        assertTrue(rows.nodes.none { it.isResidual },
            "a constant performs no effects and has no arrow to carry them")
    }

    // ---- determinism -------------------------------------------------------

    @Test
    fun `field order in the source map does not change the rows`() {
        // The rows are the index key. If map iteration order leaked into them, the
        // same type would index two ways and a lookup would miss half the time.
        val xy = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType, "y" to MezzeType.StringType))
        val yx = MezzeType.RecordType(linkedMapOf("y" to MezzeType.StringType, "x" to MezzeType.IntType))

        assertEquals(
            SignatureIndex.flatten(hash, xy).nodes,
            SignatureIndex.flatten(hash, yx).nodes,
            "Mezze records are named, so field order carries no information",
        )
    }

    // ---- malformed rows fail LOUDLY ----------------------------------------

    @Test
    fun `rebuilding zero nodes fails rather than inventing a type`() {
        assertFailsWith<IllegalArgumentException> { SignatureIndex.rebuild(SignatureRows.EMPTY) }
    }

    @Test
    fun `an unreachable node fails rather than being dropped`() {
        // Dropping an orphan would yield a SMALLER type than the one indexed, and
        // a smaller type unifies more readily — the exact silent-weakening this
        // index exists to prevent.
        val good = SignatureIndex.flatten(hash, MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType))
        val withOrphan = good.copy(
            nodes = good.nodes + TypeNodeRow(
                ownerHash = hash, nodeId = 99, parentId = 42,
                slot = TypeSlot.ARG, ordinal = 0, kind = TypeNodeKind.INT,
            )
        )
        assertFailsWith<IllegalArgumentException> { SignatureIndex.rebuild(withOrphan) }
    }

    @Test
    fun `an arrow missing its return type fails rather than rebuilding half`() {
        val good = SignatureIndex.flatten(hash, MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType))
        val truncated = good.copy(nodes = good.nodes.filter { it.slot != TypeSlot.RETURN })
        assertFailsWith<IllegalStateException> { SignatureIndex.rebuild(truncated) }
    }

    // ---- the boundary: what is deliberately NOT indexed ---------------------

    /**
     * `ascriptionWall` and `caughtByAncestor` are solver and author-ascription
     * bookkeeping, not signature shape, and the full type stays available from the
     * declaration's own blob under the same hash. Stated as a test so the boundary
     * is explicit and a future reader does not mistake it for an oversight.
     */
    @Test
    fun `solver bookkeeping is not part of the index`() {
        val walled = MezzeType.VariantType(
            linkedMapOf("Red" to null), ascriptionWall = true
        )
        assertTrue(!(roundTrip(walled) as MezzeType.VariantType).ascriptionWall,
            "ascriptionWall is not indexed — read the decl's blob if you need it")

        val caught = MezzeType.FunctionType(
            paramType = MezzeType.IntType,
            returnType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(setOf("Log")).copy(caughtByAncestor = setOf("Exception")),
        )
        assertTrue((roundTrip(caught) as MezzeType.FunctionType).effects?.caughtByAncestor.isNullOrEmpty(),
            "caughtByAncestor is not indexed")
    }
}
