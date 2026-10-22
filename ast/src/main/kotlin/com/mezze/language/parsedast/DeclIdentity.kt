package com.mezze.language.parsedast

import com.mezze.language.ast.hashing.HashTags
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.typedast.Hash

/** The namespaces a written name is looked up in. */
enum class RefKind { VALUE, TYPE, CONSTRUCTOR, ABILITY, EFFECT, PERFORMER }

/** What a name resolves to. */
sealed interface Referent {
    /** A declaration that already has its identity. */
    data class Known(val hash: Hash) : Referent
    /** A declaration being stamped in the same pass — [node] in its [IdentityGroup]. */
    data class Pending(val node: Int) : Referent
}

/**
 * What a module sees from OUTSIDE itself — its imports and the prelude — as what
 * each name resolves to.
 */
interface IdentityScope {
    /**
     * What [name] means, or null when it means no declaration: a builtin, a
     * native, or nothing at all (the typechecker reports that).
     */
    fun resolve(kind: RefKind, qualifier: List<String>?, name: String): Referent?

    /** Every outside declaration that can answer the member or effect operation [name]. */
    fun providers(name: String): Set<Referent>

    companion object {
        /** A module that imports nothing and has no prelude. */
        val NONE: IdentityScope = object : IdentityScope {
            override fun resolve(kind: RefKind, qualifier: List<String>?, name: String): Referent? = null
            override fun providers(name: String): Set<Referent> = emptySet()
        }
    }
}

/**
 * Modules stamped together, because they import each other.
 *
 * A declaration's identity includes the identities of what it uses, so modules
 * that import one another cannot be stamped one at a time — whichever went first
 * would have to hash the other's names unresolved, and a later restamp would
 * disagree with it. (The standard library's core is one such group: `std::int`,
 * `std::float`, `std::effects::random`, … import each other.) Their declarations
 * form one graph: each resolves names through its OWN module's scope, and a
 * reference to a declaration of another member is a node like any local one.
 *
 * Every declaration is node `offset(member) + index`.
 */
class IdentityGroup(val modules: List<ParsedModule>) {
    private val offsets = IntArray(modules.size).also { o ->
        var at = 0
        modules.forEachIndexed { m, mod -> o[m] = at; at += mod.decls.size }
    }
    private val size = modules.sumOf { it.decls.size }
    private val locals = modules.mapIndexed { m, mod -> LocalNames(mod.decls, offsets[m]) }

    /** The node of a member's own declaration named [name], if it has one. */
    fun node(member: Int, kind: RefKind, name: String): Int? = locals[member].byName[kind to name]

    /** The node of a member's declaration at [index] in its module. */
    fun node(member: Int, index: Int): Int = offsets[member] + index

    /**
     * Stamp every declaration of every member. [scopes] and [modulePaths] are per
     * member; a module path salts NOMINAL types only — two modules that each
     * declare `newtype Id = Str` declare two types.
     */
    fun stamp(scopes: List<IdentityScope>, modulePaths: List<String>): List<ParsedModule> {
        require(scopes.size == modules.size && modulePaths.size == modules.size)
        val owner = IntArray(size)
        modules.forEachIndexed { m, mod -> for (i in mod.decls.indices) owner[offsets[m] + i] = m }
        fun declOf(node: Int): ParsedDecl = modules[owner[node]].decls[node - offsets[owner[node]]]
        fun hash(node: Int, ref: (Int) -> Ref): Hash {
            val m = owner[node]
            val b = MezzeHashBuilder()
            Walker(b, locals[m], scopes[m], ref, modulePaths[m]).decl(declOf(node))
            return b.buildHash()
        }

        // Pass 1: which declarations of the group each declaration uses.
        val uses = (0 until size).map { n ->
            val found = sortedSetOf<Int>()
            hash(n) { j -> found.add(j); Ref.InGroup }
            found
        }

        val identity = arrayOfNulls<Hash>(size)
        for (group in recursiveGroups(size, uses)) {
            val members = group.toSet()
            val partials = group.associateWith { n ->
                hash(n) { j ->
                    if (j in members) Ref.InGroup
                    else Ref.Known(identity[j] ?: error("internal: declaration $j was used before it had an identity"))
                }
            }
            val groupHash = MezzeHashBuilder().apply {
                append(byteArrayOf(HashTags.RECURSIVE_GROUP))
                appendInt(partials.size)
                for (p in partials.values.map { it.hex }.sorted()) append(p)
            }.buildHash()
            for ((n, partial) in partials) {
                identity[n] = MezzeHashBuilder().apply {
                    append(byteArrayOf(HashTags.GROUP_MEMBER))
                    append(groupHash.hex)
                    append(partial.hex)
                }.buildHash()
            }
        }

        // A type declaration's effect rows record what their names mean HERE, so
        // the declaration means the same thing wherever it is expanded.
        fun effectIn(m: Int, written: String): Hash? {
            val parts = written.split("::")
            val qualifier = parts.dropLast(1).takeIf { it.isNotEmpty() }
            val name = parts.last()
            val own = if (qualifier == null) locals[m].byName[RefKind.EFFECT to name] else null
            return when (val r = if (own != null) Referent.Pending(own) else scopes[m].resolve(RefKind.EFFECT, qualifier, name)) {
                is Referent.Known -> r.hash
                is Referent.Pending -> identity[r.node]
                null -> null
            }
        }
        fun stampRows(m: Int, t: ParsedTypeAnnotation): ParsedTypeAnnotation = t.transformDeep { node ->
            val row = (node as? ParsedFunctionType)?.effects ?: return@transformDeep node
            node.copy(effects = row.copy(resolved = row.allEffectNames.mapNotNull { n -> effectIn(m, n)?.let { n to it } }.toMap()))
        }

        return modules.mapIndexed { m, mod ->
            mod.copy(decls = mod.decls.mapIndexed { i, d ->
                val withRows = if (d is ParsedTypeDecl) d.copy(definition = when (val def = d.definition) {
                    is ParsedTypeAliasDef -> def.copy(aliasedType = stampRows(m, def.aliasedType))
                    is ParsedNewtypeDef -> def.copy(wrappedType = stampRows(m, def.wrappedType))
                    is ParsedRecordDef -> def.copy(fields = def.fields.map { it.copy(type = stampRows(m, it.type)) })
                    is ParsedVariantDef -> def.copy(constructors = def.constructors.map { c -> c.copy(dataType = c.dataType?.let { stampRows(m, it) }) })
                }) else d
                withIdentity(withRows, identity[offsets[m] + i])
            })
        }
    }

    private companion object {
        fun withIdentity(decl: ParsedDecl, id: Hash?): ParsedDecl = when (decl) {
            is ParsedLetDecl -> decl.copy(contentHash = id)
            is ParsedTypeDecl -> decl.copy(contentHash = id)
            is ParsedAbilityDecl -> decl.copy(contentHash = id)
            is ParsedEffectDecl -> decl.copy(contentHash = id)
            is ParsedPerformerDecl -> decl.copy(contentHash = id)
            is ParsedImplAbilityDecl -> decl.copy(contentHash = id)
            is ParsedTopLevelExpr -> decl.copy(contentHash = id)
            is ParsedUseDecl, is ParsedPubUseDecl -> decl
        }

        /** Strongly connected components, every group after the groups it uses.
         *  Tarjan, iterative, so a long chain cannot overflow the stack. */
        fun recursiveGroups(n: Int, uses: List<Set<Int>>): List<List<Int>> {
            val index = IntArray(n) { -1 }
            val low = IntArray(n)
            val onStack = BooleanArray(n)
            val stack = ArrayDeque<Int>()
            val groups = ArrayList<List<Int>>()
            var next = 0
            val succ = uses.map { it.toIntArray() }

            for (root in 0 until n) {
                if (index[root] != -1) continue
                // Frames of (node, position in its successor list).
                val frames = ArrayDeque<IntArray>()
                fun enter(v: Int) {
                    index[v] = next; low[v] = next; next++
                    stack.addLast(v); onStack[v] = true
                    frames.addLast(intArrayOf(v, 0))
                }
                enter(root)
                while (frames.isNotEmpty()) {
                    val frame = frames.last()
                    val v = frame[0]
                    if (frame[1] < succ[v].size) {
                        val w = succ[v][frame[1]++]
                        if (index[w] == -1) enter(w)
                        else if (onStack[w]) low[v] = minOf(low[v], index[w])
                        continue
                    }
                    frames.removeLast()
                    frames.lastOrNull()?.let { parent -> low[parent[0]] = minOf(low[parent[0]], low[v]) }
                    if (low[v] == index[v]) {
                        val group = ArrayList<Int>()
                        do {
                            val w = stack.removeLast()
                            onStack[w] = false
                            group.add(w)
                        } while (w != v)
                        groups.add(group)
                    }
                }
            }
            return groups
        }
    }
}

/**
 * A declaration's identity: what it IS, not how it reads.
 *
 * Its structure, hashed with every free name replaced by the identity of the
 * declaration that name resolves to. So two declarations share an identity
 * exactly when they are the same text AND every name in them means the same
 * thing — and a change to anything a declaration uses changes the declaration.
 *
 * The identity used to be the text alone, a reference hashed as the name written.
 * `pub let caller = {} -> helper {}` then had ONE identity in every module that
 * wrote it, whatever `helper` meant there; compiled functions are keyed by
 * identity, so one module's `caller` ran another's code.
 *
 * Names stay hashed AS WRITTEN alongside their targets. Mezze is call-by-name
 * (`f { x = .. }`), so parameter, field and constructor names are part of a
 * declaration's interface, and renaming one must change the identity — the
 * opposite of Unison, where positional arguments make bound names scaffolding.
 * Bound locals and type variables are hashed as written too, though renaming
 * them is not an interface change: deciding which names matter is exactly what a
 * normalizer would have to get right every time, and getting it wrong silently
 * collides two declarations. The cost of including them is a cache miss on a
 * rename. (Decision: 2026-08-25.) Resolution is added on top; nothing removed.
 *
 * Mutually recursive declarations cannot each include the other's identity, so
 * a recursive group is hashed together: each member with references inside the
 * group marked rather than resolved, the group as the sorted member hashes, and
 * each member as its place in that group (Unison's scheme). Source order never
 * reaches a hash. The same holds across modules that import each other — see
 * [IdentityGroup].
 */
object DeclIdentity {

    /** Stamp one module that imports no module importing it back. */
    fun stamp(module: ParsedModule, scope: IdentityScope, modulePath: String): ParsedModule =
        IdentityGroup(listOf(module)).stamp(listOf(scope), listOf(modulePath)).single()

    /**
     * What a written value name resolves to in a STAMPED [module] seeing [scope] —
     * the answer its identity was computed from. The typechecker binds names on
     * its own; comparing the two is how a drift between them is caught instead of
     * silently leaving an identity that does not cover what a declaration calls.
     */
    class NameResolver(private val module: ParsedModule, private val scope: IdentityScope) {
        private val local = LocalNames(module.decls, 0)
        private val identities = module.decls.map { identityOfDecl(it) }

        /** The identity [name] means as a free identifier, or null when it means no declaration. */
        fun identifier(qualifier: List<String>?, name: String): Hash? {
            for (kind in LOOKUP_ORDER) {
                if (qualifier == null) local.byName[kind to name]?.let { node ->
                    return identities[node] ?: error("internal: ${module.path} is not stamped")
                }
                when (val r = scope.resolve(kind, qualifier, name)) {
                    is Referent.Known -> return r.hash
                    is Referent.Pending -> error("internal: a name resolver over a module that is still being stamped")
                    null -> {}
                }
            }
            return null
        }
    }

    /**
     * Stamp a declaration the COMPILER writes — a derived impl, a builtin type —
     * as if it stood alone in a module seeing [scope]. Its names resolve the way
     * the module that receives it would resolve them.
     */
    fun <D : ParsedDecl> stampSynthetic(decl: D, scope: IdentityScope, modulePath: String): D {
        val stamped = stamp(ParsedModule(modulePath, listOf(decl)), scope, modulePath).decls.single()
        @Suppress("UNCHECKED_CAST")
        return stamped as D
    }
}

// ----------------------------------------------------------------------------
// A module's own names
// ----------------------------------------------------------------------------

/** What each name means INSIDE a module, as nodes of its group. */
private class LocalNames(decls: List<ParsedDecl>, offset: Int) {
    val byName = HashMap<Pair<RefKind, String>, Int>()
    val providers = HashMap<String, MutableSet<Int>>()

    // First declaration wins; a duplicate is the typechecker's to report.
    private fun name(kind: RefKind, name: String, n: Int) { byName.putIfAbsent(kind to name, n) }
    private fun provide(member: String, n: Int) { providers.getOrPut(member) { sortedSetOf() }.add(n) }

    init {
        decls.forEachIndexed { i, d ->
            val n = offset + i
            when (d) {
                is ParsedLetDecl -> d.pattern.extractBindings().forEach { name(RefKind.VALUE, it, n) }
                is ParsedTypeDecl -> {
                    name(RefKind.TYPE, d.name, n)
                    when (val def = d.definition) {
                        is ParsedVariantDef -> def.constructors.forEach { name(RefKind.CONSTRUCTOR, it.name, n) }
                        is ParsedNewtypeDef -> name(RefKind.CONSTRUCTOR, def.constructor, n)
                        is ParsedRecordDef, is ParsedTypeAliasDef -> {}
                    }
                }
                is ParsedAbilityDecl -> {
                    name(RefKind.ABILITY, d.name, n)
                    d.operations.forEach { provide(it.name, n) }
                }
                is ParsedEffectDecl -> {
                    name(RefKind.EFFECT, d.name, n)
                    d.operations.forEach { provide(it.name, n) }
                }
                is ParsedPerformerDecl -> name(RefKind.PERFORMER, d.performerName, n)
                is ParsedImplAbilityDecl -> d.operations.forEach { provide(it.name, n) }
                is ParsedUseDecl, is ParsedPubUseDecl, is ParsedTopLevelExpr -> {}
            }
        }
    }
}

/** How a reference to a node of the group hashes, decided by the pass. */
private sealed interface Ref {
    data class Known(val hash: Hash) : Ref
    /** In the recursive group being hashed: its content is in the group hash instead. */
    data object InGroup : Ref
}

// ----------------------------------------------------------------------------
// Hashing one declaration
// ----------------------------------------------------------------------------

private class Walker(
    private val b: MezzeHashBuilder,
    private val local: LocalNames,
    private val scope: IdentityScope,
    private val nodeRef: (Int) -> Ref,
    private val modulePath: String,
) {
    /** Type-level names a declaration binds itself: parameters, associated types. */
    private var typeLocals: Set<String> = emptySet()

    fun decl(decl: ParsedDecl) {
        when (decl) {
            is ParsedLetDecl -> {
                b.append(byteArrayOf(HashTags.LET_BINDING))
                b.appendBoolean(decl.visibility == Visibility.PUBLIC)
                optional(decl.typeAnnotation) { type(it) }
                pattern(decl.pattern)
                expr(decl.value, emptySet())
            }
            is ParsedTypeDecl -> {
                b.append(byteArrayOf(HashTags.TYPE_DECLARATION))
                b.append(decl.name)
                names(decl.typeParams)
                typeLocals = decl.typeParams.toSet()
                typeDef(decl.definition)
            }
            is ParsedAbilityDecl -> {
                b.append(byteArrayOf(HashTags.ABILITY_DECLARATION))
                b.append(decl.name)
                names(decl.typeParams)
                optional(decl.receiverTypeParam) { b.append(it) }
                names(decl.associatedTypes)
                typeLocals = (decl.typeParams + listOfNotNull(decl.receiverTypeParam) + decl.associatedTypes).toSet()
                b.appendInt(decl.operations.size)
                for (op in decl.operations.sortedBy { it.name }) {
                    b.append(op.name)
                    type(op.paramType)
                    type(op.returnType)
                    optional(op.effects) { row(it) }
                    constraints(op.abilityConstraints)
                }
            }
            is ParsedEffectDecl -> {
                b.append(byteArrayOf(HashTags.EFFECT_DECLARATION))
                b.append(decl.name)
                names(decl.associatedTypes)
                typeLocals = decl.associatedTypes.toSet()
                b.appendInt(decl.operations.size)
                for (op in decl.operations.sortedBy { it.name }) {
                    b.append(op.name)
                    type(op.paramType)
                    type(op.returnType)
                    b.appendInt(op.effects.size)
                    for (e in op.effects.sorted()) qualifiedRef(RefKind.EFFECT, e)
                    constraints(op.abilityConstraints)
                }
            }
            is ParsedPerformerDecl -> {
                b.append(byteArrayOf(HashTags.IMPL_DECLARATION))
                // `impl Show for X` can parse in performer form; what it implements
                // is an effect or an ability, whichever the name means here.
                val target = decl.effect.qualifier?.segments
                val kind = listOf(RefKind.EFFECT, RefKind.ABILITY).firstOrNull { k ->
                    (target == null && local.byName.containsKey(k to decl.effect.localName)) ||
                        scope.resolve(k, target, decl.effect.localName) != null
                } ?: RefKind.EFFECT
                ref(kind, target, decl.effect.localName)
                b.append(decl.performerName)
                b.appendInt(decl.params.size)
                for (p in decl.params) { b.append(p.name); type(p.type) }
                bindings(decl.associatedTypeBindings)
                // A performer's parameters are in scope in its operations.
                val bound = decl.params.map { it.name }.toSet()
                b.appendInt(decl.operations.size)
                for (op in decl.operations.sortedBy { it.name }) {
                    b.append(op.name)
                    optional(op.paramType) { type(it) }
                    optional(op.returnType) { type(it) }
                    optional(op.effects) { row(it) }
                    constraints(op.abilityConstraints)
                    expr(op.implementation, bound)
                }
            }
            is ParsedImplAbilityDecl -> {
                b.append(byteArrayOf(HashTags.IMPL_ABILITY_DECLARATION))
                when (val target = decl.target) {
                    is ParsedImplTarget.Named -> {
                        b.appendBoolean(false)
                        ref(RefKind.ABILITY, target.ability.qualifier?.segments, target.ability.localName)
                    }
                    is ParsedImplTarget.Inherent -> b.appendBoolean(true)
                }
                names(decl.forallVars)
                typeLocals = decl.forallVars.toSet()
                b.appendInt(decl.abilityTypeArgs.size)
                decl.abilityTypeArgs.forEach { type(it) }
                b.appendInt(decl.targetTypes.size)
                decl.targetTypes.forEach { type(it) }
                bindings(decl.associatedTypeBindings)
                b.appendInt(decl.operations.size)
                for (op in decl.operations.sortedBy { it.name }) {
                    b.append(op.name)
                    optional(op.paramType) { type(it) }
                    optional(op.returnType) { type(it) }
                    constraints(op.abilityConstraints)
                    expr(op.implementation, emptySet())
                }
            }
            is ParsedTopLevelExpr -> {
                b.append(byteArrayOf(HashTags.TOP_LEVEL_EXPRESSION))
                expr(decl.expression, emptySet())
            }
            is ParsedUseDecl, is ParsedPubUseDecl -> b.append(byteArrayOf(0))
        }
    }

    // ---- references --------------------------------------------------------

    /** A written name and what it means. */
    private fun ref(kind: RefKind, qualifier: List<String>?, name: String) {
        b.append(byteArrayOf(HashTags.RESOLVED_REF))
        b.appendInt(kind.ordinal)
        b.append(name)
        optional(qualifier) { b.append(it.joinToString("::")) }
        val own = local.byName[kind to name]?.takeIf { qualifier == null }
        resolution(if (own != null) Referent.Pending(own) else scope.resolve(kind, qualifier, name))
    }

    /**
     * A declaration that already has its identity hashes as that identity —
     * whether it was stamped earlier or moments ago in this same pass, so the
     * answer never depends on which modules happened to be stamped together.
     */
    private fun resolution(target: Referent?) {
        val ref = when (target) {
            is Referent.Known -> Ref.Known(target.hash)
            is Referent.Pending -> nodeRef(target.node)
            // A builtin, a native, or an undefined name: only the name can say.
            null -> { b.append(byteArrayOf(0)); return }
        }
        when (ref) {
            is Ref.Known -> { b.append(byteArrayOf(1)); b.append(ref.hash.hex) }
            Ref.InGroup -> b.append(byteArrayOf(2))
        }
    }

    /** `a::b::Name` written as one string, as rows and constraints carry them. */
    private fun qualifiedRef(kind: RefKind, written: String) {
        val parts = written.split("::")
        ref(kind, parts.dropLast(1).takeIf { it.isNotEmpty() }, parts.last())
    }

    /**
     * An identifier that is not bound locally. Values first; a capitalised
     * name used as a receiver (`Ord.compare`, `Seq.empty`) is an ability, an
     * effect or a type, and is resolved in that order — whichever the module
     * has in scope.
     */
    private fun freeIdentifier(qualifier: List<String>?, name: String) {
        val kind = LOOKUP_ORDER.firstOrNull { k ->
            (qualifier == null && local.byName.containsKey(k to name)) || scope.resolve(k, qualifier, name) != null
        } ?: RefKind.VALUE
        ref(kind, qualifier, name)
    }

    /** `.name` / an unqualified effect operation: everything in scope that can answer it. */
    private fun member(name: String) {
        b.append(byteArrayOf(HashTags.MEMBER_PROVIDERS))
        b.append(name)
        val known = sortedSetOf<String>()
        var inGroup = 0
        val targets = local.providers[name].orEmpty().map { Referent.Pending(it) } + scope.providers(name)
        for (t in targets.toSet()) {
            val r = when (t) {
                is Referent.Known -> Ref.Known(t.hash)
                is Referent.Pending -> nodeRef(t.node)
            }
            when (r) {
                is Ref.Known -> known.add(r.hash.hex)
                Ref.InGroup -> inGroup++
            }
        }
        b.appendInt(known.size)
        known.forEach { b.append(it) }
        b.appendInt(inGroup)
    }

    // ---- expressions -------------------------------------------------------

    private fun expr(expr: ParsedExpr, bound: Set<String>) {
        when (expr) {
            is ParsedIntLit -> { b.append(byteArrayOf(HashTags.INTEGER_LITERAL)); b.appendLong(expr.value) }
            is ParsedNatLit -> { b.append(byteArrayOf(0x01, HashTags.INTEGER_LITERAL)); b.appendLong(expr.value) }
            is ParsedFloatLit -> { b.append(byteArrayOf(HashTags.FLOAT_LITERAL)); b.appendLong(java.lang.Double.doubleToLongBits(expr.value)) }
            is ParsedFracLit -> { b.append(byteArrayOf(0x02, HashTags.FLOAT_LITERAL)); b.appendLong(expr.numerator); b.appendLong(expr.denominator) }
            is ParsedCharLit -> { b.append(byteArrayOf(0x03, HashTags.STRING_LITERAL)); b.appendInt(expr.value.code) }
            is ParsedStringLit -> {
                b.append(byteArrayOf(HashTags.STRING_LITERAL))
                b.appendInt(expr.parts.size)
                for (part in expr.parts) when (part) {
                    is ParsedStringPart.Literal -> { b.appendBoolean(false); b.append(part.value) }
                    is ParsedStringPart.Interpolation -> { b.appendBoolean(true); expr(part.expr, bound) }
                }
            }
            is ParsedIdentifier -> identifier(expr.qualifier?.segments, expr.localName, bound)
            // A quote IS the code that builds it, and a macro call IS what it
            // expanded to: identity is over what compiles.
            is ParsedQuote -> expr(expr.value, bound)
            is ParsedMacroCall -> when (val code = expr.expansion) {
                is ParsedCode.Expr -> expr(code.expr, bound)
                // A call that could not be expanded is identified as written: the
                // type checker reports why, and that needs the module identified.
                null -> {
                    check(expr.diagnostics.isNotEmpty()) {
                        "${expr.loc}: macro call `${expr.macro.localName}!( … )` reached identity unexpanded; " +
                            "macros are expanded before a module is identified"
                    }
                    b.append(byteArrayOf(HashTags.FUNCTION_CALL, 0x7f))
                    identifier(expr.macro.qualifier?.segments, expr.macro.localName, bound)
                    b.append(expr.input)
                }
                is ParsedCode.Decls -> throw IllegalStateException(
                    "${expr.loc}: macro call `${expr.macro.localName}!( … )` has declarations where an expression stands",
                )
            }
            is ParsedLambda -> {
                b.append(byteArrayOf(HashTags.LAMBDA))
                val params = when (val p = expr.params) {
                    is ParsedLambdaParams.Typed -> {
                        b.appendBoolean(true)
                        b.appendInt(p.fields.size)
                        for (f in p.fields) { b.append(f.name); type(f.type) }
                        p.fields.map { it.name }
                    }
                    is ParsedLambdaParams.Untyped -> {
                        b.appendBoolean(false)
                        names(p.names)
                        b.appendBoolean(p.isOpen)
                        p.names
                    }
                }
                expr(expr.body, bound + params)
            }
            is ParsedFunctionCall -> { b.append(byteArrayOf(HashTags.FUNCTION_CALL)); expr(expr.function, bound); expr(expr.arguments, bound) }
            is ParsedPipe -> { b.append(byteArrayOf(HashTags.PIPE)); expr(expr.left, bound); expr(expr.right, bound) }
            is ParsedPipePlaceholder -> b.append(byteArrayOf(HashTags.PIPE, 0x01))
            is ParsedRecordLit -> {
                b.append(byteArrayOf(HashTags.RECORD))
                b.appendInt(expr.entries.size)
                for (entry in expr.entries) when (entry) {
                    is ParsedRecordEntry.Field -> {
                        b.appendBoolean(false)
                        b.append(entry.name)
                        // `{ x }` is `{ x = x }`: the bare field names a variable.
                        val value = entry.value
                        if (value != null) { b.appendBoolean(true); expr(value, bound) }
                        else { b.appendBoolean(false); identifier(null, entry.name, bound) }
                    }
                    is ParsedRecordEntry.Spread -> { b.appendBoolean(true); expr(entry.expr, bound) }
                }
            }
            is ParsedMemberAccess -> {
                b.append(byteArrayOf(HashTags.FIELD_ACCESS))
                expr(expr.receiver, bound)
                member(expr.member)
            }
            is ParsedVariantConstruct -> {
                b.append(byteArrayOf(HashTags.VARIANT_CONSTRUCTOR))
                qualifiedRef(RefKind.CONSTRUCTOR, expr.constructor)
                optional(expr.value) { expr(it, bound) }
            }
            is ParsedNewtypeConstruct -> {
                b.append(byteArrayOf(HashTags.VARIANT_CONSTRUCTOR, 0x01))
                qualifiedRef(RefKind.TYPE, expr.typeName)
                expr(expr.value, bound)
            }
            is ParsedIf -> { b.append(byteArrayOf(HashTags.IF_ELSE)); expr(expr.condition, bound); expr(expr.thenBranch, bound); expr(expr.elseBranch, bound) }
            is ParsedMatch -> {
                b.append(byteArrayOf(HashTags.MATCH))
                expr(expr.scrutinee, bound)
                b.appendInt(expr.cases.size)
                for (c in expr.cases) {
                    val caseBound = bound + c.patterns.flatMap { it.extractBindings() } +
                        c.ifLetBindings.flatMap { it.pattern.extractBindings() }
                    b.appendInt(c.patterns.size)
                    c.patterns.forEach { pattern(it) }
                    optional(c.guard) { expr(it, caseBound) }
                    b.appendInt(c.ifLetBindings.size)
                    for (ilb in c.ifLetBindings) { pattern(ilb.pattern); expr(ilb.expr, caseBound) }
                    expr(c.body, caseBound)
                }
            }
            is ParsedDo -> {
                b.append(byteArrayOf(HashTags.BLOCK))
                b.appendInt(expr.statements.size)
                // A `let` scopes over the statements after it, not over its own value.
                var scope = bound
                for (stmt in expr.statements) when (stmt) {
                    is ParsedStatement.Expr -> { b.appendBoolean(false); expr(stmt.expr, scope) }
                    is ParsedStatement.Let -> {
                        b.appendBoolean(true)
                        pattern(stmt.pattern)
                        optional(stmt.typeAnnotation) { type(it) }
                        expr(stmt.value, scope)
                        scope = scope + stmt.pattern.extractBindings()
                    }
                }
                expr(expr.result, scope)
            }
            is ParsedSeqLit -> {
                b.append(byteArrayOf(HashTags.ARRAY))
                b.appendInt(expr.elements.size)
                for (elem in expr.elements) when (elem) {
                    is ParsedSeqElement.Regular -> { b.appendBoolean(false); expr(elem.expr, bound) }
                    is ParsedSeqElement.Spread -> { b.appendBoolean(true); expr(elem.expr, bound) }
                }
            }
            is ParsedPerform -> {
                b.append(byteArrayOf(HashTags.PERFORM))
                b.appendInt(expr.handlers.size)
                for (h in expr.handlers) {
                    ref(RefKind.PERFORMER, h.performer.qualifier?.segments, h.performer.localName)
                    optional(h.params) { expr(it, bound) }
                }
                expr(expr.body, bound)
            }
            is ParsedEffectCall -> {
                b.append(byteArrayOf(HashTags.PERFORM, 0x01))
                val q = expr.operation.qualifier?.segments
                if (q != null) {
                    // `Log::info` — the operation of a named effect.
                    ref(RefKind.EFFECT, q.dropLast(1).takeIf { it.isNotEmpty() }, q.last())
                    b.append(expr.operation.localName)
                } else {
                    member(expr.operation.localName)
                }
                expr(expr.arguments, bound)
            }
            is ParsedBinaryOp -> { b.append(byteArrayOf(HashTags.FUNCTION_CALL)); b.append(expr.op.symbol); expr(expr.left, bound); expr(expr.right, bound) }
            is ParsedParens -> expr(expr.expr, bound)
            is ParsedMatchCase -> b.append(byteArrayOf(HashTags.MATCH, 0x01))
            is ParsedIfLetGuard -> { b.append(byteArrayOf(HashTags.IF_ELSE, 0x01)); pattern(expr.pattern); expr(expr.expr, bound) }
            is ParsedTopLevelExpr -> expr(expr.expression, bound)
        }
    }

    private fun identifier(qualifier: List<String>?, name: String, bound: Set<String>) {
        if (qualifier == null && name in bound) {
            b.append(byteArrayOf(HashTags.LOCAL_REF))
            b.append(name)
        } else {
            freeIdentifier(qualifier, name)
        }
    }

    // ---- patterns ----------------------------------------------------------

    private fun pattern(pattern: ParsedPattern) {
        when (pattern) {
            is ParsedVariablePattern -> { b.append(byteArrayOf(HashTags.VARIABLE_PATTERN)); b.append(pattern.name) }
            is ParsedWildcardPattern -> b.append(byteArrayOf(HashTags.WILDCARD_PATTERN))
            is ParsedLiteralPattern -> {
                b.append(byteArrayOf(HashTags.LITERAL_PATTERN))
                when (val v = pattern.value) {
                    is ParsedLiteralValue.IntValue -> { b.append(byteArrayOf(HashTags.INTEGER_LITERAL)); b.appendLong(v.value) }
                    is ParsedLiteralValue.NatValue -> { b.append(byteArrayOf(0x01, HashTags.INTEGER_LITERAL)); b.appendLong(v.value) }
                    is ParsedLiteralValue.FloatValue -> { b.append(byteArrayOf(HashTags.FLOAT_LITERAL)); b.appendLong(java.lang.Double.doubleToLongBits(v.value)) }
                    is ParsedLiteralValue.StringValue -> { b.append(byteArrayOf(HashTags.STRING_LITERAL)); b.append(v.value) }
                    is ParsedLiteralValue.BoolValue -> { b.append(byteArrayOf(HashTags.BOOLEAN_LITERAL)); b.appendBoolean(v.value) }
                }
            }
            is ParsedConstructorPattern -> {
                b.append(byteArrayOf(HashTags.CONSTRUCTOR_PATTERN))
                qualifiedRef(RefKind.CONSTRUCTOR, pattern.constructor)
                optional(pattern.payload) { pattern(it) }
            }
            is ParsedNewtypePattern -> {
                b.append(byteArrayOf(HashTags.CONSTRUCTOR_PATTERN, 0x01))
                qualifiedRef(RefKind.TYPE, pattern.typeName)
                pattern(pattern.inner)
            }
            is ParsedRecordPattern -> {
                b.append(byteArrayOf(HashTags.RECORD_PATTERN))
                b.appendInt(pattern.entries.size)
                for (e in pattern.entries) { b.append(e.name); optional(e.pattern) { pattern(it) } }
                b.appendBoolean(pattern.isOpen)
            }
            is ParsedSeqPattern -> {
                b.append(byteArrayOf(HashTags.SEQ_PATTERN))
                b.appendInt(pattern.elements.size)
                pattern.elements.forEach { pattern(it) }
                optional(pattern.restPattern) { b.append(it) }
            }
            is ParsedStringInterpPattern -> {
                b.append(byteArrayOf(HashTags.STRING_INTERPOLATION_PATTERN))
                b.appendInt(pattern.parts.size)
                for (part in pattern.parts) when (part) {
                    is ParsedStringPatternPart.Literal -> { b.appendBoolean(false); b.append(part.value) }
                    is ParsedStringPatternPart.Capture -> { b.appendBoolean(true); b.append(part.variableName) }
                }
            }
        }
    }

    // ---- types -------------------------------------------------------------

    private fun isTypeVariable(name: String) = name.startsWith("'") || name in typeLocals

    private fun type(ann: ParsedTypeAnnotation) {
        when (ann) {
            is ParsedNamedType ->
                if (isTypeVariable(ann.name)) { b.append(byteArrayOf(HashTags.TYPE_VAR)); b.append(ann.name) }
                else { b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR)); ref(RefKind.TYPE, null, ann.name) }
            is ParsedQualifiedType -> {
                b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR))
                ref(RefKind.TYPE, ann.modulePath, ann.typeName)
                b.appendInt(ann.args.size)
                ann.args.forEach { type(it) }
            }
            is ParsedTypeApp -> {
                b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x01))
                if (isTypeVariable(ann.constructor)) b.append(ann.constructor)
                else ref(RefKind.TYPE, null, ann.constructor)
                b.appendInt(ann.args.size)
                ann.args.forEach { type(it) }
            }
            is ParsedRecordType -> {
                b.append(byteArrayOf(HashTags.RECORD_TYPE))
                b.appendInt(ann.entries.size)
                for (f in ann.entries) { b.append(f.name); type(f.type) }
                b.appendBoolean(ann.isOpen)
            }
            is ParsedVariantType -> {
                b.append(byteArrayOf(HashTags.VARIANT_TYPE))
                b.appendInt(ann.constructors.size)
                for (c in ann.constructors.sortedBy { it.name }) { b.append(c.name); optional(c.dataType) { type(it) } }
                b.appendBoolean(ann.isOpen)
            }
            is ParsedFunctionType -> {
                b.append(byteArrayOf(HashTags.FUNCTION_TYPE))
                type(ann.paramType)
                type(ann.returnType)
                optional(ann.effects) { row(it) }
                constraints(ann.abilityConstraints)
            }
            is ParsedSeqType -> { b.append(byteArrayOf(HashTags.SEQ_TYPE)); type(ann.elementType) }
            is ParsedEffectRow -> row(ann)
            is ParsedEffectAnnotation -> effectAnnotation(ann)
        }
    }

    private fun row(row: ParsedEffectRow) {
        b.append(byteArrayOf(HashTags.EFFECT_ROW))
        b.appendInt(row.effects.size)
        for (e in row.effects.sorted()) qualifiedRef(RefKind.EFFECT, e)
        b.appendInt(row.effectAnnotations.size)
        for (ea in row.effectAnnotations.sortedBy { it.name }) effectAnnotation(ea)
        b.appendBoolean(row.isPolymorphic)
        b.appendBoolean(row.isPerformed)
    }

    private fun effectAnnotation(ann: ParsedEffectAnnotation) {
        b.append(byteArrayOf(HashTags.PERFORM, 0x03))
        qualifiedRef(RefKind.EFFECT, ann.name)
        bindings(ann.associatedTypes)
    }

    private fun constraints(constraints: List<ParsedFnAbilityConstraint>) {
        b.appendInt(constraints.size)
        for (c in constraints) {
            b.append(byteArrayOf(HashTags.ABILITY_CONSTRAINT))
            qualifiedRef(RefKind.ABILITY, c.abilityName)
            b.appendInt(c.typeArgs.size)
            c.typeArgs.forEach { type(it) }
            bindings(c.associatedTypeBindings)
        }
    }

    private fun bindings(bindings: Map<String, ParsedTypeAnnotation>) {
        b.appendInt(bindings.size)
        for ((k, v) in bindings.toSortedMap()) { b.append(k); type(v) }
    }

    private fun typeDef(def: ParsedTypeDef) {
        when (def) {
            is ParsedRecordDef -> {
                b.append(byteArrayOf(HashTags.RECORD_TYPE, 0x01))
                b.appendInt(def.fields.size)
                for (f in def.fields) { b.append(f.name); type(f.type) }
                b.appendBoolean(def.isOpen)
            }
            is ParsedVariantDef -> {
                b.append(byteArrayOf(HashTags.VARIANT_TYPE, 0x01))
                b.appendInt(def.constructors.size)
                for (c in def.constructors.sortedBy { it.name }) { b.append(c.name); optional(c.dataType) { type(it) } }
                b.appendBoolean(def.isOpen)
            }
            is ParsedNewtypeDef -> {
                b.append(byteArrayOf(HashTags.NEWTYPE))
                b.append(def.constructor)
                type(def.wrappedType)
                // Nominal: the declaring module is part of what the type IS.
                b.append(modulePath)
            }
            is ParsedTypeAliasDef -> {
                b.append(byteArrayOf(HashTags.TYPE_ALIAS, 0x01))
                type(def.aliasedType)
            }
        }
    }

    // ---- small encoders ----------------------------------------------------

    private fun names(names: List<String>) {
        b.appendInt(names.size)
        names.forEach { b.append(it) }
    }

    private inline fun <T : Any> optional(value: T?, write: (T) -> Unit) {
        if (value == null) b.appendBoolean(false) else { b.appendBoolean(true); write(value) }
    }
}

private val LOOKUP_ORDER = listOf(
    RefKind.VALUE, RefKind.CONSTRUCTOR, RefKind.ABILITY, RefKind.EFFECT, RefKind.TYPE, RefKind.PERFORMER,
)

// ----------------------------------------------------------------------------
// Reading an identity. Every declaration is stamped before anything reads one;
// an unstamped declaration is a pipeline bug, and recomputing a hash from the
// text here is exactly the wrong answer this replaced — so it fails instead.
// ----------------------------------------------------------------------------

private fun unstamped(what: String, loc: Loc): Nothing =
    error("internal: $what at $loc has no identity — its module was never stamped by DeclIdentity")

val ParsedLetDecl.identity: Hash get() = contentHash ?: unstamped("let", loc)
val ParsedTypeDecl.identity: Hash get() = contentHash ?: unstamped("type '$name'", loc)
val ParsedAbilityDecl.identity: Hash get() = contentHash ?: unstamped("ability '$name'", loc)
val ParsedEffectDecl.identity: Hash get() = contentHash ?: unstamped("effect '$name'", loc)
val ParsedPerformerDecl.identity: Hash get() = contentHash ?: unstamped("performer '$performerName'", loc)
val ParsedImplAbilityDecl.identity: Hash get() = contentHash ?: unstamped("impl", loc)
val ParsedTopLevelExpr.identity: Hash get() = contentHash ?: unstamped("top-level expression", loc)

private fun identityOfDecl(d: ParsedDecl): Hash? = when (d) {
    is ParsedLetDecl -> d.contentHash
    is ParsedTypeDecl -> d.contentHash
    is ParsedAbilityDecl -> d.contentHash
    is ParsedEffectDecl -> d.contentHash
    is ParsedPerformerDecl -> d.contentHash
    is ParsedImplAbilityDecl -> d.contentHash
    is ParsedTopLevelExpr -> d.contentHash
    is ParsedUseDecl, is ParsedPubUseDecl -> null
}
