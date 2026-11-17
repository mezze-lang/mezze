package com.mezze.interpreter;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test class whose assertions read the CLOCK: elapsed-time bounds, "parallel
 * is faster than sequential", sleep routing. Such a class needs the machine to
 * itself, so `:interpreter:test` excludes the {@code timing} tag and runs the
 * rest in parallel forks, and `:interpreter:testTiming` runs these alone,
 * afterwards. Nested test classes inherit the tag.
 *
 * <p>If a class you write asserts on elapsed time, annotate it. A missing
 * annotation shows up as a flake under parallel forks, not as a failure here.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("timing")
public @interface TimingSensitive {
}
