package com.mezze.language.cli

import com.mezze.language.typechecker.StdlibSnapshotData
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The precompiled stdlib must carry EVERY stdlib identity, not just the prelude's.
 *
 * The native binary ships no stdlib sources, so the snapshot is the only place it
 * can learn, say, `Decode`'s identity. The typed modules in the snapshot are the
 * prelude's alone; before the identity table existed, a codec check in a native
 * `mez check` missed, fell back to stamping stdlib sources that were not there,
 * and every check crashed at startup.
 */
class StdlibSnapshotIdentitiesTest {

    @Test
    fun `the snapshot carries identities for modules the prelude never loads`() {
        assertTrue(StdlibSnapshotData.present, "this test runs where the precompiled stdlib is on the classpath")
        val table = StdlibSnapshotData.identities
        assertNotNull(table, "the snapshot has no identity table")
        table!!
        for (ability in listOf("Eq", "Ord", "Hash", "Inspect", "NumAdd", "Encode", "Decode", "FromJson", "Arbitrary", "Embeddable")) {
            assertTrue(ability in table.abilities, "ability $ability missing from the snapshot's identities")
        }
        for (effect in listOf("Mut", "Comptime", "Log", "Sample")) {
            assertTrue(effect in table.effects, "effect $effect missing from the snapshot's identities")
        }
        for (type in listOf("Nat", "Json")) {
            assertTrue(type in table.types, "type $type missing from the snapshot's identities")
        }
        assertTrue("from_json" in table.abilityOps["FromJson"].orEmpty())
    }
}
