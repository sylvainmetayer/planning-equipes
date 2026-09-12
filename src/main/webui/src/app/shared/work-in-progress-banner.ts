import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

/**
 * Warning shown at the top of a page that is usable but not finished: its
 * behaviour and its data may still change.
 *
 * It used to double as the marker of a « En cours de développement » drawer
 * group, which no longer exists — the pages now sit with the screens they
 * belong to, and this banner is the only thing that says they are unfinished.
 * Which makes it the single source of that warning, not a reminder of it.
 */
@Component({
  selector: 'app-work-in-progress-banner',
  imports: [MatCardModule, MatIconModule],
  template: `
    <mat-card appearance="outlined" class="work-in-progress-banner">
      <mat-card-content>
        <mat-icon>construction</mat-icon>
        <div>
          @if (message()) {
            <p>{{ message() }}</p>
          } @else {
            <p i18n="@@workInProgress.message">
              Fonctionnalité en cours de développement : son comportement peut encore évoluer et le
              solveur peut ne pas en tenir compte.
            </p>
          }
          @if (marche()) {
            <p class="work-in-progress-detail">
              <strong i18n="@@workInProgress.working">Ce qui fonctionne :</strong> {{ marche() }}
            </p>
          }
          @if (manque()) {
            <p class="work-in-progress-detail">
              <strong i18n="@@workInProgress.missing">Ce qui manque :</strong> {{ manque() }}
            </p>
          }
        </div>
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
    .work-in-progress-detail {
      margin-top: 0.35rem !important;
      font: var(--mat-sys-body-small);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkInProgressBanner {
  /**
   * "En cours de développement" alone tells the user nothing actionable. These
   * two lines say where the boundary is, so someone can decide whether to rely
   * on the screen today.
   */
  readonly marche = input('');
  readonly manque = input('');

  /**
   * Replaces the default sentence. That sentence speaks of data entered on the
   * screen and of the solver taking it into account, which is exactly right for
   * a screen that writes something and wrong for one that only reads — and a
   * warning that describes the wrong screen is worse than none. A page under
   * trial for a different reason says so in its own words.
   */
  readonly message = input('');
}
