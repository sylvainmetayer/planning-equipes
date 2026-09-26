// The list's order, shared by the list and the fiche's « précédent / suivant »:
// read from and written back to the same URL keys, filtered then sorted.

import { convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { Animateur, ConfirmationView } from '../../core/models';
import {
  WHOLE_ROSTER,
  firstDay,
  neighbours,
  needsConfirmations,
  needsSeats,
  readRosterView,
  rosterLinkParams,
  rosterOrder,
  rosterViewParams,
} from './animateur-roster';

function person(id: string, prenom: string, partial: Partial<Animateur> = {}): Animateur {
  return {
    id,
    prenom,
    nom: 'X',
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
    ...partial,
  };
}

const context = {
  confirmations: new Map<string, ConfirmationView>(),
  lastPublishedAt: null,
  typologies: new Map([['JEU', 'Jeux de plateau']]),
  now: new Date('2026-07-01T12:00:00Z'),
  premierJour: '2026-07-10',
  postes: null,
};

describe('animateur roster', () => {
  it('reads the list view from the URL and writes it back, a default writing nothing', () => {
    const view = readRosterView(
      convertToParamMap({ q: 'dur', sort: 'nom', dir: 'desc', typologie: 'JEU', silence: '4' }),
    );
    expect(view).toMatchObject({
      filtre: 'dur',
      sort: { active: 'nom', direction: 'desc' },
      typologie: 'JEU',
      accuses: 'silence',
      silenceJours: 4,
    });
    expect(rosterLinkParams(view)).toEqual({
      q: 'dur',
      sort: 'nom',
      dir: 'desc',
      typologie: 'JEU',
      silence: '4',
    });
    expect(rosterLinkParams(WHOLE_ROSTER)).toEqual({});
    expect(rosterViewParams(WHOLE_ROSTER)['q']).toBeNull();
  });

  it('filters then sorts, the game category matched by its label too', () => {
    const roster = [
      person('a1', 'Zoé', { competences: { JEU: 'AUTONOME' } }),
      person('a2', 'Alice'),
      person('a3', 'Bruno', { competences: { JEU: 'REFERENT' } }),
    ];
    const view = {
      ...WHOLE_ROSTER,
      filtre: 'plateau',
      sort: { active: 'nom', direction: 'asc' as const },
    };

    expect(rosterOrder(roster, view, context).map((each) => each.id)).toEqual(['a3', 'a1']);
  });

  it("keeps the minors on the edition's first day and the managers, a view the URL carries", () => {
    const roster = [
      person('a10', 'Zoé', { dateNaissance: '2008-07-15', manager: true }),
      person('a2', 'Alice', { dateNaissance: '2008-07-05' }),
      person('a1', 'Bruno', { manager: true }),
    ];

    // Seventeen on the tenth, eighteen five days before: only one minor.
    const mineurs = readRosterView(convertToParamMap({ mineurs: '1' }));
    expect(rosterOrder(roster, mineurs, context).map((each) => each.id)).toEqual(['a10']);
    const managers = readRosterView(convertToParamMap({ manager: '1' }));
    expect(rosterLinkParams(managers)).toEqual({ manager: '1' });
    // Unsorted, the ids come in their natural order: a2 before a10.
    expect(rosterOrder(roster, managers, context).map((each) => each.id)).toEqual(['a1', 'a10']);
    expect(rosterOrder(roster, WHOLE_ROSTER, context).map((each) => each.id)).toEqual([
      'a1',
      'a2',
      'a10',
    ]);
  });

  it('sorts on the seats of the plan, which it then needs, and on the age', () => {
    const roster = [person('a1', 'A'), person('a2', 'B', { dateNaissance: '2000-01-01' })];
    const bySeats = { ...WHOLE_ROSTER, sort: { active: 'postes', direction: 'desc' as const } };
    const withSeats = { ...context, postes: new Map([['a2', 3]]) };

    expect(needsSeats(bySeats)).toBe(true);
    expect(needsSeats(WHOLE_ROSTER)).toBe(false);
    expect(rosterOrder(roster, bySeats, withSeats).map((each) => each.id)).toEqual(['a2', 'a1']);
    const byAge = { ...WHOLE_ROSTER, sort: { active: 'age', direction: 'asc' as const } };
    expect(rosterOrder(roster, byAge, context).map((each) => each.id)).toEqual(['a2', 'a1']);
    expect(firstDay(['2026-07-12', '2026-07-10'])).toBe('2026-07-10');
    expect(firstDay([])).toBeNull();
  });

  it('names the neighbours of a person, and nobody at either end or outside the list', () => {
    const ordered = [person('a1', 'A'), person('a2', 'B'), person('a3', 'C')];

    expect(neighbours(ordered, 'a2')).toMatchObject({
      previous: { id: 'a1' },
      next: { id: 'a3' },
      rank: 1,
    });
    expect(neighbours(ordered, 'a1').previous).toBeNull();
    expect(neighbours(ordered, 'a3').next).toBeNull();
    expect(neighbours(ordered, 'zz')).toEqual({ previous: null, next: null, rank: -1 });
  });

  it('asks for the acknowledgements only when the view filters or sorts on them', () => {
    expect(needsConfirmations(WHOLE_ROSTER)).toBe(false);
    expect(needsConfirmations({ ...WHOLE_ROSTER, accuses: 'jamais' })).toBe(true);
    expect(
      needsConfirmations({ ...WHOLE_ROSTER, sort: { active: 'confirmation', direction: 'asc' } }),
    ).toBe(true);
  });
});
