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
                    // #194: VariantType carries (typeName, typeArgs) for polymorphic
                    // uses, mirroring TypeApplication.
                    type is MezzeType.VariantType && type.typeName == annotation.constructor &&
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
            is ParsedNewtypeDef -> {
                // A newtype `T = T <wrapped>` constructed as `T { … }` (or `T x`)
                // solves to a single-constructor VariantType named after the
                // newtype's constructor. Match by hash, by the carried typeName,
                // or by that sole constructor name — otherwise a newtype receiver
                // (`(Money{val=5}).method{}`, whose inferred type is the anonymous
                // `| Money {…} |`) never matches `impl … for Money`.
                if (type !is MezzeType.VariantType) return false
                val hashMatch = type.typeHash != null && typeDecl.contentHash != null && type.typeHash == typeDecl.contentHash
                hashMatch || type.typeName == typeDecl.name ||
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
