--- `:checkhealth mezze`
---
--- Each check answers a question that has actually gone wrong in this tree:
--- is the grammar reachable, is the tree-sitter CLI present (the parser is
--- GENERATED, not committed), is `mez` on PATH, and did the queries get
--- installed. A missing piece here is the difference between "coloured text"
--- and "a working language integration".
local M = {}

local function report(ok, msg)
  if ok then vim.health.ok(msg) else vim.health.error(msg) end
end

function M.check()
  vim.health.start("mezze")

  local mezze = require("mezze")
  local info = mezze.health()

  local gdir = info.grammar_dir
  report(vim.fn.isdirectory(gdir) == 1,
    "grammar directory: " .. gdir)

  if vim.fn.executable("tree-sitter") == 1 then
    vim.health.ok("tree-sitter CLI found (needed: src/parser.c is generated, not committed)")
  else
    vim.health.error(
      "tree-sitter CLI not found. The Mezze parser is generated from grammar.js "
      .. "at install time, so `:TSInstall mezze` cannot work without it.")
  end

  if vim.fn.executable("mez") == 1 then
    vim.health.ok("mez found on PATH")
  else
    vim.health.warn("mez not on PATH — syntax highlighting will work, "
      .. "but there will be no diagnostics, hover or completion.")
  end

  local ok_parser = pcall(vim.treesitter.language.inspect, "mezze")
  if ok_parser then
    vim.health.ok("mezze parser is installed")
  else
    vim.health.warn("mezze parser not installed yet — run :TSInstall mezze")
  end

  for _, p in ipairs(info.problems) do
    vim.health.error(p)
  end
  if #info.problems == 0 then
    vim.health.ok("setup() completed with no problems")
  end
end

return M
