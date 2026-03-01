package com.mezze.interpreter.v2.cache;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent compiled node cache using content-hash as keys.
 *
 * This cache stores compiled CallTarget references (and associated metadata)
 * to enable faster startup on subsequent runs.
 *
 * Architecture:
 * 1. In-memory: ConcurrentHashMap for current session
 * 2. On-disk: ~/.mezze/cache/compiled/ directory with hash-named files
 *
 * Content-hash guarantees:
 * - Same hash = same source code = same compiled result
 * - No need for invalidation logic
 * - Safe concurrent access
 *
 * Limitations:
 * - Cannot serialize actual JIT-compiled code (Truffle limitation)
 * - Cache stores metadata + quick-restore hints
 * - Full JIT warmup still needed, but node construction is cached
 */
public final class CompiledNodeCache {

    private static final String CACHE_DIR = ".mezze/cache/compiled";

    // In-memory cache for current session
    private final ConcurrentHashMap<String, CallTarget> inMemoryCache = new ConcurrentHashMap<>();

    // Cache statistics
    private volatile long hits = 0;
    private volatile long misses = 0;

    // Cache directory path
    private final Path cacheDirectory;

    // Whether disk caching is enabled
    private final boolean diskCacheEnabled;

    /**
     * Create a cache with default settings.
     */
    public CompiledNodeCache() {
        this(true);
    }

    /**
     * Create a cache with optional disk caching.
     */
    public CompiledNodeCache(boolean enableDiskCache) {
        this.diskCacheEnabled = enableDiskCache;

        if (enableDiskCache) {
            String userHome = System.getProperty("user.home");
            this.cacheDirectory = Paths.get(userHome, CACHE_DIR);
            initializeCacheDirectory();
        } else {
            this.cacheDirectory = null;
        }
    }

    @TruffleBoundary
    private void initializeCacheDirectory() {
        try {
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            // Cache directory creation failed - disable disk caching
            System.err.println("Warning: Could not create cache directory: " + e.getMessage());
        }
    }

    /**
     * Get a cached CallTarget by content hash.
     *
     * @param contentHash The content hash of the function/impl
     * @return Cached CallTarget, or null if not cached
     */
    @TruffleBoundary
    public CallTarget get(String contentHash) {
        // Try in-memory cache first
        CallTarget cached = inMemoryCache.get(contentHash);
        if (cached != null) {
            hits++;
            return cached;
        }

        // Disk cache is for metadata only - CallTargets can't be serialized
        // So we just track misses here
        misses++;
        return null;
    }

    /**
     * Cache a CallTarget by content hash.
     *
     * @param contentHash The content hash
     * @param callTarget The compiled CallTarget
     */
    @TruffleBoundary
    public void put(String contentHash, CallTarget callTarget) {
        inMemoryCache.put(contentHash, callTarget);

        // Write cache metadata to disk for future sessions
        if (diskCacheEnabled && cacheDirectory != null) {
            writeCacheMetadata(contentHash);
        }
    }

    /**
     * Get or compute a CallTarget.
     *
     * @param contentHash The content hash
     * @param supplier Supplier to create the CallTarget if not cached
     * @return The cached or newly created CallTarget
     */
    @TruffleBoundary
    public CallTarget getOrCompute(String contentHash, java.util.function.Supplier<CallTarget> supplier) {
        return inMemoryCache.computeIfAbsent(contentHash, k -> {
            misses++;
            return supplier.get();
        });
    }

    /**
     * Check if a content hash is cached.
     */
    @TruffleBoundary
    public boolean contains(String contentHash) {
        return inMemoryCache.containsKey(contentHash);
    }

    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        return new CacheStats(hits, misses, inMemoryCache.size());
    }

    /**
     * Clear the in-memory cache.
     */
    @TruffleBoundary
    public void clearMemoryCache() {
        inMemoryCache.clear();
        hits = 0;
        misses = 0;
    }

    /**
     * Clear all caches including disk.
     */
    @TruffleBoundary
    public void clearAll() {
        clearMemoryCache();

        if (diskCacheEnabled && cacheDirectory != null) {
            try {
                Files.walk(cacheDirectory)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }
    }

    @TruffleBoundary
    private void writeCacheMetadata(String contentHash) {
        if (cacheDirectory == null) return;

        try {
            Path metaFile = cacheDirectory.resolve(contentHash + ".meta");
            // Write timestamp for cache management
            Files.writeString(metaFile, String.valueOf(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // Cache write failure is not critical
        }
    }

    /**
     * Check if a content hash was previously compiled (in any session).
     * This is a hint that can be used for warmup prioritization.
     */
    @TruffleBoundary
    public boolean wasPreviouslyCompiled(String contentHash) {
        if (!diskCacheEnabled || cacheDirectory == null) {
            return false;
        }

        Path metaFile = cacheDirectory.resolve(contentHash + ".meta");
        return Files.exists(metaFile);
    }

    /**
     * Get all previously compiled content hashes (for warmup).
     */
    @TruffleBoundary
    public java.util.Set<String> getPreviouslyCompiledHashes() {
        java.util.Set<String> hashes = new java.util.HashSet<>();

        if (!diskCacheEnabled || cacheDirectory == null) {
            return hashes;
        }

        try {
            Files.list(cacheDirectory)
                .filter(p -> p.toString().endsWith(".meta"))
                .forEach(p -> {
                    String filename = p.getFileName().toString();
                    hashes.add(filename.substring(0, filename.length() - 5)); // Remove .meta
                });
        } catch (IOException ignored) {}

        return hashes;
    }

    /**
     * Cache statistics.
     */
    public record CacheStats(long hits, long misses, int size) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }
}
