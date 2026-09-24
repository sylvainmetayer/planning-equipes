import { afterEach, describe, expect, it } from 'vitest';
import { JourResolu, libelleJour, libelleJourSemaine } from '../../core/horaire-stand';
import { Creneau, HoraireStand, JourSemaine } from '../../core/models';
import {
  datesEvenement,
  decrireJour,
  effectifDepuisSaisie,
  erreurRegle,
  notesRegles,
  notesReglesForStands,
  premiereErreurHoraire,
} from './stand-horaires';

function horaire(overrides: Partial<HoraireStand> = {}): HoraireStand {
  return {
    id: null,
    mode: 'OUVERTURE',
    jours: 'TOUS',
    joursSemaine: [],
    dateDebut: null,
    dateFin: null,
    dates: [],
    fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }],
    motif: null,
    ...overrides,
  };
}

function creneau(id: number, date: string): Creneau {
  return { id, jour: id, date, heureDebut: '10:00', heureFin: '12:00' };
}

describe('premiereErreurHoraire', () => {
  it('accepts a well-formed rule', () => {
    expect(premiereErreurHoraire([horaire()])).toBeNull();
  });

  it('refuses a rule carrying no window at all', () => {
    expect(premiereErreurHoraire([horaire({ fenetres: [] })])).toContain('au moins une fenêtre');
  });

  it('refuses a window with no start time', () => {
    expect(
      premiereErreurHoraire([horaire({ fenetres: [{ heureDebut: '', heureFin: '12:00' }] })]),
    ).toContain('heure de début');
  });

  it('refuses a window whose end is not after its start', () => {
    expect(
      premiereErreurHoraire([horaire({ fenetres: [{ heureDebut: '14:00', heureFin: '10:00' }] })]),
    ).toContain("L'heure de fin doit être après");
  });

  // An empty end time is the documented way of saying "until closing time".
  it('accepts a window left open until closing time', () => {
    expect(
      premiereErreurHoraire([horaire({ fenetres: [{ heureDebut: '14:00', heureFin: null }] })]),
    ).toBeNull();
  });

  it('refuses a weekday-scoped rule naming no weekday', () => {
    expect(
      premiereErreurHoraire([horaire({ jours: 'JOURS_SEMAINE', joursSemaine: [] })]),
    ).toContain('au moins un jour de la semaine');
  });

  it('refuses a range-scoped rule with no coherent bounds', () => {
    expect(
      premiereErreurHoraire([horaire({ jours: 'PLAGE', dateDebut: null, dateFin: null })]),
    ).toContain('date de début et une date de fin');
  });

  it('refuses a date-scoped rule naming no date', () => {
    expect(premiereErreurHoraire([horaire({ jours: 'DATES', dates: [] })])).toContain(
      'au moins une date',
    );
  });

  it('reports the first faulty rule, not the last', () => {
    const erreur = premiereErreurHoraire([
      horaire({ fenetres: [] }),
      horaire({ jours: 'DATES', dates: [] }),
    ]);
    expect(erreur).toContain('au moins une fenêtre');
  });

  // Two rules of the same scope, same days, one opening and one closing: the
  // backend cannot decide which wins, so the form refuses it up front.
  it('refuses an opening and a closing rule of the same scope on the same days', () => {
    const erreur = premiereErreurHoraire([
      horaire({ mode: 'OUVERTURE' }),
      horaire({ mode: 'FERMETURE' }),
    ]);
    expect(erreur).toContain("l'un une ouverture et l'autre une fermeture");
  });

  it('accepts two rules of the same scope that agree on their mode', () => {
    expect(
      premiereErreurHoraire([horaire({ mode: 'OUVERTURE' }), horaire({ mode: 'OUVERTURE' })]),
    ).toBeNull();
  });

  it('accepts an empty rule set', () => {
    expect(premiereErreurHoraire([])).toBeNull();
  });
});

describe('libelleJourSemaine', () => {
  it('names each of the seven weekdays', () => {
    const jours: JourSemaine[] = [
      'MONDAY',
      'TUESDAY',
      'WEDNESDAY',
      'THURSDAY',
      'FRIDAY',
      'SATURDAY',
      'SUNDAY',
    ];
    expect(jours.map(libelleJourSemaine)).toEqual([
      'Lundi',
      'Mardi',
      'Mercredi',
      'Jeudi',
      'Vendredi',
      'Samedi',
      'Dimanche',
    ]);
  });
});

describe('datesEvenement', () => {
  it('lists each day once, in chronological order, whatever the créneau order', () => {
    const creneaux = [creneau(1, '2026-07-15'), creneau(2, '2026-07-14'), creneau(3, '2026-07-15')];
    expect(datesEvenement(creneaux)).toEqual(['2026-07-14', '2026-07-15']);
  });

  it('is empty when the edition has no créneau yet', () => {
    expect(datesEvenement([])).toEqual([]);
  });
});

describe('libelleJour', () => {
  afterEach(() => localStorage.clear());

  it('shortens an ISO date to day/month', () => {
    expect(libelleJour('2026-07-08')).toBe('08/07');
  });

  it('follows the UI locale: month/day in English', () => {
    localStorage.setItem('planning-equipes.locale', 'en');
    expect(libelleJour('2026-07-08')).toBe('07/08');
  });
});

describe('decrireJour', () => {
  function jour(overrides: Partial<JourResolu> = {}): JourResolu {
    return { date: '2026-07-08', mode: 'OUVERTURE', fenetres: [], source: 'REGLE', ...overrides };
  }

  it('says a day nothing states anything about is open all day', () => {
    expect(decrireJour(jour({ mode: null }))).toBe('Ouvert toute la journée');
  });

  it('lists the windows a day is open on', () => {
    const text = decrireJour(jour({ fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }] }));
    expect(text).toBe('Ouvert 10:00 → 12:00');
  });

  it('joins several windows of the same day', () => {
    const text = decrireJour(
      jour({
        fenetres: [
          { heureDebut: '10:00', heureFin: '12:00' },
          { heureDebut: '14:00', heureFin: null },
        ],
      }),
    );
    expect(text).toBe('Ouvert 10:00 → 12:00, 14:00 → fermeture');
  });

  it('shows the seats of a window that names them, and nothing for the others', () => {
    const text = decrireJour(
      jour({
        fenetres: [
          { heureDebut: '10:00', heureFin: '12:00' },
          { heureDebut: '14:00', heureFin: '20:00', effectif: 3 },
        ],
      }),
    );
    expect(text).toBe('Ouvert 10:00 → 12:00, 14:00 → 20:00 ×3');
  });

  it('says "fermeture" for a window running to the end of the day', () => {
    expect(decrireJour(jour({ fenetres: [{ heureDebut: '14:00', heureFin: null }] }))).toContain(
      'fermeture',
    );
  });

  it('distinguishes a closing day from an opening one', () => {
    const ferme = decrireJour(
      jour({ mode: 'FERMETURE', fenetres: [{ heureDebut: '10:00', heureFin: '12:00' }] }),
    );
    expect(ferme).toContain('Fermé');
    expect(ferme).not.toContain('Ouvert');
  });
});

describe('effectifDepuisSaisie', () => {
  // The whole point: a bulk edit only writes what the user explicitly filled in,
  // so an emptied field must read as "leave every stand alone", never as zero.
  it('reads an emptied field as "ne pas modifier"', () => {
    expect(effectifDepuisSaisie('')).toBeNull();
    expect(effectifDepuisSaisie(null)).toBeNull();
    expect(effectifDepuisSaisie(undefined)).toBeNull();
  });

  it('reads a typed number as that number', () => {
    expect(effectifDepuisSaisie('3')).toBe(3);
    expect(effectifDepuisSaisie(3)).toBe(3);
  });

  it('keeps an explicit zero, which is a real bound', () => {
    expect(effectifDepuisSaisie('0')).toBe(0);
  });

  it('rejects anything that is not a number rather than writing NaN', () => {
    expect(effectifDepuisSaisie('abc')).toBeNull();
  });
});

describe('erreurRegle', () => {
  it('reports the compact line before the windows it has not replaced yet', () => {
    // The windows are valid; the line being typed is not: the line wins,
    // otherwise the card would say nothing while the submit stays blocked.
    expect(erreurRegle({ ...horaire(), saisie: '10:00-12:00, 14:00' })).toContain('14:00');
    expect(erreurRegle({ ...horaire(), saisie: '' })).toContain('au moins une fenêtre');
    expect(erreurRegle({ ...horaire(), saisie: '10h-douze' })).toContain('heure illisible');
    expect(erreurRegle({ ...horaire(), saisie: '10:00-12:00@0' })).toContain('après « @ »');
  });

  it('falls back on the rule checks once the line parses', () => {
    expect(erreurRegle({ ...horaire(), saisie: '10:00-12:00' })).toBeNull();
    expect(
      erreurRegle(
        {
          ...horaire({ fenetres: [{ heureDebut: '10:00', heureFin: '12:00', effectif: 5 }] }),
          saisie: null,
        },
        2,
      ),
    ).toContain("L'effectif d'une fenêtre");
  });

  it('checks the windows when no line was ever typed', () => {
    expect(erreurRegle(horaire({ fenetres: [] }))).toContain('au moins une fenêtre');
  });
});

describe('notesRegles', () => {
  const semaine = ['06', '07', '08', '09', '10', '11', '12'].map((jour) => ({
    date: `2026-07-${jour}`,
    fin: '20:00',
  }));
  const noException = { ouvertures: [], indisponibilites: [] };

  it('says nothing on a single plain rule', () => {
    expect(notesRegles([horaire()], noException, semaine, 1)).toEqual([
      { avertissements: [], priorites: [] },
    ]);
  });

  it('names the rule that replaces a masked one', () => {
    const notes = notesRegles(
      [
        horaire({ fenetres: [{ heureDebut: '10:00', heureFin: '19:00' }] }),
        horaire({
          jours: 'JOURS_SEMAINE',
          joursSemaine: [
            'MONDAY',
            'TUESDAY',
            'WEDNESDAY',
            'THURSDAY',
            'FRIDAY',
            'SATURDAY',
            'SUNDAY',
          ],
        }),
      ],
      noException,
      semaine,
      1,
    );
    expect(notes[0].avertissements).toHaveLength(1);
    expect(notes[0].avertissements[0]).toContain("n'est appliquée à aucun jour");
    expect(notes[1].avertissements).toEqual([]);
  });

  it('says so when dated exceptions replace a rule on all its days', () => {
    const notes = notesRegles(
      [horaire()],
      {
        ouvertures: [],
        indisponibilites: [
          { id: null, date: '2026-07-06', heureDebut: '00:00', heureFin: null, motif: null },
        ],
      },
      semaine.slice(0, 1),
      1,
    );
    expect(notes[0].avertissements[0]).toContain('des exceptions datées la remplacent partout');
  });

  it('reports two windows of one rule overlapping at different headcounts', () => {
    const notes = notesRegles(
      [
        horaire({
          fenetres: [
            { heureDebut: '10:00', heureFin: '14:00', effectif: 3 },
            { heureDebut: '12:00', heureFin: '18:00', effectif: 2 },
          ],
        }),
      ],
      noException,
      [],
      1,
    );
    expect(notes[0].avertissements).toEqual([
      '10:00 → 14:00 ×3 et 12:00 → 18:00 ×2 se recouvrent : 3 personne(s) de 12:00 à 14:00.',
    ]);
  });

  it('marks the priority of a weekday closure over an every-day opening, without a warning', () => {
    const notes = notesRegles(
      [
        horaire({ fenetres: [{ heureDebut: '10:00', heureFin: '19:00' }] }),
        horaire({
          mode: 'FERMETURE',
          jours: 'JOURS_SEMAINE',
          joursSemaine: ['SATURDAY', 'SUNDAY'],
          fenetres: [{ heureDebut: '10:00', heureFin: '19:00' }],
        }),
      ],
      noException,
      semaine,
      1,
    );
    expect(notes.flatMap((note) => note.avertissements)).toEqual([]);
    expect(notes[0].priorites).toEqual([]);
    expect(notes[1].priorites).toEqual(['Prime sur « tous les jours » : samedi, dimanche.']);
  });

  it('keeps the capitals of the days in English', () => {
    localStorage.setItem('planning-equipes.locale', 'en');
    try {
      const notes = notesRegles(
        [
          horaire({ fenetres: [{ heureDebut: '10:00', heureFin: '19:00' }] }),
          horaire({
            mode: 'FERMETURE',
            jours: 'JOURS_SEMAINE',
            joursSemaine: ['SATURDAY', 'SUNDAY'],
            fenetres: [{ heureDebut: '10:00', heureFin: '19:00' }],
          }),
        ],
        noException,
        semaine,
        1,
      );
      // No translation is loaded here: the French source shows, left as written.
      expect(notes[1].priorites).toEqual(['Prime sur « Tous les jours » : Samedi, Dimanche.']);
    } finally {
      localStorage.clear();
    }
  });

  it('does not count an unknown headcount as zero', () => {
    const notes = notesRegles(
      [
        horaire({
          fenetres: [
            { heureDebut: '10:00', heureFin: '14:00', effectif: 3 },
            { heureDebut: '12:00', heureFin: '18:00', effectif: null },
          ],
        }),
      ],
      noException,
      [],
      null,
    );
    expect(notes[0].avertissements).toHaveLength(1);
    expect(notes[0].avertissements[0]).toContain(
      "se recouvrent de 12:00 à 14:00 : c'est le plus haut des deux effectifs qui est retenu.",
    );
    expect(notes[0].avertissements[0]).not.toContain('personne(s)');
  });
});

describe('notesReglesForStands', () => {
  const jour = [{ date: '2026-07-06', fin: '20:00' }];
  const noException = { ouvertures: [], indisponibilites: [] };
  const closedAllDay = {
    ouvertures: [],
    indisponibilites: [
      { id: null, date: '2026-07-06', heureDebut: '00:00', heureFin: null, motif: null },
    ],
  };

  it('warns when one selected stand of several would mask the rule with its exceptions', () => {
    const notes = notesReglesForStands([horaire()], [noException, closedAllDay], jour, 1);
    expect(notes[0].avertissements).toHaveLength(1);
    expect(notes[0].avertissements[0]).toContain('des exceptions datées la remplacent partout');
  });

  it('says nothing when no selected stand has an exception in the way', () => {
    expect(notesReglesForStands([horaire()], [noException, noException], jour, 1)).toEqual([
      { avertissements: [], priorites: [] },
    ]);
  });

  it('says a warning several stands share once', () => {
    const notes = notesReglesForStands([horaire()], [closedAllDay, closedAllDay], jour, 1);
    expect(notes[0].avertissements).toHaveLength(1);
  });

  it('reads an empty selection as one stand without exceptions', () => {
    expect(notesReglesForStands([horaire()], [], jour, 1)).toEqual(
      notesRegles([horaire()], noException, jour, 1),
    );
  });
});
