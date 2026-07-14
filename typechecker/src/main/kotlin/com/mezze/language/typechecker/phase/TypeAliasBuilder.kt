package com.mezze.language.typechecker.phase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeAliasInfo
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.module.ModuleImportContext

internal fun buildTypeAliasesFromParsedModule(
    module: ParsedModule,
    contextBuilder: ContextBuilder,
    importContext: ModuleImportContext?
): Map<String, TypeAliasInfo> {
    val result = mutableMapOf<String, TypeAliasInfo>()

    fun addParsedTypeDecl(typeDecl: ParsedTypeDecl) {
        val definitionType = when (val def = typeDecl.definition) {
            is ParsedVariantDef -> {
                val constructors = def.constructors.associate { ctor ->
                    val dataType = ctor.dataType?.let { contextBuilder.convertParsedTypeAnnotation(it) }
                    ctor.name to dataType
                }
                MezzeType.VariantType(
                    constructors = constructors,
                    rowVar = if (def.isOpen) RowVar.fresh() else null,
                    typeName = typeDecl.name
                )
            }
            is ParsedRecordDef -> {
                val fields = def.fields.associate { field ->
                    field.name to contextBuilder.convertParsedTypeAnnotation(field.type)
                }
                MezzeType.RecordType(
                    fields = fields,
                    rowVar = if (def.isOpen) RowVar.fresh() else null,
                    typeName = typeDecl.name
                )
            }
            else -> null
        }
        if (definitionType != null) {
            result[typeDecl.name] = TypeAliasInfo(
                name = typeDecl.name,
                typeParams = typeDecl.typeParams,
                definition = definitionType
            )
        }
    }

    // Add local type declarations from ParsedModule
    for (decl in module.decls) {
        if (decl is ParsedTypeDecl) {
            addParsedTypeDecl(decl)
        }
    }

    // Add imported type declarations
    importContext?.importedTypes?.values?.forEach { typeDecl ->
        addParsedTypeDecl(typeDecl)
    }

    return result
}
