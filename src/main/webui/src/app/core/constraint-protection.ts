// Which rules the protection ceremony applies to — the confirmation dialog, the
// badge on the rule's row, and the banner the Solveur and Contraintes screens
// carry while one is off.
//
// `protegee` says the server treats the rule as founding the plan in law or in
// the organiser's safety policy. That is not enough on its own: a rule the
// catalogue itself ships switched off (issue #595) is off because nobody ever
// asked for it, not because somebody took back a commitment. Badging it, and
// asking a solemn confirmation to put it back where it shipped, turns the
// ceremony into noise — and a warning that is always there is one nobody reads
// when it finally means something.
//
// An older payload carries no `activeByDefault` at all; the protection must
// keep applying on it rather than fall silent, so only an explicit `false`
// lifts it.

import { ConstraintView } from './models';

/** True when switching this rule off is a decision worth a confirmation and a badge. */
export function protectionApplies(constraint: ConstraintView): boolean {
  return constraint.protegee && constraint.activeByDefault !== false;
}
