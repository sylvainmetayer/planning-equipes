import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ScorePoint } from '../../core/models';
import { intlLocale } from '../../core/locale';
import { formatDuration } from '../../core/solver-job.service';
import {
  HAUTEUR_COURBE,
  LARGEUR_COURBE,
  NiveauScore,
  SerieScore,
  construireSeries,
} from './score-curve';

/**
 * The score of the running solve, drawn as it happens (issue #304).
 *
 * <p>Three stacked mini charts, one per score level, <b>each on its own
 * scale</b>. That is the whole design decision: on a shared axis a hard score
 * at -36 is a flat line next to a soft score at -400 000, and the hard one is
 * the only one that says whether the planning can be used at all. Stacked and
 * separate, the question an operator actually asks — "is it still worth
 * waiting?" — is answered by reading three lines: hard flat at the top for two
 * minutes, soft still climbing, means keep going.</p>
 *
 * <p>Hand-written SVG polylines, no charting library: the shape needed here is
 * a curve that only grows, with no zoom, no interaction and no axis of its own,
 * and the frontend deliberately carries no chart dependency (see AGENTS.md).
 * The geometry lives in a plain module next door, unit-tested without
 * rendering; this component only puts labels around it.</p>
 */
@Component({
  selector: 'app-score-chart',
  template: `
    @if (series(); as courbes) {
      @if (replie()) {
        <!-- Folded: the numbers without the boxes. Still worth a line — "hard
             is at 0" is the answer most visits come for, and hiding the panel
             should cost the room, not the information. -->
        <p class="score-curve-resume">
          @for (serie of courbes; track serie.niveau) {
            <span class="score-curve-resume-niveau">
              <span class="score-curve-resume-titre">{{ titre(serie.niveau) }}</span>
              <span class="score-curve-valeur" [class.score-curve-resolu]="serie.dernier === 0">
                {{ nombre(serie.dernier) }}
              </span>
            </span>
          }
        </p>
      } @else {
        <div class="score-curve">
          @for (serie of courbes; track serie.niveau) {
            <div class="score-curve-niveau">
              <div class="score-curve-entete">
                <h3 class="score-curve-titre">{{ titre(serie.niveau) }}</h3>
                <span class="score-curve-valeur" [class.score-curve-resolu]="serie.dernier === 0">
                  {{ nombre(serie.dernier) }}
                </span>
                <span class="score-curve-plateau">{{ etat(serie) }}</span>
              </div>
              <svg
                class="score-curve-cadre"
                [attr.viewBox]="'0 0 ' + largeur + ' ' + hauteur"
                preserveAspectRatio="none"
                role="img"
                [attr.aria-label]="resume(serie)"
              >
                <!-- The zero line: the level at which nothing is violated any
                   more. Drawn even when it is the very top edge, because a
                   curve flattening against it is the readable end state. -->
                <line
                  class="score-curve-zero"
                  x1="0"
                  x2="100%"
                  [attr.y1]="serie.zeroY"
                  [attr.y2]="serie.zeroY"
                  vector-effect="non-scaling-stroke"
                />
                <polyline
                  class="score-curve-trace"
                  [attr.points]="serie.polyline"
                  vector-effect="non-scaling-stroke"
                />
              </svg>
            </div>
          }
        </div>
      }
    } @else if (!replie()) {
      <!-- Not while folded: the point of folding is to give the room back, and
           a paragraph explaining an empty chart is exactly the room in question. -->
      <p class="score-curve-vide" i18n="@@solver.scoreCurve.attente">
        Le solveur n'a pas encore annoncé de première solution complète : la courbe démarre dès
        qu'il en tient une.
      </p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScoreChart {
  /** The curve so far. Growing at one point per second at most — see the backend's `SolverScoreTrace`. */
  readonly points = input.required<ScorePoint[]>();
  /**
   * How long the run has been going. Carried apart from the points because
   * Timefold only announces *strict improvements*: a solve at a standstill adds
   * no point at all, so the points alone cannot say where the curve's right
   * edge is, nor how long a level has held.
   */
  readonly dureeMs = input(0);
  /** True once the run is over: the curve is final and says so rather than looking live. */
  readonly termine = input(false);
  /**
   * Folded away: the three boxes give way to one line of figures. Three charts
   * are a lot of screen for someone who started a fifteen-minute solve and went
   * to do something else — but folding must cost the room, not the reading, so
   * the current scores stay.
   */
  readonly replie = input(false);

  protected readonly largeur = LARGEUR_COURBE;
  protected readonly hauteur = HAUTEUR_COURBE;
  protected readonly series = computed(() => construireSeries(this.points(), this.dureeMs()));

  /**
   * Short names, not the constraint-page ones: these label an axis, and
   * "Dure (bloquante)" is a definition rather than a title. Built in a method
   * so $localize runs after `main.ts` has loaded the translations.
   */
  protected titre(niveau: NiveauScore): string {
    if (niveau === 'hard') {
      return $localize`:@@solver.scoreCurve.niveau.hard:Contraintes dures`;
    }
    if (niveau === 'medium') {
      return $localize`:@@solver.scoreCurve.niveau.medium:Contraintes moyennes`;
    }
    return $localize`:@@solver.scoreCurve.niveau.soft:Contraintes souples`;
  }

  protected nombre(valeur: number): string {
    return valeur.toLocaleString(intlLocale());
  }

  /**
   * How long this level has not moved — the sentence the whole screen is for.
   * A finished run says so instead, since nothing is going to move any more.
   */
  protected etat(serie: SerieScore): string {
    if (this.termine()) {
      return $localize`:@@solver.scoreCurve.final:score final`;
    }
    if (serie.plateauMs <= 0) {
      return $localize`:@@solver.scoreCurve.progresse:en progression`;
    }
    const duree = formatDuration(Math.round(serie.plateauMs / 1000));
    return $localize`:@@solver.scoreCurve.stable:stable depuis ${duree}:duree:`;
  }

  /** What a screen reader gets instead of the drawing. */
  protected resume(serie: SerieScore): string {
    const niveau = this.titre(serie.niveau);
    const valeur = this.nombre(serie.dernier);
    const etat = this.etat(serie);
    return $localize`:@@solver.scoreCurve.resume:${niveau}:niveau: : ${valeur}:valeur: (${etat}:etat:)`;
  }
}
