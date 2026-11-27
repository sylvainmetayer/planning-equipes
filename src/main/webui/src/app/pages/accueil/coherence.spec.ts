// The coherence panel's wording and links, on hand-built reports: every line
// leads to the fiche or screen that corrects it, and every link is a route
// the application declares.

import { Route } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';
import { CoherenceIssue, CoherenceSubject } from '../../core/models';
import { coherenceLines, coherenceLink, mergeIdentical } from './coherence';

function allRoutes(list: Route[]): Route[] {
  return list.flatMap((route) => [route, ...allRoutes(route.children ?? [])]);
}

function issue(partial: Partial<CoherenceIssue> = {}): CoherenceIssue {
  return {
    famille: 'STANDS',
    gravite: 'A_VERIFIER',
    code: 'FENETRE_SANS_EFFET',
    message: 'Message',
    objet: 'STAND',
    objetId: 'S1',
    date: null,
    ...partial,
  };
}

describe('coherenceLink', () => {
  it('opens the fiche a line names, for editing', () => {
    expect(coherenceLink(issue())).toMatchObject({ route: '/stands', queryParams: { edit: 'S1' } });
    expect(coherenceLink(issue({ objet: 'CRENEAU', objetId: '7' }))).toMatchObject({
      route: '/calendrier',
      queryParams: { edit: '7' },
    });
    expect(coherenceLink(issue({ objet: 'ANIMATEUR', objetId: 'A1' }))).toMatchObject({
      route: '/animateurs',
      queryParams: { edit: 'A1' },
    });
    expect(coherenceLink(issue({ objet: 'CONTRAINTE_AD_HOC', objetId: 'C1' }))).toMatchObject({
      route: '/regles',
      queryParams: { onglet: 'ajustements', edit: 'C1' },
    });
  });

  it('sends a line about the edition as a whole to the screen of its family', () => {
    expect(
      coherenceLink(issue({ famille: 'CAPACITE', objet: 'EDITION', objetId: null })),
    ).toMatchObject({ route: '/diagnostic', queryParams: { onglet: 'besoin' } });
    expect(
      coherenceLink(issue({ famille: 'CRENEAUX', objet: 'EDITION', objetId: null })).route,
    ).toBe('/calendrier');
  });

  it('leads the two counting lines to their screen already filtered', () => {
    expect(
      coherenceLink(
        issue({ famille: 'ANIMATEURS', objet: 'EDITION', code: 'ANIMATEURS_SANS_TELEPHONE' }),
      ),
    ).toMatchObject({ route: '/animateurs', queryParams: { telephone: 'aucun' } });
    expect(
      coherenceLink(issue({ objet: 'EDITION', code: 'STANDS_SANS_EMPLACEMENT' })),
    ).toMatchObject({ route: '/stands', queryParams: { emplacement: 'aucun' } });
  });

  it('only ever links to a route the application declares', () => {
    const declared = new Set(
      allRoutes(routes)
        .map((route) => route.path)
        .filter((path): path is string => path !== undefined),
    );
    const subjects: CoherenceSubject[] = [
      'STAND',
      'CRENEAU',
      'ANIMATEUR',
      'CONTRAINTE_AD_HOC',
      'VERROUILLAGE',
      'TYPOLOGIE',
      'EDITION',
    ];
    for (const family of ['STANDS', 'CRENEAUX', 'ANIMATEURS', 'AJUSTEMENTS', 'CAPACITE'] as const) {
      for (const objet of subjects) {
        const route = coherenceLink(issue({ famille: family, objet })).route;
        expect(declared, route).toContain(route.slice(1));
      }
    }
  });
});

describe('mergeIdentical', () => {
  const gap = (date: string, heures = '12:00 et 13:00') =>
    issue({
      famille: 'CRENEAUX',
      code: 'TROU_DANS_LA_JOURNEE',
      objet: 'EDITION',
      objetId: null,
      date,
      message: `${date} : rien entre ${heures}.`,
    });

  it('merges the same anomaly on several days into one line with its count', () => {
    const merged = mergeIdentical([gap('2026-09-01'), gap('2026-09-02'), gap('2026-09-03')]);
    expect(merged).toHaveLength(1);
    expect(merged[0]).toMatchObject({
      count: 3,
      message: 'rien entre 12:00 et 13:00.',
      dates: ['2026-09-01', '2026-09-02', '2026-09-03'],
    });
  });

  /** The key is all a line says and links to: nothing different is ever folded into a count. */
  it('keeps apart what differs by its message, its object or its severity', () => {
    expect(mergeIdentical([gap('2026-09-01'), gap('2026-09-02', '18:00 et 19:00')])).toHaveLength(
      2,
    );
    expect(mergeIdentical([issue({ objetId: 'S1' }), issue({ objetId: 'S2' })])).toHaveLength(2);
    expect(mergeIdentical([issue(), issue({ gravite: 'BLOQUANT' })])).toHaveLength(2);
  });

  it('writes the merged line with its count of days', () => {
    const lignes = coherenceLines([gap('2026-09-01'), gap('2026-09-02')]);
    expect(lignes).toHaveLength(1);
    expect(lignes[0].sentence).toBe('2 jours : rien entre 12:00 et 13:00.');
    expect(lignes[0].count).toBe(2);
  });
});
