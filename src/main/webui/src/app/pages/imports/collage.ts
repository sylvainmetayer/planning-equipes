// A block of cells pasted from a spreadsheet, turned into the CSV the import
// reads. Pure, so the one rule it holds is tested once for every import card.

/** The file name a paste travels under: the server reads it as any CSV. */
export const PASTE_FILE_NAME = 'collage.csv';

/**
 * The cells of a spreadsheet paste, row by row — tab-separated values as
 * Excel and LibreOffice write them to the clipboard.
 *
 * <p>Both quote a cell holding a line break, a tab or a quote: `"a<LF>b"`,
 * `"Le ""grand"" jeu"`. Splitting on the tabs and the line breaks alone cut
 * such a cell into two rows and kept its quotes as text. A cell opening on a
 * quote is therefore read up to its closing quote, a doubled one standing for
 * the quote itself; one that only looked quoted — text after the closing
 * quote, or no closing quote at all — is read as it was typed, up to the next
 * tab or line break, like any other cell.</p>
 */
export function parseTsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let index = 0;
  while (index <= text.length) {
    const cell = readCell(text, index);
    row.push(cell.value);
    index = cell.next;
    const separator = text[index];
    index++;
    if (separator !== '\t') {
      rows.push(row);
      row = [];
    }
  }
  return rows;
}

/** One cell starting at `start`, and the index of the separator that ends it (or the text's length). */
function readCell(text: string, start: number): { value: string; next: number } {
  if (text[start] === '"') {
    const quoted = readQuoted(text, start);
    if (quoted !== null) {
      return quoted;
    }
  }
  let end = start;
  while (end < text.length && text[end] !== '\t' && text[end] !== '\n') {
    end++;
  }
  return { value: text.slice(start, end), next: end };
}

/** A cell opening on a quote, or `null` when it does not close right before a separator. */
function readQuoted(text: string, start: number): { value: string; next: number } | null {
  let value = '';
  let index = start + 1;
  while (index < text.length) {
    const character = text[index];
    if (character !== '"') {
      value += character;
      index++;
    } else if (text[index + 1] === '"') {
      value += '"';
      index += 2;
    } else {
      const after = index + 1;
      const atSeparator = after >= text.length || text[after] === '\t' || text[after] === '\n';
      return atSeparator ? { value, next: after } : null;
    }
  }
  return null;
}

/**
 * A spreadsheet puts a tab between two cells of a row, and a line break
 * between two rows. The server recognises `,`, `;` and the tab alike, but by
 * counting them — and a paste easily carries more commas inside its cells
 * (« 09:00-12:00, 12:00-13:00 R ») than tabs between them. So every cell is
 * read as the spreadsheet quoted it ({@link parseTsv}), quoted again and
 * rejoined with `;`: the separators left outside quotes are then the ones
 * between cells, and the server cannot pick another.
 *
 * A text without any tab is not a spreadsheet paste — CSV typed or copied as
 * is — and travels unchanged. Trailing empty lines, which a spreadsheet adds
 * after its last row, are dropped.
 */
export function pasteToCsv(text: string): string {
  const normalised = text.replace(/\r\n?/g, '\n');
  if (!normalised.includes('\t')) {
    return normalised;
  }
  const rows = parseTsv(normalised);
  while (rows.length > 0 && rows.at(-1)!.every((cell) => cell.trim() === '')) {
    rows.pop();
  }
  return (
    rows.map((cells) => cells.map((cell) => `"${cell.replace(/"/g, '""')}"`).join(';')).join('\n') +
    '\n'
  );
}
