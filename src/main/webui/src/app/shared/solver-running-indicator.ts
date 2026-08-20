import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { SolverJobService } from '../core/solver-job.service';

/**
 * Toolbar indicator, visible on every screen while a solver job runs:
 * The mascot — the festival mascot, cut out with a transparent background —
 * spinning while linking to the Solver page, its tooltip carrying the shared
 * job description (type, edition, elapsed time). Complements the per-page
 * lock messages — those only exist on the pages that show them, whereas a
 * multi-minute solve is mostly watched from somewhere else. Self-injects its
 * data (like `app-data-stale-indicator`) so it can be dropped once in the
 * app shell.
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
        [matTooltip]="jobs.activeJobDescription()"
        matTooltipPosition="below"
        [attr.aria-label]="jobs.activeJobDescription()"
      >
        <img src="/mascotte-icone.png" alt="" class="solver-running-icon" />
      </a>
    }
  `,
  styles: `
    .solver-running-indicator {
      margin-right: 0.25rem;
      color: var(--mat-sys-tertiary);
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
}
