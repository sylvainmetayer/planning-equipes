import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatIconModule, MatIconRegistry } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { DomSanitizer } from '@angular/platform-browser';
import { SolverJobService } from '../core/solver-job.service';

/**
 * Toolbar indicator, visible on every screen while a solver job runs: a
 * spinning crossword icon linking to the Solver page, its tooltip carrying
 * the shared job description (type, elapsed time, and « groupe i/N » for a
 * queue). Complements the per-page lock messages — those only exist on the
 * pages that show them, whereas a multi-minute solve is mostly watched from
 * somewhere else. Self-injects its data (like `app-data-stale-indicator`) so
 * it can be dropped once in the app shell.
 *
 * The crossword glyph ships as an inline SVG (Material Symbols, Apache 2.0):
 * the self-hosted icon font is classic Material Icons, which predates that
 * glyph, and registering one literal costs nothing offline-wise.
 */
@Component({
  selector: 'app-solver-running-indicator',
  imports: [MatIconModule, MatButtonModule, MatTooltipModule, RouterLink],
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
        <mat-icon svgIcon="solver-en-cours" class="solver-running-icon" />
      </a>
    }
  `,
  styles: `
    .solver-running-indicator {
      margin-right: 0.25rem;
      color: var(--mat-sys-tertiary);
    }
    .solver-running-icon {
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

  constructor() {
    // Material Symbols "crossword" (Apache 2.0), inlined because the
    // self-hosted font is the classic Material Icons set. `fill` is omitted on
    // purpose: the path inherits `currentColor`, so the toolbar theme applies.
    inject(MatIconRegistry).addSvgIconLiteral(
      'solver-en-cours',
      inject(DomSanitizer).bypassSecurityTrustHtml(
        '<svg xmlns="http://www.w3.org/2000/svg" height="24" viewBox="0 -960 960 960" width="24">'
          + '<path d="M400-160h160v-160H400v160ZM160-400h160v-160H160v160Zm240 0h160v-160H400v160Zm240 '
          + '0h160v-160H640v160Zm0-240h160v-160H640v160ZM320-80v-240H80v-320h480v-240h320v560H640v240H320Z"/></svg>'
      )
    );
  }
}
