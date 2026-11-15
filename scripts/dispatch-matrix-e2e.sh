#!/usr/bin/env bash
#
# THE DISPATCH MATRIX ON THE BUILD PATH — which impl's body actually ran, after
# monomorphisation.
#
# Why this is a script and not a JUnit test, in the words of the guard next door
# (`hof-dict-e2e.sh`): `:interpreter:test` runs through MezzeTestHelper, which
# takes the INTERPRETER path, and a dispatch bug can be mono-only. BUGS 38, 39
# and 40 all were — a class param lost through a generic hop, a return-position
# class arg that no argument can reach, a tuple rebuilt by name. Each of those
# answered correctly under `mez dev` and wrongly in a built jar.
#
# So the three matrix test classes assert these same values on the interpreter
# path, and this asserts them after `mez build`. The pair is the point: a
# difference between the two IS the finding.
#
#   ReceiverlessDispatchMatrixTest  — how the impl is chosen
#   DictDeliveryMatrixTest          — how the dictionary reaches the body
#   DerivedDispatchMatrixTest       — derived instances in both roles
#
# Every impl returns a DISTINCT number, because the failure mode here is a
# dictionary of the right ABILITY and the wrong IMPL, which type-checks. A
# `mez check` cannot see it and neither can a reflexive comparison.
set -uo pipefail

MEZ="${MEZ:-$(cd "$(dirname "$0")/.." && pwd)/cli/build/install/mez/bin/mez}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK" || exit 1

"$MEZ" init matrix >/dev/null 2>&1 || { echo "FAIL: mez init"; exit 1; }
cd matrix || exit 1

cat > src/app.mz <<'MZ'
use std::effects::log::{ println, Console }
use std::abilities::compare::{ Eq, Hash }

type A = { a: Int }
type B = { b: Int }
type Inner = { n: Int }
type Outer = { i: Inner, label: Str }
newtype Parity = Int

ability Show for 'x where { show: { it: 'x } -> Int }
impl Show for A where { show: { it: A } -> Int = { it } -> 1 }
impl Show for B where { show: { it: B } -> Int = { it } -> 2 }

ability Tag for 'x where { tag: { it: 'x } -> Int }
impl Tag for A where { tag: { it: A } -> Int = { it } -> 10 }
impl Tag for B where { tag: { it: B } -> Int = { it } -> 20 }

## chosen by the type the call PRODUCES, no receiver anywhere
ability Mk for 'x where { mk: {} -> 'x }
impl Mk for A where { mk: {} -> A = {} -> { a = 7 } }
impl Mk for B where { mk: {} -> B = {} -> { b = 9 } }
pub let make: {} with Mk 'x -> 'x = {} -> Mk.mk {}

## one ability at two class args, both reachable only from the RETURN
ability Make 'b for 'a where { make_it: { it: 'a } -> 'b }
impl Make Int for A where { make_it: { it: A } -> Int = { it } -> 111 }
impl Make Str for A where { make_it: { it: A } -> Str = { it } -> "222" }

## an operation's OWN type parameter, direct and FORWARDED
ability Direct for 'w where { direct: { it: 'w, x: 'p } with Show 'p -> Int }
impl Direct for A where {
  direct: { it: A, x: 'p } with Show 'p -> Int = { it, x } -> x.show {},
}
ability Fwd for 'w where { fwd: { it: 'w, x: 'p } with Show 'p -> Int }
impl Fwd for A where {
  fwd: { it: A, x: 'p } with Show 'p -> Int = { it, x } -> it.direct { x = x } * 10,
}

## two live dictionaries that must not cross
pub let mix: { p: 'p, q: 'q } with Show 'p, Tag 'q -> Int = { p, q } ->
  p.show {} * 100 + q.tag {}

## a WRITTEN impl that disagrees with structural equality, reached through a
## DERIVED container impl
impl Eq for Parity where {
  eq:  { it: Parity, r: Parity } -> Bool = { it, r } -> match it is
    Parity.(x) -> match r is Parity.(y) -> (x % 2) == (y % 2),
  neq: { it: Parity, r: Parity } -> Bool = { it, r } -> match it is
    Parity.(x) -> match r is Parity.(y) -> (x % 2) != (y % 2)
}
impl Hash for Parity where {
  hash: { it: Parity } -> Int = { it } -> match it is Parity.(x) -> x % 2
}

let a: A = { a = 0 }
let b: B = { b = 0 }
let viaA: A = make {}
let viaB: B = make {}
let x: Outer = { i = { n = 1 }, label = "s" }
let y: Outer = { i = { n = 2 }, label = "s" }
let one: Parity = Parity.(1)
let three: Parity = Parity.(3)
let two: Parity = Parity.(2)

pub let main = {} -> perform Console in do
  println { msg = viaA.a.to_str {} }
  println { msg = viaB.b.to_str {} }
  let i: Int = a.make_it {}
  let t: Str = a.make_it {}
  println { msg = i.to_str {} }
  println { msg = t }
  println { msg = a.direct { x = b }.to_str {} }
  println { msg = a.fwd { x = b }.to_str {} }
  println { msg = mix { p = a, q = b }.to_str {} }
  println { msg = mix { p = b, q = a }.to_str {} }
  println { msg = (x == y).to_str {} }
  println { msg = ([one] == [three]).to_str {} }
  println { msg = ([one] == [two]).to_str {} }
MZ

WANT="7
9
111
222
2
20
120
210
False
True
False"

"$MEZ" build >/dev/null 2>&1 || { echo "FAIL: mez build"; exit 1; }
GOT="$(./.build/matrix 2>/dev/null | grep -vE '^WARNING|^$')"

if [ "$GOT" = "$WANT" ]; then
  echo "✓ dispatch matrix holds on the BUILD path (11 values)"
  exit 0
fi
echo "FAIL: the build path dispatched differently from the interpreter path."
echo "--- want"; echo "$WANT"
echo "--- got";  echo "$GOT"
echo
echo "Each line names an impl. A wrong line is a dictionary of the right ability"
echo "and the wrong impl — see the matrix test classes for which cell it is."
exit 1
