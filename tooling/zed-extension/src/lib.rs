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
        //
        // Order matters, and the first step used to be missing. An explicit
        // path in Zed settings wins over anything discovered:
        //
        //   "lsp": { "mezze": { "binary": { "path": "…/.mezze/bin/mez" } } }
        //
        // The README, this file's own comment and extension.toml all advertised
        // that setting while the code looked only at PATH — so the documented
        // escape hatch did nothing, for exactly the case it documents (a Zed
        // launched from the macOS Dock does NOT inherit your shell PATH, so
        // `which` misses). LspSettings has been in zed_extension_api since
        // 0.1.0, the version already pinned here.
        //
        // Falling back to bare "mez" rather than failing is deliberate: the OS
        // may still resolve it from an environment we cannot see.
        let binary = zed::settings::LspSettings::for_worktree("mezze", worktree)
            .ok()
            .and_then(|s| s.binary);

        let command = binary
            .as_ref()
            .and_then(|b| b.path.clone())
            .or_else(|| worktree.which("mez"))
            .unwrap_or_else(|| "mez".to_string());

        // Configured arguments REPLACE `lsp` rather than appending to it —
        // someone pointing at a wrapper script has to be able to say how it is
        // invoked.
        let args = binary
            .and_then(|b| b.arguments)
            .unwrap_or_else(|| vec!["lsp".to_string()]);

        Ok(zed::Command {
            command,
            args,
            env: vec![],
        })
    }
}

zed::register_extension!(MezzeExtension);
