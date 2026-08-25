package com.mezze.language.typechecker.phase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.ParsedDeclHasher
import com.mezze.language.typechecker.ImportKind
import com.mezze.language.typechecker.ResolvedImport
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typechecker.util.WellKnownTypeApp

internal fun buildResolvedImports(importContext: ModuleImportContext?): List<ResolvedImport> {
    if (importContext == null) return emptyList()

    return buildList {
        for ((name, binding) in importContext.importedBindings) {
            add(ResolvedImport(
                localName = name,
                originalName = binding.originalName,
                modulePath = binding.modulePath,
                type = binding.type,
                kind = ImportKind.FUNCTION
            ))
        }

        for ((name, typeDecl) in importContext.importedTypes) {
            val typeParams = typeDecl.typeParams.map { MezzeType.VarType(TypeVar.freshWithName(it)) }
            add(ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = WellKnownTypeApp.of(
                    name, typeParams,
                    typeHash = typeDecl.contentHash ?: ParsedDeclHasher.hashTypeDecl(typeDecl)
                ),
                kind = ImportKind.TYPE
            ))
        }

        for ((name, abilityDecl) in importContext.importedAbilities) {
            add(ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = WellKnownTypeApp.of(
                    name, emptyList(),
                    typeHash = abilityDecl.contentHash ?: ParsedDeclHasher.hashAbilityDecl(abilityDecl)
                ),
                kind = ImportKind.ABILITY
            ))
        }

        for ((name, effectDecl) in importContext.importedEffects) {
            add(ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = WellKnownTypeApp.of(
                    name, emptyList(),
                    typeHash = effectDecl.contentHash ?: ParsedDeclHasher.hashEffectDecl(effectDecl)
                ),
                kind = ImportKind.EFFECT
            ))
        }
    }
}
