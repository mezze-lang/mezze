package com.mezze.language.typechecker.macro

import com.mezze.language.parsedast.*

/**
 * A name a macro wrote means what it meant where the macro was written.
 *
 * A quote in a macro is code the macro's author wrote, in the macro's module:
 * `` `(seq::map { xs = ~xs, f = helper }) `` names the `seq` and the `helper` that
 * module sees. Compiled at the caller as written, those names would be looked up
 * in the CALLER's scope — so every caller would have to import what the macro
 * uses, and a caller's own `helper` would silently take its place.
 *
 * So each such name is resolved in the macro's module and handed to the caller
 * under a name no source can spell, through an import added for it: the
 * expansion reaches exactly what the macro's author meant, and nothing the caller
 * declares can capture it.
 *
 * Which names: those the macro wrote (decoding says which), that are not bound
 * inside the generated code itself. A name from the macro's INPUT
 * is the caller's own code and is left alone.
 *
 * Not yet covered: type names in annotations and performer and effect names keep
 * caller resolution; a variable a quote binds can still capture one of the same
 * name in a spliced piece of input.
 */
internal class Hygiene(
    private val macroModule: String,
    macro: ParsedModule,
    /** Whether a node at this location was written by the macro, rather than taken from its input. */
    private val writtenByMacro: (Loc) -> Boolean,
) {
    /** What each name the macro's module sees refers to: (module, name). */
    private val scope = HashMap<String, Pair<String, String>>()
    /** Module aliases the macro's module imports: `use a::b` makes `b` mean `a::b`. */
    private val modules = HashMap<String, String>()
    /** Names declared in the macro's module that other modules cannot import. */
    private val private = HashSet<String>()

    /** Every name handed over, by what it refers to, under its hidden name. */
    val handedOver = LinkedHashMap<Pair<String, String>, String>()

    /** Names the expansion uses that its caller cannot be given. */
    val unreachable = LinkedHashSet<String>()

    init {
        for (d in macro.decls) {
            when (d) {
                is ParsedUseDecl -> imports(d.path, d.alias)
                is ParsedPubUseDecl -> imports(d.path, d.alias)
                is ParsedLetDecl -> d.pattern.extractBindings().forEach { name ->
                    if (d.visibility == Visibility.PUBLIC) scope[name] = macroModule to name else private += name
                }
                else -> {}
            }
        }
    }

    private fun imports(path: ParsedUsePath, alias: String?) = when (path) {
        is ParsedUsePath.Module -> {
            val full = path.path.toString()
            modules[alias ?: full.substringAfterLast(MODULE_PATH_SEPARATOR)] = full
        }
        is ParsedUsePath.Selective -> path.items.forEach { scope[it.alias ?: it.name] = path.modulePath.toString() to it.name }
    }

    fun decl(d: ParsedDecl): ParsedDecl = when (d) {
        is ParsedLetDecl -> d.copy(value = expr(d.value, emptySet()))
        else -> d.mapExprs { expr(it, emptySet()) }
    }

    fun expr(e: ParsedExpr, bound: Set<String>): ParsedExpr = when (e) {
        is ParsedIdentifier -> name(e, bound)
        is ParsedLambda -> e.copy(body = expr(e.body, bound + params(e.params)))
        is ParsedDo -> {
            var inScope = bound
            val statements = e.statements.map { st ->
                when (st) {
                    is ParsedStatement.Let -> st.copy(value = expr(st.value, inScope))
                        .also { inScope = inScope + st.pattern.extractBindings() }
                    is ParsedStatement.Expr -> st.copy(expr = expr(st.expr, inScope))
                }
            }
            e.copy(statements = statements, result = expr(e.result, inScope))
        }
        is ParsedMatch -> e.copy(scrutinee = expr(e.scrutinee, bound), cases = e.cases.map { c ->
            val caseBound = bound + c.patterns.flatMap { it.extractBindings() } +
                c.ifLetBindings.flatMap { it.pattern.extractBindings() }
            c.copy(
                guard = c.guard?.let { expr(it, caseBound) },
                ifLetBindings = c.ifLetBindings.map { it.copy(expr = expr(it.expr, caseBound)) },
                body = expr(c.body, caseBound),
            )
        })
        else -> e.mapChildren { expr(it, bound) }
    }

    private fun params(p: ParsedLambdaParams): List<String> = when (p) {
        is ParsedLambdaParams.Typed -> p.fields.map { it.name }
        is ParsedLambdaParams.Untyped -> p.names
    }

    private fun name(id: ParsedIdentifier, bound: Set<String>): ParsedExpr {
        if (!writtenByMacro(id.loc)) return id
        val target = when (val q = id.qualifier) {
            null -> {
                if (id.localName in bound) return id
                if (id.localName in private) { unreachable += id.localName; return id }
                scope[id.localName] ?: return id
            }
            else -> {
                val first = modules[q.segments.first()] ?: return id
                (listOf(first) + q.segments.drop(1)).joinToString(MODULE_PATH_SEPARATOR) to id.localName
            }
        }
        val hidden = handedOver.getOrPut(target) {
            "~" + target.first.replace(MODULE_PATH_SEPARATOR, "~") + "~" + target.second
        }
        return ParsedIdentifier(hidden, null, id.loc)
    }

    companion object {
        /** The imports that give a module every name [handedOver] lists, under their hidden names. */
        fun imports(handedOver: Map<Pair<String, String>, String>, at: Loc): List<ParsedDecl> =
            handedOver.entries.groupBy { it.key.first }.map { (module, entries) ->
                ParsedUseDecl(
                    path = ParsedUsePath.Selective(
                        ParsedModulePath(module.split(MODULE_PATH_SEPARATOR)),
                        entries.map { ParsedImportItem(it.key.second, it.value, at) },
                        at,
                    ),
                    loc = at,
                )
            }
    }
}
