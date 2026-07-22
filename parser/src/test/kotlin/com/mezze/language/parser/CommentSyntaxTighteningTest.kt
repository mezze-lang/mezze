package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Comment syntax is strict: `# text` (space after #), `## text` (space after
 * ##), and well-formed `#{ … }` metadata. `#foo`, `##foo`, and an unclosed
 * `#{broken` are typos and must be rejected.
 */
class CommentSyntaxTighteningTest {
    private val parser = MezzeParser()

    private fun parses(src: String) = assertDoesNotThrow { parser.parseParsedModule(src) }
    private fun rejects(src: String) = assertThrows<ParseException> { parser.parseParsedModule(src) }

    @Test fun `line comment with space parses`() = parses("# a comment\nlet x = 1")
    @Test fun `bare hash at eol parses`() = parses("#\nlet x = 1")
    @Test fun `doc comment with space parses`() = parses("## doc\nlet x = 1")
    @Test fun `metadata parses`() = parses("#{deprecated: True}\nlet x = 1")

    @Test fun `hash without space is rejected`() = rejects("#nospace\nlet x = 1")
    @Test fun `doc without space is rejected`() = rejects("##nospace\nlet x = 1")
    @Test fun `unclosed metadata is rejected`() = rejects("#{broken\nlet x = 1")
}
