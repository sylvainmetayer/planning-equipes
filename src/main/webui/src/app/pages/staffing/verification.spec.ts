import { describe, expect, it } from 'vitest';
import { StaffingVerification } from '../../core/models';
import { historyLabel, teamLabel } from './verification';

function check(overrides: Partial<StaffingVerification> = {}): StaffingVerification {
  return {
    id: 7,
    etat: 'TERMINEE',
    effectif: 160,
    majeurs: 160,
    mineurs: 0,
    sieges: 900,
    lanceeLe: '2026-10-01T10:00:00Z',
    plafondSecondes: 600,
    reglesEnDefaut: [],
    ...overrides,
  };
}

describe('a staffing check, in words', () => {
  it('names the minors only when the team holds some', () => {
    expect(teamLabel(check())).toBe('160 personnes');
    expect(teamLabel(check({ majeurs: 150, mineurs: 10 }))).toBe(
      '160 personnes (150 majeurs, 10 mineurs)',
    );
  });

  it('reads as a trial in the history: team, time given, outcome', () => {
    expect(historyLabel(check({ realisable: true, dureeSecondes: 312 }))).toBe(
      '160 personnes, 600 s au plus : tous les sièges pourvus en 312 s',
    );
    expect(
      historyLabel(check({ effectif: 140, majeurs: 140, realisable: false, siegesNonPourvus: 12 })),
    ).toBe('140 personnes, 600 s au plus : aucun plan complet, 12 sièges vides');
    expect(historyLabel(check({ etat: 'EN_COURS' }))).toBe(
      '160 personnes, 600 s au plus : en cours',
    );
    expect(historyLabel(check({ etat: 'ECHEC', erreur: 'Interrompue.' }))).toBe(
      '160 personnes, 600 s au plus : Interrompue.',
    );
  });
});
