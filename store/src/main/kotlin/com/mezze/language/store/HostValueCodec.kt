package com.mezze.language.store

import com.mezze.language.comptime.HostValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/** [HostValue]s as bytes, for what the store keeps of them: a macro's expansions. */
@OptIn(ExperimentalSerializationApi::class)
object HostValueCodec {
    private val cbor = Cbor { }

    fun encode(value: HostValue): ByteArray = cbor.encodeToByteArray(HostValue.serializer(), value)

    fun decode(bytes: ByteArray): HostValue = cbor.decodeFromByteArray(HostValue.serializer(), bytes)
}
