import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { SolverJobService } from '../core/solver-job.service';

/**
 * Toolbar indicator, visible on every screen while a solver job runs:
 * The mascot — the event mascot, cut out with a transparent background —
 * spinning while linking to the Solver page, its tooltip carrying the shared
 * job description (type, edition, elapsed time). Complements the per-page
 * lock messages — those only exist on the pages that show them, whereas a
 * multi-minute solve is mostly watched from somewhere else. Self-injects its
 * data (like `app-data-stale-indicator`) so it can be dropped once in the
 * app shell.
 *
 * <p>It also carries the number of solves planned behind the running one: the
 * whole point of queueing is to walk away from the Solver page, so the count
 * has to be visible from wherever the operator then goes.</p>
 */
@Component({
  selector: 'app-solver-running-indicator',
  imports: [MatButtonModule, MatTooltipModule, RouterLink],
  template: `
    @if (jobs.activeJob()) {
      <a
        matIconButton
        routerLink="/"
        class="solver-running-indicator"
        [matTooltip]="description()"
        matTooltipPosition="below"
        [attr.aria-label]="description()"
      >
        <img src="/mascotte-icone.png" alt="" class="solver-running-icon" />
        @if (jobs.file().length > 0) {
          <span class="solver-file-badge">{{ jobs.file().length }}</span>
        }
      </a>
    }
  `,
  styles: `
    .solver-running-indicator {
      margin-right: 0.25rem;
      color: var(--mat-sys-tertiary);
      position: relative;
      overflow: visible;
    }
    .solver-file-badge {
      position: absolute;
      top: 0;
      right: 0;
      min-width: 1rem;
      padding: 0 0.2rem;
      border-radius: 0.5rem;
      background: var(--mat-sys-tertiary);
      color: var(--mat-sys-on-tertiary);
      font: var(--mat-sys-label-small);
      line-height: 1rem;
      text-align: center;
    }
    .solver-running-icon {
      width: 24px;
      height: 24px;
      object-fit: contain;
      animation: solver-running-spin 3s linear infinite;
    }
    @keyframes solver-running-spin {
      to {
        transform: rotate(360deg);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .solver-running-icon {
        animation: none;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SolverRunningIndicator {
  protected readonly jobs = inject(SolverJobService);

  /** The running job, plus how many are planned behind it. */
  protected readonly description = computed(() => {
    const enAttente = this.jobs.file().length;
    const enCours = this.jobs.activeJobDescription();
    if (enAttente === 0) {
      return enCours;
    }
    return `${enCours} ${$localize`:@@job.fileIndicator:${enAttente}:count: tâche(s) planifiée(s) à la suite.`}`;
  });
}
