#!/usr/bin/env python3
"""Generate every downstream copy of the tree-sitter queries from the canonical
set in tooling/tree-sitter-mezze/queries/.

Editors cannot point at the grammar repo — Zed and Neovim both have to SHIP
their query files — so the copies are structural. Maintaining them by hand is
not, and the cost of doing so has already been paid once: Zed's copy drifted
onto a `"!"` token the grammar does not have, and tree-sitter answers a query
naming a missing node by refusing the WHOLE FILE. Zed rendered Mezze with no
highlighting at all, and nothing said so.

One generator, one profile per consumer:

  zed     9 capture renames, 3 files. Zed's theme vocabulary predates
          nvim-treesitter's split of these into sub-captures, and its
          textobjects are `.around`/`.inside` rather than `.outer`/`.inner`.
  nvim    identity — the canonical queries are ALREADY written in Neovim's
          dialect, which is why only Zed needs a map. Neovim additionally
          loads folds.scm and locals.scm (same-name highlighting, scope-aware
          go-to-def); Zed loads neither, and derives folding from indents.

  ./gen-queries.py                # regenerate every consumer
  ./gen-queries.py --check        # exit 1 if any checked-in copy has drifted
  ./gen-queries.py --only zed     # one consumer

Pair --check with tree-sitter-mezze/scripts/check-queries.py: this proves the
copy matches its source, that proves the result still COMPILES. Both are
needed — matching the source cannot catch a fault the source also has.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLING = os.path.dirname(HERE)
SRC = os.path.join(TOOLING, "tree-sitter-mezze", "queries")

# Neovim/Helix capture -> consumer capture. Everything absent is passed through.
# Verified against the Zed 1.14.2 binary rather than assumed: its extension
# host loads exactly `highlights outline indents injections redactions
# runnables textobjects`, and its textobject captures are `.around`/`.inside`
# where nvim-treesitter uses `.outer`/`.inner`.
#
# Two consequences that were wrong here before:
#   - folds.scm was shipped to Zed and Zed never loads it (folding is derived
#     from indents, not a query). Dead file.
#   - textobjects.scm was NOT shipped to Zed, on the belief that Zed had no
#     concept of it. It does.
#
# The two rename sets are merged into one table because their keys cannot
# collide: `.outer`/`.inner` never appear in highlights.scm, and the
# highlight captures never appear in textobjects.scm.
ZED_CAPTURES = {
    # highlights.scm
    "@comment.documentation": "@comment.doc",
    "@constant.builtin.boolean": "@boolean",
    "@number.float": "@number",
    "@type.interface": "@type",
    "@variable.member": "@property",
    # textobjects.scm — Zed recognises only class/comment/function here; the
    # rest of our captures (@assignment.*, @block.*, @call.*, @parameter.*)
    # have no Zed equivalent and simply never match, which is harmless.
    "@function.outer": "@function.around",
    "@function.inner": "@function.inside",
    "@class.outer": "@class.around",
    "@comment.outer": "@comment.around",
}

PROFILES = {
    "zed": {
        "dest": os.path.join(TOOLING, "zed-extension", "languages", "mezze"),
        "captures": ZED_CAPTURES,
        "files": ["highlights.scm", "indents.scm", "textobjects.scm"],
        "note": "Zed's theme vocabulary predates nvim-treesitter's sub-captures.",
    },
    "nvim": {
        "dest": os.path.join(TOOLING, "nvim-mezze", "queries", "mezze"),
        "captures": {},
        # Neovim reads all five; Zed loads three of them.
        "files": ["highlights.scm", "indents.scm", "folds.scm",
                  "locals.scm", "textobjects.scm"],
        "note": "Identity — the canonical queries are already in this dialect.",
    },
}


def split_comment(line):
    """(code, comment) for one line. `;` starts a comment unless it is inside a
    string. Captures never appear inside strings, so this is enough to keep a
    rename out of prose: the `;;` commentary keeps naming the SOURCE captures
    and stays true to it, while the code below it is what the editor reads."""
    i, n, in_str = 0, len(line), False
    while i < n:
        c = line[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == ";":
            return line[:i], line[i:]
        i += 1
    return line, ""


def render(name, text, profile):
    captures = profile["captures"]
    if captures:
        longest_first = sorted(captures, key=len, reverse=True)
        pattern = re.compile("|".join(re.escape(k) for k in longest_first))
        body = []
        for line in text.split("\n"):
            code, comment = split_comment(line)
            body.append(pattern.sub(lambda m: captures[m.group(0)], code) + comment)
        body = "\n".join(body)
        table = "\n".join(f";;     {k:<28} -> {v}" for k, v in sorted(captures.items()))
        mapping = (f";;\n;; Captures renamed for this editor:\n{table}\n;;\n"
                   ";; Every other capture is passed through unchanged. The `;;`\n"
                   ";; commentary below came from the source and names the SOURCE\n"
                   ";; captures — read it through the table above.\n")
    else:
        body = text
        mapping = (";;\n;; Captures are passed through UNCHANGED: the canonical queries\n"
                   ";; are already written in this editor's dialect.\n")

    header = (f";; GENERATED — do not edit.\n"
              f";;\n"
              f";; Source: tooling/tree-sitter-mezze/queries/{name}\n"
              f";; Regenerate: tooling/syntax-generators/gen-queries.py\n"
              + mapping)
    return header + body


def main():
    argv = sys.argv[1:]
    check = "--check" in argv
    only = None
    if "--only" in argv:
        i = argv.index("--only")
        if i + 1 >= len(argv):
            print("error: --only needs a consumer name", file=sys.stderr)
            return 2
        only = argv[i + 1]
        if only not in PROFILES:
            print(f"error: unknown consumer {only!r}; have {sorted(PROFILES)}",
                  file=sys.stderr)
            return 2

    names = [only] if only else sorted(PROFILES)
    drifted, wrote, checked = [], 0, 0

    for consumer in names:
        profile = PROFILES[consumer]
        dest = profile["dest"]
        if not check:
            os.makedirs(dest, exist_ok=True)
        for name in profile["files"]:
            src = os.path.join(SRC, name)
            if not os.path.exists(src):
                print(f"error: missing source {src}", file=sys.stderr)
                return 2
            want = render(name, open(src, encoding="utf-8").read(), profile)
            out = os.path.join(dest, name)
            have = open(out, encoding="utf-8").read() if os.path.exists(out) else None
            checked += 1
            if want == have:
                continue
            if check:
                drifted.append(f"{consumer}/{name}")
            else:
                with open(out, "w", encoding="utf-8") as f:
                    f.write(want)
                wrote += 1
                print(f"wrote {os.path.relpath(out, TOOLING)}")

    if check:
        if drifted:
            print("query copies have DRIFTED from their source:")
            for d in drifted:
                print(f"  {d}")
            print("run tooling/syntax-generators/gen-queries.py")
            return 1
        print(f"query copies: {checked}/{checked} match their source "
              f"({', '.join(names)})")
    elif wrote == 0:
        print(f"query copies: all {checked} already current")
    return 0


if __name__ == "__main__":
    sys.exit(main())
