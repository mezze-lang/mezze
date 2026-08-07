package com.mezze.interpreter.runtime;

import com.mezze.language.truffleir.SourceInfo;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure Truffle Source/SourceSection helpers, extracted from TruffleCompiler
 * so PipelineRunner can attribute the entry root's source without
 * depending on the AST compiler. Callers hold their own instance —
 * source-content overrides live on the instance.
 *
 * #391 — enables TruffleCompiler.kt deletion.
 */
public final class SourceHelper {
    private final Map<String, Source> sourceCache = new HashMap<>();
    private final Map<String, String> sourceContentOverrides = new HashMap<>();

    /** Provide the real source text for {@code file} (overrides disk-reading). */
    public void registerSourceContent(String file, String content) {
        sourceContentOverrides.put(file, content);
    }

    /**
     * Get-or-build the Truffle Source for the given file path.
     * For a real on-disk file, stamps a {@code file://} URI so the debugger
     * can match a client breakpoint's URI to a source section — Truffle's
     * default {@code (lang, content, name)} overload uses a content-hashed
     * {@code truffle:<hash>//path} URI that never matches.
     */
    public Source getOrCreateSource(String file) {
        Source cached = sourceCache.get(file);
        if (cached != null) return cached;
        File f = new File(file);
        String override = sourceContentOverrides.get(file);
        String content;
        if (override != null) {
            content = override;
        } else {
            try {
                content = java.nio.file.Files.readString(f.toPath());
            } catch (Exception e) {
                content = "";
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
        sourceCache.put(file, built);
        return built;
    }

    /**
     * Build a SourceSection from a SourceInfo (1-based line/col range).
     * Returns null if the info is synthetic (non-positive coords) or the
     * source is too short for the requested range.
     */
    public SourceSection createSourceSection(Source source, SourceInfo info) {
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
