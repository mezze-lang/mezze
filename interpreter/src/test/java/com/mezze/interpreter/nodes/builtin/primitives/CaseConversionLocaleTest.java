package com.mezze.interpreter.nodes.builtin.primitives;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Case conversion must not depend on the host's locale.
 *
 * <p>`str::to_upper`, `str::to_lower` and the `Char` pair are LANGUAGE
 * PRIMITIVES: the same program has to produce the same string on every machine
 * it runs on. They used {@code String.toUpperCase()} — the overload that reads
 * {@code Locale.getDefault()} — so they did not.
 *
 * <p>Turkish is the standard counter-example, and it is not a curiosity: on a
 * {@code tr} host the dotless/dotted i rules change ASCII letters.
 *
 * <pre>
 *   "i".toUpperCase()              I (U+0049) elsewhere,  İ (U+0130) on tr
 *   "IF-MATCH".toLowerCase()       if-match  elsewhere,   ıf-match (U+0131) on tr
 *   "X-API-KEY".toLowerCase()      x-api-key elsewhere,   x-apı-key on tr
 * </pre>
 *
 * <p>The same bug reached HTTP: {@code HttpServerNatives} and {@code IONatives}
 * lower-case incoming header NAMES to key the map a guest looks them up in, so
 * on a Turkish host a request's {@code If-Match} landed under {@code ıf-match}
 * and every guest lookup by the real name silently missed. Header names are
 * ASCII and RFC 9110 folds them with ASCII rules, which is exactly what
 * {@code Locale.ROOT} does. Those two call sites are the same one-line change
 * as the four below; they are not unit-testable without a live request, so
 * they are covered by the protocol tests plus this demonstration of the
 * mechanism.
 *
 * <p>{@code @ResourceLock(Resources.LOCALE)} because the default locale is
 * process-global state: without it this test could flip the locale out from
 * under a parallel one.
 */
@ResourceLock(Resources.LOCALE)
class CaseConversionLocaleTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    /** Runs {@code body} with the default locale set to Turkish, then restores it. */
    private static void underTurkishLocale(Runnable body) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(TURKISH);
            // Guard the premise: if the JDK ever stopped special-casing this,
            // the assertions below would pass for the wrong reason.
            assertNotEquals("I", "i".toUpperCase(),
                    "premise failed — this JDK does not apply Turkish casing, so the test proves nothing");
            body.run();
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void strToUpperIgnoresTheHostLocale() {
        underTurkishLocale(() -> {
            assertEquals("I", PrimitiveNatives.stringToUpperCase("i"));
            assertEquals("IF-MATCH", PrimitiveNatives.stringToUpperCase("if-match"));
            assertEquals("X-API-KEY", PrimitiveNatives.stringToUpperCase("x-api-key"));
        });
    }

    @Test
    void strToLowerIgnoresTheHostLocale() {
        underTurkishLocale(() -> {
            assertEquals("i", PrimitiveNatives.stringToLowerCase("I"));
            assertEquals("if-match", PrimitiveNatives.stringToLowerCase("IF-MATCH"));
            assertEquals("content-disposition",
                    PrimitiveNatives.stringToLowerCase("CONTENT-DISPOSITION"));
        });
    }

    @Test
    void charToUpperAndLowerIgnoreTheHostLocale() {
        underTurkishLocale(() -> {
            assertEquals("I", CharNatives.toUpper("i"));
            assertEquals("i", CharNatives.toLower("I"));
        });
    }

    /**
     * Non-ASCII casing still works — this is about dropping the HOST's locale,
     * not about restricting the primitives to ASCII.
     */
    @Test
    void nonAsciiCasingStillWorks() {
        assertEquals("ÉCOLE", PrimitiveNatives.stringToUpperCase("école"));
        assertEquals("école", PrimitiveNatives.stringToLowerCase("ÉCOLE"));
        assertEquals("ΑΒΓ", PrimitiveNatives.stringToUpperCase("αβγ"));
    }
}
