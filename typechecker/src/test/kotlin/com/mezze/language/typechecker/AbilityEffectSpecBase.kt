package com.mezze.language.typechecker

/**
 * Shared harness for the ability/effect specification suite.
 *
 * Extends [VariantSpecBase] rather than copying it: that harness is not
 * variant-specific — `check` / `accepts` / `rejects` / `printedType` /
 * `checkMain` are the generic spec vocabulary, and its three rules apply here
 * unchanged.
 *
 *  1. The suite encodes the rules as SPECIFIED, so a red test is a gap to close
 *     rather than a test to fix.
 *  2. A directional rule ships with its mirror case. A `rejects` on its own is
 *     weak evidence, because a rule with no direction at all looks healthy.
 *  3. Assertions name an error CODE, never message prose.
 *
 * Why this suite exists: variants got 11 spec files, records 8, newtypes 4 —
 * each a deliberate family with a Base and a Claims file. Abilities and effects
 * got 40+ files named by TOPIC and accumulated one per bug, with no enumeration
 * of the shape space. BUG 47 (an inherent impl body naming `it` without
 * declaring it) was a cell in a matrix nobody had drawn.
 */
abstract class AbilityEffectSpecBase : VariantSpecBase()
