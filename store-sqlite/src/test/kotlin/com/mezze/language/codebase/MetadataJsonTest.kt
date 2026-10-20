package com.mezze.language.codebase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A declaration's `#{ … }` metadata is stored as JSON — the text `std::codebase`
 * hands a program to decode into its own type. It must BE JSON, for any value
 * the metadata syntax can write.
 *
 * It was assembled by string concatenation that escaped only `"`, so a note
 * holding a newline or a backslash (both writable: `"\n"`, `"\\"`) was stored as
 * text no JSON parser accepts, and every decode of it failed.
 */
class MetadataJsonTest {

    @Test
    fun `metadata is stored as JSON, whatever its strings hold`() {
        val store = SQLiteMezzeStore.inMemory()
        indexSource(store, """
            #{ note: "line one\nC:\\tmp \"quoted\"", limits: { rps: 10 }, verbs: ["GET", "POST"] }
            let handler = {} -> 1
        """.trimIndent())

        val text = store.getDeclsForModule("app").single { it.declName == "handler" }.metadata!!
        val json = Json.parseToJsonElement(text).jsonObject

        assertEquals("line one\nC:\\tmp \"quoted\"", json["note"]!!.jsonPrimitive.content)
        assertEquals(10, json["limits"]!!.jsonObject["rps"]!!.jsonPrimitive.int)
        assertEquals(listOf("GET", "POST"), json["verbs"]!!.jsonArray.map { it.jsonPrimitive.content })
        store.close()
    }
}
