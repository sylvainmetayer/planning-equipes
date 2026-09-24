// Where an arrow key leads inside a roving-tabindex grid: the one piece of
// keyboard navigation every such grid computes the same way. The grid keeps
// its own focus lookup and its own Enter; this answers the cell to go to.

/** A cell of a grid, by row and column index. */
export interface GridCell {
  ligne: number;
  colonne: number;
}

/**
 * The cell `key` moves to from `from`, or `null` when the key is not a move
 * — the caller then leaves the event alone, so it can travel on to the global
 * shortcuts. The ends are walls, not wraps: Home and End stay on the row.
 */
export function nextGridCell(
  key: string,
  from: GridCell,
  lastRow: number,
  lastColumn: number,
): GridCell | null {
  const { ligne, colonne } = from;
  switch (key) {
    case 'ArrowRight':
      return { ligne, colonne: Math.min(colonne + 1, lastColumn) };
    case 'ArrowLeft':
      return { ligne, colonne: Math.max(colonne - 1, 0) };
    case 'ArrowDown':
      return { ligne: Math.min(ligne + 1, lastRow), colonne };
    case 'ArrowUp':
      return { ligne: Math.max(ligne - 1, 0), colonne };
    case 'Home':
      return { ligne, colonne: 0 };
    case 'End':
      return { ligne, colonne: lastColumn };
    default:
      return null;
  }
}
