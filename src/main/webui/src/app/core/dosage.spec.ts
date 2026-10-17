import { describe, expect, it } from 'vitest';
import { dosageDifferences, dosageLines, dosageSummary } from './dosage';
import { Dosage } from './models';

const NONE: Dosage = {
  weights: {},
  instanceWeights: { stabiliteDuPlanPublie: 5 },
  disabled: [],
  enabled: [],
};
const RETUNED: Dosage = {
  weights: { souhaitsIncompatibles: 5, appreciationIncompatible: 3 },
  instanceWeights: { stabiliteDuPlanPublie: 5 },
  disabled: ['equilibrerCharge'],
  enabled: [],
};

describe('dosage', () => {
  it('says default, a count of rules, or unknown for a row older than the figure', () => {
    expect(dosageSummary(NONE)).toBe('défaut');
    expect(dosageSummary(RETUNED)).toBe('3 règle(s) repondérée(s)');
    expect(dosageSummary(null)).toBe('inconnu');
  });

  it('says default when only the instance weights moved', () => {
    const instanceMoved: Dosage = { ...NONE, instanceWeights: { stabiliteDuPlanPublie: 8 } };
    expect(dosageSummary(instanceMoved)).toBe('défaut');
  });

  it('details every rule it moves, with the default it moved from', () => {
    expect(dosageLines(RETUNED)).toEqual([
      'appreciationIncompatible : 3 (défaut 1)',
      'souhaitsIncompatibles : 5 (défaut 1)',
      'equilibrerCharge : désactivée',
    ]);
  });

  it('lists what two dosages disagree on, rule by rule', () => {
    expect(dosageDifferences(NONE, RETUNED)).toEqual([
      { name: 'appreciationIncompatible', base: '1', variante: '3' },
      { name: 'equilibrerCharge', base: '1', variante: '1, désactivée' },
      { name: 'souhaitsIncompatibles', base: '1', variante: '5' },
    ]);
    expect(dosageDifferences(RETUNED, RETUNED)).toEqual([]);
  });
});
