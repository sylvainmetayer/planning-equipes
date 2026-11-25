import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  resource,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { AdminApi } from '../../core/api/admin-api';
import { ResponsableApi } from '../../core/api/responsable-api';
import { errorMessage } from '../../core/error-message';
import { StandResponsable } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { errorText, retainedValue } from '../../core/resource-state';
import { BrandLogo } from '../../shared/brand-logo';
import { StatusMessage } from '../../shared/status-message';
import { daysOf, personLabels, staffingLabel, windowLabel } from './responsable';

/**
 * « Mon stand » (`/responsable`): what a responsable de stand reads, outside the
 * administration — the published shifts of their stands on the active edition,
 * the empty seats, and the names only when the setting says so. Read only; each
 * stand prints its sheet. What this screen hides is comfort: the server serves
 * nothing it would have to hide.
 */
@Component({
  selector: 'app-responsable-page',
  imports: [
    BrandLogo,
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatToolbarModule,
    StatusMessage,
  ],
  templateUrl: './responsable-page.html',
  styleUrl: './responsable-page.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResponsablePage {
  private readonly api = inject(ResponsableApi);
  private readonly adminApi = inject(AdminApi);
  private readonly notifications = inject(NotificationService);

  private readonly stands = resource({ loader: () => this.api.stands() });
  protected readonly view = retainedValue(this.stands);
  protected readonly erreur = errorText(this.stands);

  protected readonly monStand = $localize`:@@responsable.title:Mon stand`;
  protected readonly windowLabel = windowLabel;
  protected readonly staffingLabel = staffingLabel;
  protected readonly daysOf = daysOf;
  protected readonly personLabelList = personLabels;

  protected async imprimer(stand: StandResponsable): Promise<void> {
    try {
      await this.api.affichette(stand.standId);
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
    }
  }

  /** Ends the session — the provider's too, when it names a route for that. */
  protected async logout(): Promise<void> {
    let destination = '/login';
    try {
      destination = (await this.adminApi.logout()).urlDeconnexion ?? destination;
    } finally {
      window.location.assign(destination);
    }
  }
}
