package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

/**
 * Filesystem effect implementations.
 *
 * Two handlers:
 * - LocalDisk: Real filesystem via java.io.File
 * - MemoryFS: In-memory filesystem (GraalVM native-image compatible)
 *
 * All operations return Result types with proper error variants:
 * - FileNotFound { path }
 * - PermissionDenied { path, operation }
 * - AlreadyExists { path }
 * - DirectoryNotEmpty { path }
 * - IOError { path, message }
 * - DiskFull { path, bytes_needed }
 */
object FilesystemImplementations {

    /**
     * In-memory filesystem instance.
     * Uses our own implementation that's GraalVM native-image compatible.
     * Thread-safe via ConcurrentHashMap internally.
     */
    private val memoryFileSystem = InMemoryFileSystem()

    /**
     * Clear and reset the in-memory filesystem (for isolation between runs)
     */
    fun clearMemoryFs() {
        memoryFileSystem.clear()
    }

    /**
     * Register all Filesystem effect implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerLocalDiskOperations(context)
        registerMemoryFsOperations(context)
        registerLegacyOperations(context)
    }

    // =========================================================================
    // Helper functions
    // =========================================================================

    private fun createOk(value: MezzeValue): MezzeVariant = mezzeResultOk(value)

    private fun createErr(errorVariant: MezzeVariant): MezzeVariant = mezzeResultErr(errorVariant)

    private fun createFileSystemError(variant: String, fields: Map<String, MezzeValue>): MezzeVariant {
        return createErr(MezzeVariant(variant, MezzeRecord(fields)))
    }

    private fun fileNotFound(path: String) = createFileSystemError("FileNotFound", mapOf("path" to MezzeString(path)))

    private fun permissionDenied(path: String, operation: String) = createFileSystemError("PermissionDenied", mapOf(
        "path" to MezzeString(path),
        "operation" to MezzeString(operation)
    ))

    private fun alreadyExists(path: String) = createFileSystemError("AlreadyExists", mapOf("path" to MezzeString(path)))

    private fun directoryNotEmpty(path: String) = createFileSystemError("DirectoryNotEmpty", mapOf("path" to MezzeString(path)))

    private fun ioError(path: String, message: String) = createFileSystemError("IOError", mapOf(
        "path" to MezzeString(path),
        "message" to MezzeString(message)
    ))

    // =========================================================================
    // LocalDisk operations (real filesystem)
    // =========================================================================

    private fun registerLocalDiskOperations(context: MutableMap<String, MezzeValue>) {
        context["__native_fs_read_file"] = NativeMezzeFunction("__native_fs_read_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            try {
                val content = java.io.File(path).readText()
                createOk(MezzeString(content))
            } catch (e: FileNotFoundException) {
                fileNotFound(path)
            } catch (e: AccessDeniedException) {
                permissionDenied(path, "read")
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_fs_write_file"] = NativeMezzeFunction("__native_fs_write_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value
            val content = (record.fields["content"] as MezzeString).value

            try {
                val file = java.io.File(path)
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    return@NativeMezzeFunction fileNotFound(path)
                }
                file.writeText(content)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: FileNotFoundException) {
                fileNotFound(path)
            } catch (e: AccessDeniedException) {
                permissionDenied(path, "write")
            } catch (e: IOException) {
                if (e.message?.contains("No space left") == true) {
                    createFileSystemError("DiskFull", mapOf(
                        "path" to MezzeString(path),
                        "bytes_needed" to MezzeInt(content.length.toLong())
                    ))
                } else {
                    ioError(path, e.message ?: "Unknown IO error")
                }
            }
        }

        context["__native_fs_exists"] = NativeMezzeFunction("__native_fs_exists") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value
            createOk(MezzeBool(java.io.File(path).exists()))
        }

        context["__native_fs_delete"] = NativeMezzeFunction("__native_fs_delete") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            val file = java.io.File(path)
            when {
                !file.exists() -> fileNotFound(path)
                file.isDirectory && file.list()?.isNotEmpty() == true -> directoryNotEmpty(path)
                !file.delete() -> permissionDenied(path, "delete")
                else -> createOk(MezzeRecord(emptyMap()))
            }
        }

        context["__native_fs_create_directory"] = NativeMezzeFunction("__native_fs_create_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            val file = java.io.File(path)
            when {
                file.exists() -> alreadyExists(path)
                !file.mkdirs() -> permissionDenied(path, "create_directory")
                else -> createOk(MezzeRecord(emptyMap()))
            }
        }

        context["__native_fs_file_size"] = NativeMezzeFunction("__native_fs_file_size") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            val file = java.io.File(path)
            when {
                !file.exists() -> fileNotFound(path)
                !file.canRead() -> permissionDenied(path, "file_size")
                else -> createOk(MezzeInt(file.length()))
            }
        }

        context["__native_fs_list_directory"] = NativeMezzeFunction("__native_fs_list_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            val file = java.io.File(path)
            when {
                !file.exists() -> fileNotFound(path)
                !file.isDirectory -> ioError(path, "Not a directory")
                !file.canRead() -> permissionDenied(path, "list_directory")
                else -> {
                    val entries = file.list()?.map { MezzeString(it) } ?: emptyList()
                    createOk(MezzeArrayList(entries.toMutableList()))
                }
            }
        }

        context["__native_fs_append_file"] = NativeMezzeFunction("__native_fs_append_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value
            val content = (record.fields["content"] as MezzeString).value

            try {
                val file = java.io.File(path)
                if (!file.exists()) {
                    return@NativeMezzeFunction fileNotFound(path)
                }
                file.appendText(content)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: AccessDeniedException) {
                permissionDenied(path, "append")
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_fs_delete_recursive"] = NativeMezzeFunction("__native_fs_delete_recursive") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            val file = java.io.File(path)
            when {
                !file.exists() -> fileNotFound(path)
                !file.deleteRecursively() -> permissionDenied(path, "delete_recursive")
                else -> createOk(MezzeRecord(emptyMap()))
            }
        }

        context["__native_fs_create_directories"] = NativeMezzeFunction("__native_fs_create_directories") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            val file = java.io.File(path)
            when {
                file.exists() && file.isDirectory -> createOk(MezzeRecord(emptyMap())) // Already exists, ok
                file.exists() -> alreadyExists(path) // Exists but not a directory
                !file.mkdirs() -> permissionDenied(path, "create_directories")
                else -> createOk(MezzeRecord(emptyMap()))
            }
        }

        context["__native_fs_is_directory"] = NativeMezzeFunction("__native_fs_is_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            val file = java.io.File(path)
            when {
                !file.exists() -> fileNotFound(path)
                else -> createOk(MezzeBool(file.isDirectory))
            }
        }

        context["__native_fs_is_file"] = NativeMezzeFunction("__native_fs_is_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            val file = java.io.File(path)
            when {
                !file.exists() -> fileNotFound(path)
                else -> createOk(MezzeBool(file.isFile))
            }
        }

        context["__native_fs_copy_file"] = NativeMezzeFunction("__native_fs_copy_file") { args ->
            val record = args[0] as MezzeRecord
            val src = (record.fields["src"] as MezzeString).value
            val dest = (record.fields["dest"] as MezzeString).value

            try {
                val srcFile = java.io.File(src)
                val destFile = java.io.File(dest)
                when {
                    !srcFile.exists() -> fileNotFound(src)
                    destFile.exists() -> alreadyExists(dest)
                    else -> {
                        srcFile.copyTo(destFile)
                        createOk(MezzeRecord(emptyMap()))
                    }
                }
            } catch (e: AccessDeniedException) {
                permissionDenied(src, "copy")
            } catch (e: IOException) {
                ioError(src, e.message ?: "Unknown IO error")
            }
        }

        context["__native_fs_move_file"] = NativeMezzeFunction("__native_fs_move_file") { args ->
            val record = args[0] as MezzeRecord
            val src = (record.fields["src"] as MezzeString).value
            val dest = (record.fields["dest"] as MezzeString).value

            try {
                val srcFile = java.io.File(src)
                val destFile = java.io.File(dest)
                when {
                    !srcFile.exists() -> fileNotFound(src)
                    destFile.exists() -> alreadyExists(dest)
                    !srcFile.renameTo(destFile) -> ioError(src, "Failed to move file")
                    else -> createOk(MezzeRecord(emptyMap()))
                }
            } catch (e: AccessDeniedException) {
                permissionDenied(src, "move")
            } catch (e: IOException) {
                ioError(src, e.message ?: "Unknown IO error")
            }
        }
    }

    // =========================================================================
    // MemoryFS operations (in-memory filesystem)
    // =========================================================================

    private fun registerMemoryFsOperations(context: MutableMap<String, MezzeValue>) {
        context["__native_memfs_read_file"] = NativeMezzeFunction("__native_memfs_read_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            try {
                val content = memoryFileSystem.readString(path)
                createOk(MezzeString(content))
            } catch (e: NoSuchFileException) {
                fileNotFound(path)
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_write_file"] = NativeMezzeFunction("__native_memfs_write_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value
            val content = (record.fields["content"] as MezzeString).value

            try {
                memoryFileSystem.writeString(path, content)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: NoSuchFileException) {
                fileNotFound(path)
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_exists"] = NativeMezzeFunction("__native_memfs_exists") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value
            createOk(MezzeBool(memoryFileSystem.exists(path)))
        }

        context["__native_memfs_delete"] = NativeMezzeFunction("__native_memfs_delete") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            try {
                memoryFileSystem.deleteFile(path)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: DirectoryNotEmptyException) {
                directoryNotEmpty(path)
            } catch (e: NoSuchFileException) {
                fileNotFound(path)
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_create_directory"] = NativeMezzeFunction("__native_memfs_create_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            try {
                if (memoryFileSystem.exists(path)) {
                    return@NativeMezzeFunction alreadyExists(path)
                }
                memoryFileSystem.createDir(path)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: FileAlreadyExistsException) {
                alreadyExists(path)
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_file_size"] = NativeMezzeFunction("__native_memfs_file_size") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            try {
                createOk(MezzeInt(memoryFileSystem.size(path)))
            } catch (e: NoSuchFileException) {
                fileNotFound(path)
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_list_directory"] = NativeMezzeFunction("__native_memfs_list_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            try {
                val entries = memoryFileSystem.listDir(path).map { MezzeString(it) as MezzeValue }
                createOk(MezzeArrayList(entries.toMutableList()))
            } catch (e: NoSuchFileException) {
                fileNotFound(path)
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_append_file"] = NativeMezzeFunction("__native_memfs_append_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value
            val content = (record.fields["content"] as MezzeString).value

            try {
                memoryFileSystem.appendString(path, content)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: NoSuchFileException) {
                fileNotFound(path)
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_delete_recursive"] = NativeMezzeFunction("__native_memfs_delete_recursive") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            try {
                memoryFileSystem.deleteRecursively(path)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: NoSuchFileException) {
                fileNotFound(path)
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_create_directories"] = NativeMezzeFunction("__native_memfs_create_directories") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            try {
                if (memoryFileSystem.exists(path) && !memoryFileSystem.isDirectory(path)) {
                    return@NativeMezzeFunction alreadyExists(path)
                }
                memoryFileSystem.createDirs(path)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: FileAlreadyExistsException) {
                alreadyExists(path)
            } catch (e: IOException) {
                ioError(path, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_is_directory"] = NativeMezzeFunction("__native_memfs_is_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            if (!memoryFileSystem.exists(path)) {
                fileNotFound(path)
            } else {
                createOk(MezzeBool(memoryFileSystem.isDirectory(path)))
            }
        }

        context["__native_memfs_is_file"] = NativeMezzeFunction("__native_memfs_is_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value

            if (!memoryFileSystem.exists(path)) {
                fileNotFound(path)
            } else {
                createOk(MezzeBool(memoryFileSystem.isRegularFile(path)))
            }
        }

        context["__native_memfs_copy_file"] = NativeMezzeFunction("__native_memfs_copy_file") { args ->
            val record = args[0] as MezzeRecord
            val src = (record.fields["src"] as MezzeString).value
            val dest = (record.fields["dest"] as MezzeString).value

            try {
                if (!memoryFileSystem.exists(src)) {
                    return@NativeMezzeFunction fileNotFound(src)
                }
                if (memoryFileSystem.exists(dest)) {
                    return@NativeMezzeFunction alreadyExists(dest)
                }
                memoryFileSystem.copyFile(src, dest)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: NoSuchFileException) {
                fileNotFound(src)
            } catch (e: FileAlreadyExistsException) {
                alreadyExists(dest)
            } catch (e: IOException) {
                ioError(src, e.message ?: "Unknown IO error")
            }
        }

        context["__native_memfs_move_file"] = NativeMezzeFunction("__native_memfs_move_file") { args ->
            val record = args[0] as MezzeRecord
            val src = (record.fields["src"] as MezzeString).value
            val dest = (record.fields["dest"] as MezzeString).value

            try {
                if (!memoryFileSystem.exists(src)) {
                    return@NativeMezzeFunction fileNotFound(src)
                }
                if (memoryFileSystem.exists(dest)) {
                    return@NativeMezzeFunction alreadyExists(dest)
                }
                memoryFileSystem.moveFile(src, dest)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: NoSuchFileException) {
                fileNotFound(src)
            } catch (e: FileAlreadyExistsException) {
                alreadyExists(dest)
            } catch (e: IOException) {
                ioError(src, e.message ?: "Unknown IO error")
            }
        }

        // Clear/reset the memory filesystem (for test isolation)
        context["__native_memfs_clear"] = NativeMezzeFunction("__native_memfs_clear") { _ ->
            clearMemoryFs()
            MezzeRecord(emptyMap())
        }
    }

    // =========================================================================
    // Legacy operations (backward compatibility)
    // =========================================================================

    private fun registerLegacyOperations(context: MutableMap<String, MezzeValue>) {
        context["write_file"] = NativeMezzeFunction("write_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value
            val content = (record.fields["content"] as MezzeString).value
            java.io.File(path).writeText(content)
            MezzeRecord(emptyMap())
        }
        context["native_write_file"] = context["write_file"]!!

        context["read_file"] = NativeMezzeFunction("read_file") { args ->
            val record = args[0] as MezzeRecord
            val path = (record.fields["path"] as MezzeString).value
            MezzeString(java.io.File(path).readText())
        }
        context["native_read_file"] = context["read_file"]!!
    }
}
