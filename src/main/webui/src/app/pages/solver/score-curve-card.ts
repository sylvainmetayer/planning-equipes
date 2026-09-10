import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  defaultPanelStorage,
  readPanelCollapsed,
  writePanelCollapsed,
} from '../../core/panel-collapse';
import { SolverJobService } from '../../core/solver-job.service';
import { ScoreChart } from './score-chart';

/**
 * Where the folded state of the score curve is kept. A stable, namespaced key,
 * like every other one this application writes to localStorage.
 */
export const SCORE_CURVE_STORAGE_KEY = 'planning-equipes.solver.scoreCurveCollapsed';

/**
 * The card around the live score curve (issue #304): decides whether the
 * curve on hand really describes the run being reported, whether the card is
 * on screen at all, and remembers its fold. The drawing itself is
 * `ScoreChart`'s.
 */
@Component({
  selector: 'app-score-curve-card',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTooltipModule, ScoreChart],
  templateUrl: './score-curve-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScoreCurveCard {
  private readonly jobs = inject(SolverJobService);

  constructor() {
    // The curve is pushed from here on; this one read is what puts a solve
    // already under way on screen at once, rather than at the stream's next
    // tick — and what shows it at all in a browser without `EventSource`. It
    // is the only read of the curve carrying an edition, so it is also the
    // server's chance to refuse one belonging to another.
    void this.jobs.chargerCourbeScore();
  }

  /**
   * The score curve to draw: the running solve's, or the last one's until the
   * next replaces it.
   *
   * <p>Null in the one case that would mislead — a solve has taken the solver
   * but has not announced a first complete solution yet. The curve still on
   * hand is the <em>previous</em> run's, and leaving it up while a new job is
   * described as running would read as that job's progress.</p>
   */
  protected readonly trace = computed(() => {
    const trace = this.jobs.scoreTraceEdition();
    const active = this.jobs.activeJob();
    return trace && active && trace.jobId !== active.id ? null : trace;
  });

  /**
   * Whether the curve is folded to a single line of figures, remembered across
   * visits (see `core/panel-collapse`). Three charts are a lot of screen for
   * someone who launched a fifteen-minute solve and walked away.
   *
   * <p>Folding is a rendering choice and nothing else: the stream stays open
   * and `SolverJobService` keeps recording, so unfolding shows the run from its
   * first point rather than a hole starting where the panel was closed — which
   * would defeat the one reading the curve exists to give.</p>
   */
  protected readonly folded = signal(
    readPanelCollapsed(defaultPanelStorage(), SCORE_CURVE_STORAGE_KEY),
  );

  protected readonly points = computed(() => this.trace()?.points ?? []);
  protected readonly finished = computed(() => this.trace()?.termine ?? false);
  /** Elapsed time of the run the curve describes: its right edge, see `score-curve.ts`. */
  protected readonly durationMs = computed(() => this.trace()?.dureeMs ?? 0);

  /**
   * Whether the card is on screen at all. A run on this edition brings it up
   * even before its first point, so the card appears when the solve starts
   * rather than a few seconds later.
   */
  protected readonly visible = computed(
    () => this.trace() !== null || (this.jobs.activeJob() !== null && this.jobs.editingLocked()),
  );

  protected toggle(): void {
    const folded = !this.folded();
    this.folded.set(folded);
    writePanelCollapsed(defaultPanelStorage(), SCORE_CURVE_STORAGE_KEY, folded);
  }

  protected readonly toggleLabel = computed(() =>
    this.folded()
      ? $localize`:@@solver.scoreCurve.deplier:Afficher la courbe`
      : $localize`:@@solver.scoreCurve.replier:Réduire la courbe`,
  );
}
