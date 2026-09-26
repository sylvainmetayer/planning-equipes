// Reading a Dosage — the weighting a solve ran under — the same way wherever
// one is shown: the comparison of « Versions du plan » (a banner when two
// plans differ) and the weight history of « Règles du planning ». Pure, so it is unit tested without rendering.

import { Dosage } from './models';
import { compareCodeUnits } from './string-order';

/**
 * A canonical text for a dosage, equal for two solves under the same effective
 * weighting and different otherwise; `null` for an unknown one — an older row
 * — which equals nothing, not even another unknown.
 */
export function dosageKey(dosage: Dosage | null | undefined): string | null {
  if (!dosage) {
    return null;
  }
  return JSON.stringify([
    sortedEntries(dosage.weights),
    sortedEntries(dosage.instanceWeights),
    [...(dosage.disabled ?? [])].sort(compareCodeUnits),
    [...(dosage.enabled ?? [])].sort(compareCodeUnits),
  ]);
}

/** How many rules the edition moved away from a default: reweighted, switched off, switched on. */
export function dosageCount(dosage: Dosage): number {
  return (
    Object.keys(dosage.weights ?? {}).length +
    (dosage.disabled ?? []).length +
    (dosage.enabled ?? []).length
  );
}

/** « défaut », « 3 règles repondérées », « inconnu » — the Autopsie's column. */
export function dosageSummary(dosage: Dosage | null | undefined): string {
  if (!dosage) {
    return $localize`:@@dosage.unknown:inconnu`;
  }
  const count = dosageCount(dosage);
  return count === 0
    ? $localize`:@@dosage.default:défaut`
    : $localize`:@@dosage.count:${count}:count: règle(s) repondérée(s)`;
}

/** One line per rule the dosage moves: « souhaitsIncompatibles : 5 (défaut 1) », « x : désactivée ». */
export function dosageLines(dosage: Dosage | null | undefined): string[] {
  if (!dosage) {
    return [];
  }
  const lines: string[] = [];
  for (const [name, weight] of sortedEntries(dosage.weights)) {
    const instance = dosage.instanceWeights?.[name] ?? 1;
    lines.push(
      $localize`:@@dosage.line.weight:${name}:name: : ${weight}:weight: (défaut ${instance}:instance:)`,
    );
  }
  for (const name of [...(dosage.disabled ?? [])].sort(compareCodeUnits)) {
    lines.push($localize`:@@dosage.line.disabled:${name}:name: : désactivée`);
  }
  for (const name of [...(dosage.enabled ?? [])].sort(compareCodeUnits)) {
    lines.push($localize`:@@dosage.line.enabled:${name}:name: : activée`);
  }
  return lines;
}

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

function sortedEntries(record: Record<string, number> | undefined): [string, number][] {
  return Object.entries(record ?? {}).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
}
