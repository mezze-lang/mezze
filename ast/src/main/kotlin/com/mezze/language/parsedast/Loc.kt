package com.mezze.language.parsedast

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Source location with full span — required on every AST node.
 *
 * Both start and end are inclusive, 1-indexed.
 * Maps directly to Truffle SourceSection.
 */
@Serializable(with = LocSerializer::class)
data class Loc(
    val file: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int
) {
    override fun toString(): String = "$file:$startLine:$startCol-$endLine:$endCol"

    /**
     * Create a Loc spanning from this loc's start to another loc's end.
     */
    fun spanTo(other: Loc): Loc = Loc(file, startLine, startCol, other.endLine, other.endCol)
}

/**
 * [Loc] as its five fields, always. Inside [com.mezze.language.ast.TypeSharing]
 * a location in the same file as the one before it writes its file as "" — and
 * a file that really is "" as [EMPTY_FILE] — so a module's thousands of
 * locations carry its path once.
 */
object LocSerializer : KSerializer<Loc> {
    private const val EMPTY_FILE = "\u0000"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.mezze.language.parsedast.Loc") {
        element<String>("file")
        element<Int>("startLine")
        element<Int>("startCol")
        element<Int>("endLine")
        element<Int>("endCol")
    }

    override fun serialize(encoder: Encoder, value: Loc) {
        val last = com.mezze.language.ast.TypeSharing.lastFile.get()
        val file = when {
            last == null -> value.file
            value.file == last[0] -> ""
            else -> { last[0] = value.file; value.file.ifEmpty { EMPTY_FILE } }
        }
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, file)
            encodeIntElement(descriptor, 1, value.startLine)
            encodeIntElement(descriptor, 2, value.startCol)
            encodeIntElement(descriptor, 3, value.endLine)
            encodeIntElement(descriptor, 4, value.endCol)
        }
    }

    override fun deserialize(decoder: Decoder): Loc {
        var file = ""
        var startLine = 0; var startCol = 0; var endLine = 0; var endCol = 0
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val i = decodeElementIndex(descriptor)) {
                    0 -> file = decodeStringElement(descriptor, 0)
                    1 -> startLine = decodeIntElement(descriptor, 1)
                    2 -> startCol = decodeIntElement(descriptor, 2)
                    3 -> endLine = decodeIntElement(descriptor, 3)
                    4 -> endCol = decodeIntElement(descriptor, 4)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("unexpected Loc element $i")
                }
            }
        }
        val last = com.mezze.language.ast.TypeSharing.lastFile.get()
        if (last != null) {
            file = when (file) {
                "" -> last[0] ?: error("a location wrote its file as the previous one's, and there was none")
                EMPTY_FILE -> "".also { last[0] = it }
                else -> file.also { last[0] = it }
            }
        }
        return Loc(file, startLine, startCol, endLine, endCol)
    }
}

/**
 * Visibility modifier — pub or private (default).
 */
@Serializable
enum class Visibility {
    PUBLIC, PRIVATE
}

/**
 * A comment in source code.
 *
 * `#`  = regular comment (formatter-only, dropped after formatting)
 * `##` = doc comment (carried into TypedAST, used for docs/IDE hover)
 */
@Serializable
data class ParsedComment(
    val text: String,
    val isDoc: Boolean,
    val loc: Loc
)
