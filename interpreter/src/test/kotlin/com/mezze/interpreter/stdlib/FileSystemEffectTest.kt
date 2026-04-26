package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/filesystem module.
 * Tests file system operations using MemoryFS handler (Jimfs in-memory filesystem).
 * Migrated from V1 to V2 with unboxed values.
 */
class FileSystemEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
        // Reset in-memory filesystem before each test for isolation
        com.mezze.interpreter.nodes.builtin.effects.MemoryFilesystemNatives.clear()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

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
        assertEquals("Hello, World!", result)
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
        assertEquals(true, result)
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
        assertEquals(false, result)
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
        assertEquals(true, result)
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
        assertEquals(false, result)
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
        assertEquals(true, result)
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
        assertEquals(true, result)
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
        assertEquals(true, result)
    }

    // === list_directory Tests ===

    @Test
    fun `test list_directory returns files`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS}
            use std::collections::list

            perform MemoryFS in do
                let _ = FileSystem.create_directory {path = "/listdir"}
                let _ = FileSystem.write_file {path = "/listdir/file1.txt", content = "a"}
                let _ = FileSystem.write_file {path = "/listdir/file2.txt", content = "b"}
                let list_result = FileSystem.list_directory {path = "/listdir"}
                match list_result is
                    Ok {val} -> list::length {xs = val}
                    Err {error} -> 0
        """)
        assertEquals(2L, result)
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
        assertEquals(true, result)
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
        assertEquals(5L, result)
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
        assertEquals(true, result)
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
        assertEquals("updated", result)
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
        assertEquals("nested", result)
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
        assertEquals("/missing.txt", result)
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
        assertEquals("/dup", result)
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
        assertEquals("Hello World", result)
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
        assertEquals(true, result)
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
        assertEquals(true, result)
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
        assertEquals(false, result)
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
        assertEquals(true, result)
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
        assertEquals(false, result)
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
        assertEquals("original content", result)
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
        assertEquals(true, result)
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
        assertEquals("moved content", result)
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
        assertEquals(false, result)
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
        assertEquals(true, result)
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
        assertEquals(false, result)
    }

    // === Path Helper Function Tests ===

    @Test
    fun `test file_name extracts filename`() {
        val result = eval("""
            use std::effects::filesystem::{file_name}

            file_name {path = "/path/to/file.txt"}
        """)
        assertEquals("file.txt", result)
    }

    @Test
    fun `test parent extracts parent directory`() {
        val result = eval("""
            use std::effects::filesystem::{parent}

            parent {path = "/path/to/file.txt"}
        """)
        assertEquals("/path/to", result)
    }

    @Test
    fun `test path_join joins path components`() {
        val result = eval("""
            use std::effects::filesystem::{path_join}

            path_join {parts = ["path", "to", "file.txt"]}
        """)
        assertEquals("path/to/file.txt", result)
    }

    @Test
    fun `test extension extracts file extension`() {
        val result = eval("""
            use std::effects::filesystem::{extension}

            match extension {path = "/file.txt"} is
                Some {val} -> val
                None -> "none"
        """)
        assertEquals("txt", result)
    }

    @Test
    fun `test extension returns None for no extension`() {
        val result = eval("""
            use std::effects::filesystem::{extension}

            match extension {path = "/file"} is
                Some {val} -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test has_extension checks extension`() {
        val result = eval("""
            use std::effects::filesystem::{has_extension}

            has_extension {path = "/file.txt", ext = "txt"}
        """)
        assertEquals(true, result)
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
        assertEquals("hello", result)
    }

    @Test
    fun `test read_file_or returns default when file missing`() {
        val result = eval("""
            use std::effects::filesystem::{FileSystem, MemoryFS, read_file_or}

            perform MemoryFS in do
                read_file_or {path = "/missing.txt", default = "default"}
        """)
        assertEquals("default", result)
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
        assertEquals("deep", result)
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
        assertEquals(true, result)
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
        assertEquals(true, result)
    }
}
