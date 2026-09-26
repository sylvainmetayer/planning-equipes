import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { DateMockService } from '../core/date-mock.service';
import { toDateKey } from '../core/date-utils';

/**
 * « Voir le planning » (#709): the grid of the plan one click away from the
 * home page and from the Solveur, opened on today.
 *
 * <p>Today is the server's frozen date when there is one, the machine's
 * otherwise. The day page opens its first day when the date asked for carries
 * no seat, so a click before or after the event still lands on the plan.</p>
 */
@Component({
  selector: 'app-voir-planning-button',
  imports: [MatButtonModule, MatIconModule, RouterLink],
  template: `
    <a matButton="tonal" routerLink="/journee" [queryParams]="{ date: aujourdhui() }">
      <mat-icon>view_day</mat-icon>
      <ng-container i18n="@@voirPlanning.label">Voir le planning</ng-container>
    </a>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VoirPlanningButton {
  private readonly dates = inject(DateMockService);

  /**
   * A method, not a `computed()`: the machine's date is no signal, and a
   * memoised value would still say yesterday on a page left open past midnight.
   */
  protected aujourdhui(): string {
    return this.dates.dateDuJour() || toDateKey(new Date());
  }
}
