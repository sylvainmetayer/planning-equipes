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
    ...overrides,
  };
}

function rowValue(
  sections: ReturnType<typeof buildStandDetail>,
  label: string,
): string | undefined {
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
      stand({
        emplacement: { id: 'PLACE', nom: 'Place du Drapeau', latitude: 46.6487, longitude: 2.2503 },
      }),
    );

    expect(rowValue(sections, 'Emplacement')).toBe('Place du Drapeau (46.64870, 2.25030)');
  });

  it('spells out each recurring rule: mode, days and windows', () => {
    const sections = buildStandDetail(
      stand({
        horaires: [
          {
            id: 1,
            mode: 'FERMETURE',
            jours: 'TOUS',
            joursSemaine: [],
            dateDebut: null,
            dateFin: null,
            dates: [],
            fenetres: [{ heureDebut: '09:00', heureFin: null }],
            motif: null,
          },
          {
            id: 2,
            mode: 'OUVERTURE',
            jours: 'DATES',
            joursSemaine: [],
            dateDebut: null,
            dateFin: null,
            dates: ['2026-07-08', '2026-07-13'],
            fenetres: [
              { heureDebut: '10:00', heureFin: '12:00' },
              { heureDebut: '14:00', heureFin: null },
            ],
            motif: 'Après-midi seulement',
          },
        ],
      }),
    );

    expect(rowValue(sections, 'Règle 1')).toBe('Fermeture · Tous les jours · 09:00 → fermeture');
    expect(rowValue(sections, 'Règle 2')).toBe(
      'Ouverture · 08/07, 13/07 · 10:00 → 12:00, 14:00 → fermeture — Après-midi seulement',
    );
  });

  it('names the weekdays of a JOURS_SEMAINE rule', () => {
    const sections = buildStandDetail(
      stand({
        horaires: [
          {
            id: 1,
            mode: 'FERMETURE',
            jours: 'JOURS_SEMAINE',
            joursSemaine: ['SUNDAY', 'MONDAY'],
            dateDebut: null,
            dateFin: null,
            dates: [],
            fenetres: [{ heureDebut: '00:00', heureFin: null }],
            motif: null,
          },
        ],
      }),
    );

    expect(rowValue(sections, 'Règle 1')).toContain('Dimanche, Lundi');
  });

  it('lists each dated exception with its window and reason', () => {
    const sections = buildStandDetail(
      stand({
        ouvertures: [
          { id: 1, date: '2026-07-08', heureDebut: '20:00', heureFin: null, motif: 'Nocturne' },
        ],
        indisponibilites: [
          { id: 2, date: '2026-07-09', heureDebut: '14:00', heureFin: '16:00', motif: null },
        ],
      }),
    );

    expect(rowValue(sections, 'Ouverture du 2026-07-08')).toBe('20:00 → fermeture — Nocturne');
    expect(rowValue(sections, 'Fermeture du 2026-07-09')).toBe('14:00 → 16:00');
  });

  it('shows the seats a dated opening names, next to its window', () => {
    const sections = buildStandDetail(
      stand({
        effectifMin: 1,
        ouvertures: [
          {
            id: 1,
            date: '2026-07-08',
            heureDebut: '14:00',
            heureFin: '20:00',
            motif: 'Tournoi',
            effectif: 4,
          },
        ],
        indisponibilites: [
          { id: 2, date: '2026-07-09', heureDebut: '14:00', heureFin: null, motif: null },
        ],
      }),
    );

    expect(rowValue(sections, 'Ouverture du 2026-07-08')).toBe('14:00 → 20:00 ×4 — Tournoi');
    // A closure has no headcount to show, whatever the stand's minimum.
    expect(rowValue(sections, 'Fermeture du 2026-07-09')).toBe('14:00 → fermeture');
  });

  it('summarises how many rules and exceptions the stand carries', () => {
    const sections = buildStandDetail(
      stand({
        ouvertures: [
          { id: 1, date: '2026-07-08', heureDebut: '10:00', heureFin: '12:00', motif: null },
        ],
        indisponibilites: [
          { id: 2, date: '2026-07-09', heureDebut: '14:00', heureFin: '16:00', motif: null },
          { id: 3, date: '2026-07-10', heureDebut: '14:00', heureFin: '16:00', motif: null },
        ],
      }),
    );

    expect(rowValue(sections, 'Horaires')).toBe('3 exception(s)');
  });

  // Two windows without effect on the same day give two rows sharing a label:
  // tracked on it, one of the two would be dropped instead of shown.
  it('keeps both anomalies of a day rather than folding them into one row', () => {
    const anomalie = (heures: string) => ({
      type: 'FENETRE_SANS_EFFET' as const,
      standId: 'S1',
      standNom: 'Stand',
      date: '2027-07-12',
      message: `Fenêtre ${heures} hors de tout créneau`,
    });

    const section = buildStandDetail(
      stand({}),
      [],
      [anomalie('07:00-08:00'), anomalie('22:00-23:00')],
    ).find((s) => s.title === 'Ouvertures effectives')!;

    expect(section.rows).toHaveLength(2);
    expect(section.rows.map((row) => row.label)).toEqual(['12/07', '12/07']);
  });

  // The openings analysis, read on the fiche: a window at an hour the grid
  // does not have is the mistake a hand-typed schedule makes and never sees.
  it("lists the stand's opening anomalies in the error colour, and says « aucune » once the report is in", () => {
    const avec = buildStandDetail(
      stand({}),
      [],
      [
        {
          type: 'FENETRE_SANS_EFFET',
          standId: 'S1',
          standNom: 'Stand',
          date: '2027-07-12',
          message: 'Fenêtre 07:00-08:00 hors de tout créneau',
        },
      ],
    );
    const section = avec.find((s) => s.title === 'Ouvertures effectives')!;
    expect(section.rows).toEqual([
      { label: '12/07', value: 'Fenêtre 07:00-08:00 hors de tout créneau', alerte: true },
    ]);

    const sans = buildStandDetail(stand({}), [], []);
    expect(sans.find((s) => s.title === 'Ouvertures effectives')!.rows[0]).toMatchObject({
      muted: true,
    });
    // No report yet: no section, rather than a false « aucune ».
    expect(buildStandDetail(stand({}), []).some((s) => s.title === 'Ouvertures effectives')).toBe(
      false,
    );
  });
});
