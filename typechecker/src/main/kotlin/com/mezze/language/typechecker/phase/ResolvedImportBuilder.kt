package com.mezze.language.typechecker.phase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.ImportKind
import com.mezze.language.typechecker.ResolvedImport
import com.mezze.language.typechecker.module.ModuleImportContext

/**
 * Build resolved imports from import context for IDE support.
 */
internal fun buildResolvedImports(importContext: ModuleImportContext?): List<ResolvedImport> {
    if (importContext == null) return emptyList()

    val imports = mutableListOf<ResolvedImport>()

    // Add imported bindings (functions/values)
    for ((name, binding) in importContext.importedBindings) {
        imports.add(ResolvedImport(
            localName = name,
            originalName = binding.originalName,
            modulePath = binding.modulePath,
            type = binding.type,
            kind = ImportKind.FUNCTION
        ))
    }

    // Add imported types - use TypeApplication for type representation
    for ((name, typeDecl) in importContext.importedTypes) {
        val typeParams = typeDecl.typeParams.map { MezzeType.VarType(TypeVar.freshWithName(it)) }
        imports.add(ResolvedImport(
            localName = name,
            originalName = name,
            modulePath = "",  // TypeDeclaration doesn't track module path
            type = com.mezze.language.typechecker.util.WellKnownTypeApp.of(name, typeParams),
            kind = ImportKind.TYPE
        ))
    }

    // Add imported abilities - use simple type application for representation
    for ((name, _) in importContext.importedAbilities) {
        imports.add(ResolvedImport(
            localName = name,
            originalName = name,
            modulePath = "",
            type = com.mezze.language.typechecker.util.WellKnownTypeApp.of(name, emptyList()),
            kind = ImportKind.ABILITY
        ))
    }

    // Add imported effects - use simple type application for representation
    for ((name, _) in importContext.importedEffects) {
        imports.add(ResolvedImport(
            localName = name,
            originalName = name,
            modulePath = "",
            type = com.mezze.language.typechecker.util.WellKnownTypeApp.of(name, emptyList()),
            kind = ImportKind.EFFECT
        ))
    }

    return imports
}
