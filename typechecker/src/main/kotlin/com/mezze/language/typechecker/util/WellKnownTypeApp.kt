package com.mezze.language.typechecker.util

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typedast.Hash

internal object WellKnownTypeApp {

    fun of(
        constructor: String,
        args: List<MezzeType> = emptyList(),
        typeHash: Hash? = null
    ): MezzeType.TypeApplication {
        return MezzeType.TypeApplication(
            constructor = constructor,
            args = args,
            typeHash = typeHash ?: WellKnownHashConstants.resolveTypeHash(constructor)
        )
    }

    fun result(ok: MezzeType, err: MezzeType): MezzeType.TypeApplication =
        of("Result", listOf(ok, err))

    fun option(value: MezzeType): MezzeType.TypeApplication =
        of("Option", listOf(value))
}
