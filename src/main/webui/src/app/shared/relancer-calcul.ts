import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { errorPrefix } from '../core/error-message';
import { NotificationService } from '../core/notification.service';
import { PlanningResolutionStore } from '../core/planning-resolution.store';
import { SolverJobService } from '../core/solver-job.service';

/**
 * « Relancer le calcul », offered where an input of the next solve was just
 * written — a rule reweighted, a lock or a consigne laid down — so the reader
 * does not have to go to the Solveur to see it taken into account (issues
 * #719, #720).
 *
 * <p>`correction` asks for the incremental re-solve when it suffices: a plan
 * exists, and only what the change invalidated re-opens — the rest is
 * guaranteed unchanged, which is what a lock or a consigne wants. Without a
 * plan there is nothing to correct, and the full solve runs. `calcul` always
 * asks for the full solve, starting from the plan: a reweighted rule concerns
 * every seat.</p>
 *
 * <p>A busy solver plans the run rather than refusing it, as on the Solveur.
 * `blocked` says why the button waits — changes not saved yet, which the
 * solve would not see.</p>
 */
@Component({
  selector: 'app-relancer-calcul',
  imports: [MatButtonModule, MatIconModule, RouterLink],
  template: `
    <span class="relancer-calcul">
      <button
        matButton="tonal"
        type="button"
        [disabled]="!!blocked() || envoi()"
        (click)="relancer()"
      >
        <mat-icon>{{ jobs.solverBusy() ? 'schedule_send' : 'play_arrow' }}</mat-icon>
        {{ libelle() }}
      </button>
      @if (blocked(); as raison) {
        <span class="calendar-meta">{{ raison }}</span>
      } @else {
        <span class="calendar-meta">{{ phrase() }}</span>
      }
      <a matButton routerLink="/solveur" i18n="@@relancer.suivre">Suivre sur le Solveur</a>
    </span>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RelancerCalcul {
  /** `correction`: incremental when a plan exists; `calcul`: the full solve. */
  readonly mode = input<'calcul' | 'correction'>('calcul');
  /** Why the button waits, or empty: typically « enregistrez d'abord ». */
  readonly blocked = input('');

  protected readonly jobs = inject(SolverJobService);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly notifications = inject(NotificationService);

  protected readonly envoi = signal(false);

  /** The incremental re-solve needs a plan to correct. */
  private readonly incremental = computed(
    () => this.mode() === 'correction' && this.resolution.resolution()?.solved === true,
  );

  protected readonly libelle = computed(() =>
    this.jobs.solverBusy()
      ? $localize`:@@relancer.planifier:Planifier le calcul`
      : $localize`:@@relancer.label:Relancer le calcul`,
  );

  protected readonly phrase = computed(() =>
    this.incremental()
      ? $localize`:@@relancer.phrase.correction:Ne rouvre que ce que ce changement a invalidé.`
      : $localize`:@@relancer.phrase.calcul:Repart du plan enregistré et l'améliore.`,
  );

  constructor() {
    void this.resolution.reload().catch(() => undefined);
  }

  protected async relancer(): Promise<void> {
    const queued = this.jobs.solverBusy();
    this.envoi.set(true);
    try {
      if (this.incremental()) {
        await this.jobs.submitSolveIncremental(
          { animateurIds: [], jours: [], standIds: [] },
          undefined,
          queued,
        );
      } else {
        await this.jobs.submitSolveFromReferenceData(undefined, queued);
      }
      this.notifications.notify({
        title: queued
          ? $localize`:@@relancer.planifie:Calcul planifié : il démarrera dès que la tâche en cours sera terminée.`
          : $localize`:@@relancer.lance:Calcul lancé : le résultat arrivera sur le Solveur.`,
        variant: 'success',
        timeout: 5000,
      });
    } catch (error) {
      this.notifications.notify({ title: errorPrefix(error), variant: 'error' });
    } finally {
      this.envoi.set(false);
    }
  }
}
