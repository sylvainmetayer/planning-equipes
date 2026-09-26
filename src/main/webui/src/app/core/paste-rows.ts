// A block copied from a spreadsheet and pasted on a referential table (Ctrl+V
// on a row): the organiser's own column of e-mails, of codes, of labels,
// laid over the rows the table displays. Nothing is written by the paste
// itself: `planPaste` says which cells would change, the page shows it in a
// preview, and only « Appliquer » saves — the same law as the entry grids,
// whose block parser (`blockRows`) this reuses.
//
// Which column a pasted value lands in:
//   - a first line naming columns (« Nom », « E-mail », « Id »…) maps each
//     pasted column by its title, and « Id » then matches the rows by id
//     rather than by position; a title naming no pasteable column — every
//     export carries some: « Âge », « Typologies »… — is a column ignored,
//     so a list exported here pastes back as it is;
//   - without one, the pasted columns fill the pasteable columns in the order
//     the table shows them, from the focused row down.

import { blockRows } from './grille-saisie';
import { normaliserPourFiltre } from './text-filter';

/** One column a block can be pasted into. */
export interface PasteColumn<T> {
  key: string;
  /** Its header on screen: what a pasted first line is matched against. */
  title: string;
  /** The cell as it reads now, for the preview. */
  read: (row: T) => string;
  /**
   * The row with the pasted text written in, or a sentence saying why the
   * text is not a value of this column. Never a mutation.
   */
  write: (row: T, text: string) => T | string;
}

/** One cell the paste would change. */
export interface PasteChange {
  rowId: string;
  row: string;
  column: string;
  before: string;
  after: string;
}

/** One pasted value left out, and why. */
export interface PasteRefusal {
  row: string;
  column: string;
  value: string;
  reason: string;
}

export interface PastePlan<T> {
  changes: PasteChange[];
  refusals: PasteRefusal[];
  /** The rows to save, each once, with every pasted cell written in. */
  rows: T[];
  /** Pasted lines past the last row, or naming an id the table does not show. */
  unplaced: number;
}

export interface PasteTarget<T> {
  /** The rows in the order the table displays them. */
  rows: readonly T[];
  id: (row: T) => string;
  /** What the preview calls a row. */
  label: (row: T) => string;
  columns: readonly PasteColumn<T>[];
  /** Where a block without a header line starts; the first row when absent. */
  startRow?: number;
  /**
   * A rule on the row as a whole, once every pasted cell of it is written —
   * a bound pair checked cell by cell would refuse 4 → 6 on a 1–2 row. The
   * sentence saying why the row is refused, or `null`; a refused row changes
   * nothing.
   */
  validate?: (row: T) => string | null;
}

/**
 * The quote an export puts before a text a spreadsheet would run as a formula
 * (`core/csv-export.ts`): taken off again when that text is pasted back.
 */
function withoutFormulaGuard(value: string): string {
  return /^'[=+\-@\t\r]/.test(value) ? value.slice(1) : value;
}

/**
 * The header of a pasted first line, when it names « Id » or at least one
 * pasteable column; its other titles map to `null`, columns ignored.
 */
function headerOf<T>(
  cells: readonly string[],
  columns: readonly PasteColumn<T>[],
  idTitle: string,
): (PasteColumn<T> | 'id' | null)[] | null {
  const byTitle = new Map(columns.map((column) => [normaliserPourFiltre(column.title), column]));
  const mapped = cells.map((cell) => {
    const title = normaliserPourFiltre(cell.trim());
    if (title === '') {
      return null;
    }
    if (title === normaliserPourFiltre(idTitle)) {
      return 'id' as const;
    }
    return byTitle.get(title) ?? null;
  });
  return mapped.some((each) => each !== null) ? mapped : null;
}

/**
 * What pasting `text` on the table would do. An empty pasted cell leaves its
 * cell alone, a value equal to the cell's is no change, a value the column
 * refuses is listed with its reason and changes nothing.
 */
export function planPaste<T>(text: string, target: PasteTarget<T>, idTitle = 'Id'): PastePlan<T> {
  const lines = blockRows(text);
  const plan: PastePlan<T> = { changes: [], refusals: [], rows: [], unplaced: 0 };
  if (lines.length === 0 || target.columns.length === 0) {
    return plan;
  }
  const header = headerOf(lines[0], target.columns, idTitle);
  const data = header ? lines.slice(1) : lines;
  const mapping: (PasteColumn<T> | 'id' | null)[] = header ?? [...target.columns];
  const idColumn = mapping.indexOf('id');
  const byId = new Map(target.rows.map((row) => [target.id(row), row]));
  const patched = new Map<string, T>();

  data.forEach((cells, index) => {
    const row: T | undefined =
      idColumn >= 0
        ? byId.get((cells[idColumn] ?? '').trim())
        : target.rows[(target.startRow ?? 0) + index];
    if (row === undefined) {
      plan.unplaced++;
      return;
    }
    const label = target.label(row);
    const rowId = target.id(row);
    const start = patched.get(rowId) ?? row;
    let current: T = start;
    const rowChanges: PasteChange[] = [];
    cells.forEach((cell, position) => {
      const column = mapping[position];
      const value = withoutFormulaGuard(cell.trim());
      if (!column || column === 'id' || value === '') {
        return;
      }
      const before = column.read(current);
      if (before === value) {
        return;
      }
      const written = column.write(current, value);
      if (typeof written === 'string') {
        plan.refusals.push({ row: label, column: column.title, value, reason: written });
        return;
      }
      if (column.read(written) === before) {
        return;
      }
      rowChanges.push({
        rowId,
        row: label,
        column: column.title,
        before,
        after: column.read(written),
      });
      current = written;
    });
    const refused = current === start ? null : (target.validate?.(current) ?? null);
    if (refused !== null) {
      plan.refusals.push({
        row: label,
        column: rowChanges.map((change) => change.column).join(' / '),
        value: rowChanges.map((change) => change.after).join(' / '),
        reason: refused,
      });
      return;
    }
    plan.changes.push(...rowChanges);
    if (current !== row) {
      patched.set(rowId, current);
    }
  });
  plan.rows = [...patched.values()];
  return plan;
}

/**
 * A paste event's text, `null` when blank. On a table row nothing else takes
 * a paste — there is no field under the focus — so even a single value is a
 * block of one cell.
 */
export function pastedText(event: ClipboardEvent): string | null {
  const text = event.clipboardData?.getData('text') ?? '';
  return text.trim() === '' ? null : text;
}
