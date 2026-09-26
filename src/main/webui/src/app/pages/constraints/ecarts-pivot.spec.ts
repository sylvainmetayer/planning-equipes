import { describe, expect, it } from 'vitest';
import { CellulePivot } from '../../core/models';
import { classeCellule, buildPivot, cellLink, MAX_COLONNES } from './ecarts-pivot';

/**
 * « Où se concentrent les écarts » (issue #496): the cross-table the Contraintes
 * screen draws under its list. The list says how many times a rule is in
 * default; this says where — and the ordering is the whole point, since the
 * question is read left to right and top to bottom.
 */
describe('buildPivot', () => {
  const cellules: CellulePivot[] = [
    { contrainte: 'amplitude', axe: 'JOUR', cle: '2026-07-08', ecarts: 1 },
    { contrainte: 'amplitude', axe: 'JOUR', cle: '2026-07-06', ecarts: 6 },
    { contrainte: 'referent', axe: 'JOUR', cle: '2026-07-06', ecarts: 2 },
    { contrainte: 'referent', axe: 'STAND', cle: 'BLEU', ecarts: 2 },
    { contrainte: 'amplitude', axe: 'STAND', cle: 'ROUGE', ecarts: 7 },
  ];

  const labels: Record<string, string> = { BLEU: 'Pavillon Bleu', ROUGE: 'Hall Rouge' };
  const label = (key: string) => labels[key] ?? key;

  it('keeps the days in chronological order, which is the only one that answers « est-ce le week-end »', () => {
    const pivot = buildPivot(cellules, 'JOUR', label, (a, b) => a.cle.localeCompare(b.cle));

    expect(pivot.colonnes.map((colonne) => colonne.cle)).toEqual(['2026-07-06', '2026-07-08']);
    expect(pivot.lignes.map((ligne) => ligne.contrainte)).toEqual(['amplitude', 'referent']);
    expect(pivot.lignes[0].ecarts).toEqual([6, 1]);
    expect(pivot.lignes[1].ecarts).toEqual([2, 0]);
    expect(pivot.maximum).toBe(6);
  });

  it('puts the rule in default most at the top, and the busiest key first when no order is given', () => {
    const pivot = buildPivot(cellules, 'STAND', label);

    expect(pivot.colonnes.map((colonne) => colonne.libelle)).toEqual([
      'Hall Rouge',
      'Pavillon Bleu',
    ]);
    expect(pivot.lignes.map((ligne) => ligne.contrainte)).toEqual(['amplitude', 'referent']);
    expect(pivot.lignes[0].total).toBe(7);
  });

  it('caps the columns and says how many it left out, rather than drawing a table nobody can read', () => {
    const large: CellulePivot[] = Array.from({ length: MAX_COLONNES + 4 }, (_, index) => ({
      contrainte: 'equilibrerCharge',
      axe: 'ANIMATEUR' as const,
      cle: `a${index}`,
      // Decreasing, so the ones dropped are the ones with the fewest breaches.
      ecarts: MAX_COLONNES + 4 - index,
    }));

    const pivot = buildPivot(large, 'ANIMATEUR', (cle) => cle);

    expect(pivot.colonnes).toHaveLength(MAX_COLONNES);
    expect(pivot.colonnesMasquees).toBe(4);
    expect(pivot.colonnes[0].cle).toBe('a0');
  });

  it('has nothing to draw on an axis nothing breached', () => {
    const pivot = buildPivot(cellules, 'ANIMATEUR', (cle) => cle);

    expect(pivot.lignes).toEqual([]);
    expect(pivot.colonnes).toEqual([]);
    expect(pivot.maximum).toBe(0);
  });
});

describe('classeCellule', () => {
  it('reads the heat against the largest cell of the table, not against an absolute', () => {
    // Six breaches are a hot spot on a rule in default seven times, and a cold
    // one on a rule in default four hundred times.
    expect(classeCellule(6, 7)).toBe('heatmap-cell heatmap-cell-critical');
    expect(classeCellule(6, 400)).toBe('heatmap-cell heatmap-cell-ok');
    expect(classeCellule(200, 400)).toBe('heatmap-cell heatmap-cell-warning');
  });

  it('leaves an empty cell plain', () => {
    expect(classeCellule(0, 7)).toBe('heatmap-cell heatmap-cell-none');
    expect(classeCellule(0, 0)).toBe('heatmap-cell heatmap-cell-none');
  });
});

/**
 * A cell used to open on a count and stop there. « 210 écarts ici » is a
 * measurement, and a reader who cannot act on a screen stops opening it: each
 * axis now leads to the one screen where its breaches are actually corrected.
 */
describe('cellLink', () => {
  it('sends a day and a stand to the Planning page, a person to their fiche', () => {
    expect(cellLink('JOUR', '2026-07-06', '2026-07-06')).toEqual({
      route: '/journee',
      queryParams: { date: '2026-07-06' },
      label: 'Voir la journée du 2026-07-06',
    });
    expect(cellLink('STAND', 'BLEU', 'Pavillon Bleu')).toEqual({
      route: '/journee',
      queryParams: { stand: 'BLEU' },
      label: 'Voir le planning de Pavillon Bleu',
    });
    expect(cellLink('ANIMATEUR', 'a1', 'Alice Martin')).toEqual({
      route: '/animateurs/a1',
      queryParams: { section: 'timeline' },
      label: 'Voir la journée de Alice Martin',
    });
  });

  // The label names what the reader was looking at, not the screen: the id is
  // what the link carries, the name is what it says.
  it('links on the id and reads on the name', () => {
    const lien = cellLink('ANIMATEUR', 'a1', 'Alice Martin');

    expect(lien.route).toBe('/animateurs/a1');
    expect(lien.label).toContain('Alice Martin');
  });
});
