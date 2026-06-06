package com.mezze.language.typechecker.emit

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.*
import java.security.MessageDigest

/**
 * Wraps a TypedModule's top-level *expressions* into a single entry
 * function. Used for REPL/scratchpad-style modules that contain loose
 * top-level expressions (e.g., `1 + 2`) at the file level — the parser
 * lifts each into a `__toplevel_expr_N` decl, and this pass folds them
 * into a `__entry__` function so the rest of the pipeline (mono →
 * resolve → lower) can treat the module uniformly.
 *
 * For normal projects (whose entry function is declared explicitly by
 * the user, regardless of name — `main`, `app`, whatever Mezze.toml
 * says), there are no `__toplevel_expr_N` decls and this pass is a
 * no-op. The typechecker doesn't need to know which decl is the
 * "entry point"; that's a CLI/runtime concern.
 */
object EntryFunctionBuilder {

    /**
     * Wrap a module's top-level let decls (and any loose top-level
     * expressions lifted by the parser to `__toplevel_expr_N` decls)
     * into a synthetic `__entry__` function whose body is a sequential
     * block. The runtime executes this block in order so each let's
     * value is evaluated and registered, and so loose expressions get
     * evaluated for their side effects / final value.
     *
     * Returns the original module if there are no let decls.
     */
    fun wrapTopLevel(module: TypedModule): TypedModule {
        val decls = module.decls.filterIsInstance<TypedLetDecl>()
        if (decls.isEmpty()) return module

        val loc = decls.first().loc

        // Build statements from let declarations
        // __toplevel_expr_N decls are top-level expressions (not real let bindings)
        val statements = mutableListOf<TypedStatement>()
        var lastType: MezzeType = MezzeType.VarType(TypeVar.freshWithName("unit"))
        var lastExprValue: TypedExpr? = null

        for (i in decls.indices) {
            val decl = decls[i]
            if (decl.name.startsWith("__toplevel_expr_")) {
                // Top-level expression — if it's the last decl, it becomes the result
                if (i == decls.lastIndex) {
                    lastExprValue = decl.value
                    lastType = decl.scheme.type
                } else {
                    statements.add(TypedStatement.Expr(decl.value, decl.loc))
                    lastType = decl.scheme.type
                }
            } else {
                // Regular let binding — preserve content hash for global registration
                statements.add(TypedStatement.Let(
                    name = decl.name,
                    contentHash = decl.hash,
                    type = decl.scheme.type,
                    value = decl.value,
                    loc = decl.loc
                ))
                lastType = decl.scheme.type
                lastExprValue = null // reset — last is a let, not an expr
            }
        }

        // The result expression
        val resultExpr: TypedExpr = if (lastExprValue != null) {
            // Last decl was a top-level expression — use its value directly
            lastExprValue
        } else {
            // Last decl was a let binding — read it back
            val lastDecl = decls.last()
            TypedVar(
                name = lastDecl.name,
                binding = BindingRef.ByHash(lastDecl.hash),
                type = lastType,
                loc = lastDecl.loc
            )
        }

        // Build the block
        val blockBody = TypedBlock(
            statements = statements,
            result = resultExpr,
            type = lastType,
            loc = loc
        )

        // Build the entry function
        val entryHash = computeHash("__entry__:" + module.hash.hex)
        val entryDecl = TypedLetDecl(
            hash = entryHash,
            name = "__entry__",
            typeParams = emptyList(),
            value = blockBody,
            scheme = TypeScheme(emptyList(), emptyList(), lastType),
            isPublic = false,
            loc = loc
        )

        // Return module with entry function PLUS original decls
        // The monomorphizer needs the original decls to find functions by hash
        // The entry function wraps them as a sequential block for execution
        return module.copy(
            decls = decls + entryDecl,
            exports = module.exports
        )
    }

    /**
     * Same as wrapTopLevel but also handles top-level expressions.
     * For programs like "42" or "1 + 2" — wraps as an entry function.
     */
    fun wrapTopLevelWithExprs(
        module: TypedModule,
        topLevelExprs: List<TypedExpr>
    ): TypedModule {
        val decls = module.decls.filterIsInstance<TypedLetDecl>()
        val loc = if (decls.isNotEmpty()) decls.first().loc
                  else if (topLevelExprs.isNotEmpty()) topLevelExprs.first().loc
                  else Loc("<unknown>", 1, 1, 1, 1)

        val statements = mutableListOf<TypedStatement>()
        var lastType: MezzeType = MezzeType.VarType(TypeVar.freshWithName("unit"))

        // Add let declarations as statements
        for (decl in decls) {
            statements.add(TypedStatement.Let(decl.name, decl.hash, decl.scheme.type, decl.value, decl.loc))
            lastType = decl.scheme.type
        }

        // Add top-level expressions as expr statements (except the last one which is the result)
        for (i in 0 until topLevelExprs.size - 1) {
            statements.add(TypedStatement.Expr(topLevelExprs[i], topLevelExprs[i].loc))
        }

        // Result is the last expression, or the last let value
        val resultExpr: TypedExpr = if (topLevelExprs.isNotEmpty()) {
            lastType = topLevelExprs.last().type
            topLevelExprs.last()
        } else if (decls.isNotEmpty()) {
            TypedVar(
                name = decls.last().name,
                binding = BindingRef.ByHash(decls.last().hash),
                type = lastType,
                loc = decls.last().loc
            )
        } else {
            TypedIntLit(0, MezzeType.IntType, loc)
        }

        val blockBody = TypedBlock(statements, resultExpr, lastType, loc)
        val entryHash = computeHash("__entry__:" + module.hash.hex)
        val entryDecl = TypedLetDecl(
            hash = entryHash,
            name = "__entry__",
            typeParams = emptyList(),
            value = blockBody,
            scheme = TypeScheme(emptyList(), emptyList(), lastType),
            isPublic = false,
            loc = loc
        )

        return module.copy(decls = listOf(entryDecl))
    }

    private fun computeHash(input: String): Hash {
        val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        builder.append(input)
        return builder.buildHash()
    }
}
