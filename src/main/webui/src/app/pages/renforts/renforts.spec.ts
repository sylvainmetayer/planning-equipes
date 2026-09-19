// What the « Renforts » screen derives over the report: the sort the budget
// conversation needs, the quick filter, the per-day lookup, and the two shares
// the summary states — all pure, so they are read without rendering.

import { describe, expect, it } from 'vitest';
import { LigneRenfort, RapportRenforts } from '../../core/models';
import {
  celluleDuJour,
  heuresInutilisees,
  lignesAffichees,
  partDuBonus,
  readTri,
  tauxEmploi,
} from './renforts';

function ligne(patch: Partial<LigneRenfort> = {}): LigneRenfort {
  return {
    standId: 'BAR',
    nom: 'Le bar',
    emplacementNom: null,
    heuresOuvertes: 10,
    heuresPourvues: 4,
    jours: [
      { date: '2027-07-08', heuresOuvertes: 6, heuresPourvues: 4 },
      { date: '2027-07-09', heuresOuvertes: 4, heuresPourvues: 0 },
    ],
    ...patch,
  };
}

function rapport(patch: Partial<RapportRenforts> = {}): RapportRenforts {
  return {
    jours: ['2027-07-08', '2027-07-09'],
    stands: [ligne()],
    heuresOuvertes: 10,
    heuresPourvues: 4,
    heuresDues: 100,
    planEnregistre: true,
    message: null,
    ...patch,
  };
}

describe('readTri', () => {
  it('keeps a known sort and falls back on anything else', () => {
    expect(readTri('pourvues')).toBe('pourvues');
    expect(readTri('inutilisees')).toBe('inutilisees');
    expect(readTri('nom')).toBe('nom');
    expect(readTri('n’importe quoi')).toBe('ouvertes');
    expect(readTri(null)).toBe('ouvertes');
  });
});

describe('heuresInutilisees', () => {
  it('is what was opened and never taken', () => {
    expect(heuresInutilisees(ligne())).toBe(6);
  });

  it('never goes negative when a stale plan staffs more than the grid now opens', () => {
    expect(heuresInutilisees(ligne({ heuresOuvertes: 2, heuresPourvues: 5 }))).toBe(0);
  });
});

describe('celluleDuJour', () => {
  it('reads the hours of that day', () => {
    expect(celluleDuJour(ligne(), '2027-07-08')).toEqual({ ouvertes: 6, pourvues: 4 });
  });

  it('is null on a day the stand opens nothing — a hole, not a zero', () => {
    expect(celluleDuJour(ligne(), '2027-07-10')).toBeNull();
  });
});

describe('lignesAffichees', () => {
  const bar = ligne({ standId: 'BAR', nom: 'Le bar', heuresOuvertes: 10, heuresPourvues: 4 });
  const cirque = ligne({
    standId: 'CIRQUE',
    nom: 'Le cirque',
    emplacementNom: 'La place',
    heuresOuvertes: 20,
    heuresPourvues: 1,
  });
  const scene = ligne({
    standId: 'SCENE',
    nom: 'Aire de scène',
    heuresOuvertes: 5,
    heuresPourvues: 5,
  });
  const trois = rapport({ stands: [bar, cirque, scene] });

  it('sorts on what a cut would free by default', () => {
    expect(lignesAffichees(trois, '', 'ouvertes').map((l) => l.standId)).toEqual([
      'CIRQUE',
      'BAR',
      'SCENE',
    ]);
  });

  it('sorts on what the bonus really cost', () => {
    expect(lignesAffichees(trois, '', 'pourvues').map((l) => l.standId)).toEqual([
      'SCENE',
      'BAR',
      'CIRQUE',
    ]);
  });

  it('sorts on the margin nobody took — the free cut', () => {
    expect(lignesAffichees(trois, '', 'inutilisees').map((l) => l.standId)).toEqual([
      'CIRQUE',
      'BAR',
      'SCENE',
    ]);
  });

  it('sorts by name', () => {
    expect(lignesAffichees(trois, '', 'nom').map((l) => l.standId)).toEqual([
      'SCENE',
      'BAR',
      'CIRQUE',
    ]);
  });

  it('filters on the name, the id or the location, accents and case aside', () => {
    expect(lignesAffichees(trois, 'CIRQUE', 'ouvertes').map((l) => l.standId)).toEqual(['CIRQUE']);
    expect(lignesAffichees(trois, 'la place', 'ouvertes').map((l) => l.standId)).toEqual([
      'CIRQUE',
    ]);
    expect(lignesAffichees(trois, 'scene', 'ouvertes').map((l) => l.standId)).toEqual(['SCENE']);
    expect(lignesAffichees(trois, 'rien', 'ouvertes')).toEqual([]);
  });

  it('says nothing rather than failing on a report that has not loaded', () => {
    expect(lignesAffichees(undefined, '', 'ouvertes')).toEqual([]);
  });
});

describe('the two shares of the summary', () => {
  it('gives the share of the bonus the plan took', () => {
    expect(tauxEmploi(rapport())).toBeCloseTo(0.4);
  });

  it('gives what the bonus adds on top of what is owed', () => {
    expect(partDuBonus(rapport())).toBeCloseTo(0.1);
  });

  it('is null rather than a division by zero when nothing is opened or owed', () => {
    expect(tauxEmploi(rapport({ heuresOuvertes: 0 }))).toBeNull();
    expect(partDuBonus(rapport({ heuresDues: 0 }))).toBeNull();
    expect(tauxEmploi(undefined)).toBeNull();
    expect(partDuBonus(undefined)).toBeNull();
  });
});
