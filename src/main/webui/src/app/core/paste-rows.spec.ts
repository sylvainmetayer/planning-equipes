import { describe, expect, it } from 'vitest';
import { PasteColumn, planPaste } from './paste-rows';

interface Ligne {
  id: string;
  nom: string;
  effectif: number;
}

const colonnes: PasteColumn<Ligne>[] = [
  {
    key: 'nom',
    title: 'Nom',
    read: (ligne) => ligne.nom,
    write: (ligne, text) => ({ ...ligne, nom: text }),
  },
  {
    key: 'effectif',
    title: 'Effectif',
    read: (ligne) => String(ligne.effectif),
    write: (ligne, text) =>
      /^\d+$/.test(text) ? { ...ligne, effectif: Number(text) } : 'un nombre entier',
  },
];

const lignes: Ligne[] = [
  { id: 'S1', nom: 'Un', effectif: 1 },
  { id: 'S2', nom: 'Deux', effectif: 2 },
  { id: 'S3', nom: 'Trois', effectif: 3 },
];

const target = {
  rows: lignes,
  id: (ligne: Ligne) => ligne.id,
  label: (ligne: Ligne) => ligne.nom,
  columns: colonnes,
};

describe('planPaste', () => {
  it('fills the pasteable columns in order, from the focused row down', () => {
    const plan = planPaste('Uno\t4\nDos\t2\n', { ...target, startRow: 0 });
    expect(plan.changes.map((change) => [change.rowId, change.column, change.after])).toEqual([
      ['S1', 'Nom', 'Uno'],
      ['S1', 'Effectif', '4'],
      ['S2', 'Nom', 'Dos'],
    ]);
    expect(plan.rows).toEqual([
      { id: 'S1', nom: 'Uno', effectif: 4 },
      { id: 'S2', nom: 'Dos', effectif: 2 },
    ]);
  });

  it('maps the columns by a header line, and matches the rows by « Id » when it names one', () => {
    const plan = planPaste('Effectif\tId\n9\tS3\nx\tS1\n5\tS9', target);
    expect(plan.rows).toEqual([{ id: 'S3', nom: 'Trois', effectif: 9 }]);
    expect(plan.refusals).toEqual([
      { row: 'Un', column: 'Effectif', value: 'x', reason: 'un nombre entier' },
    ]);
    expect(plan.unplaced).toBe(1);
  });

  it('counts the lines past the last row, and leaves an empty pasted cell alone', () => {
    const plan = planPaste('\t7\nA\nB\nC', { ...target, startRow: 1 });
    expect(plan.rows.map((ligne) => ligne.id)).toEqual(['S2', 'S3']);
    expect(plan.rows[0]).toEqual({ id: 'S2', nom: 'Deux', effectif: 7 });
    expect(plan.unplaced).toBe(2);
  });

  it('reads an exported list back: its header maps what it can, the other titles ignored', () => {
    const plan = planPaste(
      'Id;Nom;Typologies;Effectif\nS2;Deux bis;Ambiance;5'.replaceAll(';', '\t'),
      target,
    );
    expect(plan.rows).toEqual([{ id: 'S2', nom: 'Deux bis', effectif: 5 }]);
    expect(plan.unplaced).toBe(0);
    expect(plan.refusals).toEqual([]);
  });

  it('checks a row as a whole once every cell is written, and refuses it whole', () => {
    const plan = planPaste('Nom\tEffectif\nUno\t9\nDos\t3', {
      ...target,
      validate: (ligne: Ligne) => (ligne.effectif > 5 ? 'trop' : null),
    });
    expect(plan.rows).toEqual([{ id: 'S2', nom: 'Dos', effectif: 3 }]);
    expect(plan.changes.map((change) => change.rowId)).toEqual(['S2', 'S2']);
    expect(plan.refusals).toEqual([
      { row: 'Un', column: 'Nom / Effectif', value: 'Uno / 9', reason: 'trop' },
    ]);
  });

  it('takes off the quote an export put before a formula-looking text', () => {
    const plan = planPaste("'=Tir\n'-Arc", { ...target, columns: [colonnes[0]] });
    expect(plan.rows.map((ligne) => ligne.nom)).toEqual(['=Tir', '-Arc']);
  });
});
