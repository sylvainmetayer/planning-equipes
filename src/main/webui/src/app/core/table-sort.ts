// The order of a referential table, shared by every page that has one: a
// column sorted on the value its cell shows, and — when no column is chosen —
// the ids in their natural order. Identifiers are drawn per edition as a
// prefix and a counter (T1, T2 … T10), and a code-point comparison files them
// T1, T10, T11, T2, which reads as shuffled. Pure, so the order is tested
// without rendering a table.

import { intlLocale } from './locale';
import { SortState } from './view-query-params';

/** What a cell is sorted on: a text, a number, a yes/no, or nothing. */
export type SortValue = string | number | boolean | null | undefined;

let collator: Intl.Collator | null = null;
let collatorLocale = '';

/** Numeric-aware and accent-insensitive: A2 before A10, « Élodie » before « Zoé ». */
export function compareNatural(left: string, right: string): number {
  const locale = intlLocale();
  if (collator === null || collatorLocale !== locale) {
    collator = new Intl.Collator(locale, { numeric: true, sensitivity: 'base' });
    collatorLocale = locale;
  }
  return collator.compare(left, right);
}

/**
 * Two cell values, ascending. An empty cell sorts last whatever the
 * direction — a blank is never what a click on a header asks to see first —
 * and « oui » comes before « non », so the rows a yes/no column exists to
 * spot come up on the first click.
 */
export function compareSortValues(left: SortValue, right: SortValue): number {
  const leftEmpty = left === null || left === undefined || left === '';
  const rightEmpty = right === null || right === undefined || right === '';
  if (leftEmpty || rightEmpty) {
    return Number(leftEmpty) - Number(rightEmpty);
  }
  if (typeof left === 'number' && typeof right === 'number') {
    return left - right;
  }
  if (typeof left === 'boolean' && typeof right === 'boolean') {
    return Number(right) - Number(left);
  }
  return compareNatural(String(left), String(right));
}

/**
 * The rows in the order of `sort`: the column's values compared by
 * {@link compareSortValues}, ties broken by the ids in their natural order;
 * with no column chosen, the ids alone. A column `value` does not know
 * answers `undefined` for every row, which leaves the id order: a link
 * carrying the sort of a column since removed degrades to the default.
 */
export function sortRows<T>(
  rows: readonly T[],
  sort: SortState,
  value: (row: T, column: string) => SortValue,
  id: (row: T) => string | number,
): T[] {
  const byId = (left: T, right: T) => compareNatural(String(id(left)), String(id(right)));
  const { active, direction } = sort;
  if (!active || direction === '') {
    return [...rows].sort(byId);
  }
  const sign = direction === 'asc' ? 1 : -1;
  return [...rows].sort((left, right) => {
    const a = value(left, active);
    const b = value(right, active);
    const aEmpty = a === null || a === undefined || a === '';
    const bEmpty = b === null || b === undefined || b === '';
    // Empty last in both directions: only the filled cells are reversed.
    const compared = aEmpty || bEmpty ? compareSortValues(a, b) : sign * compareSortValues(a, b);
    return compared === 0 ? byId(left, right) : compared;
  });
}
