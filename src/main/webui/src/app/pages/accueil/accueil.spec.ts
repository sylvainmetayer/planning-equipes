// The wording and the links of the checklist, on hand-built states: what
// each line says, where it leads, and the summary above the list. The states
// themselves are decided server-side (`EtatEditionService`), not here.

import { Route } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';
import { EtatEdition } from '../../core/models';
import { buildLignes, buildToday, statutIcon, statutLabel, summarizeLignes } from './accueil';

/** The routes of the application, children included. */
function allRoutes(liste: Route[]): Route[] {
  return liste.flatMap((route) => [route, ...allRoutes(route.children ?? [])]);
}

/** An edition with nothing entered: every line to do, as the server answers it. */
function etatVide(partial: Partial<EtatEdition> = {}): EtatEdition {
  return {
    editionId: 'DEFAUT',
    editionNom: 'Édition par défaut',
    referentiels: { stands: 0, animateurs: 0, creneaux: 0, statut: 'A_FAIRE' },
    // Nothing entered, nothing incoherent: the Référentiels line carries the « à faire ».
    coherence: { bloquants: 0, aVerifier: 0, informations: 0, statut: 'FAIT' },
    collecte: {
      ouverte: false,
      declarationsEnAttente: 0,
      declarationsTraitees: 0,
      statut: 'A_FAIRE',
    },
    ouvertures: {
      anomalies: 0,
      fenetresSansEffet: 0,
      standsJamaisOuverts: 0,
      informations: 0,
      statut: 'A_FAIRE',
    },
    besoin: { animateurs: 0, minimum: 0, manque: 0, statut: 'A_FAIRE' },
    resolution: {
      resolue: false,
      resoluLe: null,
      score: null,
      scoreHorsPlancher: null,
      faisable: null,
      dataStale: false,
      solveEnCours: false,
      statut: 'A_FAIRE',
    },
    problemes: { bloquants: 0, avertissements: 0, reglesAnalysees: true, statut: 'A_FAIRE' },
    relecture: { journees: 0, journeesValidees: 0, statut: 'A_FAIRE' },
    publication: {
      jamaisPublie: true,
      dernierePublicationLe: null,
      personnesAPrevenir: 0,
      statut: 'A_FAIRE',
    },
    confirmations: { confirmes: 0, relances: 0, silencieux: 0, statut: 'A_FAIRE' },
    foire: { ouverte: true, demandesEnAttente: 0, statut: 'A_FAIRE' },
    aTraiter: {
      aujourdhui: '2026-07-10',
      declarationsEnAttente: 0,
      plusAncienneDeclaration: null,
      echangesAArbitrer: 0,
      echangesEnAlerte: 0,
      seuilAncienneteJours: 7,
      plusAncienEchange: null,
      horizonJours: 7,
      journeesNonRelues: [],
      silencieuxARelancer: 0,
      silenceJours: 3,
      donneesModifiees: false,
      personnesAPrevenir: 0,
    },
    ...partial,
  };
}

/** Solved, published, acknowledged: every line done. */
function etatComplet(partial: Partial<EtatEdition> = {}): EtatEdition {
  return etatVide({
    referentiels: { stands: 12, animateurs: 40, creneaux: 30, statut: 'FAIT' },
    collecte: { ouverte: false, declarationsEnAttente: 0, declarationsTraitees: 8, statut: 'FAIT' },
    ouvertures: {
      anomalies: 0,
      fenetresSansEffet: 0,
      standsJamaisOuverts: 0,
      informations: 0,
      statut: 'FAIT',
    },
    besoin: { animateurs: 40, minimum: 32, manque: 0, statut: 'FAIT' },
    resolution: {
      resolue: true,
      resoluLe: '2026-05-01T10:00:00Z',
      score: '0hard/0medium/-120soft',
      scoreHorsPlancher: '0hard/0medium/-20soft',
      faisable: true,
      dataStale: false,
      solveEnCours: false,
      statut: 'FAIT',
    },
    problemes: { bloquants: 0, avertissements: 0, reglesAnalysees: true, statut: 'FAIT' },
    relecture: { journees: 12, journeesValidees: 12, statut: 'FAIT' },
    publication: {
      jamaisPublie: false,
      dernierePublicationLe: '2026-05-01T11:00:00Z',
      personnesAPrevenir: 0,
      statut: 'FAIT',
    },
    confirmations: { confirmes: 40, relances: 0, silencieux: 0, statut: 'FAIT' },
    foire: { ouverte: true, demandesEnAttente: 0, statut: 'FAIT' },
    ...partial,
  });
}

describe('buildLignes', () => {
  it('lists the eleven steps of the cycle in the order of the guide', () => {
    expect(buildLignes(etatVide()).map((ligne) => ligne.id)).toEqual([
      'referentiels',
      'coherence',
      'collecte',
      'ouvertures',
      'besoin',
      'resolution',
      'problemes',
      'relecture',
      'publication',
      'confirmations',
      'foire',
    ]);
  });

  it('carries the state the server decided, line by line', () => {
    expect(buildLignes(etatVide()).map((ligne) => ligne.statut)).toEqual([
      'A_FAIRE',
      'FAIT',
      ...new Array<string>(9).fill('A_FAIRE'),
    ]);
    expect(buildLignes(etatComplet()).map((ligne) => ligne.statut)).toEqual(
      new Array<string>(11).fill('FAIT'),
    );
  });

  it('leads an empty edition to the first referential to enter, the créneaux', () => {
    const [referentiels] = buildLignes(etatVide());
    expect(referentiels.lien!.route).toBe('/creneaux');
    expect(referentiels.detail).toBe('0 stands · 0 animateurs · 0 créneaux');

    const withoutStand = etatVide({
      referentiels: { stands: 0, animateurs: 0, creneaux: 4, statut: 'A_FAIRE' },
    });
    expect(buildLignes(withoutStand)[0].lien!.route).toBe('/stands');
    const withoutAnimateur = etatVide({
      referentiels: { stands: 3, animateurs: 0, creneaux: 4, statut: 'A_FAIRE' },
    });
    expect(buildLignes(withoutAnimateur)[0].lien!.route).toBe('/animateurs');
  });

  it('links every line to the screen that moves it, with the tab or the filter it needs', () => {
    const liens = new Map(buildLignes(etatComplet()).map((ligne) => [ligne.id, ligne.lien]));
    expect(liens.get('collecte')?.route).toBe('/disponibilites');
    expect(liens.get('ouvertures')?.route).toBe('/ouvertures');
    expect(liens.get('besoin')).toMatchObject({
      route: '/diagnostic',
      queryParams: { onglet: 'besoin' },
    });
    expect(liens.get('resolution')?.route).toBe('/solveur');
    expect(liens.get('problemes')).toMatchObject({
      route: '/diagnostic',
      queryParams: { onglet: 'problemes' },
    });
    expect(liens.get('publication')?.route).toBe('/publication');
    expect(liens.get('confirmations')).toEqual({
      route: '/animateurs',
      queryParams: undefined,
      libelle: 'Voir les animateurs',
    });
    expect(liens.get('foire')?.route).toBe('/echanges');
  });

  // Every step of the checklist links somewhere, and the checklist is the
  // first screen an organiser opens: a route that stopped existing sends them
  // to the not-found page on the one page that is supposed to orient them.
  // Checked against the router's own table rather than a hand-written list —
  // the publication line pointed at /solveur long after the publication moved
  // (issue #320), and nothing said so.
  it('links every step to a route the application actually declares', () => {
    const declarees = new Set(
      allRoutes(routes)
        .map((route) => route.path)
        .filter((path): path is string => path !== undefined && !path.includes('*')),
    );

    const cibles = [etatVide(), etatComplet()]
      .flatMap((etat) => buildLignes(etat))
      .flatMap((ligne) => (ligne.lien ? [ligne.lien] : []));

    expect(cibles.length).toBeGreaterThan(0);
    for (const lien of cibles) {
      expect(lien.route, `lien « ${lien.libelle} »`).toMatch(/^\//);
      expect(declarees, `route ${lien.route}`).toContain(lien.route.slice(1));
    }
  });

  it('filters the animateurs on the silent ones as soon as somebody has not answered', () => {
    const etat = etatComplet({
      confirmations: { confirmes: 30, relances: 4, silencieux: 6, statut: 'ATTENTION' },
    });
    const confirmations = buildLignes(etat).find((ligne) => ligne.id === 'confirmations')!;
    expect(confirmations.lien).toEqual({
      route: '/animateurs',
      queryParams: { confirmation: 'jamais' },
      libelle: "Voir qui n'a pas répondu",
    });
    expect(confirmations.detail).toBe('30 confirmé(s) · 4 relancé(s) · 6 silencieux');
  });

  it('says the solve is running rather than describing a plan about to be replaced', () => {
    const etat = etatComplet({
      resolution: { ...etatComplet().resolution, solveEnCours: true, statut: 'ATTENTION' },
    });
    const lignes = buildLignes(etat);
    const resolution = lignes.find((ligne) => ligne.id === 'resolution')!;
    expect(resolution.statut).toBe('ATTENTION');
    expect(resolution.detail).toBe('Résolution en cours');
    // And the publication line waits with it: the server refuses to publish
    // during a solve, and the count would describe a plan about to be rewritten.
    expect(lignes.find((ligne) => ligne.id === 'publication')!.detail).toContain(
      'Résolution en cours',
    );
  });

  // The rule analysis lives in memory: after a restart nothing has measured the
  // rules, and « aucun problème signalé » would acknowledge a measurement that
  // never ran.
  it('says the rules were not analysed rather than clearing them', () => {
    const etat = etatComplet({
      problemes: { bloquants: 0, avertissements: 0, reglesAnalysees: false, statut: 'ATTENTION' },
    });

    const problemes = buildLignes(etat).find((ligne) => ligne.id === 'problemes')!;
    expect(problemes.statut).toBe('ATTENTION');
    expect(problemes.detail).toContain('non analysées');
  });

  // « 0 bloquant(s) · 8 avertissement(s) » in the colour of a refusal read as a
  // failure: warnings alone are a report, and the server states them as such.
  it('reports warnings without a blocker rather than counting a zero', () => {
    const etat = etatComplet({
      problemes: { bloquants: 0, avertissements: 8, reglesAnalysees: true, statut: 'INFO' },
    });

    const problemes = buildLignes(etat).find((ligne) => ligne.id === 'problemes')!;
    expect(problemes.statut).toBe('INFO');
    expect(problemes.detail).toBe('8 avertissement(s), rien de bloquant');
  });

  it('reads the score, its floor-free twin, the broken hard rules and the stale data on one line', () => {
    const etat = etatComplet({
      resolution: {
        resolue: true,
        resoluLe: '2026-05-01T10:00:00Z',
        score: '-2hard/0medium/-120soft',
        scoreHorsPlancher: '-2hard/0medium/-20soft',
        faisable: false,
        dataStale: true,
        solveEnCours: false,
        statut: 'ATTENTION',
      },
    });
    const resolution = buildLignes(etat).find((ligne) => ligne.id === 'resolution')!;
    expect(resolution.detail).toContain('Résolue le ');
    expect(resolution.detail).toContain('score -2hard/0medium/-120soft');
    expect(resolution.detail).toContain('hors plancher -2hard/0medium/-20soft');
    expect(resolution.detail).toContain('règles dures en défaut');
    expect(resolution.detail).toContain('données modifiées depuis');
  });

  it('omits the floor-free score when it equals the score, and the score when there is no analysis', () => {
    const identiques = etatComplet({
      resolution: { ...etatComplet().resolution, scoreHorsPlancher: '0hard/0medium/-120soft' },
    });
    expect(buildLignes(identiques)[5].detail).not.toContain('hors plancher');

    const withoutAnalysis = etatComplet({
      resolution: {
        ...etatComplet().resolution,
        score: null,
        scoreHorsPlancher: null,
        faisable: null,
      },
    });
    expect(buildLignes(withoutAnalysis)[5].detail).not.toContain('score');
    expect(buildLignes(withoutAnalysis)[5].detail).toContain('Résolue le ');
  });

  it('counts what asks for attention on the lines that carry a figure', () => {
    const etat = etatComplet({
      collecte: {
        ouverte: true,
        declarationsEnAttente: 3,
        declarationsTraitees: 1,
        statut: 'ATTENTION',
      },
      ouvertures: {
        anomalies: 2,
        fenetresSansEffet: 0,
        standsJamaisOuverts: 1,
        informations: 0,
        statut: 'ATTENTION',
      },
      besoin: { animateurs: 20, minimum: 32, manque: 12, statut: 'ATTENTION' },
      problemes: { bloquants: 1, avertissements: 4, reglesAnalysees: true, statut: 'ATTENTION' },
      relecture: { journees: 12, journeesValidees: 3, statut: 'INFO' },
      publication: {
        jamaisPublie: false,
        dernierePublicationLe: '2026-05-01T11:00:00Z',
        personnesAPrevenir: 7,
        statut: 'ATTENTION',
      },
      foire: { ouverte: true, demandesEnAttente: 2, statut: 'ATTENTION' },
    });
    const details = new Map(buildLignes(etat).map((ligne) => [ligne.id, ligne.detail]));
    expect(details.get('collecte')).toBe('3 déclaration(s) à appliquer ou refuser');
    expect(details.get('ouvertures')).toBe('2 anomalie(s), 1 stand(s) jamais ouvert(s)');
    const horsGrille = etatComplet({
      ouvertures: {
        anomalies: 3,
        fenetresSansEffet: 2,
        standsJamaisOuverts: 0,
        informations: 0,
        statut: 'ATTENTION',
      },
    });
    expect(buildLignes(horsGrille).find((ligne) => ligne.id === 'ouvertures')!.detail).toBe(
      '3 anomalie(s), dont 2 fenêtre(s) hors de toute vacation, 0 stand(s) jamais ouvert(s)',
    );
    // Only how the rules are written: said for information, not as anomalies to fix.
    const informationOnly = etatComplet({
      ouvertures: {
        anomalies: 2,
        fenetresSansEffet: 0,
        standsJamaisOuverts: 0,
        informations: 2,
        statut: 'INFO',
      },
    });
    expect(buildLignes(informationOnly).find((ligne) => ligne.id === 'ouvertures')!.detail).toBe(
      "2 point(s) pour information : règles ou fenêtres d'horaires qui se recouvrent ou ne servent à rien",
    );
    expect(details.get('besoin')).toBe('20 animateurs pour un minimum de 32 : il en manque 12');
    expect(details.get('problemes')).toBe('1 bloquant(s) · 4 avertissement(s)');
    expect(details.get('relecture')).toBe('3 journée(s) relue(s) sur 12');
    expect(details.get('publication')).toBe('7 personne(s) à prévenir');
    expect(details.get('foire')).toBe('2 demande(s) en attente');
  });
});

describe('summarizeLignes', () => {
  it('counts the lines in each state', () => {
    const lignes = buildLignes(
      etatComplet({
        besoin: { animateurs: 20, minimum: 32, manque: 12, statut: 'ATTENTION' },
        foire: { ouverte: false, demandesEnAttente: 0, statut: 'A_FAIRE' },
      }),
    );
    expect(summarizeLignes(lignes)).toEqual({ faits: 9, attention: 1, info: 0, aFaire: 1 });
  });
});

describe('statut rendering', () => {
  it('gives each state a label and an icon of its own', () => {
    expect(statutLabel('A_FAIRE')).toBe('À faire');
    expect(statutLabel('ATTENTION')).toBe('À vérifier');
    expect(statutLabel('INFO')).toBe('Pour information');
    expect(statutLabel('FAIT')).toBe('Fait');
    expect(
      new Set([
        statutIcon('A_FAIRE'),
        statutIcon('ATTENTION'),
        statutIcon('INFO'),
        statutIcon('FAIT'),
      ]).size,
    ).toBe(4);
  });

  it('counts the coherence checklist and unfolds it in place rather than linking elsewhere', () => {
    const withIssues = etatComplet({
      coherence: { bloquants: 1, aVerifier: 3, informations: 2, statut: 'ATTENTION' },
    });
    const ligne = buildLignes(withIssues).find((each) => each.id === 'coherence')!;
    expect(ligne.titre).toBe('Cohérence du référentiel');
    expect(ligne.detail).toBe('1 bloquant(s) · 3 à vérifier · 2 pour information');
    expect(ligne.panneau).toBe('coherence');
    expect(ligne.lien).toBeUndefined();

    const withoutIssue = buildLignes(etatComplet()).find((each) => each.id === 'coherence')!;
    expect(withoutIssue.detail).toBe('Aucune anomalie dans ce qui est saisi');
    expect(withoutIssue.panneau).toBeUndefined();
  });
});

describe('buildToday', () => {
  const vide = etatComplet().aTraiter;

  it('says nothing when nothing waits, so the box is not drawn', () => {
    expect(buildToday(etatComplet())).toEqual([]);
  });

  it('lists each subject with a count, in a fixed order, each linking to its screen', () => {
    const items = buildToday(
      etatComplet({
        aTraiter: {
          ...vide,
          declarationsEnAttente: 2,
          plusAncienneDeclaration: '2026-07-01T08:00:00Z',
          echangesAArbitrer: 3,
          echangesEnAlerte: 1,
          journeesNonRelues: ['2026-07-10', '2026-07-13'],
          silencieuxARelancer: 4,
          donneesModifiees: true,
          personnesAPrevenir: 5,
        },
      }),
    );

    expect(items.map((item) => item.id)).toEqual([
      'declarations',
      'echanges',
      'relecture',
      'silencieux',
      'donnees',
      'prevenir',
    ]);
    const byId = new Map(items.map((item) => [item.id, item]));
    expect(byId.get('echanges')!.alerte).toBe(true);
    expect(byId.get('echanges')!.sentence).toBe(
      "3 demande(s) d'échange à arbitrer, dont 1 en attente depuis plus de 7 jour(s)",
    );
    expect(byId.get('declarations')!.alerte).toBe(false);
    expect(byId.get('relecture')!.sentence).toBe(
      '2 journée(s) à relire dans les 7 prochains jours : 10/07, 13/07',
    );
    // Each link opens its screen with the filter already applied.
    expect(byId.get('relecture')!.lien).toMatchObject({
      route: '/journee',
      queryParams: { date: '2026-07-10' },
    });
    // The never reminded only: the people the line counts, a reminded one left out.
    expect(byId.get('silencieux')!.lien).toMatchObject({
      route: '/animateurs',
      queryParams: { silence: '3', relance: 'jamais' },
    });
    expect(byId.get('donnees')!.lien.route).toBe('/solveur');
    expect(byId.get('prevenir')!.lien.route).toBe('/publication');
    expect(byId.get('declarations')!.lien).toMatchObject({
      route: '/disponibilites',
      queryParams: { statut: 'en-attente' },
    });
    expect(byId.get('echanges')!.lien).toMatchObject({
      route: '/echanges',
      queryParams: { statut: 'a-arbitrer' },
    });
  });

  it('keeps recent swap requests as information', () => {
    const [item] = buildToday(
      etatComplet({ aTraiter: { ...vide, echangesAArbitrer: 2, echangesEnAlerte: 0 } }),
    );
    expect(item.alerte).toBe(false);
    expect(item.sentence).toBe("2 demande(s) d'échange à arbitrer");
  });

  it('links every subject to a route the application declares', () => {
    const declarees = new Set(
      allRoutes(routes)
        .map((route) => route.path)
        .filter((path): path is string => path !== undefined),
    );
    const items = buildToday(
      etatComplet({
        aTraiter: {
          ...vide,
          declarationsEnAttente: 1,
          echangesAArbitrer: 1,
          journeesNonRelues: ['2026-07-10'],
          silencieuxARelancer: 1,
          donneesModifiees: true,
          personnesAPrevenir: 1,
        },
      }),
    );
    for (const item of items) {
      expect(declarees, item.lien.route).toContain(item.lien.route.slice(1));
    }
  });
});
