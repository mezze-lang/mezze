package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Nested

class DocCommentAndMetadataTest {

    private val parser = MezzeParser()

    @Nested
    inner class DocComments {

        @Test
        fun `single doc comment attaches to declaration`() {
            val module = parser.parseParsedModule("""
## This is a doc comment
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertEquals("This is a doc comment", decl.docComment)
        }

        @Test
        fun `multiple doc comment lines merge into one string`() {
            val module = parser.parseParsedModule("""
## Line one
## Line two
## Line three
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertEquals("Line one\nLine two\nLine three", decl.docComment)
        }

        @Test
        fun `doc comment on pub let`() {
            val module = parser.parseParsedModule("""
## Public function docs
pub let add = {x, y} -> x + y
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertEquals("Public function docs", decl.docComment)
        }

        @Test
        fun `doc comment on type declaration`() {
            val module = parser.parseParsedModule("""
## A point in 2D space
type Point = {x: Int, y: Int}
""".trimIndent())
            val decl = module.decls[0] as ParsedTypeDecl
            assertEquals("A point in 2D space", decl.docComment)
        }

        @Test
        fun `doc comment on ability declaration`() {
            val module = parser.parseParsedModule("""
## Equality comparison
ability Eq for 'a where {
  eq: {it: 'a, r: 'a} -> Bool
}
""".trimIndent())
            val decl = module.decls[0] as ParsedAbilityDecl
            assertEquals("Equality comparison", decl.docComment)
        }

        @Test
        fun `doc comment on effect declaration`() {
            val module = parser.parseParsedModule("""
## Logging effect
effect Log where {
  log: {msg: Str} -> {} <Log>
}
""".trimIndent())
            val decl = module.decls[0] as ParsedEffectDecl
            assertEquals("Logging effect", decl.docComment)
        }

        @Test
        fun `no doc comment when none present`() {
            val module = parser.parseParsedModule("let x = 42")
            val decl = module.decls[0] as ParsedLetDecl
            assertNull(decl.docComment)
        }

        @Test
        fun `doc comment strips leading space after hash`() {
            // A doc comment requires a space after `##` (see
            // CommentSyntaxTighteningTest — `##Without` is now rejected). The
            // single separating space is stripped; extra spaces are kept.
            val module = parser.parseParsedModule("""
## With space
##  Double space
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertEquals("With space\n Double space", decl.docComment)
        }
    }

    @Nested
    inner class Metadata {

        @Test
        fun `simple metadata with boolean`() {
            val module = parser.parseParsedModule("""
#{deprecated: True}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.BoolVal(true),
                decl.metadata!!.fields["deprecated"]
            )
        }

        @Test
        fun `metadata with string value`() {
            val module = parser.parseParsedModule("""
#{since: "0.5.0"}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.StringVal("0.5.0"),
                decl.metadata!!.fields["since"]
            )
        }

        @Test
        fun `metadata with integer value`() {
            val module = parser.parseParsedModule("""
#{timeout: 5000}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.IntVal(5000),
                decl.metadata!!.fields["timeout"]
            )
        }

        @Test
        fun `metadata with float value`() {
            val module = parser.parseParsedModule("""
#{threshold: 0.95}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.FloatVal(0.95),
                decl.metadata!!.fields["threshold"]
            )
        }

        @Test
        fun `metadata with list value`() {
            val module = parser.parseParsedModule("""
#{tags: ["math", "pure"]}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            val tags = decl.metadata!!.fields["tags"] as ParsedMetadataValue.ListVal
            assertEquals(2, tags.values.size)
            assertEquals(ParsedMetadataValue.StringVal("math"), tags.values[0])
            assertEquals(ParsedMetadataValue.StringVal("pure"), tags.values[1])
        }

        @Test
        fun `metadata with nested record value`() {
            val module = parser.parseParsedModule("""
#{config: {timeout: 5000, retries: 3}}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            val config = decl.metadata!!.fields["config"] as ParsedMetadataValue.RecordVal
            assertEquals(ParsedMetadataValue.IntVal(5000), config.fields["timeout"])
            assertEquals(ParsedMetadataValue.IntVal(3), config.fields["retries"])
        }

        @Test
        fun `metadata with multiple fields`() {
            val module = parser.parseParsedModule("""
#{deprecated: True, since: "0.5.0", replacement: "new_func"}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            val meta = decl.metadata!!.fields
            assertEquals(ParsedMetadataValue.BoolVal(true), meta["deprecated"])
            assertEquals(ParsedMetadataValue.StringVal("0.5.0"), meta["since"])
            assertEquals(ParsedMetadataValue.StringVal("new_func"), meta["replacement"])
        }

        @Test
        fun `empty metadata`() {
            val module = parser.parseParsedModule("""
#{}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertTrue(decl.metadata!!.fields.isEmpty())
        }

        @Test
        fun `no metadata when none present`() {
            val module = parser.parseParsedModule("let x = 42")
            val decl = module.decls[0] as ParsedLetDecl
            assertNull(decl.metadata)
        }

        @Test
        fun `metadata with False boolean`() {
            val module = parser.parseParsedModule("""
#{inline: False}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.BoolVal(false),
                decl.metadata!!.fields["inline"]
            )
        }

        @Test
        fun `metadata with negative integer`() {
            val module = parser.parseParsedModule("""
#{priority: -1}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.IntVal(-1),
                decl.metadata!!.fields["priority"]
            )
        }

        @Test
        fun `metadata with empty list`() {
            val module = parser.parseParsedModule("""
#{tags: []}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            val tags = decl.metadata!!.fields["tags"] as ParsedMetadataValue.ListVal
            assertTrue(tags.values.isEmpty())
        }
    }

    @Nested
    inner class DocAndMetadataCombined {

        @Test
        fun `doc comment then metadata both attach`() {
            val module = parser.parseParsedModule("""
## Sorts a sequence
#{since: "0.1.0"}
pub let sort = {xs} -> xs
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertEquals("Sorts a sequence", decl.docComment)
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.StringVal("0.1.0"),
                decl.metadata!!.fields["since"]
            )
        }

        @Test
        fun `multi-line doc then metadata`() {
            val module = parser.parseParsedModule("""
## Line one
## Line two
#{deprecated: True, replacement: "new_sort"}
pub let sort = {xs} -> xs
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertEquals("Line one\nLine two", decl.docComment)
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.BoolVal(true),
                decl.metadata!!.fields["deprecated"]
            )
            assertEquals(
                ParsedMetadataValue.StringVal("new_sort"),
                decl.metadata!!.fields["replacement"]
            )
        }
    }

    @Nested
    inner class ResetRules {

        @Test
        fun `blank line resets doc comment`() {
            val module = parser.parseParsedModule("""
## This doc is LOST

let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNull(decl.docComment)
        }

        @Test
        fun `blank line resets metadata`() {
            val module = parser.parseParsedModule("""
#{deprecated: True}

let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNull(decl.metadata)
        }

        @Test
        fun `regular comment resets doc comment`() {
            val module = parser.parseParsedModule("""
## This doc is LOST
# regular comment breaks it
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNull(decl.docComment)
        }

        @Test
        fun `regular comment resets metadata`() {
            val module = parser.parseParsedModule("""
#{deprecated: True}
# regular comment breaks it
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNull(decl.metadata)
        }

        @Test
        fun `regular comment resets both doc and metadata`() {
            val module = parser.parseParsedModule("""
## Doc is LOST
#{deprecated: True}
# regular comment breaks everything
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNull(decl.docComment)
            assertNull(decl.metadata)
        }

        @Test
        fun `only last contiguous block survives after reset`() {
            val module = parser.parseParsedModule("""
## First doc is LOST
# break
## Second doc survives
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertEquals("Second doc survives", decl.docComment)
        }

        @Test
        fun `metadata after reset attaches`() {
            val module = parser.parseParsedModule("""
## Doc is LOST
# break
#{deprecated: True}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNull(decl.docComment)
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.BoolVal(true),
                decl.metadata!!.fields["deprecated"]
            )
        }

        @Test
        fun `blank line between declarations does not bleed`() {
            val module = parser.parseParsedModule("""
## Doc for x
let x = 42

## Doc for y
let y = 99
""".trimIndent())
            val declX = module.decls[0] as ParsedLetDecl
            val declY = module.decls[1] as ParsedLetDecl
            assertEquals("Doc for x", declX.docComment)
            assertEquals("Doc for y", declY.docComment)
        }

        @Test
        fun `doc on first decl does not leak to second`() {
            val module = parser.parseParsedModule("""
## Doc for x
let x = 42
let y = 99
""".trimIndent())
            val declX = module.decls[0] as ParsedLetDecl
            val declY = module.decls[1] as ParsedLetDecl
            assertEquals("Doc for x", declX.docComment)
            assertNull(declY.docComment)
        }
    }

    @Nested
    inner class OrderingViolations {

        @Test
        fun `doc after metadata causes reset of the metadata`() {
            // ## after #{} is invalid ordering — triggers a reset
            val module = parser.parseParsedModule("""
#{deprecated: True}
## Doc after metadata
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            // The ## after #{} resets everything and starts fresh
            // Only the ## survives as the new contiguous block
            assertEquals("Doc after metadata", decl.docComment)
            assertNull(decl.metadata)
        }

        @Test
        fun `second metadata block overwrites first`() {
            // Two #{} blocks — second overwrites (error case, but graceful)
            val module = parser.parseParsedModule("""
#{deprecated: True}
#{since: "0.5.0"}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            // Second block overwrites — has 'since' but not 'deprecated'
            assertNotNull(decl.metadata!!.fields["since"])
        }
    }

    @Nested
    inner class RegularComments {

        @Test
        fun `regular comments are preserved for formatter`() {
            val module = parser.parseParsedModule("""
# regular comment
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            // Regular comments go to leadingComments (for formatter)
            assertEquals(1, decl.leadingComments.size)
            assertEquals("# regular comment", decl.leadingComments[0].text)
            // But not to docComment
            assertNull(decl.docComment)
        }

        @Test
        fun `regular comment before doc comment is preserved separately`() {
            // The regular comment resets, so only doc after it attaches
            val module = parser.parseParsedModule("""
# A note
## The actual doc
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertEquals("The actual doc", decl.docComment)
            // The regular comment should be in leadingComments
            assertEquals(1, decl.leadingComments.size)
        }

        @Test
        fun `trailing comment still works`() {
            val module = parser.parseParsedModule("""
## Doc for x
let x = 42 # trailing
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertEquals("Doc for x", decl.docComment)
            assertNotNull(decl.trailingComment)
            assertEquals("# trailing", decl.trailingComment!!.text)
        }
    }

    @Nested
    inner class AllDeclarationTypes {

        @Test
        fun `metadata on use declaration`() {
            val module = parser.parseParsedModule("""
#{deprecated: True}
use std::int
""".trimIndent())
            val decl = module.decls[0] as ParsedUseDecl
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.BoolVal(true),
                decl.metadata!!.fields["deprecated"]
            )
        }

        @Test
        fun `metadata on top level expression`() {
            val module = parser.parseParsedModule("""
#{description: "entry point"}
perform Console in println {msg = "hello"}
""".trimIndent())
            val decl = module.decls[0] as ParsedTopLevelExpr
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.StringVal("entry point"),
                decl.metadata!!.fields["description"]
            )
        }

        @Test
        fun `doc and metadata on performer`() {
            val module = parser.parseParsedModule("""
## Console logging performer
#{since: "0.1.0"}
impl Log for Console where {
  log = {msg} -> __native_print {msg}
}
""".trimIndent())
            val decl = module.decls[0] as ParsedPerformerDecl
            assertEquals("Console logging performer", decl.docComment)
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.StringVal("0.1.0"),
                decl.metadata!!.fields["since"]
            )
        }
    }

    @Nested
    inner class MetadataParserEdgeCases {

        @Test
        fun `metadata with string containing escaped quotes`() {
            val module = parser.parseParsedModule("""
#{msg: "hello \"world\""}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.StringVal("hello \"world\""),
                decl.metadata!!.fields["msg"]
            )
        }

        @Test
        fun `metadata with underscore in key`() {
            val module = parser.parseParsedModule("""
#{my_key: True}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertEquals(
                ParsedMetadataValue.BoolVal(true),
                decl.metadata!!.fields["my_key"]
            )
        }

        @Test
        fun `metadata with list of integers`() {
            val module = parser.parseParsedModule("""
#{versions: [1, 2, 3]}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            val versions = decl.metadata!!.fields["versions"] as ParsedMetadataValue.ListVal
            assertEquals(3, versions.values.size)
            assertEquals(ParsedMetadataValue.IntVal(1), versions.values[0])
            assertEquals(ParsedMetadataValue.IntVal(2), versions.values[1])
            assertEquals(ParsedMetadataValue.IntVal(3), versions.values[2])
        }

        @Test
        fun `metadata with trailing comma`() {
            val module = parser.parseParsedModule("""
#{deprecated: True, since: "0.5.0",}
let x = 42
""".trimIndent())
            val decl = module.decls[0] as ParsedLetDecl
            assertNotNull(decl.metadata)
            assertEquals(2, decl.metadata!!.fields.size)
        }

        @Test
        fun `unclosed metadata is a parse error`() {
            // `#{` must be a well-formed `#{ … }` on one line. An unclosed
            // `#{broken` is a typo, not a comment, and is rejected at lex time
            // (see CommentSyntaxTighteningTest).
            assertThrows<ParseException> {
                parser.parseParsedModule("#{broken\nlet x = 42")
            }
        }
    }

    @Nested
    inner class MultipleDeclarations {

        @Test
        fun `each declaration gets its own doc and metadata`() {
            val module = parser.parseParsedModule("""
## First function
#{test: True}
let func_a = {} -> 1

## Second function
#{test: True}
let func_b = {} -> 2
""".trimIndent())
            val declA = module.decls[0] as ParsedLetDecl
            val declB = module.decls[1] as ParsedLetDecl
            assertEquals("First function", declA.docComment)
            assertNotNull(declA.metadata)
            assertEquals("Second function", declB.docComment)
            assertNotNull(declB.metadata)
        }

        @Test
        fun `declaration without annotations between annotated declarations`() {
            val module = parser.parseParsedModule("""
## Has doc
let x = 1

let y = 2

## Also has doc
let z = 3
""".trimIndent())
            val declX = module.decls[0] as ParsedLetDecl
            val declY = module.decls[1] as ParsedLetDecl
            val declZ = module.decls[2] as ParsedLetDecl
            assertEquals("Has doc", declX.docComment)
            assertNull(declY.docComment)
            assertEquals("Also has doc", declZ.docComment)
        }
    }

    @Nested
    inner class VariantConstructorAnnotations {

        @Test
        fun `variant constructor without annotations parses fine`() {
            val module = parser.parseParsedModule("""
type Color = | Red, Green, Blue |
""".trimIndent())
            val decl = module.decls[0] as ParsedTypeDecl
            val variant = decl.definition as ParsedVariantDef
            assertEquals(3, variant.constructors.size)
            assertNull(variant.constructors[0].docComment)
            assertNull(variant.constructors[0].metadata)
            assertNull(variant.constructors[1].docComment)
            assertNull(variant.constructors[2].docComment)
        }

        // NOTE: Doc/metadata on individual variant constructors and record fields
        // requires changes to sepEndByWithNewlines (which strips comments between items).
        // The AST fields (docComment, metadata) are ready on ParsedVariantCtorDef and
        // ParsedRecordFieldDef — the parser just needs to be updated to collect them.
        // This is deferred to a follow-up since it requires more invasive parser changes.
    }
}
