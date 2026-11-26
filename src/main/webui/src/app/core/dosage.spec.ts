import { describe, expect, it } from 'vitest';
import { dosageDifferences } from './dosage';
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
  it('lists what two dosages disagree on, rule by rule', () => {
    expect(dosageDifferences(NONE, RETUNED)).toEqual([
      { name: 'appreciationIncompatible', base: '1', variante: '3' },
      { name: 'equilibrerCharge', base: '1', variante: '1, désactivée' },
      { name: 'souhaitsIncompatibles', base: '1', variante: '5' },
    ]);
    expect(dosageDifferences(RETUNED, RETUNED)).toEqual([]);
  });
});
