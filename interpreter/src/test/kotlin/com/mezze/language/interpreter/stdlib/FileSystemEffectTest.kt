package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.stdlib.effects.FilesystemImplementations
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/effects/filesystem module.
 * Tests file system operations using MemoryFS handler (Jimfs in-memory filesystem).
 */
class FileSystemEffectTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
        // Reset in-memory filesystem before each test for isolation
        FilesystemImplementations.clearMemoryFs()
        // Also clear V2's in-memory filesystem
        com.mezze.interpreter.v2.nodes.builtin.effects.MemoryFilesystemNatives.clear()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // === MemoryFS write_file and read_file Tests ===

    @Test
    fun `test write_file and read_file roundtrip`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let write_result = FileSystem.write_file {path = "/test.txt", content = "Hello, World!"}
                match write_result is
                    Ok {val} -> do
                        let read_result = FileSystem.read_file {path = "/test.txt"}
                        match read_result is
                            Ok {val = content} -> content
                            Err {error} -> "read error"
                    Err {error} -> "write error"
        """)
        assertTrue(result is MezzeString)
        assertEquals("Hello, World!", (result as MezzeString).value)
    }

    @Test
    fun `test read_file non-existent returns FileNotFound`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let read_result = FileSystem.read_file {path = "/does_not_exist.txt"}
                match read_result is
                    Ok {val} -> False
                    Err {error} -> match error is
                        FileNotFound {path} -> True
                        _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === exists Tests ===

    @Test
    fun `test exists returns false for non-existent file`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let exists_result = FileSystem.exists {path = "/nonexistent.txt"}
                match exists_result is
                    Ok {val} -> val
                    Err {error} -> True
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test exists returns true for existing file`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/exists.txt", content = "data"}
                let exists_result = FileSystem.exists {path = "/exists.txt"}
                match exists_result is
                    Ok {val} -> val
                    Err {error} -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === delete Tests ===

    @Test
    fun `test delete removes file`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/todelete.txt", content = "data"}
                let delete_result = FileSystem.delete {path = "/todelete.txt"}
                match delete_result is
                    Ok {val} -> do
                        let exists_result = FileSystem.exists {path = "/todelete.txt"}
                        match exists_result is
                            Ok {val} -> val
                            Err {error} -> True
                    Err {error} -> True
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test delete non-existent file returns FileNotFound`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let delete_result = FileSystem.delete {path = "/nonexistent.txt"}
                match delete_result is
                    Ok {val} -> False
                    Err {error} -> match error is
                        FileNotFound {path} -> True
                        _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === create_directory Tests ===

    @Test
    fun `test create_directory creates directory`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let create_result = FileSystem.create_directory {path = "/mydir"}
                match create_result is
                    Ok {val} -> do
                        let exists_result = FileSystem.exists {path = "/mydir"}
                        match exists_result is
                            Ok {val} -> val
                            Err {error} -> False
                    Err {error} -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test create_directory already exists returns error`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.create_directory {path = "/existingdir"}
                let create_result = FileSystem.create_directory {path = "/existingdir"}
                match create_result is
                    Ok {val} -> False
                    Err {error} -> match error is
                        AlreadyExists {path} -> True
                        _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === list_directory Tests ===

    @Test
    fun `test list_directory returns files`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.create_directory {path = "/listdir"}
                let _ = FileSystem.write_file {path = "/listdir/file1.txt", content = "a"}
                let _ = FileSystem.write_file {path = "/listdir/file2.txt", content = "b"}
                let list_result = FileSystem.list_directory {path = "/listdir"}
                match list_result is
                    Ok {val} -> __native_arraylist_length {xs = val}
                    Err {error} -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    @Test
    fun `test list_directory non-existent returns FileNotFound`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let list_result = FileSystem.list_directory {path = "/nonexistent"}
                match list_result is
                    Ok {val} -> False
                    Err {error} -> match error is
                        FileNotFound {path} -> True
                        _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === file_size Tests ===

    @Test
    fun `test file_size returns content length`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/sized.txt", content = "12345"}
                let size_result = FileSystem.file_size {path = "/sized.txt"}
                match size_result is
                    Ok {val} -> val
                    Err {error} -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(5L, (result as MezzeInt).value)
    }

    @Test
    fun `test file_size non-existent returns FileNotFound`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let size_result = FileSystem.file_size {path = "/nonexistent.txt"}
                match size_result is
                    Ok {val} -> False
                    Err {error} -> match error is
                        FileNotFound {path} -> True
                        _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === Multiple Operations Tests ===

    @Test
    fun `test write overwrite existing file`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/overwrite.txt", content = "original"}
                let _ = FileSystem.write_file {path = "/overwrite.txt", content = "updated"}
                let read_result = FileSystem.read_file {path = "/overwrite.txt"}
                match read_result is
                    Ok {val} -> val
                    Err {error} -> "error"
        """)
        assertTrue(result is MezzeString)
        assertEquals("updated", (result as MezzeString).value)
    }

    @Test
    fun `test nested directory operations`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.create_directory {path = "/parent"}
                let _ = FileSystem.create_directory {path = "/parent/child"}
                let _ = FileSystem.write_file {path = "/parent/child/file.txt", content = "nested"}
                let read_result = FileSystem.read_file {path = "/parent/child/file.txt"}
                match read_result is
                    Ok {val} -> val
                    Err {error} -> "error"
        """)
        assertTrue(result is MezzeString)
        assertEquals("nested", (result as MezzeString).value)
    }

    // === Error Type Tests ===

    @Test
    fun `test FileNotFound error has path`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let read_result = FileSystem.read_file {path = "/missing.txt"}
                match read_result is
                    Ok {val} -> ""
                    Err {error} -> match error is
                        FileNotFound {path} -> path
                        _ -> ""
        """)
        assertTrue(result is MezzeString)
        assertEquals("/missing.txt", (result as MezzeString).value)
    }

    @Test
    fun `test AlreadyExists error has path`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.create_directory {path = "/dup"}
                let create_result = FileSystem.create_directory {path = "/dup"}
                match create_result is
                    Ok {val} -> ""
                    Err {error} -> match error is
                        AlreadyExists {path} -> path
                        _ -> ""
        """)
        assertTrue(result is MezzeString)
        assertEquals("/dup", (result as MezzeString).value)
    }

    // === LocalDisk Tests (using temp directories) ===

    @Test
    fun `test LocalDisk write_file and read_file roundtrip`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mezze_test_").toAbsolutePath().toString()
        try {
            val result = eval("""
                use std::effects::filesystem::{FileSystem, LocalDisk}

                perform LocalDisk in do
                    let write_result = FileSystem.write_file {path = "$tempDir/test.txt", content = "Hello Local!"}
                    match write_result is
                        Ok {val} -> do
                            let read_result = FileSystem.read_file {path = "$tempDir/test.txt"}
                            match read_result is
                                Ok {val = content} -> content
                                Err {error} -> "read error"
                        Err {error} -> "write error"
            """)
            assertTrue(result is MezzeString)
            assertEquals("Hello Local!", (result as MezzeString).value)
        } finally {
            java.io.File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `test LocalDisk read_file non-existent returns FileNotFound`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mezze_test_").toAbsolutePath().toString()
        try {
            val result = eval("""
                use std::effects::filesystem::{FileSystem, LocalDisk}

                perform LocalDisk in do
                    let read_result = FileSystem.read_file {path = "$tempDir/does_not_exist.txt"}
                    match read_result is
                        Ok {val} -> False
                        Err {error} -> match error is
                            FileNotFound {path} -> True
                            _ -> False
            """)
            assertTrue(result is MezzeBool)
            assertTrue((result as MezzeBool).value)
        } finally {
            java.io.File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `test LocalDisk exists returns false for non-existent file`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mezze_test_").toAbsolutePath().toString()
        try {
            val result = eval("""
                use std::effects::filesystem::{FileSystem, LocalDisk}

                perform LocalDisk in do
                    let exists_result = FileSystem.exists {path = "$tempDir/nonexistent.txt"}
                    match exists_result is
                        Ok {val} -> val
                        Err {error} -> True
            """)
            assertTrue(result is MezzeBool)
            assertFalse((result as MezzeBool).value)
        } finally {
            java.io.File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `test LocalDisk exists returns true for existing file`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mezze_test_").toAbsolutePath().toString()
        try {
            val result = eval("""
                use std::effects::filesystem::{FileSystem, LocalDisk}

                perform LocalDisk in do
                    let _ = FileSystem.write_file {path = "$tempDir/exists.txt", content = "data"}
                    let exists_result = FileSystem.exists {path = "$tempDir/exists.txt"}
                    match exists_result is
                        Ok {val} -> val
                        Err {error} -> False
            """)
            assertTrue(result is MezzeBool)
            assertTrue((result as MezzeBool).value)
        } finally {
            java.io.File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `test LocalDisk delete removes file`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mezze_test_").toAbsolutePath().toString()
        try {
            val result = eval("""
                use std::effects::filesystem::{FileSystem, LocalDisk}

                perform LocalDisk in do
                    let _ = FileSystem.write_file {path = "$tempDir/todelete.txt", content = "data"}
                    let delete_result = FileSystem.delete {path = "$tempDir/todelete.txt"}
                    match delete_result is
                        Ok {val} -> do
                            let exists_result = FileSystem.exists {path = "$tempDir/todelete.txt"}
                            match exists_result is
                                Ok {val} -> val
                                Err {error} -> True
                        Err {error} -> True
            """)
            assertTrue(result is MezzeBool)
            assertFalse((result as MezzeBool).value)
        } finally {
            java.io.File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `test LocalDisk create_directory creates directory`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mezze_test_").toAbsolutePath().toString()
        try {
            val result = eval("""
                use std::effects::filesystem::{FileSystem, LocalDisk}

                perform LocalDisk in do
                    let create_result = FileSystem.create_directory {path = "$tempDir/mydir"}
                    match create_result is
                        Ok {val} -> do
                            let exists_result = FileSystem.exists {path = "$tempDir/mydir"}
                            match exists_result is
                                Ok {val} -> val
                                Err {error} -> False
                        Err {error} -> False
            """)
            assertTrue(result is MezzeBool)
            assertTrue((result as MezzeBool).value)
        } finally {
            java.io.File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `test LocalDisk list_directory returns files`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mezze_test_").toAbsolutePath().toString()
        try {
            val result = eval("""
                use std::effects::filesystem::{FileSystem, LocalDisk}

                perform LocalDisk in do
                    let _ = FileSystem.create_directory {path = "$tempDir/listdir"}
                    let _ = FileSystem.write_file {path = "$tempDir/listdir/file1.txt", content = "a"}
                    let _ = FileSystem.write_file {path = "$tempDir/listdir/file2.txt", content = "b"}
                    let list_result = FileSystem.list_directory {path = "$tempDir/listdir"}
                    match list_result is
                        Ok {val} -> __native_arraylist_length {xs = val}
                        Err {error} -> 0
            """)
            assertTrue(result is MezzeInt)
            assertEquals(2L, (result as MezzeInt).value)
        } finally {
            java.io.File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `test LocalDisk file_size returns content length`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mezze_test_").toAbsolutePath().toString()
        try {
            val result = eval("""
                use std::effects::filesystem::{FileSystem, LocalDisk}

                perform LocalDisk in do
                    let _ = FileSystem.write_file {path = "$tempDir/sized.txt", content = "12345"}
                    let size_result = FileSystem.file_size {path = "$tempDir/sized.txt"}
                    match size_result is
                        Ok {val} -> val
                        Err {error} -> 0
            """)
            assertTrue(result is MezzeInt)
            assertEquals(5L, (result as MezzeInt).value)
        } finally {
            java.io.File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `test LocalDisk nested directory operations`() {
        val tempDir = java.nio.file.Files.createTempDirectory("mezze_test_").toAbsolutePath().toString()
        try {
            val result = eval("""
                use std::effects::filesystem::{FileSystem, LocalDisk}

                perform LocalDisk in do
                    let _ = FileSystem.create_directory {path = "$tempDir/parent"}
                    let _ = FileSystem.create_directory {path = "$tempDir/parent/child"}
                    let _ = FileSystem.write_file {path = "$tempDir/parent/child/file.txt", content = "nested"}
                    let read_result = FileSystem.read_file {path = "$tempDir/parent/child/file.txt"}
                    match read_result is
                        Ok {val} -> val
                        Err {error} -> "error"
            """)
            assertTrue(result is MezzeString)
            assertEquals("nested", (result as MezzeString).value)
        } finally {
            java.io.File(tempDir).deleteRecursively()
        }
    }

    // === New API Tests (MemoryFS) ===

    @Test
    fun `test append_file appends content`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/append.txt", content = "Hello"}
                let _ = FileSystem.append_file {path = "/append.txt", content = " World"}
                let read_result = FileSystem.read_file {path = "/append.txt"}
                match read_result is
                    Ok {val} -> val
                    Err {error} -> "error"
        """)
        assertTrue(result is MezzeString)
        assertEquals("Hello World", (result as MezzeString).value)
    }

    @Test
    fun `test append_file non-existent returns FileNotFound`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let append_result = FileSystem.append_file {path = "/nonexistent.txt", content = "data"}
                match append_result is
                    Ok {val} -> False
                    Err {error} -> match error is
                        FileNotFound {path} -> True
                        _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_directory returns true for directory`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.create_directory {path = "/mydir"}
                let is_dir_result = FileSystem.is_directory {path = "/mydir"}
                match is_dir_result is
                    Ok {val} -> val
                    Err {error} -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_directory returns false for file`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/file.txt", content = "data"}
                let is_dir_result = FileSystem.is_directory {path = "/file.txt"}
                match is_dir_result is
                    Ok {val} -> val
                    Err {error} -> True
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test is_file returns true for file`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/file.txt", content = "data"}
                let is_file_result = FileSystem.is_file {path = "/file.txt"}
                match is_file_result is
                    Ok {val} -> val
                    Err {error} -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_file returns false for directory`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.create_directory {path = "/mydir"}
                let is_file_result = FileSystem.is_file {path = "/mydir"}
                match is_file_result is
                    Ok {val} -> val
                    Err {error} -> True
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test copy_file creates copy`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/original.txt", content = "original content"}
                let copy_result = FileSystem.copy_file {src = "/original.txt", dest = "/copy.txt"}
                match copy_result is
                    Ok {val} -> do
                        let read_result = FileSystem.read_file {path = "/copy.txt"}
                        match read_result is
                            Ok {val = content} -> content
                            Err {error} -> "read error"
                    Err {error} -> "copy error"
        """)
        assertTrue(result is MezzeString)
        assertEquals("original content", (result as MezzeString).value)
    }

    @Test
    fun `test copy_file preserves original`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/original.txt", content = "original content"}
                let _ = FileSystem.copy_file {src = "/original.txt", dest = "/copy.txt"}
                let exists_result = FileSystem.exists {path = "/original.txt"}
                match exists_result is
                    Ok {val} -> val
                    Err {error} -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test move_file moves content`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/source.txt", content = "moved content"}
                let move_result = FileSystem.move_file {src = "/source.txt", dest = "/dest.txt"}
                match move_result is
                    Ok {val} -> do
                        let read_result = FileSystem.read_file {path = "/dest.txt"}
                        match read_result is
                            Ok {val = content} -> content
                            Err {error} -> "read error"
                    Err {error} -> "move error"
        """)
        assertTrue(result is MezzeString)
        assertEquals("moved content", (result as MezzeString).value)
    }

    @Test
    fun `test move_file removes original`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/source.txt", content = "moved content"}
                let _ = FileSystem.move_file {src = "/source.txt", dest = "/dest.txt"}
                let exists_result = FileSystem.exists {path = "/source.txt"}
                match exists_result is
                    Ok {val} -> val
                    Err {error} -> True
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test create_directories creates nested dirs`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let create_result = FileSystem.create_directories {path = "/a/b/c/d"}
                match create_result is
                    Ok {val} -> do
                        let exists_result = FileSystem.exists {path = "/a/b/c/d"}
                        match exists_result is
                            Ok {val} -> val
                            Err {error} -> False
                    Err {error} -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test delete_recursive removes directory tree`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}

            perform MemoryFS in do
                let _ = FileSystem.create_directories {path = "/tree/branch/leaf"}
                let _ = FileSystem.write_file {path = "/tree/branch/file.txt", content = "data"}
                let _ = FileSystem.write_file {path = "/tree/root.txt", content = "data"}
                let delete_result = FileSystem.delete_recursive {path = "/tree"}
                match delete_result is
                    Ok {val} -> do
                        let exists_result = FileSystem.exists {path = "/tree"}
                        match exists_result is
                            Ok {val} -> val
                            Err {error} -> True
                    Err {error} -> True
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    // === Path Helper Function Tests ===

    @Test
    fun `test file_name extracts filename`() {
        val result = eval("""
            use std::effects::filesystem::{file_name}

            file_name {path = "/path/to/file.txt"}
        """)
        assertTrue(result is MezzeString)
        assertEquals("file.txt", (result as MezzeString).value)
    }

    @Test
    fun `test parent extracts parent directory`() {
        val result = eval("""
            use std::effects::filesystem::{parent}

            parent {path = "/path/to/file.txt"}
        """)
        assertTrue(result is MezzeString)
        assertEquals("/path/to", (result as MezzeString).value)
    }

    @Test
    fun `test path_join joins path components`() {
        val result = eval("""
            use std::effects::filesystem::{path_join}

            path_join {parts = ["path", "to", "file.txt"]}
        """)
        assertTrue(result is MezzeString)
        assertEquals("path/to/file.txt", (result as MezzeString).value)
    }

    @Test
    fun `test extension extracts file extension`() {
        val result = eval("""
            use std::effects::filesystem::{extension}

            match extension {path = "/file.txt"} is
                Some {val} -> val
                None -> "none"
        """)
        assertTrue(result is MezzeString)
        assertEquals("txt", (result as MezzeString).value)
    }

    @Test
    fun `test extension returns None for no extension`() {
        val result = eval("""
            use std::effects::filesystem::{extension}

            match extension {path = "/file"} is
                Some {val} -> False
                None -> True
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test has_extension checks extension`() {
        val result = eval("""
            use std::effects::filesystem::{has_extension}

            has_extension {path = "/file.txt", ext = "txt"}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === Convenience Function Tests ===

    @Test
    fun `test read_file_or returns content when file exists`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS, read_file_or}

            perform MemoryFS in do
                let _ = FileSystem.write_file {path = "/exists.txt", content = "hello"}
                read_file_or {path = "/exists.txt", default = "default"}
        """)
        assertTrue(result is MezzeString)
        assertEquals("hello", (result as MezzeString).value)
    }

    @Test
    fun `test read_file_or returns default when file missing`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS, read_file_or}

            perform MemoryFS in do
                read_file_or {path = "/missing.txt", default = "default"}
        """)
        assertTrue(result is MezzeString)
        assertEquals("default", (result as MezzeString).value)
    }

    @Test
    fun `test write_file_mkdirs creates parent dirs`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS, write_file_mkdirs}

            perform MemoryFS in do
                let write_result = write_file_mkdirs {path = "/a/b/c/file.txt", content = "deep"}
                match write_result is
                    Ok {val} -> do
                        let read_result = FileSystem.read_file {path = "/a/b/c/file.txt"}
                        match read_result is
                            Ok {val = content} -> content
                            Err {error} -> "read error"
                    Err {error} -> "write error"
        """)
        assertTrue(result is MezzeString)
        assertEquals("deep", (result as MezzeString).value)
    }

    @Test
    fun `test ensure_directory creates if not exists`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS, ensure_directory}

            perform MemoryFS in do
                let ensure_result = ensure_directory {path = "/newdir"}
                match ensure_result is
                    Ok {val} -> do
                        let is_dir_result = FileSystem.is_directory {path = "/newdir"}
                        match is_dir_result is
                            Ok {val} -> val
                            Err {error} -> False
                    Err {error} -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test ensure_directory succeeds if already exists`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS, ensure_directory}

            perform MemoryFS in do
                let _ = FileSystem.create_directory {path = "/existingdir"}
                let ensure_result = ensure_directory {path = "/existingdir"}
                match ensure_result is
                    Ok {val} -> True
                    Err {error} -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }
}
