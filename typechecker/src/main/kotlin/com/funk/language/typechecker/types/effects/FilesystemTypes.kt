package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType

/**
 * Helper function to create Result type (as TypeApplication)
 * Note: Result takes value type first, then error type
 */
private fun resultType(valueType: FunkType, errorType: FunkType): FunkType {
    return FunkType.TypeApplication("Result", listOf(valueType, errorType))
}

/**
 * Type signatures for filesystem effect operations
 *
 * Corresponds to stdlib file: std/effects/filesystem.funk
 *
 * Provides types for:
 * - LocalDisk filesystem operations (read, write, delete, list, create directories, etc.)
 * - MemoryFS filesystem operations (in-memory filesystem for testing)
 *
 * Both implementations share the same error types and operations but use different
 * native function prefixes (__native_fs_* for LocalDisk, __native_memfs_* for MemoryFS)
 */
object FilesystemTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerLocalDiskOperations(context)
        registerMemoryFSOperations(context)
    }

    /**
     * Common filesystem error variants used by both LocalDisk and MemoryFS
     */
    private fun getFilesystemErrors(): Map<String, Map<String, FunkType>> {
        return mapOf(
            "fileNotFound" to mapOf("FileNotFound" to FunkType.RecordType(mapOf("path" to FunkType.StringType))),
            "permissionDenied" to mapOf("PermissionDenied" to FunkType.RecordType(mapOf(
                "path" to FunkType.StringType,
                "operation" to FunkType.StringType
            ))),
            "ioError" to mapOf("IOError" to FunkType.RecordType(mapOf(
                "path" to FunkType.StringType,
                "message" to FunkType.StringType
            ))),
            "diskFull" to mapOf("DiskFull" to FunkType.RecordType(mapOf(
                "path" to FunkType.StringType,
                "bytes_needed" to FunkType.IntType
            ))),
            "directoryNotEmpty" to mapOf("DirectoryNotEmpty" to FunkType.RecordType(mapOf("path" to FunkType.StringType))),
            "alreadyExists" to mapOf("AlreadyExists" to FunkType.RecordType(mapOf("path" to FunkType.StringType))),
            "invalidPath" to mapOf("InvalidPath" to FunkType.RecordType(mapOf("path" to FunkType.StringType))),
            "notADirectory" to mapOf("NotADirectory" to FunkType.RecordType(mapOf("path" to FunkType.StringType)))
        )
    }

    private fun registerLocalDiskOperations(context: MutableMap<String, FunkType>) {
        val unitType = FunkType.RecordType(emptyMap())
        val errors = getFilesystemErrors()
        val fileNotFoundError = errors["fileNotFound"]!!
        val permissionDeniedError = errors["permissionDenied"]!!
        val ioError = errors["ioError"]!!
        val diskFullError = errors["diskFull"]!!
        val directoryNotEmptyError = errors["directoryNotEmpty"]!!
        val alreadyExistsError = errors["alreadyExists"]!!
        val invalidPathError = errors["invalidPath"]!!
        val notADirectoryError = errors["notADirectory"]!!

        context["__native_fs_read_file"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                FunkType.StringType,
                FunkType.VariantType(fileNotFoundError + permissionDeniedError + ioError)
            )
        )

        context["__native_fs_write_file"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "path" to FunkType.StringType,
                "content" to FunkType.StringType
            )),
            resultType(
                unitType,
                FunkType.VariantType(permissionDeniedError + ioError + diskFullError)
            )
        )

        context["__native_fs_exists"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                FunkType.BooleanType,
                FunkType.VariantType(permissionDeniedError + ioError)
            )
        )

        context["__native_fs_delete"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                unitType,
                FunkType.VariantType(fileNotFoundError + permissionDeniedError + directoryNotEmptyError + ioError)
            )
        )

        context["__native_fs_list_directory"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                FunkType.ArrayListType(FunkType.StringType),
                FunkType.VariantType(fileNotFoundError + permissionDeniedError + notADirectoryError + ioError)
            )
        )

        context["__native_fs_create_directory"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                unitType,
                FunkType.VariantType(alreadyExistsError + permissionDeniedError + invalidPathError + ioError)
            )
        )

        context["__native_fs_file_size"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                FunkType.IntType,
                FunkType.VariantType(fileNotFoundError + permissionDeniedError + ioError)
            )
        )
    }

    private fun registerMemoryFSOperations(context: MutableMap<String, FunkType>) {
        val unitType = FunkType.RecordType(emptyMap())
        val errors = getFilesystemErrors()
        val fileNotFoundError = errors["fileNotFound"]!!
        val permissionDeniedError = errors["permissionDenied"]!!
        val ioError = errors["ioError"]!!
        val diskFullError = errors["diskFull"]!!
        val directoryNotEmptyError = errors["directoryNotEmpty"]!!
        val alreadyExistsError = errors["alreadyExists"]!!
        val invalidPathError = errors["invalidPath"]!!
        val notADirectoryError = errors["notADirectory"]!!

        context["__native_memfs_read_file"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                FunkType.StringType,
                FunkType.VariantType(fileNotFoundError + permissionDeniedError + ioError)
            )
        )

        context["__native_memfs_write_file"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "path" to FunkType.StringType,
                "content" to FunkType.StringType
            )),
            resultType(
                unitType,
                FunkType.VariantType(permissionDeniedError + ioError + diskFullError)
            )
        )

        context["__native_memfs_exists"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                FunkType.BooleanType,
                FunkType.VariantType(permissionDeniedError + ioError)
            )
        )

        context["__native_memfs_delete"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                unitType,
                FunkType.VariantType(fileNotFoundError + permissionDeniedError + directoryNotEmptyError + ioError)
            )
        )

        context["__native_memfs_list_directory"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                FunkType.ArrayListType(FunkType.StringType),
                FunkType.VariantType(fileNotFoundError + permissionDeniedError + notADirectoryError + ioError)
            )
        )

        context["__native_memfs_create_directory"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                unitType,
                FunkType.VariantType(alreadyExistsError + permissionDeniedError + invalidPathError + ioError)
            )
        )

        context["__native_memfs_file_size"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            resultType(
                FunkType.IntType,
                FunkType.VariantType(fileNotFoundError + permissionDeniedError + ioError)
            )
        )
    }
}
