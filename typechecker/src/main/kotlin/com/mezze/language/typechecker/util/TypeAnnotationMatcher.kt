package com.mezze.language.typechecker.util

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*

/**
 * Shared utility for structural matching of [MezzeType] against [ParsedTypeAnnotation].
 *
 * Consolidates the duplicate `typeMatchesAnnotation`, `typeMatchesDeclaration`,
 * `inferTypeArgsFromVariant`, and `extractTypeParamMappings` functions from
 * AbilityResolver, TypeValidator, and ConstraintSolver.
 *
 * All three had the same signature and nearly identical logic. The ConstraintSolver
 * version was the most complete (handling Set/Map in TypeApp), so this implementation
 * is based on that.
 */
object TypeAnnotationMatcher {

    /**
     * Check if a concrete [MezzeType] matches a [ParsedTypeAnnotation] structurally.
     *
     * @param type The concrete type to check.
     * @param annotation The parsed type annotation to match against.
     * @param typeDeclarations Type declarations available for lookup (e.g., for hash-based matching).
     * @param typeVars Set of names that are type variables (should be bound, not matched structurally).
     * @param typeVarBindings Mutable map tracking bindings for type variables encountered during matching.
     */
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
                            is MezzeType.RecordType -> type.typeHash?.let {
                                it == typeDeclarations[annotation.name]?.contentHash
                            } ?: (type.typeName == annotation.name)
                            is MezzeType.VariantType -> {
                                val declHash = typeDeclarations[annotation.name]?.contentHash
                                if (type.typeHash != null && declHash != null) type.typeHash == declHash
                                else if (type.typeName == annotation.name) true
                                else if (type.typeName == null && type.constructors.containsKey(annotation.name)) true
                                else false
                            }
                            is MezzeType.TypeApplication -> type.typeHash?.let {
                                it == typeDeclarations[annotation.name]?.contentHash
                            } ?: (type.constructor == annotation.name)
                            else -> false
                        }
                    }
                }
            }
            is ParsedTypeApp -> {
                when {
                    type is MezzeType.TypeApplication && type.constructor == annotation.constructor -> {
                        type.args.size == annotation.args.size &&
                        type.args.zip(annotation.args).all { (a, b) ->
                            typeMatchesAnnotation(a, b, typeDeclarations, typeVars, typeVarBindings)
                        }
                    }
                    type is MezzeType.VariantType && type.typeName == null -> {
                        val typeDecl = typeDeclarations[annotation.constructor]
                        if (typeDecl != null && typeDecl.definition is ParsedVariantDef) {
                            val definition = typeDecl.definition as ParsedVariantDef
                            val declConstructors = definition.constructors.map { it.name }.toSet()
                            if (!declConstructors.containsAll(type.constructors.keys)) false
                            else {
                                val inferredArgs = inferTypeArgsFromVariant(type, typeDecl, definition)
                                if (inferredArgs != null && inferredArgs.size == annotation.args.size) {
                                    inferredArgs.zip(annotation.args).all { (inf, argAnn) ->
                                        typeMatchesAnnotation(inf, argAnn, typeDeclarations, typeVars, typeVarBindings)
                                    }
                                } else false
                            }
                        } else false
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

    /**
     * Check if a type matches a type declaration (for non-parametric types).
     */
    fun typeMatchesDeclaration(type: MezzeType, typeDecl: ParsedTypeDecl): Boolean {
        if (type is MezzeType.TypeApplication) {
            val hashMatch = type.typeHash != null && typeDecl.contentHash != null && type.typeHash == typeDecl.contentHash
            return hashMatch || (type.constructor == typeDecl.name && type.args.isEmpty())
        }

        return when (val definition = typeDecl.definition) {
            is ParsedRecordDef -> {
                if (type is MezzeType.RecordType) {
                    val hashMatch = type.typeHash != null && typeDecl.contentHash != null && type.typeHash == typeDecl.contentHash
                    return hashMatch || type.typeName == typeDecl.name || type.fields.keys == definition.fields.map { it.name }.toSet()
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
                val hashMatch = type.typeHash != null && typeDecl.contentHash != null && type.typeHash == typeDecl.contentHash
                hashMatch || type.typeName == typeDecl.name ||
                definition.constructors.map { it.name }.toSet().containsAll(type.constructors.keys)
            }
            else -> false
        }
    }

    /**
     * Infer type arguments from a variant's concrete payload types by matching against
     * the type declaration's constructor patterns.
     */
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

    /**
     * Extract type parameter mappings by comparing a type annotation with a concrete type.
     */
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
