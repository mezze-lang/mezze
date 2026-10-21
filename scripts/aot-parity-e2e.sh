#!/usr/bin/env bash
#
# Differential test: the NATIVE binary must agree with the JVM one.
#
# Why this exists. The whole interpreter suite — 6,984 tests — runs on the JVM,
# so a divergence that only appears in an AOT image is invisible to all of it.
# One did, and it was a silent wrong answer rather than a crash:
#
#   `getVariantTag` recovered a constructor NAME by reverse-scanning the tag
#   registry, which yields "Unknown(<tag>)" for a constructor that was never
#   registered — i.e. every stdlib constructor in a native image. Six natives
#   switched on that name. In the native binary `write_bytes` ignored its
#   WriteMode and always truncated (so CreateNew never refused and
#   CreateOrAppend overwrote), and every FileLock came out Exclusive.
#
# The fix was to compare TAGS instead of recovered names. This script is the
# guard, and it is deliberately NOT a list of expected strings: it runs each
# program through both binaries and demands the same output. That catches the
# next AOT divergence too, whatever it turns out to be, without anyone having
# to predict it.
#
# WHERE THE CONSTRUCTOR IS BUILT DECIDES WHETHER A CASE CAN SEE THIS BUG, and
# it took reverting the fix to find that out. A constructor written in USER
# source registers its name in the tag registry on the way through, so the
# reverse lookup then succeeds and the bug hides: a case that writes
# `kind = Shared` itself passes either way. The failure needs a constructor
# built inside PRECOMPILED STDLIB code, where the tag is baked in at compile
# time and nothing registers the name at run time — which is why the writemode
# case goes through `create_exclusive` and `append_text` rather than calling
# `write_bytes` with a mode of its own. Measured: with the fix reverted, the
# writemode case diverges (`lock=True/True log=c` native vs
# `lock=True/False log=abc` JVM) and the lockkind case does not budge.
#
# So: a new case here is only a guard if the variant it depends on is
# constructed by the stdlib. Otherwise it is a parity check, which is still
# worth having, but will not catch this class.
#
#     bash scripts/aot-parity-e2e.sh
#
# Needs both builds:
#     just build     -> cli/build/install/mez/bin/mez          (JVM)
#     just native    -> cli/build/native/nativeCompile/mez     (AOT)
#
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JVM_MEZ="${JVM_MEZ:-$REPO/cli/build/install/mez/bin/mez}"
NATIVE_MEZ="${NATIVE_MEZ:-$REPO/cli/build/native/nativeCompile/mez}"

for bin in "$JVM_MEZ" "$NATIVE_MEZ"; do
  if [ ! -x "$bin" ]; then
    echo "❌ not found or not executable: $bin"
    echo "   build both:  just build && just native"
    exit 2
  fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail=0

# $1 = case name, $2 = program source.
#
# `mez init` scaffolds the project and the program replaces src/app.mz — the
# project layout is the CLI's business, not this script's.
parity() {
  local name=$1 src=$2
  local proj="$WORK/$name"
  # `mez init` takes a NAME and scaffolds into the current directory, so cd
  # first rather than handing it a path.
  if ! (cd "$WORK" && "$JVM_MEZ" init "$name" >/dev/null 2>&1); then
    echo "  ❌ $name — mez init failed"
    fail=1
    return
  fi
  if [ ! -f "$proj/src/app.mz" ]; then
    echo "  ❌ $name — mez init did not scaffold $proj/src/app.mz"
    fail=1
    return
  fi
  printf '%s\n' "$src" > "$proj/src/app.mz"

  local jvm_out native_out
  # MEZZE_HOME= forces the dev runtime rather than an installed toolchain.
  jvm_out="$(cd "$proj" && MEZZE_HOME= "$JVM_MEZ" dev --no-watch 2>&1 || true)"
  native_out="$(cd "$proj" && MEZZE_HOME= "$NATIVE_MEZ" dev --no-watch 2>&1 || true)"

  # Two empty outputs are not agreement — that is how this script first
  # reported three green cases that had never run. A case must PRODUCE
  # something before its equality means anything.
  if [ -z "$jvm_out" ]; then
    echo "  ❌ $name — produced no output on the JVM; the case did not run"
    fail=1
    return
  fi

  if [ "$jvm_out" = "$native_out" ]; then
    echo "  ✅ $name — both: $jvm_out"
  else
    echo "  ❌ $name — DIVERGED"
    echo "       jvm:    $jvm_out"
    echo "       native: $native_out"
    fail=1
  fi
}

echo "AOT parity (JVM vs native), $(basename "$NATIVE_MEZ")"

# WriteMode: the three arms must be distinguishable. CreateNew must refuse the
# second caller, and CreateOrAppend must not truncate.
parity writemode 'use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateNew, CreateOrAppend, CreateOrTruncate, read_text, write_text, create_exclusive, append_text }
use std::collections::bytes
use std::bool
use std::effects::log::{ println, Console }

let main = {} -> perform Console in perform MemoryFileSystem in do
  let first = create_exclusive { path = "/lock", bytes = bytes::from_str { s = "a" } }
  let second = create_exclusive { path = "/lock", bytes = bytes::from_str { s = "b" } }
  let _ = write_text { path = "/log", text = "a" }
  let _ = append_text { path = "/log", text = "b" }
  let _ = append_text { path = "/log", text = "c" }
  let report = match first is
    Ok { val = f } -> match second is
      Ok { val = s } -> match read_text { path = "/log" } is
        Ok { val = t } -> "lock=$(bool::to_str { b = f })/$(bool::to_str { b = s }) log=$(t)"
        Err { error = _ } -> "read-failed"
      Err { error = _ } -> "second-errored"
    Err { error = _ } -> "first-errored"
  println { msg = report }'

# LockKind: two Shared locks must coexist. NOT a guard for the tag bug — this
# builds `Shared` in user source, which registers the name and makes the old
# code work; verified by reverting the fix and watching this stay green. Kept
# as a plain parity check, since lock semantics crossing JVM/AOT is worth
# knowing about on its own.
parity lockkind 'use std::effects::filesystem::{ FileLock, MemoryFileLock, Shared }
use std::effects::log::{ println, Console }

let main = {} -> perform Console in perform MemoryFileLock in do
  let report = match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
    Ok { val = a } -> match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
      Ok { val = b } -> "two-shared-locks-coexist"
      Err { error = _ } -> "second-shared-refused"
    Err { error = _ } -> "first-failed"
  println { msg = report }'

# OpenMode: Append must not truncate what is already there. Same caveat as
# lockkind — `Append` is constructed here, so this is parity, not a guard.
parity openmode 'use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFileSystem, MemoryFileStreaming, Append, read_text, write_text }
use std::collections::bytes
use std::effects::log::{ println, Console }

let main = {} -> perform Console in perform MemoryFileSystem in perform MemoryFileStreaming in do
  let _ = write_text { path = "/f", text = "keep" }
  let report = match FileStreaming.open { path = "/f", mode = Append } is
    Ok { val = h } -> do
      let _ = FileStreaming.write_chunk { handle = h, bytes = bytes::from_str { s = "-added" } }
      let _ = FileStreaming.close { handle = h }
      match read_text { path = "/f" } is
        Ok { val = t } -> t
        Err { error = _ } -> "read-failed"
    Err { error = _ } -> "open-failed"
  println { msg = report }'

echo
if [ "$fail" = 0 ]; then
  echo "✅ aot-parity: the native binary agrees with the JVM on every case"
else
  echo "❌ aot-parity: FAILURES above — a divergence the JVM test suite cannot see"
fi
exit "$fail"
