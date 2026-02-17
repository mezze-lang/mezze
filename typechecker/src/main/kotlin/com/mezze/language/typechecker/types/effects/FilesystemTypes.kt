package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Result type (as TypeApplication)
 * Note: Result takes value type first, then error type
 */
private fun resultType(valueType: MezzeType, errorType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Result", listOf(valueType, errorType))
}

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
        registerLocalDiskOperations(context)
        registerMemoryFSOperations(context)
    }

    /**
     * Common filesystem error variants used by both LocalDisk and MemoryFS
     */
    private fun getFilesystemErrors(): Map<String, Map<String, MezzeType>> {
        return mapOf(
            "fileNotFound" to mapOf("FileNotFound" to MezzeType.RecordType(mapOf("path" to MezzeType.StringType))),
            "permissionDenied" to mapOf("PermissionDenied" to MezzeType.RecordType(mapOf(
                "path" to MezzeType.StringType,
                "operation" to MezzeType.StringType
            ))),
            "ioError" to mapOf("IOError" to MezzeType.RecordType(mapOf(
                "path" to MezzeType.StringType,
                "message" to MezzeType.StringType
            ))),
            "diskFull" to mapOf("DiskFull" to MezzeType.RecordType(mapOf(
                "path" to MezzeType.StringType,
                "bytes_needed" to MezzeType.IntType
            ))),
            "directoryNotEmpty" to mapOf("DirectoryNotEmpty" to MezzeType.RecordType(mapOf("path" to MezzeType.StringType))),
            "alreadyExists" to mapOf("AlreadyExists" to MezzeType.RecordType(mapOf("path" to MezzeType.StringType))),
            "invalidPath" to mapOf("InvalidPath" to MezzeType.RecordType(mapOf("path" to MezzeType.StringType))),
            "notADirectory" to mapOf("NotADirectory" to MezzeType.RecordType(mapOf("path" to MezzeType.StringType)))
        )
    }

    private fun registerLocalDiskOperations(context: MutableMap<String, MezzeType>) {
        val unitType = MezzeType.RecordType(emptyMap())
        val errors = getFilesystemErrors()
        val fileNotFoundError = errors["fileNotFound"]!!
        val permissionDeniedError = errors["permissionDenied"]!!
        val ioError = errors["ioError"]!!
        val diskFullError = errors["diskFull"]!!
        val directoryNotEmptyError = errors["directoryNotEmpty"]!!
        val alreadyExistsError = errors["alreadyExists"]!!
        val invalidPathError = errors["invalidPath"]!!
        val notADirectoryError = errors["notADirectory"]!!

        context["__native_fs_read_file"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                MezzeType.StringType,
                MezzeType.VariantType(fileNotFoundError + permissionDeniedError + ioError)
            )
        )

        context["__native_fs_write_file"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "path" to MezzeType.StringType,
                "content" to MezzeType.StringType
            )),
            resultType(
                unitType,
                MezzeType.VariantType(permissionDeniedError + ioError + diskFullError)
            )
        )

        context["__native_fs_exists"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                MezzeType.BooleanType,
                MezzeType.VariantType(permissionDeniedError + ioError)
            )
        )

        context["__native_fs_delete"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                unitType,
                MezzeType.VariantType(fileNotFoundError + permissionDeniedError + directoryNotEmptyError + ioError)
            )
        )

        context["__native_fs_list_directory"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                MezzeType.ArrayType(MezzeType.StringType),
                MezzeType.VariantType(fileNotFoundError + permissionDeniedError + notADirectoryError + ioError)
            )
        )

        context["__native_fs_create_directory"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                unitType,
                MezzeType.VariantType(alreadyExistsError + permissionDeniedError + invalidPathError + ioError)
            )
        )

        context["__native_fs_file_size"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                MezzeType.IntType,
                MezzeType.VariantType(fileNotFoundError + permissionDeniedError + ioError)
            )
        )
    }

    private fun registerMemoryFSOperations(context: MutableMap<String, MezzeType>) {
        val unitType = MezzeType.RecordType(emptyMap())
        val errors = getFilesystemErrors()
        val fileNotFoundError = errors["fileNotFound"]!!
        val permissionDeniedError = errors["permissionDenied"]!!
        val ioError = errors["ioError"]!!
        val diskFullError = errors["diskFull"]!!
        val directoryNotEmptyError = errors["directoryNotEmpty"]!!
        val alreadyExistsError = errors["alreadyExists"]!!
        val invalidPathError = errors["invalidPath"]!!
        val notADirectoryError = errors["notADirectory"]!!

        context["__native_memfs_read_file"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                MezzeType.StringType,
                MezzeType.VariantType(fileNotFoundError + permissionDeniedError + ioError)
            )
        )

        context["__native_memfs_write_file"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "path" to MezzeType.StringType,
                "content" to MezzeType.StringType
            )),
            resultType(
                unitType,
                MezzeType.VariantType(permissionDeniedError + ioError + diskFullError)
            )
        )

        context["__native_memfs_exists"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                MezzeType.BooleanType,
                MezzeType.VariantType(permissionDeniedError + ioError)
            )
        )

        context["__native_memfs_delete"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                unitType,
                MezzeType.VariantType(fileNotFoundError + permissionDeniedError + directoryNotEmptyError + ioError)
            )
        )

        context["__native_memfs_list_directory"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                MezzeType.ArrayType(MezzeType.StringType),
                MezzeType.VariantType(fileNotFoundError + permissionDeniedError + notADirectoryError + ioError)
            )
        )

        context["__native_memfs_create_directory"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                unitType,
                MezzeType.VariantType(alreadyExistsError + permissionDeniedError + invalidPathError + ioError)
            )
        )

        context["__native_memfs_file_size"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            resultType(
                MezzeType.IntType,
                MezzeType.VariantType(fileNotFoundError + permissionDeniedError + ioError)
            )
        )
    }
}
