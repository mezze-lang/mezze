package com.mezze.language.codebase

import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SQLiteCodebaseTest {

    private var codebase: SQLiteCodebase? = null

    @AfterEach
    fun cleanup() {
        codebase?.close()
    }

    @Test
    fun `in-memory codebase basic operations`() {
        codebase = SQLiteCodebase.inMemory()

        val hash = MezzeHash.fromString("test definition content")
        val astBlob = byteArrayOf(1, 2, 3, 4)

        // Store definition
        codebase!!.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.FUNCTION,
            name = "myFunction",
            modulePath = "test::module",
            visibility = Visibility.PUBLIC,
            astBlob = astBlob
        ))

        // Retrieve definition
        val retrieved = codebase!!.getDefinition(hash)
        assertNotNull(retrieved)
        assertEquals("myFunction", retrieved.name)
        assertEquals(DefinitionKind.FUNCTION, retrieved.kind)
        assertEquals("test::module", retrieved.modulePath)
        assertTrue(astBlob.contentEquals(retrieved.astBlob))
    }

    @Test
    fun `name registration and lookup`() {
        codebase = SQLiteCodebase.inMemory()

        val hash = MezzeHash.fromString("function content")

        // Store definition first
        codebase!!.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.FUNCTION,
            name = "foo",
            modulePath = null,
            visibility = Visibility.PUBLIC,
            astBlob = byteArrayOf()
        ))

        // Register name
        codebase!!.putName(null, "foo", hash, isPrimary = true)
        codebase!!.putName("std::prelude", "foo", hash, isPrimary = false)

        // Lookup by name
        val foundHash = codebase!!.getHashByName(null, "foo")
        assertEquals(hash, foundHash)

        // Lookup by qualified name
        val qualifiedHash = codebase!!.getHashByName("std::prelude", "foo")
        assertEquals(hash, qualifiedHash)

        // Reverse lookup
        val names = codebase!!.getNamesByHash(hash)
        assertEquals(2, names.size)
        assertTrue(names.any { it.name == "foo" && it.modulePath == null && it.isPrimary })
        assertTrue(names.any { it.name == "foo" && it.modulePath == "std::prelude" && !it.isPrimary })
    }

    @Test
    fun `type storage and retrieval`() {
        codebase = SQLiteCodebase.inMemory()

        val hash = MezzeHash.fromString("typed function")

        // Store definition
        codebase!!.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.FUNCTION,
            name = "typedFunc",
            modulePath = null,
            visibility = Visibility.PUBLIC,
            astBlob = byteArrayOf()
        ))

        // Store type
        codebase!!.putType(hash, MezzeType.IntType, "Int")

        // Retrieve type
        val storedType = codebase!!.getType(hash)
        assertNotNull(storedType)
        assertEquals("Int", storedType.typeText)

        // Deserialize type
        val deserializedType = CodebaseSerialization.deserializeType(storedType.typeBlob)
        assertEquals(MezzeType.IntType, deserializedType)
    }

    @Test
    fun `dependency tracking`() {
        codebase = SQLiteCodebase.inMemory()

        val hashA = MezzeHash.fromString("function A")
        val hashB = MezzeHash.fromString("function B")
        val hashC = MezzeHash.fromString("function C")

        // Store definitions
        listOf(hashA to "funcA", hashB to "funcB", hashC to "funcC").forEach { (hash, name) ->
            codebase!!.putDefinition(StoredDefinition(
                hash = hash,
                kind = DefinitionKind.FUNCTION,
                name = name,
                modulePath = null,
                visibility = Visibility.PUBLIC,
                astBlob = byteArrayOf()
            ))
        }

        // A calls B and C
        codebase!!.addDependency(hashA, hashB, DependencyKind.CALLS)
        codebase!!.addDependency(hashA, hashC, DependencyKind.CALLS)

        // Check dependencies
        val depsOfA = codebase!!.getDependencies(hashA)
        assertEquals(2, depsOfA.size)
        assertTrue(depsOfA.any { it.toHash == hashB })
        assertTrue(depsOfA.any { it.toHash == hashC })

        // Check dependents (reverse)
        val dependentsOfB = codebase!!.getDependents(hashB)
        assertEquals(1, dependentsOfB.size)
        assertEquals(hashA, dependentsOfB[0].fromHash)
    }

    @Test
    fun `module registration`() {
        codebase = SQLiteCodebase.inMemory()

        codebase!!.putModule(StoredModule(
            path = "std::list",
            filePath = "/path/to/list.mz",
            sourceHash = "abc123",
            isStdlib = true,
            visibility = Visibility.PUBLIC
        ))

        codebase!!.putModule(StoredModule(
            path = "myapp::utils",
            filePath = "/project/utils.mz",
            sourceHash = "def456",
            isStdlib = false,
            visibility = Visibility.PUBLIC
        ))

        // Lookup
        val stdModule = codebase!!.getModule("std::list")
        assertNotNull(stdModule)
        assertTrue(stdModule.isStdlib)
        assertEquals("/path/to/list.mz", stdModule.filePath)

        // Get all
        val allModules = codebase!!.getAllModules()
        assertEquals(2, allModules.size)
    }

    @Test
    fun `export registration and lookup`() {
        codebase = SQLiteCodebase.inMemory()

        val hash = MezzeHash.fromString("exported function")

        // Store module and definition
        codebase!!.putModule(StoredModule(
            path = "mymodule",
            filePath = null,
            sourceHash = null
        ))

        codebase!!.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.FUNCTION,
            name = "exportedFunc",
            modulePath = "mymodule",
            visibility = Visibility.PUBLIC,
            astBlob = byteArrayOf()
        ))

        // Register export
        codebase!!.putExport("mymodule", "exportedFunc", hash, DefinitionKind.FUNCTION)

        // Lookup exports
        val exports = codebase!!.getExports("mymodule")
        assertEquals(1, exports.size)
        assertEquals("exportedFunc", exports[0].exportName)
        assertEquals(hash, exports[0].hash)
    }

    @Test
    fun `transaction rollback`() {
        codebase = SQLiteCodebase.inMemory()

        val hash = MezzeHash.fromString("will be rolled back")

        codebase!!.beginTransaction()
        codebase!!.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.FUNCTION,
            name = "temp",
            modulePath = null,
            visibility = Visibility.PUBLIC,
            astBlob = byteArrayOf()
        ))

        // Rollback
        codebase!!.rollback()

        // Should not exist
        assertNull(codebase!!.getDefinition(hash))
    }

    @Test
    fun `clear removes all data`() {
        codebase = SQLiteCodebase.inMemory()

        // Add some data
        val hash = MezzeHash.fromString("to be cleared")
        codebase!!.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.FUNCTION,
            name = "temp",
            modulePath = null,
            visibility = Visibility.PUBLIC,
            astBlob = byteArrayOf()
        ))
        codebase!!.putName(null, "temp", hash)
        codebase!!.putModule(StoredModule(path = "temp", filePath = null, sourceHash = null))

        // Clear
        codebase!!.clear()

        // Verify empty
        assertTrue(codebase!!.getAllDefinitionHashes().isEmpty())
        assertTrue(codebase!!.getAllModules().isEmpty())
        assertNull(codebase!!.getHashByName(null, "temp"))
    }

    @Test
    fun `toBytes and fromBytes roundtrip`() {
        val original = SQLiteCodebase.inMemory()

        val hash = MezzeHash.fromString("persistent content")
        original.putDefinition(StoredDefinition(
            hash = hash,
            kind = DefinitionKind.FUNCTION,
            name = "persistentFunc",
            modulePath = "test",
            visibility = Visibility.PUBLIC,
            astBlob = byteArrayOf(1, 2, 3)
        ))
        original.putName("test", "persistentFunc", hash)

        // Export to bytes
        val bytes = SQLiteCodebase.toBytes(original)
        original.close()

        // Import from bytes
        codebase = SQLiteCodebase.fromBytes(bytes)

        // Verify data survived
        val retrieved = codebase!!.getDefinition(hash)
        assertNotNull(retrieved)
        assertEquals("persistentFunc", retrieved.name)

        val foundHash = codebase!!.getHashByName("test", "persistentFunc")
        assertEquals(hash, foundHash)
    }

    @Test
    fun `getDefinitionsByKind filters correctly`() {
        codebase = SQLiteCodebase.inMemory()

        // Add different kinds
        val funcHash = MezzeHash.fromString("function")
        val typeHash = MezzeHash.fromString("type")
        val abilityHash = MezzeHash.fromString("ability")

        codebase!!.putDefinition(StoredDefinition(
            hash = funcHash, kind = DefinitionKind.FUNCTION,
            name = "func", modulePath = null, visibility = Visibility.PUBLIC, astBlob = byteArrayOf()
        ))
        codebase!!.putDefinition(StoredDefinition(
            hash = typeHash, kind = DefinitionKind.TYPE_RECORD,
            name = "MyType", modulePath = null, visibility = Visibility.PUBLIC, astBlob = byteArrayOf()
        ))
        codebase!!.putDefinition(StoredDefinition(
            hash = abilityHash, kind = DefinitionKind.ABILITY,
            name = "MyAbility", modulePath = null, visibility = Visibility.PUBLIC, astBlob = byteArrayOf()
        ))

        // Filter by kind
        val functions = codebase!!.getDefinitionsByKind(DefinitionKind.FUNCTION)
        assertEquals(1, functions.size)
        assertEquals("func", functions[0].name)

        val types = codebase!!.getDefinitionsByKind(DefinitionKind.TYPE_RECORD)
        assertEquals(1, types.size)
        assertEquals("MyType", types[0].name)
    }

    @Test
    fun `schema version is set`() {
        codebase = SQLiteCodebase.inMemory()
        assertEquals(SQLiteCodebase.SCHEMA_VERSION, codebase!!.getSchemaVersion())
    }

    @Test
    fun `metadata storage`() {
        codebase = SQLiteCodebase.inMemory()

        codebase!!.setMetadata("build_time", "2024-01-15T10:30:00Z")
        codebase!!.setMetadata("compiler_version", "0.1.0")

        assertEquals("2024-01-15T10:30:00Z", codebase!!.getMetadata("build_time"))
        assertEquals("0.1.0", codebase!!.getMetadata("compiler_version"))
        assertNull(codebase!!.getMetadata("nonexistent"))
    }
}
