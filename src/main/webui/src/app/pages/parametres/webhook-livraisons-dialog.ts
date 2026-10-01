import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { WebhooksApi } from '../../core/api/webhooks-api';
import { errorMessage } from '../../core/error-message';
import { WebhookDeliveryStatus, WebhookDeliveryView, WebhookView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { eventLabel, statusLabel } from './webhooks';

export interface WebhookLivraisonsData {
  webhook: WebhookView;
}

/**
 * The journal of one webhook: each delivery with its attempts, its status,
 * the receiver's code and the time it took — never the receiver's body — and
 * « Renvoyer » to start one again under the same id.
 */
@Component({
  selector: 'app-webhook-livraisons-dialog',
  imports: [DatePipe, MatButtonModule, MatDialogModule, MatIconModule, MatTableModule],
  templateUrl: './webhook-livraisons-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WebhookLivraisonsDialog implements OnInit {
  protected readonly dialogRef = inject<MatDialogRef<WebhookLivraisonsDialog>>(MatDialogRef);
  protected readonly data = inject<WebhookLivraisonsData>(MAT_DIALOG_DATA);
  private readonly api = inject(WebhooksApi);
  private readonly notifications = inject(NotificationService);

  protected readonly colonnes = [
    'createdAt',
    'event',
    'attempts',
    'status',
    'httpStatus',
    'error',
    'actions',
  ];
  protected readonly livraisons = signal<WebhookDeliveryView[]>([]);
  protected readonly loading = signal(false);

  ngOnInit(): void {
    void this.reload();
  }

  protected async reload(): Promise<void> {
    this.loading.set(true);
    try {
      this.livraisons.set(await this.api.deliveries(this.data.webhook.id));
    } catch (error) {
      this.notifyError(error);
    } finally {
      this.loading.set(false);
    }
  }

  protected async resend(livraison: WebhookDeliveryView): Promise<void> {
    try {
      await this.api.resend(livraison.id);
      this.notifications.notify({
        title: $localize`:@@parametres.webhooks.renvoyee:Livraison renvoyée.`,
        variant: 'success',
        timeout: 3000,
      });
      await this.reload();
    } catch (error) {
      this.notifyError(error);
    }
  }

  protected eventLabel(code: string): string {
    return eventLabel(code);
  }

  protected statusLabel(status: WebhookDeliveryStatus): string {
    return statusLabel(status);
  }

  private notifyError(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: errorMessage(error),
      variant: 'error',
    });
  }
}
