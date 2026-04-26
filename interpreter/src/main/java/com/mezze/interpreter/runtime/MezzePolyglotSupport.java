package com.mezze.interpreter.runtime;

import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.graalvm.polyglot.Value;

import java.util.Set;

/**
 * Polyglot support for Mezze.
 *
 * Provides access to other GraalVM languages (Python, JavaScript, Ruby, etc.)
 * when running in JVM mode with the required language JARs on the classpath.
 *
 * Note: This does NOT work in native binary mode unless languages are
 * embedded at build time.
 */
public final class MezzePolyglotSupport {

    private final Env env;
    private volatile Set<String> availableLanguages;

    public MezzePolyglotSupport(Env env) {
        this.env = env;
    }

    /**
     * Check if a language is available.
     */
    @TruffleBoundary
    public boolean isLanguageAvailable(String languageId) {
        return getAvailableLanguages().contains(languageId);
    }

    /**
     * Get all available language IDs.
     */
    @TruffleBoundary
    public Set<String> getAvailableLanguages() {
        if (availableLanguages == null) {
            synchronized (this) {
                if (availableLanguages == null) {
                    availableLanguages = env.getPublicLanguages().keySet();
                }
            }
        }
        return availableLanguages;
    }

    /**
     * Import a module from a guest language.
     *
     * @param languageId The language ID (e.g., "python", "js", "ruby")
     * @param moduleName The module to import
     * @return The imported module as a polyglot Value
     */
    @TruffleBoundary
    public Object importModule(String languageId, String moduleName) {
        assertLanguageAvailable(languageId);

        // Use language-specific import mechanism
        String importCode = switch (languageId) {
            case "python" -> "import " + moduleName + "; " + moduleName;
            case "js" -> "require('" + moduleName + "')";
            case "ruby" -> "require '" + moduleName + "'; " + moduleName.replace("/", "::");
            default -> throw new UnsupportedOperationException(
                "Import not supported for language: " + languageId
            );
        };

        return eval(languageId, importCode);
    }

    /**
     * Evaluate code in a guest language.
     *
     * @param languageId The language ID
     * @param code The code to evaluate
     * @return The result as a polyglot Value
     */
    @TruffleBoundary
    public Object eval(String languageId, String code) {
        assertLanguageAvailable(languageId);
        return env.parsePublic(
            com.oracle.truffle.api.source.Source.newBuilder(languageId, code, "<mezze-eval>").build()
        ).call();
    }

    /**
     * Get the bindings (global scope) of a guest language.
     */
    @TruffleBoundary
    public Object getBindings(String languageId) {
        assertLanguageAvailable(languageId);
        return env.getPolyglotBindings();
    }

    private void assertLanguageAvailable(String languageId) {
        if (!isLanguageAvailable(languageId)) {
            throw new MezzePolyglotException(
                "Language '" + languageId + "' is not available.\n" +
                "Available languages: " + getAvailableLanguages() + "\n\n" +
                "To use " + languageId + ", run Mezze in JVM mode with the language JAR:\n" +
                "  java -cp mez.jar:" + languageId + ".jar com.mezze.language.cli.MainKt exec <file>"
            );
        }
    }

    /**
     * Exception thrown when polyglot operations fail.
     */
    public static final class MezzePolyglotException extends RuntimeException {
        public MezzePolyglotException(String message) {
            super(message);
        }

        public MezzePolyglotException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
