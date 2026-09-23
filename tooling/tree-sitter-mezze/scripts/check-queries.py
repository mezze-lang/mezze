#!/usr/bin/env python3
"""Validate tree-sitter query files against the grammar's node-types.json.

tree-sitter reports ONE query error per run, which makes fixing a drifted
query set a slow ping-pong. This reports every bad reference at once:
  - a node type the grammar does not have
  - a field the node does not have
  - a field whose declared child types don't include the one the query asks for
"""
import json, re, sys, os

grammar_dir = sys.argv[1]
nt = json.load(open(os.path.join(grammar_dir, "src", "node-types.json")))

named = {n["type"] for n in nt if n.get("named")}
all_types = {n["type"] for n in nt}
# `_` is tree-sitter's wildcard (matches any node); ERROR/MISSING are built-ins
# every grammar has. None of the three appear in node-types.json.
BUILTIN = {"_", "ERROR", "MISSING"}
all_types |= BUILTIN
fields = {}          # node -> {field -> set(child types)}
subtypes = {}        # supertype -> set(members)
for n in nt:
    if n.get("fields"):
        fields[n["type"]] = {
            f: {t["type"] for t in spec.get("types", [])}
            for f, spec in n["fields"].items()
        }
    if n.get("subtypes"):
        subtypes[n["type"]] = {t["type"] for t in n["subtypes"]}

def expand(t):
    """A supertype stands for any of its members."""
    return subtypes.get(t, {t})

def tokenize(src):
    # strip ; comments (not inside strings)
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n and (src[j] != '"' or src[j-1] == '\\'):
                j += 1
            out.append(('str', src[i:j+1])); i = j + 1
        elif c == ';':
            while i < n and src[i] != '\n':
                i += 1
        elif c in '()[]':
            out.append(('punct', c)); i += 1
        elif c.isspace():
            i += 1
        else:
            j = i
            while j < n and not src[j].isspace() and src[j] not in '()[];"':
                j += 1
            out.append(('atom', src[i:j])); i = j
    return out

# Every directory whose .scm files must compile against THIS grammar. The
# extensions ship their own copies — Zed cannot point at the grammar repo — and
# a copy is exactly as fatal as the original when it names a node the grammar
# does not have: tree-sitter refuses the whole FILE, not the one pattern. The
# Zed copy sat on a `"!"` token for long enough to ship with no highlighting at
# all, because only the source directory was ever checked.
query_dirs = [("tree-sitter", os.path.join(grammar_dir, "queries"))]
tooling = os.path.dirname(os.path.abspath(grammar_dir.rstrip("/")))
zed = os.path.join(tooling, "zed-extension", "languages", "mezze")
if os.path.isdir(zed):
    query_dirs.append(("zed", zed))
query_dirs += [(os.path.basename(d.rstrip("/")), d) for d in sys.argv[2:]]

problems = []
for label, qdir in query_dirs:
  for fn in sorted(os.listdir(qdir)):
    if not fn.endswith(".scm"):
        continue
    path = os.path.join(qdir, fn)
    fn = f"{label}/{fn}"
    src = open(path, encoding="utf-8").read()
    toks = tokenize(src)
    stack = []            # enclosing named-node context
    pending_field = None
    for k, (kind, tok) in enumerate(toks):
        if kind == 'punct' and tok == '(':
            nxt = toks[k+1] if k + 1 < len(toks) else None
            if nxt and nxt[0] == 'atom' and re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]*', nxt[1]):
                node = nxt[1]
                if node not in all_types:
                    problems.append((fn, f"unknown node type ({node})"))
                # validate against the field we were told to expect
                if pending_field and node not in BUILTIN:
                    owner, fname = pending_field
                    allowed = fields.get(owner, {}).get(fname)
                    if allowed is not None:
                        ok = bool(expand(node) & set().union(*(expand(a) for a in allowed)) ) if allowed else False
                        if not ok:
                            problems.append((fn,
                                f"({owner} {fname}: ({node})) — field takes {sorted(allowed)}"))
                stack.append(node)
                pending_field = None
            else:
                stack.append(None)
        elif kind == 'punct' and tok == ')':
            if stack:
                stack.pop()
            pending_field = None
        elif kind == 'atom' and tok.endswith(':'):
            fname = tok[:-1]
            owner = next((s for s in reversed(stack) if s), None)
            if owner:
                if owner in fields and fname not in fields[owner]:
                    problems.append((fn, f"({owner}) has no field '{fname}' — has {sorted(fields[owner])}"))
                elif owner not in fields:
                    problems.append((fn, f"({owner}) has no fields at all, but query asks '{fname}'"))
                else:
                    pending_field = (owner, fname)
        elif kind == 'str':
            lit = tok[1:-1]
            if lit and lit not in all_types:
                problems.append((fn, f'anonymous token "{lit}" is not in the grammar'))

seen = set()
for fn, msg in problems:
    key = (fn, msg)
    if key in seen:
        continue
    seen.add(key)
    print(f"{fn}: {msg}")
print(f"\n{len(seen)} distinct problem(s)")

# This script had no exit code: it printed its findings and returned 0 whatever
# they were, so wiring it into CI would have reported a pass while naming the
# fault. That is how the Zed copy kept a `"!"` the grammar does not have, which
# costs the whole file — tree-sitter refuses a query that names a missing node.
sys.exit(1 if seen else 0)
