// The espace's « now » marker (issue #535), tested without rendering: what has
// to be frozen here is how the clock is read — the browser's is the only one
// this page has, timezone included.

import { afterEach, describe, expect, it, vi } from 'vitest';
import { PauseAnimateurView, PosteAnimateurView } from '../../core/models';
import {
  JourPlanning,
  aujourdhuiLocal,
  duringTheEvent,
  isPasse,
  repereMaintenant,
} from './espace-maintenant';

function poste(overrides: Partial<PosteAnimateurView> = {}): PosteAnimateurView {
  return {
    creneauId: 1,
    date: '2026-07-11',
    heureDebut: '10:00:00',
    heureFin: '12:00:00',
    standId: 'ninja',
    standNom: 'Ninja',
    coequipiers: [],
    emplacementNom: null,
    emplacementLatitude: null,
    emplacementLongitude: null,
    ...overrides,
  };
}

function pause(overrides: Partial<PauseAnimateurView> = {}): PauseAnimateurView {
  return {
    date: '2026-07-11',
    debut: '18:40:00',
    fin: '19:00:00',
    heureLimite: '19:00:00',
    dureeMinutes: 20,
    standId: 'ninja',
    standNom: 'Ninja',
    relaisDisponible: true,
    emplacementNom: null,
    emplacementLatitude: null,
    emplacementLongitude: null,
    ...overrides,
  };
}

function jour(date: string, postes: PosteAnimateurView[], pauses: PauseAnimateurView[] = []) {
  return { date, postes, repos: postes.length === 0, pauses } satisfies JourPlanning;
}

/** Local time, never UTC: that difference is what this module exists for. */
function at(annee: number, mois: number, jourDuMois: number, heure: number, minute = 0): Date {
  return new Date(annee, mois - 1, jourDuMois, heure, minute);
}

describe('repereMaintenant', () => {
  const vendredi = jour('2026-07-10', [poste({ date: '2026-07-10', standNom: 'Cirque' })]);
  const samedi = jour(
    '2026-07-11',
    [
      poste({ heureDebut: '10:00:00', heureFin: '12:00:00' }),
      poste({ creneauId: 2, heureDebut: '14:00:00', heureFin: '18:00:00', standNom: 'Molkky' }),
    ],
    [pause()],
  );
  const dimanche = jour('2026-07-12', [
    poste({ creneauId: 3, date: '2026-07-12', standNom: 'Kubb' }),
  ]);
  const jours = [vendredi, samedi, dimanche];

  it('names the seat being held when there is one', () => {
    const repere = repereMaintenant(jours, at(2026, 7, 11, 15, 30))!;

    expect(repere.enCours?.standNom).toBe('Molkky');
    // The next one stays the one after: the head block shows one OR the other.
    expect(repere.prochain?.standNom).toBe('Kubb');
    expect(repere.aujourdhui).toBe('2026-07-11');
  });

  it('names the next seat when standing between two of them', () => {
    const repere = repereMaintenant(jours, at(2026, 7, 11, 13, 0))!;

    expect(repere.enCours).toBeNull();
    expect(repere.prochain?.standNom).toBe('Molkky');
  });

  it('never looks back: yesterday is neither in progress nor to come', () => {
    const repere = repereMaintenant(jours, at(2026, 7, 11, 9, 0))!;

    expect(repere.enCours).toBeNull();
    expect(repere.prochain?.standNom).toBe('Ninja');
  });

  it('says « rest » when today holds no assignment', () => {
    const repere = repereMaintenant([samedi, jour('2026-07-12', [])], at(2026, 7, 12, 11, 0))!;

    expect(repere.reposAujourdhui).toBe(true);
  });

  it("carries today's breaks and no others", () => {
    const repere = repereMaintenant(jours, at(2026, 7, 11, 15, 0))!;

    expect(repere.pausesDuJour).toHaveLength(1);
    expect(repereMaintenant(jours, at(2026, 7, 12, 15, 0))!.pausesDuJour).toHaveLength(0);
  });

  it('drops a break whose window has closed — the day card still lists it', () => {
    // « Pause à prendre au plus tard à 19:00 » says nothing at 19:30; this
    // block answers « what now », and the break of 18:40–19:00 is behind.
    expect(repereMaintenant(jours, at(2026, 7, 11, 18, 45))!.pausesDuJour).toHaveLength(1);
    expect(repereMaintenant(jours, at(2026, 7, 11, 19, 30))!.pausesDuJour).toHaveLength(0);
  });

  it('stays silent outside the event, before it and after it', () => {
    expect(repereMaintenant(jours, at(2026, 7, 9, 12, 0))).toBeNull();
    expect(repereMaintenant(jours, at(2026, 7, 13, 12, 0))).toBeNull();
    expect(repereMaintenant([], at(2026, 7, 11, 12, 0))).toBeNull();
  });

  describe('a night shift, which is dated on the evening that opens it', () => {
    const nuit = [
      jour('2026-07-11', [poste({ heureDebut: '22:00:00', heureFin: '02:00:00' })]),
      dimanche,
    ];

    it('holds it before midnight', () => {
      // Reading the « 02:00 » end as an hour of the same day would drop the
      // shift off the screen of the very person working it.
      expect(repereMaintenant(nuit, at(2026, 7, 11, 23, 0))!.enCours?.standNom).toBe('Ninja');
      expect(repereMaintenant(nuit, at(2026, 7, 11, 23, 59))!.enCours?.standNom).toBe('Ninja');
    });

    it('still holds it after midnight, on the day it is being worked', () => {
      // 00:30 on the 12th: the seat is dated the 11th and the person is on it.
      const repere = repereMaintenant(nuit, at(2026, 7, 12, 0, 30))!;

      expect(repere.enCours?.standNom).toBe('Ninja');
      expect(repere.aujourdhui).toBe('2026-07-12');
      // And its day stays unfolded: folding away the day of the shift
      // somebody is working reads as a planning that lost a day.
      expect(repere.jourPlancher).toBe('2026-07-11');
      expect(isPasse(nuit[0], repere.jourPlancher)).toBe(false);
      // The next seat is the one of today, not the one being held.
      expect(repere.prochain?.standNom).toBe('Kubb');
    });

    it('lets it go once it is over, and folds its day back', () => {
      const repere = repereMaintenant(nuit, at(2026, 7, 12, 2, 0))!;

      expect(repere.enCours).toBeNull();
      expect(repere.jourPlancher).toBe('2026-07-12');
      expect(isPasse(nuit[0], repere.jourPlancher)).toBe(true);
    });

    it('does not reach back two days: only the night just passed can still run', () => {
      const nuitOubliee = [
        jour('2026-07-10', [
          poste({ date: '2026-07-10', heureDebut: '22:00:00', heureFin: '02:00:00' }),
        ]),
        dimanche,
      ];

      expect(repereMaintenant(nuitOubliee, at(2026, 7, 12, 0, 30))!.enCours).toBeNull();
    });
  });

  it('has nothing left to announce once the last seat has started', () => {
    const repere = repereMaintenant(jours, at(2026, 7, 12, 11, 0))!;

    expect(repere.enCours?.standNom).toBe('Kubb');
    expect(repere.prochain).toBeNull();
  });
});

describe('aujourdhuiLocal', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  /**
   * The case that matters, and this module's reason to exist: a local time
   * whose UTC date is another day. `toISOString()` would answer the day before
   * (or after) here, and the day being lived would be folded away as elapsed
   * on the phone of the person living it.
   */
  it.each([
    // Kiritimati is UTC+14: 00:30 there is still the day before in UTC.
    ['Pacific/Kiritimati', 2026, 7, 11, 0, 30, '2026-07-11'],
    // Honolulu is UTC-10: 23:30 there is already the next day in UTC.
    ['Pacific/Honolulu', 2026, 7, 11, 23, 30, '2026-07-11'],
  ])(
    "answers in the machine's timezone (%s), never in UTC",
    (zone, annee, mois, jourDuMois, heure, minute, attendu) => {
      vi.stubEnv('TZ', zone as string);
      const maintenant = at(
        annee as number,
        mois as number,
        jourDuMois as number,
        heure as number,
        minute as number,
      );

      // Precondition of the test: were the timezone ignored, both dates would
      // coincide and the assertion below would prove nothing.
      expect(maintenant.toISOString().slice(0, 10)).not.toBe(attendu);
      expect(aujourdhuiLocal(maintenant)).toBe(attendu);
    },
  );
});

describe('duringTheEvent / isPasse', () => {
  const jours = [
    jour('2026-07-10', [poste({ date: '2026-07-10' })]),
    jour('2026-07-12', [poste()]),
  ];

  it('covers both bounds, and the gap between two days', () => {
    expect(duringTheEvent(jours, '2026-07-10')).toBe(true);
    expect(duringTheEvent(jours, '2026-07-11')).toBe(true);
    expect(duringTheEvent(jours, '2026-07-12')).toBe(true);
    expect(duringTheEvent(jours, '2026-07-09')).toBe(false);
    expect(duringTheEvent(jours, '2026-07-13')).toBe(false);
  });

  it('folds away only what is strictly before today', () => {
    expect(isPasse(jours[0], '2026-07-12')).toBe(true);
    expect(isPasse(jours[1], '2026-07-12')).toBe(false);
    // A day with no date (a seat on no dated timeslot) is never folded away.
    expect(isPasse(jour('', [poste({ date: null })]), '2026-07-12')).toBe(false);
  });
});
