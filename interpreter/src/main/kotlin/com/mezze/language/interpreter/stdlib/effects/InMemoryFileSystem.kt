package com.mezze.language.interpreter.stdlib.effects

import org.graalvm.polyglot.io.FileSystem
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory FileSystem implementation for Mezze's MemoryFS effect handler.
 *
 * This implements GraalVM's polyglot FileSystem interface, making it fully
 * compatible with GraalVM native-image (no ServiceLoader issues like Jimfs).
 *
 * Users can use this via: `perform MemoryFS in <expression>`
 *
 * Features:
 * - Full read/write file operations
 * - Directory creation and listing
 * - File attributes (size, timestamps)
 * - Thread-safe via ConcurrentHashMap
 */
class InMemoryFileSystem : FileSystem {

    private val files = ConcurrentHashMap<String, FileEntry>()
    private val directories = ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile
    private var currentWorkingDirectory: Path = Paths.get("/")

    init {
        // Create root directory
        directories["/"] = ConcurrentHashMap.newKeySet()
    }

    /**
     * Represents a file in the in-memory filesystem
     */
    private data class FileEntry(
        var content: ByteArray,
        val creationTime: Long = System.currentTimeMillis(),
        var lastModifiedTime: Long = System.currentTimeMillis(),
        var lastAccessTime: Long = System.currentTimeMillis(),
        val isDirectory: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FileEntry) return false
            return content.contentEquals(other.content) &&
                   creationTime == other.creationTime &&
                   isDirectory == other.isDirectory
        }

        override fun hashCode(): Int {
            var result = content.contentHashCode()
            result = 31 * result + creationTime.hashCode()
            result = 31 * result + isDirectory.hashCode()
            return result
        }
    }

    // =========================================================================
    // Path Operations
    // =========================================================================

    override fun parsePath(uri: URI): Path {
        if (uri.scheme != "file" && uri.scheme != "memory") {
            throw UnsupportedOperationException("Unsupported URI scheme: ${uri.scheme}")
        }
        return Paths.get(uri.path ?: "/")
    }

    override fun parsePath(path: String): Path {
        return Paths.get(path)
    }

    override fun toAbsolutePath(path: Path): Path {
        return if (path.isAbsolute) {
            path
        } else {
            currentWorkingDirectory.resolve(path).normalize()
        }
    }

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path {
        val absolute = toAbsolutePath(path)
        val normalized = normalize(absolute)

        // Check if path exists
        if (!pathExists(normalized)) {
            throw NoSuchFileException(normalized.toString())
        }

        return Paths.get(normalized)
    }

    override fun setCurrentWorkingDirectory(currentWorkingDirectory: Path) {
        this.currentWorkingDirectory = toAbsolutePath(currentWorkingDirectory)
    }

    private fun normalize(path: Path): String {
        val absolute = toAbsolutePath(path)
        return absolute.normalize().toString().let {
            if (it.isEmpty()) "/" else it
        }
    }

    private fun pathExists(path: String): Boolean {
        return files.containsKey(path) || directories.containsKey(path)
    }

    private fun pathIsDirectory(path: String): Boolean {
        return directories.containsKey(path)
    }

    private fun pathIsFile(path: String): Boolean {
        return files.containsKey(path) && files[path]?.isDirectory != true
    }

    private fun getParentPath(path: String): String {
        val parent = Paths.get(path).parent?.toString() ?: "/"
        return if (parent.isEmpty()) "/" else parent
    }

    private fun getFileName(path: String): String {
        return Paths.get(path).fileName?.toString() ?: ""
    }

    // =========================================================================
    // File Access
    // =========================================================================

    override fun checkAccess(path: Path, modes: MutableSet<out AccessMode>, vararg linkOptions: LinkOption) {
        val normalized = normalize(path)

        if (!pathExists(normalized)) {
            throw NoSuchFileException(path.toString())
        }

        // In-memory filesystem allows all access modes for existing files
        // (no permission system)
    }

    // =========================================================================
    // Directory Operations
    // =========================================================================

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        val normalized = normalize(dir)
        val parent = getParentPath(normalized)

        // Check parent exists
        if (!pathIsDirectory(parent)) {
            throw NoSuchFileException(parent, null, "Parent directory does not exist")
        }

        // Check if already exists
        if (pathExists(normalized)) {
            throw FileAlreadyExistsException(normalized)
        }

        // Create directory
        directories[normalized] = ConcurrentHashMap.newKeySet()

        // Add to parent's children
        directories[parent]?.add(getFileName(normalized))
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val normalized = normalize(dir)

        if (!pathIsDirectory(normalized)) {
            if (!pathExists(normalized)) {
                throw NoSuchFileException(dir.toString())
            }
            throw NotDirectoryException(dir.toString())
        }

        val children = directories[normalized] ?: emptySet<String>()
        val filteredPaths = children
            .map { dir.resolve(it) }
            .filter { filter.accept(it) }

        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = filteredPaths.toMutableList().iterator()
            override fun close() {}
        }
    }

    // =========================================================================
    // File Operations
    // =========================================================================

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        val normalized = normalize(path)
        val parent = getParentPath(normalized)

        val read = options.contains(StandardOpenOption.READ) || options.isEmpty()
        val write = options.contains(StandardOpenOption.WRITE) ||
                    options.contains(StandardOpenOption.APPEND)
        val create = options.contains(StandardOpenOption.CREATE) ||
                     options.contains(StandardOpenOption.CREATE_NEW)
        val truncate = options.contains(StandardOpenOption.TRUNCATE_EXISTING)
        val append = options.contains(StandardOpenOption.APPEND)

        // Handle CREATE_NEW - must not exist
        if (options.contains(StandardOpenOption.CREATE_NEW) && pathExists(normalized)) {
            throw FileAlreadyExistsException(normalized)
        }

        // Check parent directory exists for create operations
        if (create && !pathIsDirectory(parent)) {
            throw NoSuchFileException(parent, null, "Parent directory does not exist")
        }

        // Get or create file entry
        val entry = if (pathExists(normalized)) {
            if (pathIsDirectory(normalized)) {
                throw IOException("Cannot open directory as file: $normalized")
            }
            files[normalized]!!
        } else if (create) {
            val newEntry = FileEntry(ByteArray(0))
            files[normalized] = newEntry
            directories[parent]?.add(getFileName(normalized))
            newEntry
        } else {
            throw NoSuchFileException(normalized)
        }

        // Truncate if requested
        if (truncate && write) {
            entry.content = ByteArray(0)
            entry.lastModifiedTime = System.currentTimeMillis()
        }

        return InMemorySeekableByteChannel(entry, read, write, append)
    }

    override fun delete(path: Path) {
        val normalized = normalize(path)

        if (!pathExists(normalized)) {
            throw NoSuchFileException(path.toString())
        }

        if (pathIsDirectory(normalized)) {
            val children = directories[normalized]
            if (children != null && children.isNotEmpty()) {
                throw DirectoryNotEmptyException(normalized)
            }
            directories.remove(normalized)
        } else {
            files.remove(normalized)
        }

        // Remove from parent
        val parent = getParentPath(normalized)
        directories[parent]?.remove(getFileName(normalized))
    }

    // =========================================================================
    // File Attributes
    // =========================================================================

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): MutableMap<String, Any> {
        val normalized = normalize(path)

        if (!pathExists(normalized)) {
            throw NoSuchFileException(path.toString())
        }

        val result = mutableMapOf<String, Any>()
        val entry = files[normalized]

        val isDir = pathIsDirectory(normalized)
        val size = entry?.content?.size?.toLong() ?: 0L
        val creationTime = entry?.creationTime ?: System.currentTimeMillis()
        val lastModified = entry?.lastModifiedTime ?: System.currentTimeMillis()
        val lastAccess = entry?.lastAccessTime ?: System.currentTimeMillis()

        // Parse attribute names (e.g., "basic:size,isDirectory" or just "size")
        val attrNames = if (attributes.contains(":")) {
            attributes.substringAfter(":").split(",")
        } else if (attributes == "*") {
            listOf("size", "isDirectory", "isRegularFile", "isSymbolicLink", "isOther",
                   "creationTime", "lastModifiedTime", "lastAccessTime")
        } else {
            attributes.split(",")
        }

        for (attr in attrNames) {
            when (attr.trim()) {
                "size" -> result["size"] = size
                "isDirectory" -> result["isDirectory"] = isDir
                "isRegularFile" -> result["isRegularFile"] = !isDir
                "isSymbolicLink" -> result["isSymbolicLink"] = false
                "isOther" -> result["isOther"] = false
                "creationTime" -> result["creationTime"] = FileTime.fromMillis(creationTime)
                "lastModifiedTime" -> result["lastModifiedTime"] = FileTime.fromMillis(lastModified)
                "lastAccessTime" -> result["lastAccessTime"] = FileTime.fromMillis(lastAccess)
            }
        }

        return result
    }

    override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption) {
        val normalized = normalize(path)

        if (!pathExists(normalized)) {
            throw NoSuchFileException(path.toString())
        }

        val entry = files[normalized] ?: return

        val attrName = if (attribute.contains(":")) {
            attribute.substringAfter(":")
        } else {
            attribute
        }

        when (attrName) {
            "lastModifiedTime" -> {
                if (value is FileTime) {
                    entry.lastModifiedTime = value.toMillis()
                }
            }
            "lastAccessTime" -> {
                if (value is FileTime) {
                    entry.lastAccessTime = value.toMillis()
                }
            }
        }
    }

    // =========================================================================
    // Copy/Move Operations
    // =========================================================================

    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        val sourcePath = normalize(source)
        val targetPath = normalize(target)

        if (!pathExists(sourcePath)) {
            throw NoSuchFileException(source.toString())
        }

        val replaceExisting = options.contains(StandardCopyOption.REPLACE_EXISTING)

        if (pathExists(targetPath) && !replaceExisting) {
            throw FileAlreadyExistsException(targetPath)
        }

        if (pathIsDirectory(sourcePath)) {
            // Copy directory (empty)
            if (!pathExists(targetPath)) {
                createDirectory(target)
            }
        } else {
            // Copy file
            val sourceEntry = files[sourcePath]!!
            val targetEntry = FileEntry(
                content = sourceEntry.content.copyOf(),
                creationTime = System.currentTimeMillis(),
                lastModifiedTime = if (options.contains(StandardCopyOption.COPY_ATTRIBUTES)) {
                    sourceEntry.lastModifiedTime
                } else {
                    System.currentTimeMillis()
                }
            )
            files[targetPath] = targetEntry

            // Add to parent directory
            val parent = getParentPath(targetPath)
            directories[parent]?.add(getFileName(targetPath))
        }
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        copy(source, target, *options)
        delete(source)
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    override fun getSeparator(): String = "/"

    override fun getPathSeparator(): String = ":"

    override fun getMimeType(path: Path): String? {
        val normalized = normalize(path)
        val fileName = getFileName(normalized)

        return when {
            fileName.endsWith(".txt") -> "text/plain"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".xml") -> "application/xml"
            fileName.endsWith(".html") -> "text/html"
            fileName.endsWith(".mz") -> "text/x-mezze"
            else -> null
        }
    }

    override fun getEncoding(path: Path): java.nio.charset.Charset? = Charsets.UTF_8

    override fun getTempDirectory(): Path = Paths.get("/tmp")

    override fun isSameFile(path1: Path, path2: Path, vararg options: LinkOption): Boolean {
        return normalize(path1) == normalize(path2)
    }


    // =========================================================================
    // Clear filesystem (for reset between tests/runs)
    // =========================================================================

    fun clear() {
        files.clear()
        directories.clear()
        directories["/"] = ConcurrentHashMap.newKeySet()
        currentWorkingDirectory = Paths.get("/")
    }

    // =========================================================================
    // High-level Convenience Methods (for use by FilesystemImplementations)
    // =========================================================================

    /**
     * Read file content as a string
     */
    fun readString(path: String): String {
        val normalized = normalize(Paths.get(path))
        if (!pathExists(normalized)) {
            throw NoSuchFileException(normalized)
        }
        if (pathIsDirectory(normalized)) {
            throw IOException("Is a directory: $normalized")
        }
        val entry = files[normalized] ?: throw NoSuchFileException(normalized)
        entry.lastAccessTime = System.currentTimeMillis()
        return String(entry.content, Charsets.UTF_8)
    }

    /**
     * Write string content to a file, creating it if it doesn't exist
     */
    fun writeString(path: String, content: String) {
        val normalized = normalize(Paths.get(path))
        val parent = getParentPath(normalized)

        if (!pathIsDirectory(parent)) {
            throw NoSuchFileException(parent, null, "Parent directory does not exist")
        }
        if (pathIsDirectory(normalized)) {
            throw IOException("Is a directory: $normalized")
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        val existingEntry = files[normalized]
        if (existingEntry != null) {
            existingEntry.content = bytes
            existingEntry.lastModifiedTime = System.currentTimeMillis()
        } else {
            files[normalized] = FileEntry(bytes)
            directories[parent]?.add(getFileName(normalized))
        }
    }

    /**
     * Append string content to a file
     */
    fun appendString(path: String, content: String) {
        val normalized = normalize(Paths.get(path))
        if (!pathExists(normalized)) {
            throw NoSuchFileException(normalized)
        }
        if (pathIsDirectory(normalized)) {
            throw IOException("Is a directory: $normalized")
        }
        val entry = files[normalized] ?: throw NoSuchFileException(normalized)
        entry.content = entry.content + content.toByteArray(Charsets.UTF_8)
        entry.lastModifiedTime = System.currentTimeMillis()
    }

    /**
     * Check if a path exists (file or directory)
     */
    fun exists(path: String): Boolean {
        return pathExists(normalize(Paths.get(path)))
    }

    /**
     * Check if path is a directory
     */
    fun isDirectory(path: String): Boolean {
        return pathIsDirectory(normalize(Paths.get(path)))
    }

    /**
     * Check if path is a regular file
     */
    fun isRegularFile(path: String): Boolean {
        return pathIsFile(normalize(Paths.get(path)))
    }

    /**
     * Get file size in bytes
     */
    fun size(path: String): Long {
        val normalized = normalize(Paths.get(path))
        if (!pathExists(normalized)) {
            throw NoSuchFileException(normalized)
        }
        if (pathIsDirectory(normalized)) {
            throw IOException("Is a directory: $normalized")
        }
        return files[normalized]?.content?.size?.toLong() ?: 0L
    }

    /**
     * Delete a file or empty directory
     */
    fun deleteFile(path: String) {
        delete(Paths.get(path))
    }

    /**
     * Create a directory (parent must exist)
     */
    fun createDir(path: String) {
        createDirectory(Paths.get(path))
    }

    /**
     * Create directories recursively
     */
    fun createDirs(path: String) {
        val normalized = normalize(Paths.get(path))
        if (pathIsDirectory(normalized)) return // Already exists

        // Build path components and create from root
        val parts = normalized.split("/").filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = "$current/$part"
            if (!pathIsDirectory(current)) {
                if (pathExists(current)) {
                    throw FileAlreadyExistsException(current)
                }
                directories[current] = ConcurrentHashMap.newKeySet()
                val parent = getParentPath(current)
                directories[parent]?.add(part)
            }
        }
    }

    /**
     * List directory contents
     */
    fun listDir(path: String): List<String> {
        val normalized = normalize(Paths.get(path))
        if (!pathExists(normalized)) {
            throw NoSuchFileException(normalized)
        }
        if (!pathIsDirectory(normalized)) {
            throw IOException("Not a directory: $normalized")
        }
        return directories[normalized]?.toList() ?: emptyList()
    }

    /**
     * Copy a file
     */
    fun copyFile(src: String, dest: String) {
        copy(Paths.get(src), Paths.get(dest))
    }

    /**
     * Move a file
     */
    fun moveFile(src: String, dest: String) {
        move(Paths.get(src), Paths.get(dest))
    }

    /**
     * Delete recursively
     */
    fun deleteRecursively(path: String) {
        val normalized = normalize(Paths.get(path))
        if (!pathExists(normalized)) {
            throw NoSuchFileException(normalized)
        }

        if (pathIsDirectory(normalized)) {
            // Delete children first
            val children = directories[normalized]?.toList() ?: emptyList()
            for (child in children) {
                deleteRecursively("$normalized/$child")
            }
            directories.remove(normalized)
        } else {
            files.remove(normalized)
        }

        // Remove from parent
        val parent = getParentPath(normalized)
        directories[parent]?.remove(getFileName(normalized))
    }

    // =========================================================================
    // SeekableByteChannel Implementation
    // =========================================================================

    private inner class InMemorySeekableByteChannel(
        private val entry: FileEntry,
        private val readable: Boolean,
        private val writable: Boolean,
        private val append: Boolean
    ) : SeekableByteChannel {

        private var position: Long = if (append) entry.content.size.toLong() else 0L
        private var open = true

        override fun read(dst: ByteBuffer): Int {
            if (!readable) throw NonReadableChannelException()
            if (!open) throw ClosedChannelException()

            val content = entry.content
            if (position >= content.size) return -1

            val remaining = (content.size - position).toInt()
            val toRead = minOf(remaining, dst.remaining())

            dst.put(content, position.toInt(), toRead)
            position += toRead
            entry.lastAccessTime = System.currentTimeMillis()

            return toRead
        }

        override fun write(src: ByteBuffer): Int {
            if (!writable) throw NonWritableChannelException()
            if (!open) throw ClosedChannelException()

            val toWrite = src.remaining()
            val newSize = maxOf(entry.content.size, (position + toWrite).toInt())

            if (newSize > entry.content.size) {
                entry.content = entry.content.copyOf(newSize)
            }

            src.get(entry.content, position.toInt(), toWrite)
            position += toWrite
            entry.lastModifiedTime = System.currentTimeMillis()

            return toWrite
        }

        override fun position(): Long = position

        override fun position(newPosition: Long): SeekableByteChannel {
            if (newPosition < 0) throw IllegalArgumentException("Negative position")
            this.position = newPosition
            return this
        }

        override fun size(): Long = entry.content.size.toLong()

        override fun truncate(size: Long): SeekableByteChannel {
            if (!writable) throw NonWritableChannelException()
            if (size < entry.content.size) {
                entry.content = entry.content.copyOf(size.toInt())
                entry.lastModifiedTime = System.currentTimeMillis()
            }
            if (position > size) {
                position = size
            }
            return this
        }

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }
    }
}
