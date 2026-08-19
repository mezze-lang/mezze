package com.mezze.language.typechecker
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #209: a newtype constructor must enforce its declared wrapped TYPE at the
 * CONSTRUCTION site — `Meters { val = "boom" }` on `newtype Meters = Meters Int`
 * used to type-check (the payload validation only ran for `ParsedVariantDef`,
 * never `ParsedNewtypeDef`). Two agents converged on this. Fixed: newtype
 * construction now pins each field to its declared type. The scalar-wrapper field
 * NAME stays flexible on purpose — the codebase uses both `{ val = … }` and
 * `{ value = … }` interchangeably — so only the wrapped type is enforced, not the
 * field name. Generic newtypes (`Box 'a`) still accept any element type.
 */
class NewtypeConstructionSoundnessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker
    @BeforeEach fun s() { ModuleResolver.clearCache(); TypeChecker.clearCache(); parser = MezzeParser(); tc = TypeChecker() }
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    @Test fun `scalar newtype rejects a wrong-typed wrapped value`() {
        assertNotNull(err("newtype Meters = Meters Int\nlet a = Meters { val = \"boom\" }"),
            "Str into a `Meters Int` must be rejected")
    }
    @Test fun `scalar newtype accepts either val or value as the field name`() {
        // The codebase uses both spellings; only the wrapped TYPE is enforced.
        assertNull(err("newtype Meters = Meters Int\nlet a = Meters { val = 5 }"))
        assertNull(err("newtype Meters = Meters Int\nlet a = Meters { value = 5 }"))
    }
    @Test fun `scalar newtype rejects a wrong-typed value under the value spelling`() {
        assertNotNull(err("newtype Meters = Meters Int\nlet a = Meters { value = \"boom\" }"),
            "wrong wrapped type must be rejected regardless of the field spelling")
    }
    @Test fun `record newtype rejects a wrong-typed field`() {
        assertNotNull(err("newtype Point = Point { x: Int, y: Int }\nlet p = Point { x = \"no\", y = 2 }"))
    }
    @Test fun `correct scalar newtype is accepted`() {
        assertNull(err("newtype Meters = Meters Int\nlet a = Meters { val = 5 }"))
    }
    @Test fun `correct record newtype is accepted`() {
        assertNull(err("newtype Point = Point { x: Int, y: Int }\nlet p = Point { x = 1, y = 2 }"))
    }
    @Test fun `generic newtype accepts any element type`() {
        assertNull(err("newtype Box 'a = Box 'a\nlet a = Box { val = 5 }"))
        assertNull(err("newtype Box 'a = Box 'a\nlet a = Box { val = \"hi\" }"))
    }
    @Test fun `a constructed newtype round-trips through its own type`() {
        assertNull(err("newtype Meters = Meters Int\nlet f = { m: Meters } -> m\nlet g = {} -> f { m = Meters { val = 5 } }"))
    }
}
