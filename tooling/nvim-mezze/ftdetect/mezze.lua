-- `.mz` is Mezze. Registered here as well as in setup() so the filetype is
-- correct even for a user who drops this plugin on the runtimepath without
-- calling require("mezze").setup() — the queries and ftplugin below key off
-- the filetype, so getting it wrong means nothing else fires.
vim.filetype.add({ extension = { mz = "mezze" } })
