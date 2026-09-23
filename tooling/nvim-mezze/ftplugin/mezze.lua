-- Buffer options for Mezze.
--
-- In ftplugin/, not after/ftplugin/. `after/` is for OVERRIDING another
-- plugin's settings, and it only loads at all when something appends this
-- plugin's `after` directory to the runtimepath — a plugin manager does,
-- a plain `rtp:prepend` does not, so these options silently never applied.
-- ftplugin/ is also the polite place: a user's own after/ftplugin still wins.
-- `#` is the line comment and `##` is a doc comment. `#{` is NEITHER — it is
-- compiler metadata (an attribute), so a naive "# starts a comment" rule is
-- wrong about it; the tree-sitter grammar and the TextMate grammar both
-- require a space or EOL after the `#`. commentstring inserts "# " with the
-- space, which stays on the right side of that rule.
vim.bo.commentstring = "# %s"
vim.bo.comments = ":##,:#"

-- Mezze is indentation-significant: the scanner emits INDENT/DEDENT tokens,
-- so a stray tab where spaces are expected is a parse-level problem, not a
-- style one.
vim.bo.expandtab = true
vim.bo.shiftwidth = 2
vim.bo.tabstop = 2
vim.bo.softtabstop = 2
