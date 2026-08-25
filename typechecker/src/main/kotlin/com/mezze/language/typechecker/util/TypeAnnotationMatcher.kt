package com.mezze.language.typechecker.util

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*

object TypeAnnotationMatcher {

        fun typeMatchesAnnotation(
        type: MezzeType,
        annotation: ParsedTypeAnnotation,
        typeDeclarations: Map<String, ParsedTypeDecl>,
        typeVars: Set<String> = emptySet(),
        typeVarBindings: MutableMap<String, MezzeType> = mutableMapOf()
    ): Boolean {
        return when (annotation) {
            is ParsedNamedType -> {
                if (annotation.name in typeVars) {
                    val existing = typeVarBindings[annotation.name]
                    if (existing != null) existing == type
                    else { typeVarBindings[annotation.name] = type; true }
                } else {
                    val builtinMatch = PrimitiveTypes.matchesBuiltinType(annotation.name, type)
                    if (builtinMatch != null) builtinMatch
                    else {
                        val typeDecl = typeDeclarations[annotation.name]
                        if (typeDecl != null) typeMatchesDeclaration(type, typeDecl)
                        else when (type) {
                            is MezzeType.RecordType -> (type.identity as? TypeIdentity.Declared)?.hash?.let {
                                it == typeDeclarations[annotation.name]?.contentHash
                            } ?: (type.identity.displayNameOrNull() == annotation.name)
                            is MezzeType.VariantType -> {
                                val declHash = typeDeclarations[annotation.name]?.contentHash
                                val typeHash = (type.identity as? TypeIdentity.Declared)?.hash
                                if (typeHash != null && declHash != null) typeHash == declHash
                                else if (type.identity.displayNameOrNull() == annotation.name) true
                                else if (type.identity.displayNameOrNull() == null && type.constructors.containsKey(annotation.name)) true
                                else false
                            }
                            is MezzeType.TypeApplication -> (type.identity as? TypeIdentity.Declared)?.hash?.let {
                                it == typeDeclarations[annotation.name]?.contentHash
                            } ?: (type.constructor == annotation.name)
                            else -> false
                        }
                    }
                }
            }
            is ParsedTypeApp -> {
                // Transparent-alias bridge: `impl for Wrap 'a` where the
                // receiver was resolved through a transparent alias to a bare
                // RecordType or a nameless VariantType. Reconstruct the alias's
                // aliased annotation with type-args substituted and re-match.
                // Per project invariant: keyed on decl identity (looked up via
                // the alias's canonical name in typeDecls), never on string-
                // matching of type names in the concrete side.
                val aliasedDecl = typeDeclarations[annotation.constructor]
                if (aliasedDecl != null && annotation.args.size == aliasedDecl.typeParams.size) {
                    val aliasedAnnotation: ParsedTypeAnnotation? = when (type) {
                        is MezzeType.RecordType -> (aliasedDecl.definition as? ParsedRecordDef)?.let { d ->
                            ParsedRecordType(
                                entries = d.fields.map { ParsedRecordTypeEntry(it.name, it.type, it.loc) },
                                isOpen = d.isOpen, loc = d.loc,
                            )
                        }
                        is MezzeType.VariantType -> if (type.identity.displayNameOrNull() == null || type.identity.displayNameOrNull() == aliasedDecl.name) {
                            (aliasedDecl.definition as? ParsedVariantDef)?.let { d ->
                                ParsedVariantType(
                                    constructors = d.constructors.map { c -> ParsedVariantTypeCtor(c.name, c.dataType, c.loc) },
                                    isOpen = d.isOpen, loc = d.loc,
                                )
                            }
                        } else null
                        else -> null
                    }
                    if (aliasedAnnotation != null) {
                        val substitutions = aliasedDecl.typeParams.zip(annotation.args).toMap()
                        val instantiated = substituteTypeAppArgsInAnnotation(aliasedAnnotation, substitutions)
                        if (typeMatchesAnnotation(type, instantiated, typeDeclarations, typeVars, typeVarBindings)) return true
                    }
                }
                when {
                    type is MezzeType.TypeApplication && type.constructor == annotation.constructor -> {
                        type.args.size == annotation.args.size &&
                        type.args.zip(annotation.args).all { (a, b) ->
                            typeMatchesAnnotation(a, b, typeDeclarations, typeVars, typeVarBindings)
                        }
                    }
                    // #194: VariantType carries (typeName, typeArgs) for polymorphic
                    // uses, mirroring TypeApplication.
                    type is MezzeType.VariantType && type.identity.displayNameOrNull() == annotation.constructor &&
                            type.typeArgs.size == annotation.args.size -> {
                        type.typeArgs.zip(annotation.args).all { (a, b) ->
                            typeMatchesAnnotation(a, b, typeDeclarations, typeVars, typeVarBindings)
                        }
                    }
                    type is MezzeType.SeqType && annotation.constructor == BuiltinTypes.SEQ ->
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeDeclarations, typeVars, typeVarBindings)
                    type is MezzeType.ListType && annotation.constructor == BuiltinTypes.LIST ->
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeDeclarations, typeVars, typeVarBindings)
                    type is MezzeType.SetType && annotation.constructor == BuiltinTypes.SET ->
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeDeclarations, typeVars, typeVarBindings)
                    type is MezzeType.MapType && annotation.constructor == BuiltinTypes.MAP ->
                        annotation.args.size == 2 &&
                        typeMatchesAnnotation(type.keyType, annotation.args[0], typeDeclarations, typeVars, typeVarBindings) &&
                        typeMatchesAnnotation(type.valueType, annotation.args[1], typeDeclarations, typeVars, typeVarBindings)
                    else -> false
                }
            }
            is ParsedRecordType -> {
                if (type !is MezzeType.RecordType || type.fields.size != annotation.entries.size) false
                else annotation.entries.all { entry ->
                    val fieldType = type.fields[entry.name] ?: return false
                    typeMatchesAnnotation(fieldType, entry.type, typeDeclarations, typeVars, typeVarBindings)
                }
            }
            is ParsedVariantType -> {
                if (type !is MezzeType.VariantType) false
                else annotation.constructors.all { ctor ->
                    val concretePayload = type.constructors[ctor.name]
                    if (ctor.dataType == null) concretePayload == null
                    else if (concretePayload == null) false
                    else typeMatchesAnnotation(concretePayload, ctor.dataType!!, typeDeclarations, typeVars, typeVarBindings)
                }
            }
            is ParsedSeqType -> type is MezzeType.SeqType &&
                typeMatchesAnnotation(type.elementType, annotation.elementType, typeDeclarations, typeVars, typeVarBindings)
            is ParsedFunctionType -> false
            is ParsedQualifiedType -> false
        }
    }

        fun typeMatchesDeclaration(type: MezzeType, typeDecl: ParsedTypeDecl): Boolean {
        if (type is MezzeType.TypeApplication) {
            val typeHash = (type.identity as? TypeIdentity.Declared)?.hash
            val hashMatch = typeHash != null && typeDecl.contentHash != null && typeHash == typeDecl.contentHash
            return hashMatch || (type.constructor == typeDecl.name && type.args.isEmpty())
        }

        return when (val definition = typeDecl.definition) {
            is ParsedRecordDef -> {
                if (type is MezzeType.RecordType) {
                    val typeHash = (type.identity as? TypeIdentity.Declared)?.hash
                    val hashMatch = typeHash != null && typeDecl.contentHash != null && typeHash == typeDecl.contentHash
                    return hashMatch || type.identity.displayNameOrNull() == typeDecl.name || type.fields.keys == definition.fields.map { it.name }.toSet()
                }
                if (type is MezzeType.VariantType && type.constructors.size == 1) {
                    val (ctorName, ctorPayload) = type.constructors.entries.first()
                    if (ctorName == typeDecl.name && ctorPayload is MezzeType.RecordType) {
                        return ctorPayload.fields.keys == definition.fields.map { it.name }.toSet()
                    }
                }
                return false
            }
            is ParsedVariantDef -> {
                if (type !is MezzeType.VariantType) return false
                val typeHash = (type.identity as? TypeIdentity.Declared)?.hash
                val hashMatch = typeHash != null && typeDecl.contentHash != null && typeHash == typeDecl.contentHash
                hashMatch || type.identity.displayNameOrNull() == typeDecl.name ||
                definition.constructors.map { it.name }.toSet().containsAll(type.constructors.keys)
            }
            is ParsedNewtypeDef -> {
                // A newtype `T = T <wrapped>` constructed as `T { … }` (or `T x`)
                // solves to a single-constructor VariantType named after the
                // newtype's constructor. Match by hash, by the carried typeName,
                // or by that sole constructor name — otherwise a newtype receiver
                // (`(Money{val=5}).method{}`, whose inferred type is the anonymous
                // `| Money {…} |`) never matches `impl … for Money`.
                if (type !is MezzeType.VariantType) return false
                val typeHash = (type.identity as? TypeIdentity.Declared)?.hash
                val hashMatch = typeHash != null && typeDecl.contentHash != null && typeHash == typeDecl.contentHash
                hashMatch || type.identity.displayNameOrNull() == typeDecl.name ||
                (type.constructors.size == 1 && type.constructors.containsKey(definition.constructor))
            }
            else -> false
        }
    }

        fun inferTypeArgsFromVariant(
        variantType: MezzeType.VariantType,
        typeDecl: ParsedTypeDecl,
        definition: ParsedVariantDef
    ): List<MezzeType>? {
        val typeArgMap = mutableMapOf<String, MezzeType>()

        for ((constructorName, concretePayload) in variantType.constructors) {
            val declConstructor = definition.constructors.find { it.name == constructorName } ?: continue
            val dataTypeAnnotation = declConstructor.dataType ?: continue
            if (concretePayload == null) continue
            extractTypeParamMappings(dataTypeAnnotation, concretePayload, typeDecl.typeParams, typeArgMap)
        }

        return if (typeArgMap.size == typeDecl.typeParams.size) {
            typeDecl.typeParams.map { typeArgMap[it] ?: return null }
        } else {
            null
        }
    }

    private fun substituteTypeAppArgsInAnnotation(
        annotation: ParsedTypeAnnotation,
        substitutions: Map<String, ParsedTypeAnnotation>
    ): ParsedTypeAnnotation {
        return when (annotation) {
            is ParsedNamedType -> substitutions[annotation.name] ?: substitutions[annotation.name.removePrefix("'")] ?: substitutions["'" + annotation.name.removePrefix("'")] ?: annotation
            is ParsedTypeApp -> annotation.copy(args = annotation.args.map { substituteTypeAppArgsInAnnotation(it, substitutions) })
            is ParsedRecordType -> annotation.copy(entries = annotation.entries.map { it.copy(type = substituteTypeAppArgsInAnnotation(it.type, substitutions)) })
            is ParsedVariantType -> annotation.copy(constructors = annotation.constructors.map { c -> c.copy(dataType = c.dataType?.let { substituteTypeAppArgsInAnnotation(it, substitutions) }) })
            is ParsedSeqType -> annotation.copy(elementType = substituteTypeAppArgsInAnnotation(annotation.elementType, substitutions))
            is ParsedFunctionType -> annotation.copy(paramType = substituteTypeAppArgsInAnnotation(annotation.paramType, substitutions), returnType = substituteTypeAppArgsInAnnotation(annotation.returnType, substitutions))
            is ParsedQualifiedType -> annotation
        }
    }

        fun extractTypeParamMappings(
        annotation: ParsedTypeAnnotation,
        concreteType: MezzeType,
        typeParams: List<String>,
        result: MutableMap<String, MezzeType>
    ) {
        when (annotation) {
            is ParsedNamedType -> {
                if (annotation.name in typeParams) result[annotation.name] = concreteType
            }
            is ParsedRecordType -> {
                if (concreteType is MezzeType.RecordType) {
                    for (entry in annotation.entries) {
                        val fieldType = concreteType.fields[entry.name] ?: continue
                        extractTypeParamMappings(entry.type, fieldType, typeParams, result)
                    }
                }
            }
            is ParsedTypeApp -> {
                if (concreteType is MezzeType.TypeApplication && concreteType.constructor == annotation.constructor) {
                    for ((annArg, concreteArg) in annotation.args.zip(concreteType.args)) {
                        extractTypeParamMappings(annArg, concreteArg, typeParams, result)
                    }
                }
            }
            is ParsedFunctionType -> {
                if (concreteType is MezzeType.FunctionType) {
                    extractTypeParamMappings(annotation.paramType, concreteType.paramType, typeParams, result)
                    extractTypeParamMappings(annotation.returnType, concreteType.returnType, typeParams, result)
                }
            }
            else -> {}
        }
    }
}
