import { describe, expect, it } from 'vitest';
import { Creneau, PosteAffectation } from '../../core/models';
import {
  requestedKey,
  dayKey,
  jourDemande,
  jourInconnu,
  planningDays,
  readAxe,
  readRendu,
  readView,
  JOURNEE_VIEWS,
  viewParams,
  tabOf,
} from './journee';

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

function poste(id: string, creneau: Creneau | null): PosteAffectation {
  return { id, stand: null, creneau, animateur: null };
}

describe('planningDays', () => {
  it('lists each day once, in order, keyed by its date', () => {
    const jours = planningDays([
      poste('p2', creneau({ id: 2, jour: 2, date: '2026-08-02' })),
      poste('p1', creneau({ id: 1 })),
      poste('p3', creneau({ id: 3 })),
      poste('p4', null),
    ]);

    expect(jours.map((jour) => jour.key)).toEqual(['2026-08-01', '2026-08-02']);
    expect(jours[0].title).toBe('Jour 1 — 2026-08-01');
  });

  it('keys a day without a date by its number, so it can still be selected', () => {
    const jours = planningDays([poste('p1', creneau({ id: 1, jour: 3, date: undefined }))]);

    expect(jours[0].key).toBe('J3');
    expect(jours[0].title).toBe('Jour 3');
    expect(dayKey(3, null)).toBe('J3');
  });
});

describe('readView', () => {
  it('names one of the renderings, the rail otherwise', () => {
    expect(readView('rail')).toBe('rail');
    expect(readView('jour')).toBe('rail');
    expect(readView('jour', 'tableau')).toBe('calendrier');
    expect(readView('jour', 'rail')).toBe('rail');
    expect(readView('calendrier')).toBe('calendrier');
    expect(readView('carte')).toBe('carte');
    // The breaks and the changes left the page: their addresses are redirected before it reads them.
    expect(readView('pauses')).toBe('rail');
    expect(readView('changements')).toBe('rail');
    expect(readView('libres')).toBe('rail');
    expect(readView(null)).toBe('rail');
    expect(readView(null, 'tableau')).toBe('calendrier');
  });

  it('reads back every rendering from the params it writes, the rail from none', () => {
    for (const view of JOURNEE_VIEWS) {
      const { vue, mode } = viewParams(view);
      expect(readView(vue, mode)).toBe(view);
    }
    expect(viewParams('rail')).toEqual({ vue: null, mode: null });
    expect(viewParams('calendrier')).toEqual({ vue: 'jour', mode: 'tableau' });
    expect(JOURNEE_VIEWS.map(tabOf)).toEqual(['jour', 'jour', 'carte']);
  });
});

describe('readAxe', () => {
  it('names a grid by `vue`, or by the 3.x `axe`, the day otherwise', () => {
    expect(readAxe('stand')).toBe('stand');
    expect(readAxe('personne')).toBe('personne');
    expect(readAxe(null, 'stand')).toBe('stand');
    expect(readAxe('synthese', 'personne')).toBe('personne');
    expect(readAxe('carte', 'typologie')).toBe('jour');
    expect(readAxe('animateur')).toBe('jour');
    expect(readAxe(null)).toBe('jour');
  });

  it('reads the rendering of a grid from `rendu`, or from the 3.x `vue` of the axis', () => {
    const query = (params: Record<string, string>) => new URLSearchParams(params);
    expect(readRendu(query({ vue: 'stand', rendu: 'treemap' }))).toBe('treemap');
    expect(readRendu(query({ axe: 'personne', vue: 'frise' }))).toBe('frise');
    expect(readRendu(query({ vue: 'personne' }))).toBeNull();
    expect(readRendu(query({}))).toBeNull();
  });
});

describe('the day a link asks for', () => {
  const jours = planningDays([
    poste('p1', creneau({ id: 1 })),
    poste('p2', creneau({ id: 2, jour: 2, date: '2026-08-02' })),
  ]);

  it('reads the date first, then the day number of the four former screens', () => {
    expect(requestedKey('2026-08-02', null)).toBe('2026-08-02');
    expect(requestedKey(null, '2')).toBe('J2');
    expect(requestedKey(null, '2026-08-02')).toBe('2026-08-02');
    expect(requestedKey(null, 'deux')).toBeNull();
    expect(requestedKey(null, null)).toBeNull();
  });

  it('resolves a day number against a dated day, and nothing against a day gone', () => {
    expect(jourDemande(jours, 'J2')?.date).toBe('2026-08-02');
    expect(jourDemande(jours, '2026-08-01')?.jour).toBe(1);
    expect(jourDemande(jours, 'J9')).toBeNull();
    expect(jourDemande(jours, null)).toBeNull();
  });

  it('says so when the day asked for is none of the plan, and only then', () => {
    expect(jourInconnu(jours, '2026-10-05')).toBe(
      "Le 05/10/2026 n'est pas un jour du planning : voici le premier jour.",
    );
    // Another edition's bookmark: same day and month as a day of this one.
    expect(jourInconnu(jours, '2025-08-02')).toBe(
      "Le 02/08/2025 n'est pas un jour du planning : voici le premier jour.",
    );
    // An impossible date is shown as typed, never rolled over to another day.
    expect(jourInconnu(jours, '2026-02-30')).toBe(
      "Le 2026-02-30 n'est pas un jour du planning : voici le premier jour.",
    );
    expect(jourInconnu(jours, 'J9')).toBe(
      "Le J9 n'est pas un jour du planning : voici le premier jour.",
    );
    expect(jourInconnu(jours, '2026-08-02')).toBeNull();
    expect(jourInconnu(jours, 'J2')).toBeNull();
    expect(jourInconnu(jours, null)).toBeNull();
    // The plan not read yet: nothing is known to be missing.
    expect(jourInconnu([], '2026-10-05')).toBeNull();
  });
});
