// Reading a Dosage — the weighting a solve ran under — in the comparison of
// « Versions du plan ». Pure, so it is unit tested without rendering.

import { Dosage } from './models';
import { compareCodeUnits } from './string-order';

/** What two dosages disagree on, rule by rule — the Comparateur's list. */
export interface DosageDifference {
  name: string;
  base: string;
  variante: string;
}

export function dosageDifferences(base: Dosage, variante: Dosage): DosageDifference[] {
  const names = new Set<string>([
    ...Object.keys(base.weights ?? {}),
    ...Object.keys(variante.weights ?? {}),
    ...Object.keys(base.instanceWeights ?? {}),
    ...Object.keys(variante.instanceWeights ?? {}),
    ...(base.disabled ?? []),
    ...(variante.disabled ?? []),
    ...(base.enabled ?? []),
    ...(variante.enabled ?? []),
  ]);
  const differences: DosageDifference[] = [];
  for (const name of [...names].sort(compareCodeUnits)) {
    const a = ruleState(base, name);
    const b = ruleState(variante, name);
    if (a !== b) {
      differences.push({ name, base: a, variante: b });
    }
  }
  return differences;
}

/** « 5 », « 5, désactivée », « 1, activée » — one rule under one dosage. */
function ruleState(dosage: Dosage, name: string): string {
  const weight = dosage.weights?.[name] ?? dosage.instanceWeights?.[name] ?? 1;
  if ((dosage.disabled ?? []).includes(name)) {
    return $localize`:@@dosage.state.disabled:${weight}:weight:, désactivée`;
  }
  if ((dosage.enabled ?? []).includes(name)) {
    return $localize`:@@dosage.state.enabled:${weight}:weight:, activée`;
  }
  return String(weight);
}
