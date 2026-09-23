--- Mezze support for Neovim: tree-sitter grammar + the `mez` language server.
---
--- The tree-sitter queries under queries/mezze/ are GENERATED from
--- tooling/tree-sitter-mezze/queries by tooling/syntax-generators/gen-queries.py.
--- For Neovim that generation is an identity copy — the canonical queries are
--- already written in nvim-treesitter's capture dialect (@variable.member,
--- @type.interface, @number.float). Zed is the consumer that needs renaming.
---
--- Because of that, Neovim gets two query files Zed has no concept of:
---   locals.scm       same-name highlighting, scope-aware go-to-definition
---   textobjects.scm  `daf` / `vif` / `]f` style motions
---
--- Usage (lazy.nvim):
---   { dir = "/path/to/mezze/tooling/nvim-mezze", config = function()
---       require("mezze").setup()
---     end }

local M = {}

--- The grammar lives in this repo, not in a standalone tree-sitter-mezze repo.
--- Resolve it from THIS file's location so the plugin works from a worktree
--- without the user hardcoding a path.
local function grammar_dir()
  local this = debug.getinfo(1, "S").source:sub(2)       -- .../lua/mezze/init.lua
  local plugin_root = vim.fn.fnamemodify(this, ":h:h:h") -- .../nvim-mezze
  local tooling = vim.fn.fnamemodify(plugin_root, ":h")  -- .../tooling
  return tooling .. "/tree-sitter-mezze"
end

local DEFAULTS = {
  --- Register the parser with nvim-treesitter. Set false if you install the
  --- grammar some other way.
  treesitter = true,
  --- Start `mez lsp` for Mezze buffers.
  lsp = true,
  --- Command for the language server. `mez` must be on PATH, or give a path.
  cmd = { "mez", "lsp" },
  --- A Mezze project is the directory containing Mezze.toml.
  root_markers = { "Mezze.toml" },
  --- Extra settings merged into the LSP config.
  lsp_settings = {},
}

--- nvim-treesitter compiles src/parser.c directly — it does NOT run
--- `tree-sitter generate`. That file is gitignored here (only the hand-written
--- scanner.c is tracked), so a plain install would find no parser at all.
--- `requires_generate_from_grammar = true` makes nvim-treesitter run the
--- tree-sitter CLI first, which is why that CLI is a prerequisite.
local function register_parser()
  local ok, parsers = pcall(require, "nvim-treesitter.parsers")
  if not ok then
    return false, "nvim-treesitter is not installed"
  end
  local config = parsers.get_parser_configs()
  config.mezze = {
    install_info = {
      url = grammar_dir(),
      files = { "src/parser.c", "src/scanner.c" },
      branch = "main",
      generate_requires_npm = false,
      requires_generate_from_grammar = true,
    },
    filetype = "mezze",
  }
  return true
end

--- Neovim 0.11 introduced vim.lsp.config/vim.lsp.enable. Older versions need
--- lspconfig. Both paths configure the SAME server; only the plumbing differs.
local function setup_lsp(opts)
  local config = {
    cmd = opts.cmd,
    filetypes = { "mezze" },
    root_markers = opts.root_markers,
    settings = opts.lsp_settings,
  }

  if vim.lsp.config ~= nil and vim.lsp.enable ~= nil then
    vim.lsp.config("mezze", config)
    vim.lsp.enable("mezze")
    return true
  end

  local ok, lspconfig = pcall(require, "lspconfig")
  if not ok then
    return false, "Neovim < 0.11 and lspconfig is not installed"
  end
  local configs = require("lspconfig.configs")
  if configs.mezze == nil then
    configs.mezze = {
      default_config = {
        cmd = opts.cmd,
        filetypes = { "mezze" },
        root_dir = lspconfig.util.root_pattern(unpack(opts.root_markers)),
        settings = opts.lsp_settings,
      },
    }
  end
  lspconfig.mezze.setup({})
  return true
end

--- Report what did not come up rather than failing silently. A half-configured
--- editor that still colours text looks like it works, which is the failure
--- mode this whole tooling tree has hit before.
function M.setup(user_opts)
  local opts = vim.tbl_deep_extend("force", DEFAULTS, user_opts or {})
  local problems = {}

  vim.filetype.add({ extension = { mz = "mezze" } })

  if opts.treesitter then
    local ok, err = register_parser()
    if not ok then
      table.insert(problems, "tree-sitter: " .. err)
    end
  end

  if opts.lsp then
    local ok, err = setup_lsp(opts)
    if not ok then
      table.insert(problems, "lsp: " .. err)
    end
  end

  M._problems = problems
  return #problems == 0, problems
end

--- `:checkhealth mezze` reports the same things; this is the programmatic form.
function M.health()
  return {
    grammar_dir = grammar_dir(),
    problems = M._problems or { "setup() has not been called" },
  }
end

return M
