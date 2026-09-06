package com.mezze.language.typechecker.emit

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.*

/**
 * Per-call-site decoder resolution for `from_json` / `from_json_with`.
 *
 * A `from_json` call returns `Result 'a DecodeError`, and `'a` — the shape the
 * program decodes into — is inferred purely from USAGE (the fields the code
 * reads off the decoded value). That target NEVER lands on the call node or in
 * the solve substitution: it must stay a bare var there, or the `Decode` ability
 * check rejects it and codegen reads a record where the input is a string. So
 * two `from_json` calls of DIFFERENT shapes in one scope both carry `Result 'a`,
 * and codegen — which resolves the inner `.decode` dictionary from the call's
 * concrete types — cannot tell their decoders apart and wires the SECOND to the
 * FIRST's decoder (crash: `Field not found: decode`).
 *
 * This pass recovers each call's concrete target the SAME way hover does —
 * structurally, from the field accesses on the callback binder the decoded value
 * flows into — WITHOUT touching the solve. It matches that target against the
 * registered auto-derived codec impls and records `callLoc -> decode op hash`,
 * which the compiler consults to wire the right decoder per call site.
 */
object DecodeTargetResolver {

    private val FROM_JSON = setOf("from_json", "from_json_with")

    data class Result(val module: TypedModule, val decodeOpBySite: Map<Loc, Hash>)

    /**
     * Resolve each `from_json` call's decoder (by usage-inferred target), then
     * (1) STAMP the call's instantiation with the decoder's AbilityImplRef so two
     * structurally-identical decode callbacks hash distinctly and are not deduped
     * onto one decoder, and (2) return `decodeOpBySite` for the compiler's dict
     * wiring.
     */
    fun process(module: TypedModule): Result {
        val decodeImpls = module.abilityImpls.filter { it.operations.containsKey("decode") && it.targetTypes.size == 3 }
        if (decodeImpls.isEmpty()) return Result(module, emptyMap())

        val opBySite = HashMap<Loc, Hash>()
        val refBySite = HashMap<Loc, AbilityImplRef>()
        for (decl in module.decls) {
            if (decl is TypedLetDecl) resolveDecl(decl, decodeImpls, opBySite, refBySite)
        }
        if (opBySite.isEmpty()) return Result(module, emptyMap())
        val stamped = module.copy(decls = module.decls.map { d ->
            if (d is TypedLetDecl) d.copy(value = stampExpr(d.value, refBySite)) else d
        })
        return Result(stamped, opBySite)
    }

    /**
     * Cross-module `from_json` wrapper wiring.
     *
     * A generic wrapper like `decw : … with FromJson 'a Str -> Result 'a E`
     * lives in module A but is called with a concrete target from module B. The
     * `from_json` call inside decw's body is compiled as part of B's compilation
     * (decw is an imported module), yet [process] only walked B's OWN decls — so
     * decw's from_json call is never mapped to B's derived decoder and degrades to
     * a raw field read (runtime crash).
     *
     * Walk the IMPORTED modules' decls with B's derived [decodeImpls] and return
     * the extra `callLoc -> decode op hash` entries. Same resolution as [process]
     * (usage/constraint reconstruction + the single-decoder fast path); no
     * stamping needed — the compiler consults the returned map by call Loc when it
     * compiles the imported bodies. Only fires when B actually derived a decoder
     * (`decodeImpls` non-empty), so codec-free imports are untouched.
     */
    fun processImportedCalls(
        importedModules: List<TypedModule>,
        decodeImpls: List<TypedAbilityImpl>,
    ): Map<Loc, Hash> {
        if (decodeImpls.isEmpty() || importedModules.isEmpty()) return emptyMap()
        val opBySite = HashMap<Loc, Hash>()
        val refSink = HashMap<Loc, AbilityImplRef>()
        for (m in importedModules) {
            for (decl in m.decls) {
                if (decl is TypedLetDecl) resolveDecl(decl, decodeImpls, opBySite, refSink)
            }
        }
        return opBySite
    }

    private fun stampExpr(e: TypedExpr, refBySite: Map<Loc, AbilityImplRef>): TypedExpr {
        val rebuilt = withChildren(e) { stampExpr(it, refBySite) }
        if (rebuilt is TypedCall && rebuilt.function.let { it is TypedFieldAccess && it.field in FROM_JSON }) {
            refBySite[rebuilt.loc]?.let { ref ->
                val inst = rebuilt.instantiation ?: TypeInstantiation()
                return rebuilt.copy(instantiation = inst.copy(abilityImpls = inst.abilityImpls + ref))
            }
        }
        return rebuilt
    }

    /**
     * Within one decl: each `from_json` call feeds exactly one downstream
     * callback that reads the decoded value's fields. Collect both in the same
     * pre-order walk and pair by position — decode chains are linear, so the Nth
     * `from_json` corresponds to the Nth value-reading callback. Reconstruct each
     * callback binder's record (the target) and map the call to the matching
     * derived decoder.
     */
    private fun resolveDecl(
        decl: TypedLetDecl,
        decodeImpls: List<TypedAbilityImpl>,
        out: MutableMap<Loc, Hash>,
        refOut: MutableMap<Loc, AbilityImplRef>,
    ) {
        val root = decl.value
        // from_json call sites, in pre-order.
        val fromJsonLocs = ArrayList<Loc>()
        // A concrete decode target per call, from usage reconstruction — its leaf
        // types may be `?` and it does NOT see through list elements.
        val reconTargets = ArrayList<MezzeType>()
        run {
            fun go(e: TypedExpr) {
                if (e is TypedCall && e.function.let { it is TypedFieldAccess && it.field in FROM_JSON }) fromJsonLocs.add(e.loc)
                if (e is TypedLambda && e.params.isNotEmpty())
                    reconstructBinder(e.body, e.params.first().name, 0)?.let { reconTargets.add(it) }
                for (c in children(e)) go(c)
            }
            go(root)
        }
        if (fromJsonLocs.isEmpty()) return

        // Fast path: exactly one derived decoder in scope — every from_json here
        // decodes into it. No reconstruction needed, and it is immune to access
        // patterns reconstruction can't see through (`list.first {}`, `match`).
        if (decodeImpls.size == 1) {
            val impl = decodeImpls[0]
            val opHash = Hash.computeOpHash(impl.hash, "decode")
            val ref = AbilityImplRef(impl.abilityHash, impl.hash, impl.targetTypes[1])
            for (loc in fromJsonLocs) { out[loc] = opHash; refOut[loc] = ref }
            return
        }

        // AUTHORITATIVE targets: the FromJson/Decode constraints the typechecker
        // resolved onto this decl's function type. These are FULLY concrete —
        // list elements and all — unlike usage reconstruction. Present only when
        // the decode surfaces on the signature (not a fully-discharged do-block
        // local), so we fall back to recon for the rest.
        val constraintTargets = ArrayList<MezzeType>()
        (decl.scheme.type as? MezzeType.FunctionType)?.abilityConstraints
            ?.filter { it.abilityName == "FromJson" || it.abilityName == "Decode" }
            ?.forEach { c -> c.typeArgs.firstOrNull()?.let { constraintTargets.add(it) } }

        // Prefer constraint targets when they cover every call; else recon.
        val targets: List<MezzeType> = when {
            constraintTargets.size == fromJsonLocs.size -> constraintTargets
            reconTargets.size == fromJsonLocs.size -> reconTargets
            else -> return  // ambiguous count — don't risk a wrong pairing
        }

        for (i in fromJsonLocs.indices) {
            val matches = decodeImpls.filter { shapeMatches(it.targetTypes[1], targets[i]) }
            val impl = (if (matches.size == 1) matches[0]
                        else matches.firstOrNull { structural(it.targetTypes[1]) == structural(targets[i]) }) ?: continue
            out[fromJsonLocs[i]] = Hash.computeOpHash(impl.hash, "decode")
            refOut[fromJsonLocs[i]] = AbilityImplRef(impl.abilityHash, impl.hash, impl.targetTypes[1])
        }
    }

    /** Reconstruct a CLOSED record from field accesses on the var named [binder]. */
    private fun reconstructBinder(root: TypedExpr, binder: String, depth: Int): MezzeType? {
        if (depth > 8) return null
        // A method call `x.m {…}` is a TypedCall whose FUNCTION is a
        // TypedFieldAccess `x.m`. Those are not data-field reads and must not
        // become record fields (`val.from_json {}` would spuriously add a
        // `from_json` field). Collect the field-access nodes that sit in a call's
        // function position, and exclude them.
        val methodAccesses = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TypedFieldAccess, Boolean>())
        run {
            fun mark(e: TypedExpr) {
                if (e is TypedCall) { val fn = e.function; if (fn is TypedFieldAccess) methodAccesses.add(fn) }
                for (c in children(e)) mark(c)
            }
            mark(root)
        }
        // Collect DATA field accesses whose receiver is a TypedVar named `binder`.
        val fields = LinkedHashMap<String, MezzeType>()
        fun scan(e: TypedExpr) {
            if (e is TypedFieldAccess && e !in methodAccesses) {
                val r = e.record
                if (r is TypedVar && r.name == binder) {
                    fields.putIfAbsent(e.field, deepen(root, e, depth))
                }
            }
            for (c in children(e)) scan(c)
        }
        scan(root)
        return if (fields.isEmpty()) null else MezzeType.RecordType(fields, null)
    }

    // A field access node may itself be a receiver of deeper accesses (a.b.c);
    // reconstruct the nested record when this node's own type is still a var.
    private fun deepen(root: TypedExpr, node: TypedFieldAccess, depth: Int): MezzeType {
        val ft = node.type
        if (ft !is MezzeType.VarType) return ft
        val nested = LinkedHashMap<String, MezzeType>()
        fun scan(e: TypedExpr) {
            if (e is TypedFieldAccess && e.record === node) nested.putIfAbsent(e.field, deepen(root, e, depth + 1))
            for (c in children(e)) scan(c)
        }
        if (depth < 8) scan(root)
        return if (nested.isEmpty()) ft else MezzeType.RecordType(nested, null)
    }

    // Structural match where a VarType (`?`) on EITHER side is a wildcard. The
    // reconstructed target's leaf types can be unresolved; field names + nesting
    // still pin the decoder.
    private fun shapeMatches(impl: MezzeType, target: MezzeType): Boolean {
        if (impl is MezzeType.VarType || target is MezzeType.VarType) return true
        return when {
            impl is MezzeType.RecordType && target is MezzeType.RecordType -> {
                if (impl.fields.keys != target.fields.keys) false
                else impl.fields.all { (k, v) -> shapeMatches(v, target.fields.getValue(k)) }
            }
            impl is MezzeType.SeqType && target is MezzeType.SeqType -> shapeMatches(impl.elementType, target.elementType)
            impl is MezzeType.ListType && target is MezzeType.ListType -> shapeMatches(impl.elementType, target.elementType)
            impl is MezzeType.TypeApplication && target is MezzeType.TypeApplication ->
                impl.constructor == target.constructor && impl.args.size == target.args.size &&
                    impl.args.indices.all { shapeMatches(impl.args[it], target.args[it]) }
            else -> structural(impl) == structural(target)
        }
    }

    private fun structural(t: MezzeType): String = when (t) {
        is MezzeType.RecordType ->
            "{" + t.fields.toSortedMap().entries.joinToString(",") { "${it.key}:${structural(it.value)}" } + "}"
        is MezzeType.SeqType -> "[" + structural(t.elementType) + "]"
        is MezzeType.ListType -> "[" + structural(t.elementType) + "]"
        is MezzeType.TypeApplication -> t.constructor + "(" + t.args.joinToString(",") { structural(it) } + ")"
        is MezzeType.IntType -> "Int"
        is MezzeType.FloatType -> "Float"
        is MezzeType.StringType -> "Str"
        is MezzeType.BoolType -> "Bool"
        is MezzeType.VarType -> "?"
        else -> t.javaClass.simpleName
    }

    private fun withChildren(e: TypedExpr, g: (TypedExpr) -> TypedExpr): TypedExpr = when (e) {
        is TypedCall -> e.copy(function = g(e.function), arg = g(e.arg))
        is TypedLambda -> e.copy(body = g(e.body))
        is TypedFieldAccess -> e.copy(record = g(e.record))
        is TypedRecordUpdate -> e.copy(record = g(e.record), updates = e.updates.map { mapEntry(it, g) })
        is TypedRecord -> e.copy(entries = e.entries.map { mapEntry(it, g) })
        is TypedVariantConstruct -> e.payload?.let { p -> e.copy(payload = g(p)) } ?: e
        is TypedMatch -> e.copy(scrutinee = g(e.scrutinee), cases = e.cases.map { it.copy(guard = it.guard?.let(g), body = g(it.body)) })
        is TypedIf -> e.copy(condition = g(e.condition), thenBranch = g(e.thenBranch), elseBranch = g(e.elseBranch))
        is TypedBlock -> e.copy(statements = e.statements.map { mapStmt(it, g) }, result = g(e.result))
        is TypedSeq -> e.copy(elements = e.elements.map { mapSeq(it, g) })
        is TypedPipe -> e.copy(left = g(e.left), right = g(e.right))
        is TypedPerform -> e.copy(body = g(e.body))
        else -> e
    }

    private fun mapEntry(entry: TypedRecordEntry, g: (TypedExpr) -> TypedExpr): TypedRecordEntry = when (entry) {
        is TypedRecordEntry.Field -> entry.copy(value = g(entry.value))
        is TypedRecordEntry.Spread -> entry.copy(expr = g(entry.expr))
    }
    private fun mapStmt(stmt: TypedStatement, g: (TypedExpr) -> TypedExpr): TypedStatement = when (stmt) {
        is TypedStatement.Let -> stmt.copy(value = g(stmt.value))
        is TypedStatement.Expr -> stmt.copy(expr = g(stmt.expr))
    }
    private fun mapSeq(el: TypedSeqElement, g: (TypedExpr) -> TypedExpr): TypedSeqElement = when (el) {
        is TypedSeqElement.Regular -> el.copy(expr = g(el.expr))
        is TypedSeqElement.Spread -> el.copy(expr = g(el.expr))
    }

    private fun children(e: TypedExpr): List<TypedExpr> = when (e) {
        is TypedCall -> listOf(e.function, e.arg)
        is TypedLambda -> listOf(e.body)
        is TypedFieldAccess -> listOf(e.record)
        is TypedRecordUpdate -> listOf(e.record) + e.updates.mapNotNull { entryExpr(it) }
        is TypedRecord -> e.entries.mapNotNull { entryExpr(it) }
        is TypedVariantConstruct -> listOfNotNull(e.payload)
        is TypedMatch -> listOf(e.scrutinee) + e.cases.flatMap { listOfNotNull(it.guard, it.body) }
        is TypedIf -> listOf(e.condition, e.thenBranch, e.elseBranch)
        is TypedBlock -> e.statements.mapNotNull { stmtExpr(it) } + e.result
        is TypedSeq -> e.elements.map { seqExpr(it) }
        is TypedPipe -> listOf(e.left, e.right)
        is TypedPerform -> listOf(e.body)
        else -> emptyList()
    }

    private fun entryExpr(entry: TypedRecordEntry): TypedExpr? = when (entry) {
        is TypedRecordEntry.Field -> entry.value
        is TypedRecordEntry.Spread -> entry.expr
    }
    private fun stmtExpr(stmt: TypedStatement): TypedExpr? = when (stmt) {
        is TypedStatement.Let -> stmt.value
        is TypedStatement.Expr -> stmt.expr
    }
    private fun seqExpr(el: TypedSeqElement): TypedExpr = when (el) {
        is TypedSeqElement.Regular -> el.expr
        is TypedSeqElement.Spread -> el.expr
    }
}
