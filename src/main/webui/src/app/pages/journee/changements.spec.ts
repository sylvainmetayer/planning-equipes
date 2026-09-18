import { describe, expect, it } from 'vitest';
import { ChangementsJournee } from '../../core/models';
import {
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

describe('typeSiegeLabel', () => {
  it('words each seat change', () => {
    expect(typeSiegeLabel('NOUVEAU')).toBe('nouveau');
    expect(typeSiegeLabel('RETIRE')).toBe('retiré');
    expect(typeSiegeLabel('REMPLACE')).toBe('remplacé');
  });
});
