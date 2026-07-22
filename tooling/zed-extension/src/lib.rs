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
        // Prefer a `mez` on the user's PATH; fall back to the conventional
        // install location under ~/.mezze/bin. `mez lsp` speaks LSP over stdio.
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
