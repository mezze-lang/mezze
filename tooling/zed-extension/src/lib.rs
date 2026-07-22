//! Zed extension for Mezze.
//!
//! Tree-sitter grammar + queries (bundled in this extension) give the
//! syntactic base coloring; the `mez lsp` language server gives diagnostics,
//! hover, completion, go-to-definition, code actions, and SEMANTIC TOKENS —
//! the resolution-based coloring (params vs. locals vs. functions, record
//! field names as properties, concrete type names) the grammar can't do alone.

use zed_extension_api::{self as zed, LanguageServerId, Result};

struct MezzeExtension;

impl zed::Extension for MezzeExtension {
    fn new() -> Self {
        MezzeExtension
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        // Resolve the `mez` binary; `mez lsp` speaks LSP over stdio.
        // `worktree.which("mez")` looks up PATH; if that misses (e.g. a Zed
        // launched from the macOS Dock/Finder does NOT inherit your shell PATH),
        // it falls back to bare `"mez"` and lets the OS resolve it.
        //
        // If the server doesn't start, ensure `mez` is visible to Zed — either
        // launch Zed from a terminal (`zed .`), add `~/.mezze/bin` to your
        // login PATH, or set an explicit path in Zed settings:
        //   "lsp": { "mezze": { "binary": { "path": "/Users/you/.mezze/bin/mez" } } }
        let path = worktree
            .which("mez")
            .unwrap_or_else(|| "mez".to_string());

        Ok(zed::Command {
            command: path,
            args: vec!["lsp".to_string()],
            env: vec![],
        })
    }
}

zed::register_extension!(MezzeExtension);
