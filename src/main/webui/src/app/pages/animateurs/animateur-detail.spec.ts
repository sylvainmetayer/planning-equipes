import { describe, expect, it } from 'vitest';
import { Animateur, TypologieItem } from '../../core/models';
import { buildAnimateurDetail } from './animateur-detail';

function animateur(overrides: Partial<Animateur> = {}): Animateur {
  return {
    id: 'A1',
    prenom: 'Ada',
    nom: 'Lovelace',
    dateNaissance: '2000-06-15',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
    ...overrides
  };
}

function rowValue(sections: ReturnType<typeof buildAnimateurDetail>, label: string): string | undefined {
  return sections.flatMap((section) => section.rows).find((row) => row.label === label)?.value;
}

describe('buildAnimateurDetail', () => {
  it('derives adult status from the birth date at the given reference date', () => {
    const sections = buildAnimateurDetail(animateur(), [], new Date(2026, 6, 8));
    expect(rowValue(sections, 'Majeur')).toBe('Majeur (26 ans)');
  });

  it('counts the birthday as not yet passed earlier in the year', () => {
    // Born 2009-06-15, read on 2026-06-14: still 16, i.e. still a minor.
    const sections = buildAnimateurDetail(animateur({ dateNaissance: '2009-06-15' }), [], new Date(2026, 5, 14));
    expect(rowValue(sections, 'Majeur')).toBe('Mineur (16 ans)');
  });

  it('says "inconnu" rather than guessing when there is no birth date', () => {
    const sections = buildAnimateurDetail(animateur({ dateNaissance: null }), [], new Date(2026, 6, 8));
    expect(rowValue(sections, 'Majeur')).toBe('Inconnu');
  });

  it('labels the appreciation with the typologie label and its level', () => {
    const typologies: TypologieItem[] = [{ id: 'ENF', label: 'Enfance', ninja: false }];
    const sections = buildAnimateurDetail(
      animateur({ competences: { ENF: 'REFERENT' } }),
      typologies,
      new Date(2026, 6, 8)
    );

    const chips = sections.flatMap((section) => section.rows).find((row) => row.chips)?.chips;
    expect(chips).toEqual(['Enfance · REFERENT']);
  });

  it('sorts the unavailable days and keeps them out of the chips when there are none', () => {
    const avec = buildAnimateurDetail(
      animateur({ joursIndisponibles: ['2026-07-12', '2026-07-06'] }),
      [],
      new Date(2026, 6, 8)
    );
    expect(avec.flatMap((section) => section.rows).find((row) => row.chips)?.chips).toEqual([
      '2026-07-06',
      '2026-07-12'
    ]);

    const sans = buildAnimateurDetail(animateur(), [], new Date(2026, 6, 8));
    expect(rowValue(sans, 'Indisponibilités')).toBe('Disponible tous les jours');
  });
});
