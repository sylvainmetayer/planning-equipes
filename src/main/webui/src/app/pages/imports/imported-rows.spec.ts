// The rows an import wrote, found again among the store's the way the server
// matched them: by code, else by name; a timeslot by its date and its hours.

import { describe, expect, it } from 'vitest';
import { LigneImportReferentiel } from '../../core/models';
import { importedRowIds, ImportedRowsSource } from './imported-rows';

function ligne(partial: Partial<LigneImportReferentiel>): LigneImportReferentiel {
  return { line: 2, id: null, libelle: null, action: 'CREE', raisons: [], details: [], ...partial };
}

const SOURCE: ImportedRowsSource = {
  typologies: [
    { id: 't-1', code: 'STRATEGIE', label: 'Stratégie' },
    { id: 't-2', code: null, label: 'Jeux de rôle' },
  ],
  emplacements: [{ id: 'e-1', code: 'HALL', nom: 'Grand hall' }],
  stands: [
    { id: 's-1', code: 'S1', nom: 'Cirque' },
    { id: 's-2', code: null, nom: 'Médiathèque' },
  ],
  creneaux: [
    { id: 10, date: '2026-07-10', heureDebut: '09:00', heureFin: '12:00' },
    { id: 11, date: '2026-07-10', heureDebut: '14:00', heureFin: '18:00' },
    { id: 12, date: '2026-07-11', heureDebut: '09:00', heureFin: '12:00' },
  ],
};

describe('importedRowIds', () => {
  it('finds a row by its code, else by its name as the server compares it, each once', () => {
    const ids = importedRowIds(
      'STANDS',
      [
        ligne({ id: 'S1', libelle: 'Cirque' }),
        ligne({ id: 'mediatheque', libelle: 'Médiathèque', action: 'MIS_A_JOUR' }),
        ligne({ id: 'S1', libelle: 'Cirque', action: 'MIS_A_JOUR' }),
      ],
      SOURCE,
    );

    expect(ids).toEqual(['s-1', 's-2']);
  });

  it('leaves out the refused rows and the rows the store does not hold', () => {
    const ids = importedRowIds(
      'TYPOLOGIES',
      [
        ligne({ id: 'STRATEGIE' }),
        ligne({ id: 'jeux de role', action: 'REFUSE' }),
        ligne({ id: 'INCONNUE' }),
        ligne({ id: null, action: 'REFUSE' }),
      ],
      SOURCE,
    );

    expect(ids).toEqual(['t-1']);
  });

  it('finds a timeslot by its date and its hours, however they were typed', () => {
    const ids = importedRowIds(
      'CRENEAUX',
      [
        ligne({ id: '2026-07-10', libelle: '9h-12' }),
        ligne({ id: '2026-07-10', libelle: '14:00:00-18:00:00' }),
        ligne({ id: '2026-07-12', libelle: '09:00-12:00' }),
      ],
      SOURCE,
    );

    expect(ids).toEqual(['10', '11']);
  });

  it('has nothing to single out for the day templates', () => {
    expect(importedRowIds('JOURNEES_TYPES', [ligne({ id: 'Semaine' })], SOURCE)).toBeNull();
  });

  it('finds the emplacements the same way', () => {
    expect(importedRowIds('EMPLACEMENTS', [ligne({ id: 'HALL' })], SOURCE)).toEqual(['e-1']);
  });
});
