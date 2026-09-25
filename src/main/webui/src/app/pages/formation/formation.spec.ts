import { describe, expect, it } from 'vitest';
import { TypologieAFormer } from '../../core/models';
import { libelleJour, libelleNiveau, resumeDeficit } from './formation';

function ligne(patch: Partial<TypologieAFormer>): TypologieAFormer {
  return {
    typologie: 'ESCAPE',
    label: 'Escape game',
    ninja: false,
    manque: 0,
    specialistes: 1,
    competencesRares: 0,
    groupesSansSpecialiste: 0,
    postesIrremplacables: 0,
    joursTension: [],
    candidats: [],
    ...patch,
  };
}

describe('formation', () => {
  it('names the two levels a candidate can hold', () => {
    expect(libelleNiveau('DEBUTANT')).toBe('Débutant');
    expect(libelleNiveau('AUTONOME')).toBe('Autonome');
  });

  it('writes a day the way the fragility tab does', () => {
    expect(libelleJour('2026-07-10')).toBe('10/07');
  });

  it('sums the shortage up from both reports, leaving the zeros out', () => {
    expect(resumeDeficit(ligne({ manque: 2 }))).toBe('manque 2 animateur(s) au besoin');
    expect(resumeDeficit(ligne({ competencesRares: 3, postesIrremplacables: 1 }))).toBe(
      '3 couple(s) stand × créneau à un seul spécialiste · 1 poste(s) irremplaçable(s)',
    );
    expect(resumeDeficit(ligne({ competencesRares: 3, groupesSansSpecialiste: 2 }))).toBe(
      '3 couple(s) stand × créneau à un spécialiste ou aucun, dont 2 sans spécialiste',
    );
  });
});
