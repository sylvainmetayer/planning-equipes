import { describe, expect, it } from 'vitest';
import { Stand, TypologieItem } from '../../core/models';
import { buildStandDetail } from './stand-detail';

function stand(overrides: Partial<Stand> = {}): Stand {
  return {
    id: 'STAND-1',
    nom: 'Village des Jeux',
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
    ...overrides
  };
}

function rowValue(sections: ReturnType<typeof buildStandDetail>, label: string): string | undefined {
  return sections.flatMap((section) => section.rows).find((row) => row.label === label)?.value;
}

describe('buildStandDetail', () => {
  it('resolves typologie ids to their human label', () => {
    const typologies: TypologieItem[] = [{ id: 'ENF', label: 'Enfance', ninja: false }];
    const sections = buildStandDetail(stand({ typologiesProposees: ['ENF', 'DIV'] }), typologies);

    const chips = sections.flatMap((section) => section.rows).find((row) => row.chips)?.chips;
    // Unknown ids fall back to the id itself rather than disappearing.
    expect(chips).toEqual(['Enfance', 'DIV']);
  });

  it('spells out an absent emplacement instead of leaving the row blank', () => {
    const sections = buildStandDetail(stand());

    const row = sections.flatMap((section) => section.rows).find((r) => r.label === 'Emplacement');
    expect(row?.value).toBe('Aucun');
    expect(row?.muted).toBe(true);
  });

  it('shows the emplacement with its coordinates when it is geocoded', () => {
    const sections = buildStandDetail(
      stand({ emplacement: { id: 'PLACE', nom: 'Place du Drapeau', latitude: 46.6487, longitude: 2.2503 } })
    );

    expect(rowValue(sections, 'Emplacement')).toBe('Place du Drapeau (46.64870, 2.25030)');
  });

  it('counts the dated exceptions that override the recurring rules', () => {
    const sections = buildStandDetail(
      stand({
        ouvertures: [{ id: 1, date: '2026-07-08', heureDebut: '10:00', heureFin: '12:00', motif: null }],
        indisponibilites: [
          { id: 2, date: '2026-07-09', heureDebut: '14:00', heureFin: '16:00', motif: null },
          { id: 3, date: '2026-07-10', heureDebut: '14:00', heureFin: '16:00', motif: null }
        ]
      })
    );

    expect(rowValue(sections, 'Ouvertures datées')).toBe('1');
    expect(rowValue(sections, 'Fermetures datées')).toBe('2');
  });
});
