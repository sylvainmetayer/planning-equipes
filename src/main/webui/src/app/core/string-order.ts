/**
 * The order `Array.prototype.sort()` applies when given no comparator — UTF-16
 * code units, locale-blind — spelled out, for the sorts whose keys are ISO
 * dates, times or ids: there the code-unit order *is* the chronological or the
 * stable one, and a locale-aware `localeCompare` would only add a way for two
 * browsers to disagree.
 */
export function compareCodeUnits(a: string, b: string): number {
  if (a < b) {
    return -1;
  }
  return a > b ? 1 : 0;
}
