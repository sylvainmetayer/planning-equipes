import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ValidationsStore } from '../core/validations.store';

/**
 * Where the relecture of the edition has got to — « 3 journées sur 12 relues
 * et acceptées » — shown on every screen a day of the plan is looked at.
 *
 * <p>It reads the shared store and never loads by itself: the page that shows
 * it owns when the figure is refreshed, which is what keeps the banner and the
 * panel that accepts a day from telling two stories. Silent while there is
 * nothing to say (no timeslot, no plan, a failed read): an edition being typed
 * in must not be told « 0 sur 0 ».</p>
 */
@Component({
  selector: 'app-validation-banner',
  imports: [MatIconModule, MatProgressBarModule],
  template: `
    @if (store.libelle(); as libelle) {
      <div class="relecture-banniere">
        <mat-icon>fact_check</mat-icon>
        <p>{{ libelle }}</p>
        @if (store.pourcentage(); as pourcentage) {
          <mat-progress-bar
            class="relecture-jauge"
            mode="determinate"
            [value]="pourcentage"
            i18n-aria-label="@@validations.jauge.label"
            aria-label="Avancement de la relecture"
          />
        }
        @if (store.libelleStands(); as stands) {
          <p class="relecture-banniere-stands">{{ stands }}</p>
        }
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ValidationBanner {
  protected readonly store = inject(ValidationsStore);
}
