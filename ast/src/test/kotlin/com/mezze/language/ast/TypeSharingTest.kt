package com.mezze.language.ast

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeSharingTest {
    private val point = MezzeType.RecordType(mapOf("x" to MezzeType.IntType, "y" to MezzeType.IntType))
    private val shape = MezzeType.VariantType(mapOf("A" to point, "B" to MezzeType.RecordType(mapOf("p" to point, "q" to point))))
    /** A type whose parts repeat: `shape` sixteen times over. */
    private val big = MezzeType.FunctionType(
        MezzeType.RecordType((1..16).associate { "f$it" to shape }),
        MezzeType.TypeApplication("Seq", listOf(shape)),
    )
    private val json = Json { allowStructuredMapKeys = true }

    @Test
    fun `a type written shared reads back equal, smaller, with its repeats one object`() {
        val plain = json.encodeToString(MezzeTypeSerializer, big)
        val shared = TypeSharing.write { json.encodeToString(MezzeTypeSerializer, big) }
        assertTrue(shared.length * 3 < plain.length, "shared ${shared.length} vs plain ${plain.length}")
        val back = TypeSharing.read { json.decodeFromString(MezzeTypeSerializer, shared) } as MezzeType.FunctionType
        assertEquals(big, back)
        val fields = (back.paramType as MezzeType.RecordType).fields
        assertSame(fields["f1"], fields["f2"], "equal parts are one object after reading")
    }

    @Test
    fun `outside a sharing scope types are written as they always were`() {
        val plain = json.encodeToString(MezzeTypeSerializer, big)
        assertEquals(big, json.decodeFromString(MezzeTypeSerializer, plain))
    }

    private val locs = listOf(
        com.mezze.language.parsedast.Loc("src/a.mz", 1, 2, 3, 4),
        com.mezze.language.parsedast.Loc("src/a.mz", 5, 6, 7, 8),
        com.mezze.language.parsedast.Loc("", 1, 1, 1, 1),
        com.mezze.language.parsedast.Loc("", 2, 2, 2, 2),
        com.mezze.language.parsedast.Loc("src/b.mz", 9, 9, 9, 9),
        com.mezze.language.parsedast.Loc("src/a.mz", 1, 1, 1, 1),
    )
    private val locList = kotlinx.serialization.builtins.ListSerializer(com.mezze.language.parsedast.Loc.serializer())

    @Test
    fun `locations written shared carry each file once and read back equal`() {
        val plain = json.encodeToString(locList, locs)
        val shared = TypeSharing.write { json.encodeToString(locList, locs) }
        assertEquals(1, Regex("src/a.mz").findAll(shared).count() - 1, "a.mz written again only after b.mz: $shared")
        assertTrue(shared.length < plain.length)
        assertEquals(locs, TypeSharing.read { json.decodeFromString(locList, shared) })
    }

    @Test
    fun `outside a sharing scope locations are written in full`() {
        val plain = json.encodeToString(locList, locs)
        assertEquals(3, Regex("src/a.mz").findAll(plain).count())
        assertEquals(locs, json.decodeFromString(locList, plain))
    }
}
