package com.mezze.language.parsedast.diagnostics

/**
 * Advice that belongs to a KIND of error rather than to one site that raises it.
 *
 * A hint spelled out at the call site gets copied to the next site that needs it,
 * and then the copies drift: the same situation starts being explained two
 * slightly different ways depending on which pass noticed it, and improving the
 * wording means finding every copy first. The annotation-mismatch hint below had
 * eight copies across three files.
 *
 * Put a hint here once it is needed in more than one place. A hint that is
 * genuinely specific to one site — because it names that site's variables, or
 * only makes sense given what that site just checked — belongs at the site.
 *
 * Wording rules, same as for messages: say what is wrong and what to do about it,
 * in that order; name the construct in the language the user wrote, not the
 * compiler's internal name for it.
 */
object DiagnosticHints {

    /**
     * A binding's declared type and its body disagree. Both sides are equally
     * likely to be the mistake, so the hint must not assume which.
     */
    const val ANNOTATION_VS_BODY =
        "either change the annotation to match the body, or change the body to produce the annotated type"

    /**
     * A variant was matched or destructured as though it were a plain value.
     * `Some { ... }` is the example because Option is the variant every program
     * meets first.
     */
    const val USE_CONSTRUCTOR_PATTERN =
        "use a constructor pattern like `Some { ... }`"

    /**
     * A newtype's constructor was referenced from outside the module that
     * declares it. This is not a missing `pub` the user can add — newtype
     * constructors are deliberately module-private, so the answer is to look for
     * the factory the module exports instead.
     */
    const val NEWTYPE_CONSTRUCTOR_NOT_EXPORTED =
        "newtype constructors are not exported; look for a `pub` factory function in the defining module"

    /**
     * A recursive binding whose type cannot be inferred without help. Phrased as
     * the usual CAUSE rather than as the occurs-check failure it technically is,
     * because the user did not write an occurs check.
     */
    const val INFINITE_TYPE_NEEDS_ANNOTATION =
        "this usually means a recursive let-binding is missing a type annotation"
}
