import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { TODAY_ANCHOR, DateMockService } from '../core/date-mock.service';

/**
 * Toolbar warning that this server's notion of today has been frozen
 * (issue #297): every screen that reasons about "now" — the mode jour J one
 * above all — is then showing another day as if it were this one.
 *
 * <p>It exists although the setting is development- and staging-only, because the danger it
 * covers is not deployment but forgetting: a date frozen at the start of a
 * session is invisible an hour later, and the screen looks simply wrong rather
 * than mocked.
 *
 * <p>A link rather than the plain icon of its neighbours, and a link that lands
 * on the field rather than on the page: the only two things anyone wants from
 * this warning are to change the date or to clear it, and hunting for the
 * control down a debug page is how a warning gets ignored instead.
 */
@Component({
  selector: 'app-date-mock-indicator',
  imports: [MatIconModule, MatTooltipModule, RouterLink],
  template: `
    @if (dates.actif()) {
      <a
        class="date-mock-indicator"
        routerLink="/debug"
        [queryParams]="{ onglet: 'verifications', focus: ancre }"
        [fragment]="ancre"
        [matTooltip]="tooltip()"
        matTooltipPosition="below"
        [attr.aria-label]="tooltip()"
      >
        <mat-icon>hourglass_disabled</mat-icon>
      </a>
    }
  `,
  styles: `
    .date-mock-indicator {
      color: var(--mat-sys-error);
      margin-right: 0.5rem;
      display: inline-flex;
      align-items: center;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DateMockIndicator {
  protected readonly dates = inject(DateMockService);
  protected readonly ancre = TODAY_ANCHOR;

  protected readonly tooltip = computed(
    () =>
      $localize`:@@dateMock.tooltip:MOCK — la date du jour est figée au ${this.dates.libelle()}:date: sur ce serveur : les écrans qui raisonnent sur « maintenant » ne montrent pas la réalité. Cliquez pour modifier ou effacer cette date.`,
  );
}
