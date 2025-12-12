package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.io.IOException

/**
 * Truffle runtime implementations for Filesystem effect operations
 */
object FilesystemImplementations {

    /**
     * Register all Filesystem effect implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerFilesystemOperations(context)
    }

    /**
     * Helper: Create Ok variant - Ok { val = value }
     */
    private fun createOk(value: FunkValue): FunkVariant {
        return FunkVariant("Ok", FunkRecord(mapOf("val" to value)))
    }

    /**
     * Helper: Create Err variant - Err { error = errorVariant }
     */
    private fun createErr(errorVariant: FunkVariant): FunkVariant {
        return FunkVariant("Err", FunkRecord(mapOf("error" to errorVariant)))
    }

    /**
     * Helper: Create FileSystemError variant
     */
    private fun createFileSystemError(variant: String, fields: Map<String, FunkValue>): FunkVariant {
        return createErr(FunkVariant(variant, FunkRecord(fields)))
    }

    /**
     * Filesystem operations
     */
    private fun registerFilesystemOperations(context: MutableMap<String, FunkValue>) {
        // Filesystem effect functions
        context["__native_fs_write_file"] = NativeFunkFunction("__native_fs_write_file") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_fs_write_file expects 'path' as String")
            val content = record.fields["content"] as? FunkString ?: throw FunkException("__native_fs_write_file expects 'content' as String")

            try {
                val file = java.io.File(path.value)

                // Check if the file exists and we can't write to it
                if (file.exists() && !file.canWrite()) {
                    return@NativeFunkFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to FunkString(path.value),
                        "operation" to FunkString("write")
                    ))
                }

                // Check if parent directory exists and is writable
                val parentDir = file.parentFile
                if (parentDir != null) {
                    if (!parentDir.exists()) {
                        return@NativeFunkFunction createFileSystemError("FileNotFound", mapOf(
                            "path" to FunkString(path.value)
                        ))
                    }
                    if (!parentDir.canWrite()) {
                        return@NativeFunkFunction createFileSystemError("PermissionDenied", mapOf(
                            "path" to FunkString(path.value),
                            "operation" to FunkString("write")
                        ))
                    }
                }

                file.writeText(content.value)
                createOk(FunkRecord(emptyMap()))
            } catch (e: FileNotFoundException) {
                createFileSystemError("FileNotFound", mapOf(
                    "path" to FunkString(path.value)
                ))
            } catch (e: AccessDeniedException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to FunkString(path.value),
                    "operation" to FunkString("write")
                ))
            } catch (e: IOException) {
                // Check if it's a disk full error
                if (e.message?.contains("No space left") == true) {
                    createFileSystemError("DiskFull", mapOf(
                        "path" to FunkString(path.value),
                        "bytes_needed" to FunkInt(content.value.length.toLong())
                    ))
                } else {
                    createFileSystemError("IOError", mapOf(
                        "path" to FunkString(path.value),
                        "message" to FunkString(e.message ?: "Unknown IO error")
                    ))
                }
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to FunkString(path.value),
                    "message" to FunkString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_read_file"] = NativeFunkFunction("__native_fs_read_file") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_fs_read_file expects 'path' as String")

            try {
                val content = java.io.File(path.value).readText()
                createOk(FunkString(content))
            } catch (e: FileNotFoundException) {
                createFileSystemError("FileNotFound", mapOf(
                    "path" to FunkString(path.value)
                ))
            } catch (e: AccessDeniedException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to FunkString(path.value),
                    "operation" to FunkString("read")
                ))
            } catch (e: IOException) {
                createFileSystemError("IOError", mapOf(
                    "path" to FunkString(path.value),
                    "message" to FunkString(e.message ?: "Unknown IO error")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to FunkString(path.value),
                    "message" to FunkString(e.message ?: "Unknown error")
                ))
            }
        }

        // Other filesystem operations
        context["__native_fs_exists"] = NativeFunkFunction("__native_fs_exists") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_fs_exists expects 'path' as String")

            try {
                val exists = java.io.File(path.value).exists()
                createOk(FunkBool(exists))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to FunkString(path.value),
                    "operation" to FunkString("exists")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to FunkString(path.value),
                    "message" to FunkString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_delete"] = NativeFunkFunction("__native_fs_delete") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_fs_delete expects 'path' as String")

            try {
                val file = java.io.File(path.value)
                if (!file.exists()) {
                    return@NativeFunkFunction createFileSystemError("FileNotFound", mapOf(
                        "path" to FunkString(path.value)
                    ))
                }
                if (file.isDirectory && file.list()?.isNotEmpty() == true) {
                    return@NativeFunkFunction createFileSystemError("DirectoryNotEmpty", mapOf(
                        "path" to FunkString(path.value)
                    ))
                }
                val deleted = file.delete()
                if (!deleted) {
                    return@NativeFunkFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to FunkString(path.value),
                        "operation" to FunkString("delete")
                    ))
                }
                createOk(FunkRecord(emptyMap()))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to FunkString(path.value),
                    "operation" to FunkString("delete")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to FunkString(path.value),
                    "message" to FunkString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_create_directory"] = NativeFunkFunction("__native_fs_create_directory") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_fs_create_directory expects 'path' as String")

            try {
                val file = java.io.File(path.value)
                if (file.exists()) {
                    return@NativeFunkFunction createFileSystemError("AlreadyExists", mapOf(
                        "path" to FunkString(path.value)
                    ))
                }
                val created = file.mkdirs()
                if (!created) {
                    return@NativeFunkFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to FunkString(path.value),
                        "operation" to FunkString("create_directory")
                    ))
                }
                createOk(FunkRecord(emptyMap()))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to FunkString(path.value),
                    "operation" to FunkString("create_directory")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to FunkString(path.value),
                    "message" to FunkString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_file_size"] = NativeFunkFunction("__native_fs_file_size") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_fs_file_size expects 'path' as String")

            try {
                val file = java.io.File(path.value)
                if (!file.exists()) {
                    return@NativeFunkFunction createFileSystemError("FileNotFound", mapOf(
                        "path" to FunkString(path.value)
                    ))
                }
                if (!file.canRead()) {
                    return@NativeFunkFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to FunkString(path.value),
                        "operation" to FunkString("file_size")
                    ))
                }
                createOk(FunkInt(file.length()))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to FunkString(path.value),
                    "operation" to FunkString("file_size")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to FunkString(path.value),
                    "message" to FunkString(e.message ?: "Unknown error")
                ))
            }
        }

        context["__native_fs_list_directory"] = NativeFunkFunction("__native_fs_list_directory") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_fs_list_directory expects 'path' as String")

            try {
                val file = java.io.File(path.value)
                if (!file.exists()) {
                    return@NativeFunkFunction createFileSystemError("FileNotFound", mapOf(
                        "path" to FunkString(path.value)
                    ))
                }
                if (!file.isDirectory) {
                    return@NativeFunkFunction createFileSystemError("IOError", mapOf(
                        "path" to FunkString(path.value),
                        "message" to FunkString("Not a directory")
                    ))
                }
                if (!file.canRead()) {
                    return@NativeFunkFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to FunkString(path.value),
                        "operation" to FunkString("list_directory")
                    ))
                }
                val entries = file.list()?.map { FunkString(it) } ?: emptyList()
                createOk(FunkArrayList(entries.toMutableList()))
            } catch (e: SecurityException) {
                createFileSystemError("PermissionDenied", mapOf(
                    "path" to FunkString(path.value),
                    "operation" to FunkString("list_directory")
                ))
            } catch (e: Exception) {
                createFileSystemError("IOError", mapOf(
                    "path" to FunkString(path.value),
                    "message" to FunkString(e.message ?: "Unknown error")
                ))
            }
        }

        // MemoryFS stubs - for now, return IOError until fully implemented
        context["__native_memfs_read_file"] = NativeFunkFunction("__native_memfs_read_file") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_memfs_read_file expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to FunkString(path.value),
                "message" to FunkString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_write_file"] = NativeFunkFunction("__native_memfs_write_file") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_memfs_write_file expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to FunkString(path.value),
                "message" to FunkString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_exists"] = NativeFunkFunction("__native_memfs_exists") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_memfs_exists expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to FunkString(path.value),
                "message" to FunkString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_delete"] = NativeFunkFunction("__native_memfs_delete") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_memfs_delete expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to FunkString(path.value),
                "message" to FunkString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_list_directory"] = NativeFunkFunction("__native_memfs_list_directory") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_memfs_list_directory expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to FunkString(path.value),
                "message" to FunkString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_create_directory"] = NativeFunkFunction("__native_memfs_create_directory") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_memfs_create_directory expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to FunkString(path.value),
                "message" to FunkString("MemoryFS not yet implemented")
            ))
        }
        context["__native_memfs_file_size"] = NativeFunkFunction("__native_memfs_file_size") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_memfs_file_size expects 'path' as String")
            createFileSystemError("IOError", mapOf(
                "path" to FunkString(path.value),
                "message" to FunkString("MemoryFS not yet implemented")
            ))
        }

        // Legacy write file operation
        context["write_file"] = NativeFunkFunction("write_file") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("write_file expects 'path' as String")
            val content = record.fields["content"] as? FunkString ?: throw FunkException("write_file expects 'content' as String")

            try {
                java.io.File(path.value).writeText(content.value)
                FunkRecord(emptyMap()) // Unit type
            } catch (e: Exception) {
                throw FunkException("write_file failed to write file: ${e.message}")
            }
        }

        // Keep backward compatibility
        context["native_write_file"] = context["write_file"]!!

        // Legacy read file operation
        context["read_file"] = NativeFunkFunction("read_file") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("read_file expects 'path' as String")

            try {
                val content = java.io.File(path.value).readText()
                FunkString(content)
            } catch (e: Exception) {
                throw FunkException("read_file failed to read file: ${e.message}")
            }
        }

        // Keep backward compatibility
        context["native_read_file"] = context["read_file"]!!
    }
}
