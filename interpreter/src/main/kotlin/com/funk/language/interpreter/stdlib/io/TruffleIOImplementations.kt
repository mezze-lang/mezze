package com.funk.language.interpreter.stdlib.io

import com.funk.language.interpreter.*
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.io.IOException

/**
 * Truffle runtime implementations for IO abilities
 */
object TruffleIOImplementations {

    /**
     * Register all IO runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerIOOperations(context)
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
     * Input/Output operations
     */
    private fun registerIOOperations(context: MutableMap<String, FunkValue>) {
        // Print operation
        context["print"] = NativeFunkFunction("print") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("print expects 'msg' as String")
            println(msg.value)
            FunkRecord(emptyMap()) // Unit type
        }
        
        // Keep backward compatibility
        context["native_print"] = context["print"]!!
        
        // Debug operation
        context["debug"] = NativeFunkFunction("debug") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("debug expects 'msg' as String")
            System.err.println("[DEBUG] ${msg.value}")
            FunkRecord(emptyMap()) // Unit type
        }
        
        // Keep backward compatibility
        context["native_debug"] = context["debug"]!!
        
        // Write file operation
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
        
        // Read file operation (might be needed too)
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

        // Console logging functions (for effect handlers)
        context["__native_console_print"] = NativeFunkFunction("__native_console_print") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_console_print expects 'msg' as String")
            print(msg.value)
            FunkRecord(emptyMap())
        }

        context["__native_console_println"] = NativeFunkFunction("__native_console_println") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_console_println expects 'msg' as String")
            println(msg.value)
            FunkRecord(emptyMap())
        }

        context["__native_log_info"] = NativeFunkFunction("__native_log_info") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_info expects 'msg' as String")
            println("[INFO] ${msg.value}")
            FunkRecord(emptyMap())
        }

        context["__native_log_warn"] = NativeFunkFunction("__native_log_warn") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_warn expects 'msg' as String")
            println("[WARN] ${msg.value}")
            FunkRecord(emptyMap())
        }

        context["__native_log_error"] = NativeFunkFunction("__native_log_error") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_error expects 'msg' as String")
            System.err.println("[ERROR] ${msg.value}")
            FunkRecord(emptyMap())
        }

        context["__native_log_debug"] = NativeFunkFunction("__native_log_debug") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_debug expects 'msg' as String")
            println("[DEBUG] ${msg.value}")
            FunkRecord(emptyMap())
        }

        context["__native_log_trace"] = NativeFunkFunction("__native_log_trace") { args ->
            val record = args[0] as FunkRecord
            val msg = record.fields["msg"] as? FunkString ?: throw FunkException("__native_log_trace expects 'msg' as String")
            println("[TRACE] ${msg.value}")
            FunkRecord(emptyMap())
        }

        // Filesystem effect functions
        context["__native_fs_write_file"] = NativeFunkFunction("__native_fs_write_file") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString ?: throw FunkException("__native_fs_write_file expects 'path' as String")
            val content = record.fields["content"] as? FunkString ?: throw FunkException("__native_fs_write_file expects 'content' as String")

            try {
                val file = java.io.File(path.value)
                // Check if we have permission to write
                if (file.exists() && !file.canWrite()) {
                    return@NativeFunkFunction createFileSystemError("PermissionDenied", mapOf(
                        "path" to FunkString(path.value),
                        "operation" to FunkString("write")
                    ))
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
                createOk(FunkList(entries))
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
    }
}
