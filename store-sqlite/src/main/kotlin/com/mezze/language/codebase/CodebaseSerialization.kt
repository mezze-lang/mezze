@file:Suppress("unused")
package com.mezze.language.codebase

/**
 * Re-export from store module.
 *
 * The canonical definition lives in com.mezze.language.store.StoreSerialization.
 * This object delegates to it for backward compatibility.
 *
 * New code should use com.mezze.language.store.StoreSerialization directly.
 */
object CodebaseSerialization {
    private val delegate = com.mezze.language.store.StoreSerialization

    fun serializeType(type: com.mezze.language.ast.MezzeType) = delegate.serializeType(type)
    fun deserializeType(bytes: ByteArray) = delegate.deserializeType(bytes)
    fun serializeTypeList(types: List<com.mezze.language.ast.MezzeType>) = delegate.serializeTypeList(types)
    fun deserializeTypeList(bytes: ByteArray) = delegate.deserializeTypeList(bytes)
    fun serializeTypedModule(module: com.mezze.language.typedast.TypedModule) = delegate.serializeTypedModule(module)
    fun deserializeTypedModule(bytes: ByteArray) = delegate.deserializeTypedModule(bytes)
    fun serializeParsedModule(module: com.mezze.language.parsedast.ParsedModule) = delegate.serializeParsedModule(module)
    fun deserializeParsedModule(bytes: ByteArray) = delegate.deserializeParsedModule(bytes)
    fun serializeFunctionIR(fn: com.mezze.language.truffleir.FunctionIR) = delegate.serializeFunctionIR(fn)
    fun deserializeFunctionIR(bytes: ByteArray) = delegate.deserializeFunctionIR(bytes)
    fun serializeFunctionIRList(fns: List<com.mezze.language.truffleir.FunctionIR>) = delegate.serializeFunctionIRList(fns)
    fun deserializeFunctionIRList(bytes: ByteArray) = delegate.deserializeFunctionIRList(bytes)
    fun serializeTypedLetDecl(decl: com.mezze.language.typedast.TypedLetDecl) = delegate.serializeTypedLetDecl(decl)
    fun deserializeTypedLetDecl(bytes: ByteArray) = delegate.deserializeTypedLetDecl(bytes)
    fun serializeTypedTypeDecl(decl: com.mezze.language.typedast.TypedTypeDecl) = delegate.serializeTypedTypeDecl(decl)
    fun deserializeTypedTypeDecl(bytes: ByteArray) = delegate.deserializeTypedTypeDecl(bytes)
    fun serializeTypedAbilityDecl(decl: com.mezze.language.typedast.TypedAbilityDecl) = delegate.serializeTypedAbilityDecl(decl)
    fun deserializeTypedAbilityDecl(bytes: ByteArray) = delegate.deserializeTypedAbilityDecl(bytes)
    fun serializeTypedAbilityImpl(impl: com.mezze.language.typedast.TypedAbilityImpl) = delegate.serializeTypedAbilityImpl(impl)
    fun deserializeTypedAbilityImpl(bytes: ByteArray) = delegate.deserializeTypedAbilityImpl(bytes)
    fun serializeTypedEffectDecl(decl: com.mezze.language.typedast.TypedEffectDecl) = delegate.serializeTypedEffectDecl(decl)
    fun deserializeTypedEffectDecl(bytes: ByteArray) = delegate.deserializeTypedEffectDecl(bytes)
    fun serializeTypedPerformerDecl(decl: com.mezze.language.typedast.TypedPerformerDecl) = delegate.serializeTypedPerformerDecl(decl)
    fun deserializeTypedPerformerDecl(bytes: ByteArray) = delegate.deserializeTypedPerformerDecl(bytes)
}
