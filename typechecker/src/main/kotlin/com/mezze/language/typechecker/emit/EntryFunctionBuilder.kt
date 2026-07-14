package com.mezze.language.typechecker.emit

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.typedast.*
import java.security.MessageDigest

object EntryFunctionBuilder {

        fun wrapTopLevel(module: TypedModule): TypedModule {
        val decls = module.decls.filterIsInstance<TypedLetDecl>()
        if (decls.isEmpty()) return module

        val loc = decls.first().loc

        val statements = mutableListOf<TypedStatement>()
        var lastType: MezzeType = MezzeType.VarType(TypeVar.freshWithName("unit"))
        var lastExprValue: TypedExpr? = null

        for (i in decls.indices) {
            val decl = decls[i]
            if (decl.name.startsWith("__toplevel_expr_")) {
                if (i == decls.lastIndex) {
                    lastExprValue = decl.value
                    lastType = decl.scheme.type
                } else {
                    statements.add(TypedStatement.Expr(decl.value, decl.loc))
                    lastType = decl.scheme.type
                }
            } else {
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

        val resultExpr: TypedExpr = if (lastExprValue != null) {
            lastExprValue
        } else {
            val lastDecl = decls.last()
            TypedVar(
                name = lastDecl.name,
                binding = BindingRef.ByHash(lastDecl.hash),
                type = lastType,
                loc = lastDecl.loc
            )
        }

        val blockBody = TypedBlock(
            statements = statements,
            result = resultExpr,
            type = lastType,
            loc = loc
        )

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

        return module.copy(
            decls = decls + entryDecl,
            exports = module.exports
        )
    }

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

        for (decl in decls) {
            statements.add(TypedStatement.Let(decl.name, decl.hash, decl.scheme.type, decl.value, decl.loc))
            lastType = decl.scheme.type
        }

        for (i in 0 until topLevelExprs.size - 1) {
            statements.add(TypedStatement.Expr(topLevelExprs[i], topLevelExprs[i].loc))
        }

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
        val builder = MezzeHashBuilder()
        builder.append(input)
        return builder.buildHash()
    }
}
