import { describe, expect, it } from 'vitest';
import { ScorePoint } from '../../core/models';
import { HAUTEUR_COURBE, LARGEUR_COURBE, SerieScore, construireSeries } from './score-curve';

function point(tempsMs: number, hard: number, medium: number, soft: number): ScorePoint {
  return { tempsMs, hard, medium, soft };
}

function serie(series: SerieScore[] | null, niveau: 'hard' | 'medium' | 'soft'): SerieScore {
  const trouvee = series?.find((candidate) => candidate.niveau === niveau);
  expect(trouvee).toBeDefined();
  return trouvee!;
}

describe('construireSeries', () => {
  it('ne dessine rien tant que le solveur n’a pas annoncé de solution complète', () => {
    expect(construireSeries([], 30000)).toBeNull();
  });

  it('donne à chaque niveau sa propre échelle', () => {
    // Le cas qui interdit un axe commun : à cette échelle-là, un hard à -36
    // serait un trait plat confondu avec zéro à côté d’un soft à -400 000,
    // alors que c’est lui qui décide de la faisabilité.
    const series = construireSeries(
      [point(0, -36, -500, -400000), point(10000, 0, -500, -300000)],
      10000,
    );

    // Le hard part du bas de SA boîte et finit collé au plafond : il a atteint
    // zéro, ce que l’écran doit rendre lisible d’un coup d’œil.
    expect(serie(series, 'hard').polyline).toBe(`0,${HAUTEUR_COURBE} ${LARGEUR_COURBE},0`);
    // Le soft, sur la même donnée, n’a parcouru qu’un quart de sa boîte.
    expect(serie(series, 'soft').polyline).toBe(`0,${HAUTEUR_COURBE} ${LARGEUR_COURBE},54`);
  });

  it('force le zéro dans l’échelle, pour que le haut de la boîte veuille dire « plus rien à corriger »', () => {
    const series = construireSeries([point(0, -10, 0, 0), point(1000, -4, 0, 0)], 1000);

    const hard = serie(series, 'hard');
    expect(hard.haut).toBe(0);
    expect(hard.bas).toBe(-10);
    // Tout est négatif : la ligne de zéro est le bord supérieur.
    expect(hard.zeroY).toBe(0);
    // Et la courbe ne le touche pas, puisqu’elle s’arrête à -4.
    expect(hard.polyline).toBe(`0,${HAUTEUR_COURBE} ${LARGEUR_COURBE},28.8`);
  });

  /**
   * Le cœur de l’écran. Timefold n’annonce un nouveau meilleur score que
   * lorsqu’il s’améliore *strictement* : une résolution qui plafonne
   * n’enregistre plus aucun point. Sans la durée du run, la courbe se
   * normaliserait sur ses points, toucherait toujours le bord droit, et un
   * solve à l’arrêt depuis dix minutes se dessinerait comme s’il progressait
   * encore — soit l’inverse de ce que l’utilisateur vient y lire.
   */
  describe('quand la résolution plafonne', () => {
    const progression = [
      point(0, -40, -10, -1000),
      point(60000, 0, -6, -800),
      point(120000, 0, -6, -700),
    ];

    it('tient la dernière valeur à plat jusqu’au bord droit', () => {
      // Dernière amélioration à t=120 s, mais le solveur tourne depuis 600 s.
      const series = construireSeries(progression, 600000);

      // Le dernier point est à 120/600 du run, donc au cinquième de la
      // largeur, et un segment plat le prolonge jusqu’au bout : c’est ça, un
      // plateau.
      const hard = serie(series, 'hard');
      expect(hard.polyline).toBe(`0,${HAUTEUR_COURBE} 60,0 120,0 ${LARGEUR_COURBE},0`);
    });

    it('compte le plateau jusqu’à maintenant, pas jusqu’au dernier point', () => {
      const series = construireSeries(progression, 600000);

      // Le hard est à zéro depuis t=60 s et il est t=600 s : 9 minutes. Mesuré
      // jusqu’au dernier point enregistré, il aurait annoncé 60 s — et serait
      // resté sur ce chiffre indéfiniment, quelle que soit l’attente.
      expect(serie(series, 'hard').plateauMs).toBe(540000);
      // Le souple, lui, vient de bouger à t=120 s : 8 minutes d’immobilité.
      expect(serie(series, 'soft').plateauMs).toBe(480000);
    });

    it('n’ajoute pas de segment quand le dernier point est l’instant présent', () => {
      const series = construireSeries(progression, 120000);

      expect(serie(series, 'hard').polyline).toBe(`0,${HAUTEUR_COURBE} 300,0 ${LARGEUR_COURBE},0`);
    });
  });

  it('ne rétrécit jamais l’axe en deçà du dernier point', () => {
    // Un instantané pris entre une amélioration et la lecture d’horloge
    // suivante : l’axe ne doit pas finir avant la donnée qu’il porte.
    const series = construireSeries([point(0, -5, 0, 0), point(10000, -1, 0, 0)], 9000);

    expect(serie(series, 'hard').polyline).toBe(`0,${HAUTEUR_COURBE} ${LARGEUR_COURBE},14.4`);
  });

  it('trace un segment plat quand un seul point est connu', () => {
    // Une polyligne à un seul sommet ne dessine rien du tout : le premier point
    // d’un solve resterait invisible.
    const series = construireSeries([point(2500, -3, -2, -1)], 2500);

    expect(serie(series, 'hard').polyline).toBe(
      `0,${HAUTEUR_COURBE} ${LARGEUR_COURBE},${HAUTEUR_COURBE}`,
    );
    expect(serie(series, 'hard').dernier).toBe(-3);
  });

  it('reste plat au plafond quand un niveau est resté à zéro tout du long', () => {
    const series = construireSeries([point(0, 0, 0, 0), point(5000, 0, 0, -10)], 5000);

    const hard = serie(series, 'hard');
    expect(hard.polyline).toBe(`0,0 ${LARGEUR_COURBE},0`);
    expect(hard.zeroY).toBe(0);
    expect(hard.plateauMs).toBe(5000);
  });
});
