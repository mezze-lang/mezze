package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

/**
 * Type signatures for filesystem effect operations
 *
 * Corresponds to stdlib file: std/effects/filesystem.mz
 *
 * Provides types for:
 * - LocalDisk filesystem operations (read, write, delete, list, create directories, etc.)
 * - MemoryFS filesystem operations (in-memory filesystem for testing)
 *
 * Both implementations share the same error types and operations but use different
 * native function prefixes (__native_fs_* for LocalDisk, __native_memfs_* for MemoryFS)
 */
object FilesystemTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        val errors = filesystemErrors()
        registerFsOps(context, "__native_fs_", errors)
        registerFsOps(context, "__native_memfs_", errors)
    }

    private data class FsErrors(
        val fileNotFound: Map<String, MezzeType.RecordType>,
        val permissionDenied: Map<String, MezzeType.RecordType>,
        val ioError: Map<String, MezzeType.RecordType>,
        val diskFull: Map<String, MezzeType.RecordType>,
        val directoryNotEmpty: Map<String, MezzeType.RecordType>,
        val alreadyExists: Map<String, MezzeType.RecordType>,
        val invalidPath: Map<String, MezzeType.RecordType>,
        val notADirectory: Map<String, MezzeType.RecordType>,
    )

    private fun filesystemErrors() = FsErrors(
        fileNotFound = mapOf("FileNotFound" to rec("path" to str)),
        permissionDenied = mapOf("PermissionDenied" to rec("path" to str, "operation" to str)),
        ioError = mapOf("IOError" to rec("path" to str, "message" to str)),
        diskFull = mapOf("DiskFull" to rec("path" to str, "bytes_needed" to int)),
        directoryNotEmpty = mapOf("DirectoryNotEmpty" to rec("path" to str)),
        alreadyExists = mapOf("AlreadyExists" to rec("path" to str)),
        invalidPath = mapOf("InvalidPath" to rec("path" to str)),
        notADirectory = mapOf("NotADirectory" to rec("path" to str)),
    )

    private fun fsResult(ok: MezzeType, vararg errorMaps: Map<String, MezzeType.RecordType>): MezzeType {
        val combined = errorMaps.fold(emptyMap<String, MezzeType>()) { acc, m -> acc + m }
        return result(ok, MezzeType.VariantType(combined))
    }

    private fun registerFsOps(context: MutableMap<String, MezzeType>, prefix: String, e: FsErrors) = natives(context) {
        fn("${prefix}read_file") {
            param("path" to str)
            returns(fsResult(str, e.fileNotFound, e.permissionDenied, e.ioError))
        }
        fn("${prefix}write_file") {
            param("path" to str, "content" to str)
            returns(fsResult(unit, e.permissionDenied, e.ioError, e.diskFull))
        }
        fn("${prefix}exists") {
            param("path" to str)
            returns(fsResult(bool, e.permissionDenied, e.ioError))
        }
        fn("${prefix}delete") {
            param("path" to str)
            returns(fsResult(unit, e.fileNotFound, e.permissionDenied, e.directoryNotEmpty, e.ioError))
        }
        fn("${prefix}list_directory") {
            param("path" to str)
            returns(fsResult(seq(str), e.fileNotFound, e.permissionDenied, e.notADirectory, e.ioError))
        }
        fn("${prefix}create_directory") {
            param("path" to str)
            returns(fsResult(unit, e.alreadyExists, e.permissionDenied, e.invalidPath, e.ioError))
        }
        fn("${prefix}file_size") {
            param("path" to str)
            returns(fsResult(int, e.fileNotFound, e.permissionDenied, e.ioError))
        }
    }
}
