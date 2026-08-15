import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

/**
 * Warning shown at the top of the pages grouped under « En cours de
 * développement » in the navigation drawer: the feature is usable but not
 * finished, so its behaviour and its data may still change.
 */
@Component({
  selector: 'app-work-in-progress-banner',
  imports: [MatCardModule, MatIconModule],
  template: `
    <mat-card appearance="outlined" class="work-in-progress-banner">
      <mat-card-content>
        <mat-icon>construction</mat-icon>
        <p i18n="@@workInProgress.message">
          Cette fonctionnalité est en cours de développement : son comportement et les données saisies ici peuvent
          encore évoluer, et le résultat du solveur peut ne pas en tenir compte.
        </p>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .work-in-progress-banner {
      --mdc-outlined-card-container-color: var(--mat-sys-tertiary-container);
      margin-bottom: 1rem;
    }
    .work-in-progress-banner mat-card-content {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      color: var(--mat-sys-on-tertiary-container);
    }
    .work-in-progress-banner mat-icon {
      flex-shrink: 0;
    }
    .work-in-progress-banner p {
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class WorkInProgressBanner {}
