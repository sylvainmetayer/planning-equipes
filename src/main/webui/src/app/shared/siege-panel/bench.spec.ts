import { describe, expect, it } from 'vitest';
import {
  Animateur,
  AnimateurBanc,
  BancDeTouche,
  MotifExclusion,
  VerrouillagePlanning,
} from '../../core/models';
import {
  benchLines,
  PlacementContext,
  placeable,
  placementBlock,
  sortReasons,
  splitLines,
  stateOf,
} from './bench';

const motif = (contrainte: string, niveau: MotifExclusion['niveau']): MotifExclusion => ({
  contrainte,
  niveau,
  categorie: 'Légal (temps de travail)',
  description: 'Une règle du Code du travail.',
});

const line = (partial: Partial<AnimateurBanc> & { animateurId: string }): AnimateurBanc => ({
  disponible: true,
  degradeLePlan: false,
  delta: null,
  motifs: [],
  ...partial,
});

const animateur = (id: string, prenom: string, nom: string): Animateur => ({
  id,
  prenom,
  nom,
  dateNaissance: '1990-01-01',
  manager: false,
  competences: {},
  souhaits: [],
  joursIndisponibles: [],
});

describe('stateOf', () => {
  it('keeps the three states apart instead of flattening them into two', () => {
    expect(stateOf(line({ animateurId: 'A', disponible: true, degradeLePlan: false }))).toBe(
      'disponible',
    );
    expect(stateOf(line({ animateurId: 'B', disponible: false, degradeLePlan: false }))).toBe(
      'sousReserve',
    );
    expect(stateOf(line({ animateurId: 'C', disponible: false, degradeLePlan: true }))).toBe(
      'impossible',
    );
  });

  // The server guarantees `disponible` implies `!degradeLePlan`; if that ever
  // stops holding, the stricter reading must win rather than the laxer one.
  it('keeps the stricter verdict when the server contradicts itself', () => {
    expect(stateOf(line({ animateurId: 'D', disponible: true, degradeLePlan: true }))).toBe(
      'impossible',
    );
  });
});

describe('sortReasons', () => {
  it('puts the hard rules before the penalties, then sorts by name', () => {
    const sorted = sortReasons([
      motif('souhaitsIncompatibles', 'MEDIUM'),
      motif('reposQuotidienMinimal', 'HARD'),
      motif('animateurDisponible', 'HARD'),
    ]);

    expect(sorted.map((m) => m.contrainte)).toEqual([
      'animateurDisponible',
      'reposQuotidienMinimal',
      'souhaitsIncompatibles',
    ]);
  });

  it('leaves the array it was given untouched', () => {
    const source = [motif('b', 'MEDIUM'), motif('a', 'HARD')];
    sortReasons(source);
    expect(source.map((m) => m.contrainte)).toEqual(['b', 'a']);
  });
});

describe('benchLines', () => {
  const bench: BancDeTouche = {
    creneauId: 1,
    statut: 'EVALUATED',
    posteCibleId: 'P1',
    standCibleId: 'S1',
    animateurCibleId: null,
    seatStarted: false,
    total: 3,
    disponibles: 1,
    creneauxAvecSieges: [],
    animateurs: [
      line({ animateurId: 'A1', delta: { hardScore: 1, mediumScore: -2, softScore: 0 } }),
      line({
        animateurId: 'A2',
        disponible: false,
        degradeLePlan: true,
        delta: { hardScore: -3, mediumScore: 0, softScore: 0 },
        motifs: [motif('souhaitsIncompatibles', 'MEDIUM'), motif('animateurDisponible', 'HARD')],
      }),
      line({ animateurId: 'A-INCONNU', disponible: false, degradeLePlan: false }),
    ],
  };

  it('keeps the order of the server and names every animateur', () => {
    const rows = benchLines(bench, [
      animateur('A1', 'Léa', 'Martin'),
      animateur('A2', 'Omar', 'Bernard'),
    ]);

    expect(rows.map((row) => row.nom)).toEqual(['Léa Martin', 'Omar Bernard', 'A-INCONNU']);
    expect(rows.map((row) => row.state)).toEqual(['disponible', 'impossible', 'sousReserve']);
  });

  it('carries the hard and medium costs and sorts the reasons', () => {
    const rows = benchLines(bench, [animateur('A2', 'Omar', 'Bernard')]);
    const omar = rows.find((row) => row.animateurId === 'A2');

    expect(omar?.hardCost).toBe(-3);
    expect(rows[0].mediumCost).toBe(-2);
    expect(omar?.motifs.map((m) => m.contrainte)).toEqual([
      'animateurDisponible',
      'souhaitsIncompatibles',
    ]);
  });

  // The bench is read from a persisted plan, which can legitimately be older
  // than a since-deleted animateur. Dropping the row would silently shorten a
  // list whose whole point is to be exhaustive.
  it('keeps a line whose animateur left the referential', () => {
    const rows = benchLines(bench, []);
    expect(rows).toHaveLength(3);
    expect(rows[2].nom).toBe('A-INCONNU');
  });

  it('answers an empty list without an answer from the server', () => {
    expect(benchLines(null, [])).toEqual([]);
  });

  it('offers « Placer » on the available lines only, and folds the impossible ones', () => {
    const rows = benchLines(bench, []);
    const free: PlacementContext = { creneauId: 1, seatStarted: false, locks: [] };

    expect(rows.filter((row) => placeable(row, free)).map((row) => row.animateurId)).toEqual([
      'A1',
    ]);
    const { shown, folded } = splitLines(rows);
    expect(shown.map((row) => row.animateurId)).toEqual(['A1', 'A-INCONNU']);
    expect(folded.map((row) => row.animateurId)).toEqual(['A2']);
  });
});

describe('placementBlock', () => {
  const lock = (partial: Partial<VerrouillagePlanning>): VerrouillagePlanning => ({
    id: 'L',
    type: 'ANIMATEUR',
    animateurId: null,
    standId: null,
    creneauId: null,
    jour: null,
    raison: null,
    ...partial,
  });
  const [lea] = benchLines(
    {
      creneauId: 1,
      statut: 'EVALUATED',
      posteCibleId: 'P1',
      standCibleId: 'S1',
      animateurCibleId: null,
      seatStarted: false,
      total: 1,
      disponibles: 1,
      creneauxAvecSieges: [],
      animateurs: [line({ animateurId: 'A1' })],
    },
    [animateur('A1', 'Léa', 'Martin')],
  );
  const context = (partial: Partial<PlacementContext>): PlacementContext => ({
    creneauId: 1,
    seatStarted: false,
    locks: [],
    ...partial,
  });

  it('blocks a person whose schedule, or this very timeslot, is locked — not another one', () => {
    expect(placementBlock(lea, context({ locks: [lock({ animateurId: 'A1' })] }))).toBe('locked');
    expect(
      placementBlock(
        lea,
        context({ locks: [lock({ type: 'ANIMATEUR_CRENEAU', animateurId: 'A1', creneauId: 1 })] }),
      ),
    ).toBe('locked');
    expect(
      placementBlock(
        lea,
        context({ locks: [lock({ type: 'ANIMATEUR_CRENEAU', animateurId: 'A1', creneauId: 2 })] }),
      ),
    ).toBeNull();
    expect(placementBlock(lea, context({ locks: [lock({ animateurId: 'A2' })] }))).toBeNull();
    expect(placeable(lea, context({}))).toBe(true);
  });

  it('blocks everybody on a timeslot already started', () => {
    expect(placementBlock(lea, context({ seatStarted: true }))).toBe('started');
    expect(placeable(lea, context({ seatStarted: true }))).toBe(false);
  });
});
