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
    expect(construireSeries([])).toBeNull();
  });

  it('donne à chaque niveau sa propre échelle', () => {
    // Le cas qui interdit un axe commun : à cette échelle-là, un hard à -36
    // serait un trait plat confondu avec zéro à côté d’un soft à -400 000,
    // alors que c’est lui qui décide de la faisabilité.
    const series = construireSeries([point(0, -36, -500, -400000), point(10000, 0, -500, -300000)]);

    // Le hard part du bas de SA boîte et finit collé au plafond : il a atteint
    // zéro, ce que l’écran doit rendre lisible d’un coup d’œil.
    expect(serie(series, 'hard').polyline).toBe(`0,${HAUTEUR_COURBE} ${LARGEUR_COURBE},0`);
    // Le soft, sur la même donnée, n’a parcouru qu’un quart de sa boîte.
    expect(serie(series, 'soft').polyline).toBe(`0,${HAUTEUR_COURBE} ${LARGEUR_COURBE},54`);
  });

  it('force le zéro dans l’échelle, pour que le haut de la boîte veuille dire « plus rien à corriger »', () => {
    const series = construireSeries([point(0, -10, 0, 0), point(1000, -4, 0, 0)]);

    const hard = serie(series, 'hard');
    expect(hard.haut).toBe(0);
    expect(hard.bas).toBe(-10);
    // Tout est négatif : la ligne de zéro est le bord supérieur.
    expect(hard.zeroY).toBe(0);
    // Et la courbe ne le touche pas, puisqu’elle s’arrête à -4.
    expect(hard.polyline).toBe(`0,${HAUTEUR_COURBE} ${LARGEUR_COURBE},28.8`);
  });

  it('trace un segment plat quand un seul point est connu', () => {
    // Une polyligne à un seul sommet ne dessine rien du tout : le premier point
    // d’un solve resterait invisible.
    const series = construireSeries([point(2500, -3, -2, -1)]);

    expect(serie(series, 'hard').polyline).toBe(`0,${HAUTEUR_COURBE} ${LARGEUR_COURBE},${HAUTEUR_COURBE}`);
    expect(serie(series, 'hard').dernier).toBe(-3);
  });

  it('dit depuis combien de temps chaque niveau ne bouge plus', () => {
    // Le signal recherché : « le dur est à 0 depuis deux minutes, le souple
    // progresse encore ». Il se lit là, pas dans la forme de la courbe.
    const series = construireSeries([
      point(0, -5, -50, -900),
      point(60000, 0, -40, -800),
      point(120000, 0, -40, -700),
      point(180000, 0, -40, -600)
    ]);

    expect(serie(series, 'hard').plateauMs).toBe(120000);
    expect(serie(series, 'medium').plateauMs).toBe(120000);
    expect(serie(series, 'soft').plateauMs).toBe(0);
  });

  it('reste plat au plafond quand un niveau est resté à zéro tout du long', () => {
    const series = construireSeries([point(0, 0, 0, 0), point(5000, 0, 0, -10)]);

    const hard = serie(series, 'hard');
    expect(hard.polyline).toBe(`0,0 ${LARGEUR_COURBE},0`);
    expect(hard.zeroY).toBe(0);
    expect(hard.plateauMs).toBe(5000);
  });
});
