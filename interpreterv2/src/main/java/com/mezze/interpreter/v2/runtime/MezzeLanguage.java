package com.mezze.interpreter.v2.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;

/**
 * Mezze language registration for Truffle.
 *
 * Note: We use "mezze-v2" as the language ID to allow coexistence
 * with the v1 interpreter during migration.
 */
@TruffleLanguage.Registration(
    id = MezzeLanguage.ID,
    name = "Mezze",
    version = "2.0",
    defaultMimeType = MezzeLanguage.MIME_TYPE,
    characterMimeTypes = MezzeLanguage.MIME_TYPE
)
public final class MezzeLanguage extends TruffleLanguage<MezzeContext> {

    public static final String ID = "mezze-v2";
    public static final String MIME_TYPE = "application/x-mezze";

    @Override
    protected MezzeContext createContext(Env env) {
        return new MezzeContext(this, env);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws Exception {
        // Parsing is handled externally by the Mezze parser
        // This method is called when Truffle's polyglot API is used directly
        throw new UnsupportedOperationException(
            "Direct parsing not supported. Use MezzeInterpreterV2 to execute Mezze code."
        );
    }

    /**
     * Get the language instance from a node.
     */
    public static MezzeLanguage get(Node node) {
        return REFERENCE.get(node);
    }

    /**
     * Get the current context for the current thread.
     */
    public MezzeContext getCurrentContext() {
        return CONTEXT_REFERENCE.get(null);
    }

    private static final LanguageReference<MezzeLanguage> REFERENCE =
        LanguageReference.create(MezzeLanguage.class);

    private static final ContextReference<MezzeContext> CONTEXT_REFERENCE =
        ContextReference.create(MezzeLanguage.class);
}
