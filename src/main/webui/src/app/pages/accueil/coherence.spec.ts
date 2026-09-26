// The coherence panel's wording and links, on hand-built reports: every line
// leads to the fiche or screen that corrects it, and every link is a route
// the application declares.

import { Route } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';
import { CoherenceIssue, CoherenceReport, CoherenceSubject } from '../../core/models';
import { coherenceGroups, coherenceLink, familyCounts, mergeIdentical } from './coherence';

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
      route: '/creneaux',
      queryParams: { edit: '7' },
    });
    expect(coherenceLink(issue({ objet: 'ANIMATEUR', objetId: 'A1' }))).toMatchObject({
      route: '/animateurs',
      queryParams: { edit: 'A1' },
    });
    expect(coherenceLink(issue({ objet: 'CONTRAINTE_AD_HOC', objetId: 'C1' }))).toMatchObject({
      route: '/consignes-solveur',
      queryParams: { onglet: 'ajustements', edit: 'C1' },
    });
  });

  it('sends a line about the edition as a whole to the screen of its family', () => {
    expect(
      coherenceLink(issue({ famille: 'CAPACITE', objet: 'EDITION', objetId: null })),
    ).toMatchObject({ route: '/diagnostic', queryParams: { onglet: 'besoin' } });
    expect(
      coherenceLink(issue({ famille: 'CRENEAUX', objet: 'EDITION', objetId: null })).route,
    ).toBe('/creneaux');
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

describe('coherenceGroups', () => {
  it('keeps the families holding a line, in the order the server gave, with their counts', () => {
    const report: CoherenceReport = {
      bloquants: 1,
      aVerifier: 1,
      informations: 1,
      familles: [
        { famille: 'STANDS', bloquants: 0, aVerifier: 1, informations: 1 },
        { famille: 'CRENEAUX', bloquants: 0, aVerifier: 0, informations: 0 },
        { famille: 'AJUSTEMENTS', bloquants: 1, aVerifier: 0, informations: 0 },
      ],
      anomalies: [
        issue(),
        issue({ gravite: 'INFORMATION', code: 'REGLE_MASQUEE' }),
        issue({ famille: 'AJUSTEMENTS', gravite: 'BLOQUANT', objet: 'CONTRAINTE_AD_HOC' }),
      ],
    };

    const groups = coherenceGroups(report);

    expect(groups.map((group) => group.titre)).toEqual([
      'Stands et ouvertures',
      'Ajustements manuels',
    ]);
    expect(groups[0].comptage).toBe('1 à vérifier · 1 pour information');
    expect(groups[0].lignes.map((line) => line.severityLabel)).toEqual([
      'À vérifier',
      'Pour information',
    ]);
    expect(groups[1].comptage).toBe('1 bloquant(s)');
  });

  it('says nothing for a family at zero', () => {
    expect(familyCounts({ famille: 'STANDS', bloquants: 0, aVerifier: 0, informations: 0 })).toBe(
      '',
    );
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
    const report: CoherenceReport = {
      bloquants: 0,
      aVerifier: 2,
      informations: 0,
      familles: [{ famille: 'CRENEAUX', bloquants: 0, aVerifier: 2, informations: 0 }],
      anomalies: [gap('2026-09-01'), gap('2026-09-02')],
    };
    const [groupe] = coherenceGroups(report);
    expect(groupe.lignes).toHaveLength(1);
    expect(groupe.lignes[0].sentence).toBe('2 jours : rien entre 12:00 et 13:00.');
    expect(groupe.lignes[0].count).toBe(2);
  });
});
