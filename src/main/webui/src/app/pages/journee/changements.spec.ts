import { describe, expect, it } from 'vitest';
import {
  ChangementAnimateur,
  ChangementSiege,
  ChangementsJournee,
  TitulaireSiege,
  TypeChangementSiege,
} from '../../core/models';
import {
  animateurIdsOnStand,
  countSeatLines,
  filterPersonLines,
  filterSeatLines,
  isUnchanged,
  readReading,
  readReference,
  referenceParam,
  typeSiegeLabel,
} from './changements';

function changements(overrides: Partial<ChangementsJournee> = {}): ChangementsJournee {
  return {
    jour: '2026-08-01',
    reference: 'PUBLICATION',
    referenceDisponible: true,
    referenceLe: '2026-07-20T10:00:00Z',
    nouveaux: 0,
    retires: 0,
    remplaces: 0,
    horairesModifies: 0,
    animateursConcernes: 0,
    parVacation: [],
    parAnimateur: [],
    ...overrides,
  };
}

describe('readReference', () => {
  it('names one of the two references, and leaves the choice to the server otherwise', () => {
    expect(readReference('publication')).toBe('publication');
    expect(readReference('resolution')).toBe('resolution');
    expect(readReference(null)).toBeNull();
    expect(readReference('instantane')).toBeNull();
  });
});

describe('readReading', () => {
  it('opens on the seat table unless the URL asks for the per-person reading', () => {
    expect(readReading(null)).toBe('vacations');
    expect(readReading('animateurs')).toBe('animateurs');
    expect(readReading('stands')).toBe('vacations');
  });
});

describe('referenceParam', () => {
  it('turns the reference an answer names into its query-param form', () => {
    expect(referenceParam('PUBLICATION')).toBe('publication');
    expect(referenceParam('RESOLUTION')).toBe('resolution');
  });
});

describe('isUnchanged', () => {
  it('is true only against an existing reference with no line either way', () => {
    expect(isUnchanged(changements())).toBe(true);
    expect(isUnchanged(changements({ referenceDisponible: false }))).toBe(false);
    expect(
      isUnchanged(
        changements({
          parAnimateur: [{ animateurId: 'a', nomAffiche: 'A', changements: [] }],
        }),
      ),
    ).toBe(false);
  });
});

describe('filterSeatLines', () => {
  const alice = { animateurId: 'a1', nomAffiche: 'Alice Martin' };
  const bob = { animateurId: 'a2', nomAffiche: 'Bob Durand' };
  const lines: ChangementSiege[] = [
    seat('TIR', 'Tir à l’arc', alice, bob, 'REMPLACE'),
    seat('DIXIT', 'Dixit', null, alice, 'NOUVEAU'),
    seat('DIXIT', 'Dixit', bob, null, 'RETIRE'),
  ];

  it('keeps everything when nothing is typed or picked', () => {
    expect(filterSeatLines(lines, '', '', '')).toHaveLength(3);
  });

  it('narrows on the stand, and on the animateur whichever side of the change they are on', () => {
    expect(filterSeatLines(lines, '', 'DIXIT', '').map((line) => line.type)).toEqual([
      'NOUVEAU',
      'RETIRE',
    ]);
    expect(filterSeatLines(lines, '', '', 'a1').map((line) => line.standId)).toEqual([
      'TIR',
      'DIXIT',
    ]);
  });

  it('matches the typed text against the stand and both holders, accents aside', () => {
    expect(filterSeatLines(lines, 'tir arc', '', '')).toHaveLength(1);
    expect(filterSeatLines(lines, 'durand', '', '').map((line) => line.type)).toEqual([
      'REMPLACE',
      'RETIRE',
    ]);
  });
});

describe('animateurIdsOnStand', () => {
  const alice = { animateurId: 'a1', nomAffiche: 'Alice Martin' };
  const bob = { animateurId: 'a2', nomAffiche: 'Bob Durand' };
  const lines: ChangementSiege[] = [
    seat('TIR', 'Tir', alice, bob, 'REMPLACE'),
    seat('TIR-ARC', 'Tir à l’arc', null, alice, 'NOUVEAU'),
    seat('DIXIT', 'Dixit', bob, null, 'RETIRE'),
  ];

  it('is nobody to narrow on without a stand', () => {
    expect(animateurIdsOnStand(lines, '')).toBeNull();
  });

  it('names the people on either side of the seats of that stand, and that stand only', () => {
    expect([...(animateurIdsOnStand(lines, 'TIR') ?? [])]).toEqual(['a1', 'a2']);
    // « Tir » is a prefix of « Tir à l’arc »: an id match, never a name match.
    expect([...(animateurIdsOnStand(lines, 'TIR-ARC') ?? [])]).toEqual(['a1']);
    expect(animateurIdsOnStand(lines, 'PUZZLE')?.size).toBe(0);
  });
});

describe('filterPersonLines', () => {
  const lines: ChangementAnimateur[] = [
    {
      animateurId: 'a1',
      nomAffiche: 'Alice Martin',
      changements: [{ type: 'AJOUT', libelle: 'samedi 01/08 : Dixit 10h-12h (nouveau)' }],
    },
    {
      animateurId: 'a2',
      nomAffiche: 'Bob Durand',
      changements: [{ type: 'RETRAIT', libelle: 'samedi 01/08 : Tir à l’arc 14h-18h (retiré)' }],
    },
  ];

  it('narrows on the animateur, on the people the stand filter names, and on the typed text', () => {
    expect(filterPersonLines(lines, '', null, 'a2').map((line) => line.animateurId)).toEqual([
      'a2',
    ]);
    expect(
      filterPersonLines(lines, '', new Set(['a1']), '').map((line) => line.animateurId),
    ).toEqual(['a1']);
    expect(filterPersonLines(lines, '', new Set(), '')).toHaveLength(0);
    expect(filterPersonLines(lines, 'retire', null, '').map((line) => line.animateurId)).toEqual([
      'a2',
    ]);
    expect(filterPersonLines(lines, '', null, '')).toHaveLength(2);
  });
});

describe('countSeatLines', () => {
  it('counts the lines by type, so the counters can follow the filters', () => {
    const alice = { animateurId: 'a1', nomAffiche: 'Alice Martin' };
    const bob = { animateurId: 'a2', nomAffiche: 'Bob Durand' };
    expect(
      countSeatLines([
        seat('TIR', 'Tir', alice, bob, 'REMPLACE'),
        seat('DIXIT', 'Dixit', null, alice, 'NOUVEAU'),
        seat('DIXIT', 'Dixit', bob, null, 'RETIRE'),
        seat('DIXIT', 'Dixit', bob, bob, 'HORAIRES'),
        seat('PUZZLE', 'Puzzle', null, bob, 'NOUVEAU'),
      ]),
    ).toEqual({ nouveaux: 2, retires: 1, remplaces: 1, horairesModifies: 1 });
    expect(countSeatLines([])).toEqual({
      nouveaux: 0,
      retires: 0,
      remplaces: 0,
      horairesModifies: 0,
    });
  });
});

function seat(
  standId: string,
  standNom: string,
  avant: TitulaireSiege | null,
  apres: TitulaireSiege | null,
  type: TypeChangementSiege,
): ChangementSiege {
  return {
    standId,
    standNom,
    date: '2026-08-01',
    heureDebut: '10:00',
    heureFin: '12:00',
    heureDebutAvant: null,
    heureFinAvant: null,
    avant,
    apres,
    type,
  };
}

describe('typeSiegeLabel', () => {
  it('words each seat change', () => {
    expect(typeSiegeLabel('NOUVEAU')).toBe('nouveau');
    expect(typeSiegeLabel('RETIRE')).toBe('retiré');
    expect(typeSiegeLabel('REMPLACE')).toBe('remplacé');
    expect(typeSiegeLabel('HORAIRES')).toBe('horaires modifiés');
  });
});
