// The two moves of repetitive entry, shared by the entry grids: take the row
// above, or push one value down a column (issue #316).
//
// Generic over the cell, because the grids disagree on what one holds — a
// headcount, an appreciation, a value spread over every date of a journée
// type — and agree on everything else: rows and columns in the order the
// screen shows them, a write that returns a new map so the signal holding it
// notifies, and nothing sent to the server before « Enregistrer ». A grid says
// how it reads and writes one cell; the moves themselves are the same, and are
// tested here once rather than twice.

/** A cell's address in a grid, whatever the grid calls its rows. */
export interface GrilleAddress {
  ligneId: string;
  colonneId: string;
}

/** How a grid reads and writes one of its cells. */
export interface AccesGrille<C, V> {
  /**
   * One cell's value, or `undefined` when there is nothing to copy there — a
   * column standing for several dates that do not say the same thing, or one
   * standing for no date at all.
   */
  read(cellules: C, ligneId: string, colonneId: string): V | undefined;
  /** A copy of the cells with one cell written; never a mutation. */
  write(cellules: C, ligneId: string, colonneId: string, valeur: V): C;
}

/** The cells after a move, and how many of them it changed — what the screen reports. */
export interface RecopieGrille<C> {
  cellules: C;
  changees: number;
}

/** Whether `ligneId` has a row above it — what greys out the button on the first one. */
export function hasLignePrecedente(ligneId: string, ligneIds: readonly string[]): boolean {
  return ligneIds.indexOf(ligneId) > 0;
}

/**
 * Copies the row above `ligneId` onto it, column by column. "Above" is the
 * displayed order, which is the only one the eye can name: filter the grid and
 * the previous row is the previous row *on screen*. The first displayed row has
 * nothing above it and is left alone.
 */
export function copyLignePrecedente<C, V>(
  cellules: C,
  ligneId: string,
  ligneIds: readonly string[],
  colonneIds: readonly string[],
  acces: AccesGrille<C, V>,
): RecopieGrille<C> {
  const rang = ligneIds.indexOf(ligneId);
  if (rang <= 0) {
    return { cellules, changees: 0 };
  }
  return writeCellules(
    cellules,
    colonneIds.map((colonneId) => ({
      colonneId,
      valeur: acces.read(cellules, ligneIds[rang - 1], colonneId),
    })),
    [ligneId],
    acces,
  );
}

/**
 * Writes `valeur` in `colonneId` for every displayed row. Only that column is
 * touched: the other cells of a row are nobody's business here, the way a bulk
 * edit leaves alone every field the user did not fill in.
 */
export function applyColonne<C, V>(
  cellules: C,
  colonneId: string,
  valeur: V,
  ligneIds: readonly string[],
  acces: AccesGrille<C, V>,
): RecopieGrille<C> {
  return writeCellules(cellules, [{ colonneId, valeur }], ligneIds, acces);
}

/**
 * The row « appliquer à la colonne » takes its value from: the active cell's
 * when it sits in that column, else the first row displayed — the same rule as
 * the row copy of the ouvertures grid, so the two buttons are read alike.
 */
export function ligneSourceColonne(
  colonneId: string,
  active: GrilleAddress | null,
  ligneIds: readonly string[],
): string | undefined {
  return active !== null && active.colonneId === colonneId && ligneIds.includes(active.ligneId)
    ? active.ligneId
    : ligneIds[0];
}

/**
 * Writes one value per column onto every row named, skipping what there is
 * nothing to write (`undefined`) and what already says it — a move that
 * changes nothing marks nothing modified, and the count is what the screen
 * says out loud when it is zero.
 */
function writeCellules<C, V>(
  cellules: C,
  valeurs: readonly { colonneId: string; valeur: V | undefined }[],
  ligneIds: readonly string[],
  acces: AccesGrille<C, V>,
): RecopieGrille<C> {
  let resultat = cellules;
  let changees = 0;
  for (const { colonneId, valeur } of valeurs) {
    if (valeur === undefined) {
      continue;
    }
    for (const ligneId of ligneIds) {
      if (acces.read(resultat, ligneId, colonneId) === valeur) {
        continue;
      }
      resultat = acces.write(resultat, ligneId, colonneId, valeur);
      changees++;
    }
  }
  return { cellules: resultat, changees };
}

/**
 * A block copied from a spreadsheet: its lines, each cut at its tabs. The
 * trailing newline every spreadsheet copy carries is not a line, and a
 * carriage return is never part of a value.
 */
export function blockRows(text: string): string[][] {
  const lignes = text.replaceAll('\r', '').split('\n');
  if (lignes.length > 1 && lignes.at(-1) === '') {
    lignes.pop();
  }
  return lignes.map((ligne) => ligne.split('\t'));
}

/** One cell a pasted block would change, before anything is written: what the preview lists. */
export interface CelluleCollee<V> {
  ligneId: string;
  colonneId: string;
  before: V | undefined;
  after: V;
}

/**
 * A block pasted from `depuis`, laid over the displayed rows and columns:
 * one line per row, one tab-separated value per column. Cells past the last
 * row or column are dropped and counted, a value `lire` does not understand
 * leaves its cell alone and is counted too, and a value equal to the cell's
 * is no change. Nothing is written: the grid shows what would change, and
 * writes it on the user's say-so with {@link applyPaste}.
 */
export function planCollage<C, V>(
  cellules: C,
  text: string,
  depuis: GrilleAddress,
  ligneIds: readonly string[],
  colonneIds: readonly string[],
  lire: (texte: string) => V | undefined,
  acces: AccesGrille<C, V>,
): { cellules: CelluleCollee<V>[]; horsGrille: number; illisibles: string[] } {
  const ligne0 = ligneIds.indexOf(depuis.ligneId);
  const colonne0 = colonneIds.indexOf(depuis.colonneId);
  const plan = { cellules: [] as CelluleCollee<V>[], horsGrille: 0, illisibles: [] as string[] };
  if (ligne0 < 0 || colonne0 < 0) {
    return plan;
  }
  blockRows(text).forEach((valeurs, i) => {
    valeurs.forEach((texte, j) => {
      const ligneId = ligneIds[ligne0 + i];
      const colonneId = colonneIds[colonne0 + j];
      if (ligneId === undefined || colonneId === undefined) {
        plan.horsGrille++;
        return;
      }
      const lu = lire(texte);
      if (lu === undefined) {
        plan.illisibles.push(texte);
        return;
      }
      const before = acces.read(cellules, ligneId, colonneId);
      if (before !== lu) {
        plan.cellules.push({ ligneId, colonneId, before, after: lu });
      }
    });
  });
  return plan;
}

/** The cells of a paste plan, written locally — « Enregistrer » stays the only thing that reaches the server. */
export function applyPaste<C, V>(
  cellules: C,
  collees: readonly CelluleCollee<V>[],
  acces: AccesGrille<C, V>,
): RecopieGrille<C> {
  let resultat = cellules;
  for (const cellule of collees) {
    resultat = acces.write(resultat, cellule.ligneId, cellule.colonneId, cellule.after);
  }
  return { cellules: resultat, changees: collees.length };
}
