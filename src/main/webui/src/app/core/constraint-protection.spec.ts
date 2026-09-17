import { describe, expect, it } from 'vitest';
import { ConstraintView } from './models';
import { protectionApplies } from './constraint-protection';

function contrainte(overrides: Partial<ConstraintView> = {}): ConstraintView {
  return {
    name: 'travailDeNuitInterditPourMineur',
    niveau: 'HARD',
    categorie: 'Légal (mineurs)',
    description: 'Pas de travail de nuit pour un mineur (art. L3163-1).',
    actif: true,
    protegee: true,
    legale: true,
    dosable: false,
    poids: 1,
    score: null,
    matchCount: null,
    violations: [],
    postesEvalues: null,
    plancher: null,
    references: [],
    ...overrides,
  };
}

/**
 * One predicate for three things that must agree: the badge on the rule's row,
 * the confirmation asked before switching it off, and the banner carried while
 * it is off. A badge promising a ceremony that never comes is worse than no
 * badge at all.
 */
describe('protectionApplies', () => {
  it('covers a rule that founds the plan in law or in the safety policy', () => {
    expect(protectionApplies(contrainte())).toBe(true);
  });

  it('leaves an ordinary rule alone', () => {
    expect(protectionApplies(contrainte({ protegee: false }))).toBe(false);
  });

  // Issue #595: a rule the catalogue ships switched off is off because nobody
  // ever asked for it, not because somebody took back a commitment.
  it('says nothing about a rule the catalogue itself ships switched off', () => {
    expect(protectionApplies(contrainte({ activeByDefault: false }))).toBe(false);
    expect(protectionApplies(contrainte({ activeByDefault: false, actif: false }))).toBe(false);
  });

  // An older payload carries no `activeByDefault` at all: the protection must
  // keep applying on it rather than fall silent.
  it('still applies when the payload does not say what the default is', () => {
    expect(protectionApplies(contrainte({ activeByDefault: undefined }))).toBe(true);
    expect(protectionApplies(contrainte({ activeByDefault: true }))).toBe(true);
  });
});
