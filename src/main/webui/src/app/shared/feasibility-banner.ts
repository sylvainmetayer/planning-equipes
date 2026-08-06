import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { FeasibilityReport } from '../core/models';

/**
 * Plain-language warning shown when the reference data cannot fill every
 * seat no matter how long the solver runs. Silent when feasible, so it adds
 * no noise on the nominal path.
 */
@Component({
  selector: 'app-feasibility-banner',
  imports: [MatCardModule, MatIconModule],
  template: `
    @if (report() && !report()!.feasible) {
      <mat-card appearance="outlined" class="feasibility-banner">
        <mat-card-content>
          <mat-icon>warning</mat-icon>
          <p>{{ report()!.message }}</p>
        </mat-card-content>
      </mat-card>
    }
  `,
  styles: `
    .feasibility-banner {
      --mdc-outlined-card-container-color: var(--mat-sys-error-container);
      margin-bottom: 1rem;
    }
    .feasibility-banner mat-card-content {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      color: var(--mat-sys-on-error-container);
    }
    .feasibility-banner mat-icon {
      color: var(--mat-sys-error);
      flex-shrink: 0;
    }
    .feasibility-banner p {
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class FeasibilityBanner {
  readonly report = input<FeasibilityReport | null>(null);
}
