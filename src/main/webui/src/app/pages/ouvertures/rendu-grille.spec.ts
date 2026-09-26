import { describe, expect, it } from 'vitest';
import { COUCHES, Couche } from './calendrier-couches';
import {
  CoucheCellule,
  columnSpan,
  portionsIn,
  renderCell,
  segmentSpans,
  windowSpans,
} from './rendu-grille';

const ALL_LAYERS = new Set<Couche>(COUCHES);

/** A cell of the 14-20 timeslot, open 14-18 under a consigne that closes 18-20. */
function sousConsigne(): CoucheCellule {
  const colonne = columnSpan('14:00', '20:00');
  return {
    resultat: portionsIn(
      segmentSpans([{ heureDebut: '14:00', heureFin: '18:00', effectif: 3 }], colonne),
      colonne,
    ),
    source: 'REGLE',
    nominal: portionsIn(
      windowSpans([{ debutMinutes: 840, finMinutes: 1200, effectif: 3 }]),
      colonne,
    ),
    bande: portionsIn([[1080, 1200]], colonne),
    reouvertures: [],
  };
}

describe('rendu-grille', () => {
  it('measures a column and a stretch past midnight on the same scale', () => {
    const nuit = columnSpan('22:00', '02:00');
    expect(nuit).toEqual([1320, 1560]);
    const afterMidnight = segmentSpans(
      [{ heureDebut: '00:00', heureFin: '02:00', effectif: 1 }],
      nuit,
    );
    expect(portionsIn(afterMidnight, nuit)).toEqual([[0.5, 1]]);
  });

  it('keeps only what falls inside the column, as fractions, in order', () => {
    const colonne = columnSpan('10:00', '12:00');
    expect(
      portionsIn(
        [
          [660, 780],
          [540, 630],
        ],
        colonne,
      ),
    ).toEqual([
      [0, 0.25],
      [0.5, 1],
    ]);
    expect(portionsIn([[780, 900]], colonne)).toEqual([]);
  });

  it('draws the result, the stand hours and the band of a day under consigne', () => {
    const cellule = sousConsigne();
    expect(cellule.resultat).toEqual([[0, 2 / 3]]);
    expect(cellule.bande).toEqual([[2 / 3, 1]]);

    const rendu = renderCell(cellule, ALL_LAYERS);
    const images = rendu.image!.split('linear-gradient').length - 1;
    expect(images).toBe(3);
    expect(rendu.image).toContain('var(--ouv-regle) 0%');
    expect(rendu.image).toContain('var(--ouv-regle) 66.7%');
    expect(rendu.image).toContain('var(--ouv-bande) 66.7%');
    expect(rendu.size).toBe('100% 5px, 100% 3px, 100% 100%');
  });

  it('draws only the ticked layers, and nothing at all when none is', () => {
    const cellule = sousConsigne();
    const seul = renderCell(cellule, new Set<Couche>(['stand']));
    expect(seul.image).toContain('var(--ouv-nominal)');
    expect(seul.image).not.toContain('var(--ouv-regle)');
    expect(renderCell(cellule, new Set<Couche>())).toEqual({
      image: null,
      size: null,
      position: null,
    });
  });

  it('colours a dated exception apart from a rule, and draws a closed cell empty', () => {
    const exception = renderCell({ ...sousConsigne(), source: 'EXCEPTION', bande: [] }, ALL_LAYERS);
    expect(exception.image).toContain('var(--ouv-exception)');
    const closed = renderCell(
      { resultat: [], source: 'REGLE', nominal: [], bande: [], reouvertures: [] },
      ALL_LAYERS,
    );
    expect(closed.image).toBeNull();
  });
});
