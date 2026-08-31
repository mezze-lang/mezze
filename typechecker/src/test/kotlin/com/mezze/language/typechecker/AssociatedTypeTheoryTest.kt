package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedStatement
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * S6.2 (#367) — "one general theory" regression pins for ability associated
 * types (`having`), keyed on the INFERRED TYPE at a call site.
 *
 * ## The theory these tests lock (verified against HEAD, not asserted blind)
 *
 * An associated type is a PROJECTION resolved by three cooperating pieces, NONE
 * of which lets the associated-type *name* drive a unification or dispatch
 * decision:
 *
 *  1. IMPL SELECTION is structural under a HASH-KEYED lookup. `AbilityChecker`
 *     resolves the ability's decl content hash (`registry.resolveAbilityHash`)
 *     and unions the by-hash impl index with the name index (the name index only
 *     surfaces hashless builtins); the impl itself is then chosen by matching the
 *     receiver/arg TYPES against each impl's target annotations
 *     (`TypeAnnotationMatcher.typeMatchesAnnotation`) — never by an
 *     associated-type name.
 *  2. The chosen impl's `associatedTypeBindings` (`Map<String, annotation>`) is a
 *     PER-IMPL binding side-table. Its key (e.g. `"Config"`, `"inner"`) is a
 *     binding LABEL local to that impl — looked up by name because that is what
 *     an associated-type name IS (a slot label), exactly like a record field
 *     name. The looked-up annotation is resolved to a concrete type against the
 *     impl's own type-var bindings.
 *  3. That concrete type is then `unify`d INTO a FRESH TYPE VARIABLE that the
 *     constraint minted for this associated type at use-site
 *     (`FunctionAbilityConstraint.associatedTypeVars: Map<String, TypeVar>`). The
 *     value flowing through unification is a VarType (then its solved binding) —
 *     NOT a name read off a `TypeIdentity`.
 *
 * When two function types both carry ability constraints, `Unification` pairs
 * their binding maps by `(abilityName, abilityHash)` and then, within a matched
 * pair, lines up bindings by their associated-type name — pure map-key pairing
 * ("the Config slot on one side vs the Config slot on the other"); the TYPES are
 * what unify. The name never decides an identity/dispatch outcome.
 *
 * CONCLUSION: the S6.2 "make associated types hash-keyed on the hot path" rewrite
 * is a NON-ISSUE — the premise already holds. These tests pin the observable
 * behaviour so a regression that reintroduced a name-keyed identity decision (or
 * broke projection resolution) would flip a concrete inferred-type assertion.
 *
 * Sibling pins: [AssociatedTypeSpecializationTest] (Tryable / `having 'inner =
 * 'param`), [AbilityAssociatedTypeTest] (with-clause rendering + soundness),
 * [AbilityAssociatedTypesTest] (multi-assoc resolution to concrete types).
 */
class AssociatedTypeTheoryTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); typeChecker = TypeChecker()
    }

    /** Inferred type of a top-level `let` (or a `let` nested one level inside one). */
    private fun letType(source: String, letName: String): MezzeType? {
        val parsed = parser.parseParsedModule(source.trimIndent(), "test.mz")
        val result = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
        val typed = result.typedModule ?: return null
        for (decl in typed.decls) {
            if (decl is TypedLetDecl) {
                if (decl.name == letName) return decl.scheme.type
                findNestedLetType(decl.value, letName)?.let { return it }
            }
        }
        return null
    }

    private fun findNestedLetType(expr: com.mezze.language.typedast.TypedExpr, name: String): MezzeType? =
        when (expr) {
            is TypedBlock -> {
                var found: MezzeType? = null
                for (stmt in expr.statements) {
                    when (stmt) {
                        is TypedStatement.Let -> {
                            if (stmt.name == name) { found = stmt.type }
                            else found = findNestedLetType(stmt.value, name)
                        }
                        is TypedStatement.Expr -> found = findNestedLetType(stmt.expr, name)
                    }
                    if (found != null) break
                }
                found ?: findNestedLetType(expr.result, name)
            }
            is com.mezze.language.typedast.TypedLambda -> findNestedLetType(expr.body, name)
            is com.mezze.language.typedast.TypedPerform -> findNestedLetType(expr.body, name)
            is com.mezze.language.typedast.TypedIf ->
                findNestedLetType(expr.condition, name)
                    ?: findNestedLetType(expr.thenBranch, name)
                    ?: findNestedLetType(expr.elseBranch, name)
            else -> null
        }

    private fun render(t: MezzeType) = prettyPrintTypeWithAliases(t, emptyMap())

    // ── 1. `having` OMITTED at the annotation: the assoc type is inferred ──
    //
    // The ability call carries a fresh assoc var; the impl's binding
    // (`having 'out = Str`) is unified into it. No `having` is written anywhere
    // at the USE site — the projection is fully inferred from the selected impl.

    @Test
    fun `omitted having is inferred from the impl binding`() {
        val t = letType(
            """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            let v = (5).produce {}
            """,
            "v",
        )
        assertNotNull(t, "v should typecheck with `having` inferred")
        val r = render(t!!)
        assertTrue(r.contains("Str"), "'out inferred from the impl must be Str, got: $r")
        assertTrue(!r.contains("'"), "no unbound var should remain, got: $r")
    }

    // ── 2. Projection through FUNCTION INDIRECTION ──
    //
    // A generic helper `run` takes an ability-constrained param and returns the
    // associated type. Applying it to a concrete receiver must specialize the
    // associated type through the helper's signature — the assoc var threads
    // across the call boundary and is pinned by the argument's impl.

    @Test
    fun `associated type specializes through a helper function`() {
        val t = letType(
            """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            let run = { x } -> x.produce {}
            let v = run { x = 5 }
            """,
            "v",
        )
        assertNotNull(t, "v (through helper) should typecheck")
        val r = render(t!!)
        assertTrue(r.contains("Str"),
            "the helper's 'out must specialize to Str at the concrete call, got: $r")
        assertTrue(!r.contains("'"), "no unbound var should survive the concrete call, got: $r")
    }

    // ── 3. BOTH associated types of an ability, packed into one return ──
    //
    // A single op returns `Result 'to 'error`, using BOTH declared associated
    // types at once. On a concrete receiver each must resolve independently to
    // its impl binding: `Result Int Bool`.

    @Test
    fun `a method using both associated types resolves each independently`() {
        val t = letType(
            """
            ability Convert for 'from having 'to, 'error where {
              tryConvert: { it: 'from } -> Result 'to 'error
            }
            impl Convert for Str having 'to = Int, 'error = Bool where {
              tryConvert = { it } -> Ok { val = 42 }
            }
            let v = ("hi").tryConvert {}
            """,
            "v",
        )
        assertNotNull(t, "v should typecheck with both assoc types resolved")
        val r = render(t!!)
        assertTrue(r.contains("Result"), "return should stay a Result, got: $r")
        assertTrue(r.contains("Int"), "'to must resolve to Int, got: $r")
        assertTrue(r.contains("Bool"), "'error must resolve to Bool, got: $r")
        assertTrue(!r.contains("'"), "both assoc slots must be concrete, got: $r")
    }

    // ── 4. Codec-style explicit NAMED-TYPE binding (`having Config = HttpConfig`) ──
    //
    // The canonical S6.2 shape: an impl binds an associated type to a concrete
    // NAMED type (not to one of the impl's own params). The projection must
    // resolve to that bound named type at the call site — proving the binding is
    // looked up by its per-impl label and its bound TYPE (HttpConfig) is what
    // flows out, not the label.

    @Test
    fun `explicit named-type having binding resolves the projection to the bound type`() {
        val t = letType(
            """
            type Json = { raw: Str }
            type HttpConfig = { url: Str }
            ability Codec for 'fmt having 'config where {
              configFor: { it: 'fmt } -> 'config
            }
            impl Codec for Json having 'config = HttpConfig where {
              configFor = { it } -> { url = "x" }
            }
            let mk : {} -> Json = {} -> { raw = "{}" }
            let v = (mk {}).configFor {}
            """,
            "v",
        )
        assertNotNull(t, "v should typecheck; Codec projection resolves")
        val r = render(t!!)
        assertTrue(r.contains("HttpConfig") || r.contains("url"),
            "'config must resolve to the bound HttpConfig (or its structural form), got: $r")
        assertTrue(!r.contains("'"), "the projection must be fully concrete, got: $r")
    }

    // ── 5. Two impls of ONE ability: receiver TYPE (not name) picks the binding ──
    //
    // Two impls of the same ability bind the associated type differently. The
    // impl — and therefore which `having` binding is projected — is chosen by the
    // receiver's TYPE. If the name (rather than the structural receiver) drove
    // selection, both call sites would collapse to one binding; the distinct
    // inferred types below prove structural selection.

    @Test
    fun `receiver type selects among impls with different associated bindings`() {
        val src = """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            impl Producer for Bool having 'out = Int where {
              produce = { it } -> 0
            }
            let onInt = (5).produce {}
            let onBool = (True).produce {}
        """
        val ti = letType(src, "onInt")
        val tb = letType(src, "onBool")
        assertNotNull(ti, "onInt should typecheck")
        assertNotNull(tb, "onBool should typecheck")
        assertTrue(render(ti!!).contains("Str"),
            "Int receiver selects `having 'out = Str`, got: ${render(ti)}")
        assertTrue(render(tb!!).contains("Int"),
            "Bool receiver selects `having 'out = Int`, got: ${render(tb)}")
    }
}
