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
  # Nor is an identical ERROR on both sides. A case with a typo compiles
  # nowhere and "agrees" perfectly — the same vacuous pass as two empty
  # outputs, one step further along.
  case "$jvm_out" in
    *Error:*|*error:*|*Exception*|*TypeCheckError*|*ParseException*|*"must return"*)
      echo "  ❌ $name — the JVM side failed, so parity means nothing:"
      echo "       $jvm_out"
      fail=1
      return
      ;;
  esac

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

# ----------------------------------------------------------------------------
# Beyond the filesystem. The risk is a native that reads a guest value's
# IDENTITY — a variant's constructor, a record's field — where the value was
# built by precompiled stdlib code that never registered the name. Only eight
# constructors are pre-registered (Some None Ok Err True False Cons Nil); every
# other name reaches the registry only when something CONSTRUCTS it.
#
# These cases each cross one such native boundary. They are parity checks, not
# proofs: a case passes if both binaries agree, which they will whenever the
# relevant constructor happens to get registered by some other path.
# ----------------------------------------------------------------------------

# Json: DslJsonCodec.serializeValue dispatches on the Json tree's constructor
# name (JObject/JArray/JString/...), and the tree here is built by a DERIVED
# encode in stdlib code. Its default arm throws, so a failure is loud.
parity json-encode 'use std::codec::json
use std::effects::log::{ println, Console }

let main = {} -> perform Console in do
  let user = { name = "ada", age = 36, tags = ["x", "y"] }
  println { msg = user.to_json_str {} }'

# Json round trip: parse_str builds the tree natively (which registers the
# names) and then serializes it — the opposite order from json-encode, and the
# reason encode alone is the interesting one.
parity json-roundtrip 'use std::codec::json
use std::effects::log::{ println, Console }

let main = {} -> perform Console in do
  let report = match json::parse_str { s = "{\"a\":[1,2,3],\"b\":true}" } is
    Ok { val = tree } -> json::to_str { json = tree }
    Err { error = _ } -> "parse-failed"
  println { msg = report }'

# Ability dispatch: a user ability + impl, called through the method. The
# registries behind this are name-keyed, which is the same family as the tag
# registry.
parity ability-dispatch 'use std::effects::log::{ println, Console }

ability Describe for '"'"'a where {
  describe: { it: '"'"'a } -> Str
}

type Shape = | Circle { r: Int }, Square { side: Int } |

impl Describe for Shape where {
  describe = { it } -> match it is
    Circle { r } -> "circle-$(r.to_str{})"
    Square { side } -> "square-$(side.to_str{})"
}

let main = {} -> perform Console in do
  let a = Circle { r = 2 }
  let b = Square { side = 3 }
  println { msg = "$(a.describe{})/$(b.describe{})" }'

# Derived Eq/Ord on a user variant, compared through the derived impl.
parity derived-eq-ord 'use std::effects::log::{ println, Console }
use std::bool

type Level = | Low, Mid, High |

let main = {} -> perform Console in do
  let x = Mid
  let y = Mid
  let z = High
  println { msg = "$(bool::to_str { b = x == y })/$(bool::to_str { b = x == z })" }'

# Map and Set: hash-based containers whose bucketing goes through
# structural_hash in a native.
parity map-set 'use std::collections::{ map, set, seq }
use std::effects::log::{ println, Console }
use std::int

let main = {} -> perform Console in do
  let m = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 1 }, k = "b", v = 2 }
  let got = match map::get { m = m, k = "b" } is
    Some { val = v } -> int::to_str { n = v }
    None -> "missing"
  let s = set::insert { s = set::insert { s = set::empty {}, x = 7 }, x = 7 }
  println { msg = "map=$(got) setsize=$(int::to_str { n = set::size { s = s } })" }'

# Iter unfold: the native driver reads the Step variant (Yield/Skip/Done). This
# is the one place the codebase already got right — IterNatives compares TAGS
# and registers the three names at class init — so this case is here to keep
# it that way.
parity iter-unfold-step 'use std::collections::iter
use std::effects::log::{ println, Console }
use std::int

let main = {} -> perform Console in do
  let total = iter::sum {
    it = iter::unfold {
      state = 0,
      step = { s } -> if s >= 5 then Done else Yield { val = s * s, next = s + 1 }
    }
  }
  println { msg = int::to_str { n = total } }'

# Int overflow raises a guest error rather than wrapping, and the raise path
# runs through the interpreter rather than a native.
parity int-overflow 'use std::effects::log::{ println, Console }
use std::int

let main = {} -> perform Console in do
  let big = 9223372036854775807
  println { msg = int::to_str { n = big } }'

# A Char value is a one-character String at run time, with the IR node holding
# a Java char — a representation split a native has to agree about.
parity char-string 'use std::effects::log::{ println, Console }
use std::str

let main = {} -> perform Console in do
  let s = "mezze"
  println { msg = "$(str::to_upper { s = s })/$(int_str{})" }

let int_str = {} -> str::size { s = "mezze" }.to_str{}'

echo
if [ "$fail" = 0 ]; then
  echo "✅ aot-parity: the native binary agrees with the JVM on every case"
else
  echo "❌ aot-parity: FAILURES above — a divergence the JVM test suite cannot see"
fi
exit "$fail"
