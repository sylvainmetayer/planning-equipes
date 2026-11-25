import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  resource,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RhApi } from '../../core/api/rh-api';
import { errorMessage } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { errorText, retainedValue } from '../../core/resource-state';
import { StatusMessage } from '../../shared/status-message';
import { hasFindings, hoursLabel, minorsOf, restLabel } from './conformite';

/**
 * The compliance report of one edition's published plan: the ceilings it was
 * checked against, the breaches of each legal rule, one line per person and the
 * minors on their own, with the PDF and CSV to take away. Read only; every
 * reading and every export is journalled by the server.
 */
@Component({
  selector: 'app-conformite-rapport',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatIconModule, StatusMessage],
  templateUrl: './conformite-rapport.html',
  styleUrl: './conformite-rapport.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConformiteRapport {
  private readonly api = inject(RhApi);
  private readonly notifications = inject(NotificationService);

  readonly editionId = input.required<string>();

  private readonly rapport = resource({
    params: () => this.editionId(),
    loader: ({ params }) => this.api.rapport(params),
  });
  protected readonly report = retainedValue(this.rapport);
  protected readonly erreur = errorText(this.rapport);

  protected readonly hoursLabel = hoursLabel;
  protected readonly minorsOf = minorsOf;
  protected readonly hasFindings = hasFindings;
  protected readonly restLabel = restLabel;

  protected async exporter(format: 'pdf' | 'csv'): Promise<void> {
    try {
      await (format === 'pdf' ? this.api.pdf(this.editionId()) : this.api.csv(this.editionId()));
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
    }
  }
}
