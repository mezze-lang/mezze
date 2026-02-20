package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.io.IOException

/**
 * Truffle-optimized implementations for Filesystem effect operations.
 *
 * Phase 6 of Truffle migration: I/O and System operations optimized for JIT performance.
 * Handles file system operations with proper error handling using Result/Option types.
 *
 * Optimizations:
 * - Direct Java File I/O operations for JIT compilation
 * - Proper error handling with FileSystemError variants (FileNotFound, PermissionDenied, IOError, etc.)
 * - Result types (Ok/Err) for robust error reporting
 * - Security exception handling for permission checks
 * - Optimized directory operations (list, create, delete)
 * - MemoryFS stubs for future in-memory filesystem implementation
 *
 * Operations: read_file, write_file, exists, delete, create_directory, file_size, list_directory
 *
 * Total: 18 filesystem functions
 */
object FilesystemImplementations {

    /**
     * Register all Filesystem effect implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerFilesystemOperations(context)
    }

    /**
     * Helper: Create Ok variant - Ok { val = value }
     */
    private fun createOk(value: MezzeValue): MezzeVariant {
        return mezzeResultOk(value)
    }

    /**
     * Helper: Create Err variant - Err { error = errorVariant }
     */
    private fun createErr(errorVariant: MezzeVariant): MezzeVariant {
        return mezzeResultErr(errorVariant)
    }

    /**
     * Helper: Create FileSystemError variant
     */
    private fun createFileSystemError(variant: String, fields: Map<String, MezzeValue>): MezzeVariant {
        return createErr(MezzeVariant(variant, MezzeRecord(fields)))
    }

    /**
     * Filesystem operations
     */
    private fun registerFilesystemOperations(context: MutableMap<String, MezzeValue>) {
        // Filesystem effect functions
        context["__native_fs_write_file"] = NativeMezzeFunction("__native_fs_write_file") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_fs_write_file expects 'path' as String")
            val content = record.fields["content"] as? MezzeString ?: throw MezzeException("__native_fs_write_file expects 'content' as String")

            try {
                val file = java.io.File(path.value)

                // Check if the file exists and we can't write to it
                if (file.exists() && !file.canWrite()) {
                    return@NativeMezzeFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to MezzeString(path.value),
                        "operation" to MezzeString("write")
                    ))
                }

                // Check if parent directory exists and is writable
                val parentDir = file.parentFile
                if (parentDir != null) {
                    if (!parentDir.exists()) {
                        return@NativeMezzeFunction createFileSystemError("FileNotFound", mapOf(
                            "path" to MezzeString(path.value)
                        ))
                    }
                    if (!parentDir.canWrite()) {
                        return@NativeMezzeFunction createFileSystemError("PermissionDenied", mapOf(
                            "path" to MezzeString(path.value),
                            "operation" to MezzeString("write")
                        ))
                    }
                }

                file.writeText(content.value)
                createOk(MezzeRecord(emptyMap()))
            } catch (e: FileNotFoundException) {
                createFileSystemError("FileNotFound", mapOf(
                    "path" to MezzeString(path.value)
                ))
            } catch (e: AccessDeniedException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to MezzeString(path.value),
                    "operation" to MezzeString("write")
                ))
            } catch (e: IOException) {
                // Check if it's a disk full error
                if (e.message?.contains("No space left") == true) {
                    createFileSystemError("DiskFull", mapOf(
                        "path" to MezzeString(path.value),
                        "bytes_needed" to MezzeInt(content.value.length.toLong())
                    ))
                } else {
                    createFileSystemError("IOError", mapOf(
                        "path" to MezzeString(path.value),
                        "message" to MezzeString(e.message ?: "Unknown IO error")
                    ))
                }
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to MezzeString(path.value),
                    "message" to MezzeString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_read_file"] = NativeMezzeFunction("__native_fs_read_file") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_fs_read_file expects 'path' as String")

            try {
                val content = java.io.File(path.value).readText()
                createOk(MezzeString(content))
            } catch (e: FileNotFoundException) {
                createFileSystemError("FileNotFound", mapOf(
                    "path" to MezzeString(path.value)
                ))
            } catch (e: AccessDeniedException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to MezzeString(path.value),
                    "operation" to MezzeString("read")
                ))
            } catch (e: IOException) {
                createFileSystemError("IOError", mapOf(
                    "path" to MezzeString(path.value),
                    "message" to MezzeString(e.message ?: "Unknown IO error")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to MezzeString(path.value),
                    "message" to MezzeString(e.message ?: "Unknown error")
                ))
            }
        }

        // Other filesystem operations
        context["__native_fs_exists"] = NativeMezzeFunction("__native_fs_exists") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_fs_exists expects 'path' as String")

            try {
                val exists = java.io.File(path.value).exists()
                createOk(MezzeBool(exists))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to MezzeString(path.value),
                    "operation" to MezzeString("exists")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to MezzeString(path.value),
                    "message" to MezzeString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_delete"] = NativeMezzeFunction("__native_fs_delete") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_fs_delete expects 'path' as String")

            try {
                val file = java.io.File(path.value)
                if (!file.exists()) {
                    return@NativeMezzeFunction createFileSystemError("FileNotFound", mapOf(
                        "path" to MezzeString(path.value)
                    ))
                }
                if (file.isDirectory && file.list()?.isNotEmpty() == true) {
                    return@NativeMezzeFunction createFileSystemError("DirectoryNotEmpty", mapOf(
                        "path" to MezzeString(path.value)
                    ))
                }
                val deleted = file.delete()
                if (!deleted) {
                    return@NativeMezzeFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to MezzeString(path.value),
                        "operation" to MezzeString("delete")
                    ))
                }
                createOk(MezzeRecord(emptyMap()))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to MezzeString(path.value),
                    "operation" to MezzeString("delete")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to MezzeString(path.value),
                    "message" to MezzeString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_create_directory"] = NativeMezzeFunction("__native_fs_create_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_fs_create_directory expects 'path' as String")

            try {
                val file = java.io.File(path.value)
                if (file.exists()) {
                    return@NativeMezzeFunction createFileSystemError("AlreadyExists", mapOf(
                        "path" to MezzeString(path.value)
                    ))
                }
                val created = file.mkdirs()
                if (!created) {
                    return@NativeMezzeFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to MezzeString(path.value),
                        "operation" to MezzeString("create_directory")
                    ))
                }
                createOk(MezzeRecord(emptyMap()))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to MezzeString(path.value),
                    "operation" to MezzeString("create_directory")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to MezzeString(path.value),
                    "message" to MezzeString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_file_size"] = NativeMezzeFunction("__native_fs_file_size") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_fs_file_size expects 'path' as String")

            try {
                val file = java.io.File(path.value)
                if (!file.exists()) {
                    return@NativeMezzeFunction createFileSystemError("FileNotFound", mapOf(
                        "path" to MezzeString(path.value)
                    ))
                }
                if (!file.canRead()) {
                    return@NativeMezzeFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to MezzeString(path.value),
                        "operation" to MezzeString("file_size")
                    ))
                }
                createOk(MezzeInt(file.length()))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to MezzeString(path.value),
                    "operation" to MezzeString("file_size")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to MezzeString(path.value),
                    "message" to MezzeString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_list_directory"] = NativeMezzeFunction("__native_fs_list_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_fs_list_directory expects 'path' as String")

            try {
                val file = java.io.File(path.value)
                if (!file.exists()) {
                    return@NativeMezzeFunction createFileSystemError("FileNotFound", mapOf(
                        "path" to MezzeString(path.value)
                    ))
                }
                if (!file.isDirectory) {
                    return@NativeMezzeFunction createFileSystemError("IOError", mapOf(
                        "path" to MezzeString(path.value),
                        "message" to MezzeString("Not a directory")
                    ))
                }
                if (!file.canRead()) {
                    return@NativeMezzeFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to MezzeString(path.value),
                        "operation" to MezzeString("list_directory")
                    ))
                }
                val entries = file.list()?.map { MezzeString(it) } ?: emptyList()
                createOk(MezzeArrayList(entries.toMutableList()))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to MezzeString(path.value),
                    "operation" to MezzeString("list_directory")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to MezzeString(path.value),
                    "message" to MezzeString(e.message ?: "Unknown error")
                ))
            }
        }

        // MemoryFS stubs - for now, return IOError until fully implemented
        context["__native_memfs_read_file"] = NativeMezzeFunction("__native_memfs_read_file") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_memfs_read_file expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to MezzeString(path.value),
                "message" to MezzeString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_write_file"] = NativeMezzeFunction("__native_memfs_write_file") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_memfs_write_file expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to MezzeString(path.value),
                "message" to MezzeString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_exists"] = NativeMezzeFunction("__native_memfs_exists") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_memfs_exists expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to MezzeString(path.value),
                "message" to MezzeString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_delete"] = NativeMezzeFunction("__native_memfs_delete") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_memfs_delete expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to MezzeString(path.value),
                "message" to MezzeString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_list_directory"] = NativeMezzeFunction("__native_memfs_list_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_memfs_list_directory expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to MezzeString(path.value),
                "message" to MezzeString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_create_directory"] = NativeMezzeFunction("__native_memfs_create_directory") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_memfs_create_directory expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to MezzeString(path.value),
                "message" to MezzeString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_file_size"] = NativeMezzeFunction("__native_memfs_file_size") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("__native_memfs_file_size expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to MezzeString(path.value),
                "message" to MezzeString("MemoryFS not yet implemented")
            ))
        }

        // Legacy write file operation
        context["write_file"] = NativeMezzeFunction("write_file") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("write_file expects 'path' as String")
            val content = record.fields["content"] as? MezzeString ?: throw MezzeException("write_file expects 'content' as String")

            try {
                java.io.File(path.value).writeText(content.value)
                MezzeRecord(emptyMap()) // Unit type
            } catch (e: Exception) {
                throw MezzeException("write_file failed to write file: ${e.message}")
            }
        }

        // Keep backward compatibility
        context["native_write_file"] = context["write_file"]!!

        // Legacy read file operation
        context["read_file"] = NativeMezzeFunction("read_file") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString ?: throw MezzeException("read_file expects 'path' as String")

            try {
                val content = java.io.File(path.value).readText()
                MezzeString(content)
            } catch (e: Exception) {
                throw MezzeException("read_file failed to read file: ${e.message}")
            }
        }

        // Keep backward compatibility
        context["native_read_file"] = context["read_file"]!!
    }
}
