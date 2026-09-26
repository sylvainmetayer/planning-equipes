import { describe, expect, it } from 'vitest';
import { AnomalieOuverture, Stand } from '../../core/models';
import { anomalyLabel, scheduleRows } from './stand-detail';

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

function rowValue(rows: ReturnType<typeof scheduleRows>, label: string): string | undefined {
  return rows.find((row) => row.label === label)?.value;
}

describe('scheduleRows', () => {
  it('spells out each recurring rule: mode, days and windows', () => {
    const sections = scheduleRows(
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
    const sections = scheduleRows(
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
    const sections = scheduleRows(
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
    const sections = scheduleRows(
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
    const sections = scheduleRows(
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
});

describe('anomalyLabel', () => {
  const anomaly = (partial: Partial<AnomalieOuverture>): AnomalieOuverture => ({
    type: 'FENETRE_SANS_EFFET',
    standId: 'S1',
    standNom: 'Stand',
    date: '2027-07-12',
    message: 'Fenêtre 07:00-08:00 hors de tout créneau',
    ...partial,
  });

  it('names a dated anomaly by its day, and an undated one by the whole edition', () => {
    expect(anomalyLabel(anomaly({}))).toBe('12/07');
    expect(anomalyLabel(anomaly({ type: 'STAND_JAMAIS_OUVERT', date: null }))).toBe(
      "Toute l'édition",
    );
  });

  // How the rules are written is named by its kind rather than a day.
  it('names the rule overlaps by their kind', () => {
    expect(anomalyLabel(anomaly({ type: 'REGLES_CHEVAUCHANTES', date: null }))).toBe(
      'Règles qui se recouvrent',
    );
    expect(anomalyLabel(anomaly({ type: 'REGLE_MASQUEE', date: null }))).toBe('Règle sans effet');
    expect(anomalyLabel(anomaly({ type: 'FENETRES_CHEVAUCHANTES' }))).toBe(
      'Fenêtres qui se recouvrent, 12/07',
    );
  });
});
