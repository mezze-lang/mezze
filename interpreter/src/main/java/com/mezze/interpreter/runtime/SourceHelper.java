package com.mezze.interpreter.runtime;

import com.mezze.language.truffleir.SourceInfo;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place a Truffle {@link Source} is made for a Mezze file.
 *
 * <h2>Why this is process-wide and not per-caller</h2>
 *
 * A Source IS the debugger's identity for a file: a breakpoint resolves
 * against the Source a section was built from, so two Source objects for one
 * file means a breakpoint that cannot see half the program. That was a real
 * defect — {@code BytecodeCompiler.resolveSource} built its own with
 * {@code Source.newBuilder("mezze", text, f.getName())}, i.e. the BASENAME as
 * name against this class's FULL PATH, and re-read the file from disk so it
 * never saw {@link #registerSourceContent}'s override. Sections emitted by the
 * BDSL path therefore belonged to a different Source than the AST path's, and
 * which one a given function got depended on which path compiled it.
 *
 * So the cache and the overrides are static: one Source per path for the whole
 * process, and every caller goes through {@link #sourceFor}. Truffle Sources
 * are VM-level and context-independent, so sharing them across Contexts is
 * what Truffle already does internally when two builders agree.
 *
 * #391 — enables TruffleCompiler.kt deletion.
 */
public final class SourceHelper {

    /**
     * A cached Source plus what it was built FROM, so a file edited on disk is
     * not served stale. The cache is process-wide, so unlike the old
     * per-PipelineRunner-run instance it does not get thrown away between
     * runs — a `mez dev` reload or an editor save would otherwise be pinned to
     * the first read forever. An override-backed entry carries mtime -1 and is
     * invalidated by {@link #register} instead.
     */
    private static final class Entry {
        final long mtime;
        final long size;
        final Source source;
        Entry(long mtime, long size, Source source) {
            this.mtime = mtime; this.size = size; this.source = source;
        }
    }

    /** path -> the single Source for that path. */
    private static final Map<String, Entry> SOURCES = new ConcurrentHashMap<>();

    /** path -> caller-supplied text, for a buffer that is not on disk yet. */
    private static final Map<String, String> CONTENT_OVERRIDES = new ConcurrentHashMap<>();

    /** Provide the real source text for {@code file} (overrides disk-reading). */
    public void registerSourceContent(String file, String content) {
        register(file, content);
    }

    /**
     * Static form of {@link #registerSourceContent}. Drops any Source already
     * built for {@code file}: its text is exactly what just changed, and a
     * Source is immutable, so keeping it would pin the debugger to stale lines
     * across an editor edit or a `mez dev` reload.
     */
    public static void register(String file, String content) {
        CONTENT_OVERRIDES.put(file, content);
        SOURCES.remove(file);
    }

    /** @see #sourceFor */
    public Source getOrCreateSource(String file) {
        return sourceFor(file);
    }

    /**
     * The Source for {@code file}, or null when there is neither a registered
     * override nor a readable file — a path we cannot read is not a program we
     * can attribute, and saying so is better than fabricating an empty Source
     * whose every line lookup then fails somewhere further away.
     *
     * <p>For a real on-disk file this stamps a {@code file://} URI so the
     * debugger can match a client breakpoint's URI to a source section —
     * Truffle's default {@code (lang, content, name)} overload uses a
     * content-hashed {@code truffle:<hash>//path} URI that never matches.
     */
    public static Source sourceFor(String file) {
        if (file == null || file.isEmpty()) return null;
        String content = CONTENT_OVERRIDES.get(file);
        File f = new File(file);
        final long mtime = content != null ? -1L : f.lastModified();
        final long size = content != null ? -1L : f.length();

        Entry cached = SOURCES.get(file);
        if (cached != null && cached.mtime == mtime && cached.size == size) return cached.source;

        if (content == null) {
            if (!f.isFile()) return null;
            try {
                content = java.nio.file.Files.readString(f.toPath());
            } catch (Exception e) {
                return null;
            }
        }

        Source.SourceBuilder builder = Source.newBuilder("mezze", content, file);
        if (f.isFile()) {
            try {
                builder.uri(f.toURI());
            } catch (Exception ignored) {
                // keep default URI
            }
        }
        Source built;
        try {
            built = builder.build();
        } catch (java.io.IOException e) {
            // Content-based Source.newBuilder normally never throws IOException
            // (no disk read), but the signature declares it. Wrap defensively.
            throw new RuntimeException("SourceHelper: failed to build Source for " + file, e);
        }
        // put, not putIfAbsent: reaching here means any existing entry was
        // built from content that has since changed, so it must be replaced.
        SOURCES.put(file, new Entry(mtime, size, built));
        return built;
    }

    /**
     * Build a SourceSection from a SourceInfo (1-based line/col range).
     * Returns null if there is no source, the info is synthetic (non-positive
     * coords), or the source is too short for the requested range.
     */
    public SourceSection createSourceSection(Source source, SourceInfo info) {
        if (source == null || info == null) return null;
        try {
            if (info.getStartLine() <= 0 || info.getStartCol() <= 0) return null;
            int startOffset = source.getLineStartOffset(info.getStartLine()) + (info.getStartCol() - 1);
            int endLine = info.getEndLine() > 0 ? info.getEndLine() : info.getStartLine();
            int endCol = info.getEndCol() > 0 ? info.getEndCol() : info.getStartCol();
            int endOffset = source.getLineStartOffset(endLine) + (endCol - 1);
            int length = Math.max(endOffset - startOffset, 1);
            return source.createSection(startOffset, length);
        } catch (Exception e) {
            return null;
        }
    }
}
