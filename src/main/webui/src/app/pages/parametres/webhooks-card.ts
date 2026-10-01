import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTableModule } from '@angular/material/table';
import { WebhooksApi } from '../../core/api/webhooks-api';
import { errorMessage } from '../../core/error-message';
import {
  WebhookDeliveryStatus,
  WebhookFormat,
  WebhookRequest,
  WebhookView,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { WebhookFormData, WebhookFormDialog } from './webhook-form-dialog';
import { WebhookLivraisonsData, WebhookLivraisonsDialog } from './webhook-livraisons-dialog';
import { eventLabel, formatLabel, statusLabel } from './webhooks';

/** A secret just generated: the one moment it can be copied. */
interface ShownSecret {
  name: string;
  secret: string;
}

/**
 * « Webhooks » of Paramètres › Instance: what the application announces to
 * n8n, Slack, Discord, Matrix or Telegram — counts, never a person. The HMAC
 * secret of a generic webhook is shown once, right after it is generated.
 */
@Component({
  selector: 'app-webhooks-card',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatIconModule, MatMenuModule, MatTableModule],
  templateUrl: './webhooks-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WebhooksCard implements OnInit {
  private readonly api = inject(WebhooksApi);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly colonnes = ['name', 'format', 'events', 'lastDelivery', 'actions'];
  protected readonly webhooks = signal<WebhookView[]>([]);
  protected readonly secret = signal<ShownSecret | null>(null);
  protected readonly busy = signal(false);

  ngOnInit(): void {
    void this.load();
  }

  protected formatLabel(format: WebhookFormat): string {
    return formatLabel(format);
  }

  protected statusLabel(status: WebhookDeliveryStatus): string {
    return statusLabel(status);
  }

  protected eventsOf(webhook: WebhookView): string {
    return webhook.events.length === 0
      ? $localize`:@@parametres.webhooks.aucunEvenement:Aucun (test seulement)`
      : webhook.events.map(eventLabel).join(', ');
  }

  protected add(): void {
    this.openForm(null);
  }

  protected edit(webhook: WebhookView): void {
    this.openForm(webhook);
  }

  private openForm(webhook: WebhookView | null): void {
    const ref = this.dialog.open<WebhookFormDialog, WebhookFormData, WebhookRequest | null>(
      WebhookFormDialog,
      { data: { webhook }, width: '40rem', maxWidth: '95vw', autoFocus: 'first-tabbable' },
    );
    ref.afterClosed().subscribe((request) => {
      if (request) {
        void this.save(webhook, request);
      }
    });
  }

  private async save(webhook: WebhookView | null, request: WebhookRequest): Promise<void> {
    this.busy.set(true);
    try {
      const saved = webhook
        ? await this.api.update(webhook.id, request)
        : await this.api.create(request);
      this.secret.set(saved.secret ? { name: saved.webhook.name, secret: saved.secret } : null);
      this.notifications.notify({
        title: $localize`:@@parametres.webhooks.enregistre:Webhook enregistré.`,
        variant: 'success',
        timeout: 3000,
      });
      await this.load();
    } catch (error) {
      this.notifyError(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async test(webhook: WebhookView): Promise<void> {
    this.busy.set(true);
    try {
      const result = await this.api.test(webhook.id);
      const code = result.httpStatus === null ? '—' : String(result.httpStatus);
      if (result.status === 'DELIVERED') {
        this.notifications.notify({
          title: $localize`:@@parametres.webhooks.testOk:Test livré : code ${code}:code: en ${result.durationMs}:duree: ms.`,
          variant: 'success',
          timeout: 5000,
        });
      } else {
        this.notifications.notify({
          title: $localize`:@@parametres.webhooks.testEchec:Test non livré (code ${code}:code:, ${result.durationMs}:duree: ms).`,
          message: result.error ?? '',
          variant: 'warning',
        });
      }
      await this.load();
    } catch (error) {
      this.notifyError(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected journal(webhook: WebhookView): void {
    this.dialog
      .open<WebhookLivraisonsDialog, WebhookLivraisonsData>(WebhookLivraisonsDialog, {
        data: { webhook },
        width: '64rem',
        maxWidth: '95vw',
      })
      .afterClosed()
      .subscribe(() => void this.load());
  }

  protected async regenerate(webhook: WebhookView): Promise<void> {
    const ok = await this.confirm.ask({
      title: $localize`:@@parametres.webhooks.regenererTitre:Régénérer le secret ?`,
      message: $localize`:@@parametres.webhooks.regenererMessage:Le récepteur de « ${webhook.name}:nom: » refusera les livraisons tant qu'il n'aura pas le nouveau secret.`,
      confirmLabel: $localize`:@@parametres.webhooks.regenerer:Régénérer le secret`,
      danger: true,
    });
    if (!ok) {
      return;
    }
    try {
      const { secret } = await this.api.regenerateSecret(webhook.id);
      this.secret.set({ name: webhook.name, secret });
    } catch (error) {
      this.notifyError(error);
    }
  }

  protected async remove(webhook: WebhookView): Promise<void> {
    const ok = await this.confirm.ask({
      title: $localize`:@@parametres.webhooks.supprimerTitre:Supprimer ce webhook ?`,
      message: $localize`:@@parametres.webhooks.supprimerMessage:« ${webhook.name}:nom: » ne recevra plus rien, et son journal de livraisons part avec lui.`,
      confirmLabel: $localize`:@@common.delete:Supprimer`,
      danger: true,
    });
    if (!ok) {
      return;
    }
    try {
      await this.api.delete(webhook.id);
      await this.load();
    } catch (error) {
      this.notifyError(error);
    }
  }

  protected async copy(secret: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(secret);
      this.notifications.notify({
        title: $localize`:@@parametres.webhooks.copie:Secret copié.`,
        variant: 'success',
        timeout: 3000,
      });
    } catch {
      this.notifications.notify({
        title: $localize`:@@parametres.mural.copieImpossible:Copie impossible : sélectionnez l'adresse à la main.`,
        variant: 'warning',
      });
    }
  }

  private async load(): Promise<void> {
    try {
      this.webhooks.set(await this.api.list());
    } catch (error) {
      this.notifyError(error);
    }
  }

  private notifyError(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: errorMessage(error),
      variant: 'error',
    });
  }
}
