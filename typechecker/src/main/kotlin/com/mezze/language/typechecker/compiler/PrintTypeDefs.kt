package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.typedast.TypedAliasDef
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedNewtypeDef
import com.mezze.language.typedast.TypedRecordDef
import com.mezze.language.typedast.TypedTypeDecl
import com.mezze.language.typedast.TypedVariantDef

/**
 * The tables [PrintTypeBuilder] needs to turn a type into a printable shape.
 *
 * A use site only ever carries a nominal `TypeApplication("Money")`; rendering
 * needs to know that `Money` is a newtype over `Int`. These two maps are that
 * knowledge, collected from the type declarations of a module and everything it
 * imported.
 *
 * Extracted from the lowering pipeline because a second caller arrived: an
 * expression compiled for EVAL needs the shape of its RESULT, computed outside
 * lowering. Two copies of this collection would drift in exactly the way that is
 * hardest to notice — a type whose shape one caller resolves and the other
 * renders from the runtime value alone, which mostly looks right.
 */
data class PrintTypeDefs(
    /** Declared type name → its resolved underlying type. */
    val defs: Map<String, MezzeType>,
    /** Declared type name → its type-parameter names, positional (`Box 'a` → `['a]`). */
    val params: Map<String, List<String>>,
) {
    companion object {
        val EMPTY = PrintTypeDefs(emptyMap(), emptyMap())

        /**
         * Collect from [modules], earliest wins.
         *
         * Order matters and the caller owns it: pass the module being compiled
         * first, then its imports, then the stdlib, so a local declaration
         * shadows an imported one of the same name rather than the reverse.
         */
        fun collect(modules: List<TypedModule>): PrintTypeDefs {
            val defs = HashMap<String, MezzeType>()
            val params = HashMap<String, List<String>>()
            for (module in modules) {
                for (decl in module.decls) {
                    if (decl !is TypedTypeDecl) continue
                    params.putIfAbsent(decl.name, decl.typeParams.map { it.name })
                    resolve(decl)?.let { defs.putIfAbsent(decl.name, it) }
                }
            }
            return PrintTypeDefs(defs, params)
        }

        /**
         * A declaration's underlying type.
         *
         * Newtypes, records and variants get a nominal [TypeIdentity.declared]
         * identity — never Anonymous — so that two distinct types with the same
         * structure stay distinct. An alias resolves to its target, which has its
         * own identity already.
         */
        private fun resolve(decl: TypedTypeDecl): MezzeType? = when (val def = decl.definition) {
            is TypedNewtypeDef -> MezzeType.NewtypeType(
                decl.name, def.constructor, def.wrappedType,
                identity = TypeIdentity.declared(decl.name, decl.hash),
            )

            is TypedRecordDef -> MezzeType.RecordType(
                def.fields,
                rowVar = if (def.isOpen) RowVar.fresh() else null,
                identity = TypeIdentity.declared(decl.name, decl.hash),
            )

            is TypedVariantDef -> MezzeType.VariantType(
                def.constructors,
                rowVar = if (def.isOpen) RowVar.fresh() else null,
                identity = TypeIdentity.declared(decl.name, decl.hash),
            )

            is TypedAliasDef -> def.aliasedType
        }
    }
}
