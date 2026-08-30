---
name: 332_scrutinee_type_by_loc
description: BUG D — exhaustiveness now sees non-parameter scrutinees via loc-keyed solved type
metadata:
  type: project
---

#332 BUG D FIXED. Exhaustiveness (`MatchValidator`) runs on the PARSED AST and
used to recover the scrutinee's type ONLY from the enclosing fn's PARAMETER shape
(`extractParamType` on `{ n: Int } ->`). So `let n = a+1; match n is 0->..1->..`
(local let), `match r.n is …` (field access), and call-result scrutinees had no
recoverable type → the check was SILENTLY SKIPPED → non-exhaustive match crashed
at runtime with no matching arm.

FIX: capture the scrutinee's inference-time type keyed by `scrutinee.loc` into a
new `ConstraintResult.matchScrutineeTypes: Map<Loc, MezzeType>`. Populated in
`generateMatch` (ExprControlFlowConstraints), propagated via `ConstraintCollector`
merge/toResult AND the manual fn-body reconstruction (ExprFunctionConstraints:625,
which — like matchCaseBindings — bypasses merge). Validator's
`inferParsedScrutineeType` now consults the loc-keyed solved type FIRST (applying
substitution), via shared `surfaceScrutineeType()` helper; the old param-shape
re-derivation is kept only as a defensive fallback. Bool still flows through the
constructor-name path (surfaceScrutineeType returns null for bare BoolType), so no
new Bool rejection. Regression: NonParamScrutineeExhaustivenessTest.

PATTERN (recurring fault line): a validator that runs on parsed AST must get the
SOLVED type by loc, never re-derive it from surface syntax. Same shape as
[[typechecker_suspended]] leaks. matchCaseBindings & matchScrutineeTypes are BOTH
plumbed manually through ExprFunctionConstraints — merge() does not carry them
into the fn-body result; if adding another loc-keyed map, forward it there too.
