import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { WebhookFormat, WebhookRequest, WebhookView } from '../../core/models';
import {
  SUBSCRIBABLE_EVENTS,
  WEBHOOK_FORMATS,
  WebhookDraft,
  addressIsSecret,
  draftComplete,
  eventLabel,
  formatLabel,
  toRequest,
} from './webhooks';

/** The webhook being edited, or `null` for a creation. */
export interface WebhookFormData {
  webhook: WebhookView | null;
}

/**
 * The form of one webhook, adapted to its format: an address for the generic
 * JSON and the three chat presets, a bot token and a chat for Telegram. A
 * secret is never shown back, so on an edit a blank address or token keeps
 * the stored one.
 */
@Component({
  selector: 'app-webhook-form-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
  ],
  templateUrl: './webhook-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WebhookFormDialog {
  protected readonly dialogRef =
    inject<MatDialogRef<WebhookFormDialog, WebhookRequest | null>>(MatDialogRef);
  protected readonly data = inject<WebhookFormData>(MAT_DIALOG_DATA);

  protected readonly formats = WEBHOOK_FORMATS;
  protected readonly events = SUBSCRIBABLE_EVENTS;
  protected readonly editing = this.data.webhook !== null;

  protected readonly draft = signal<WebhookDraft>({
    name: this.data.webhook?.name ?? '',
    format: this.data.webhook?.format ?? 'GENERIC',
    // A generic address is no secret and comes back as typed; the others never do.
    url: this.data.webhook?.format === 'GENERIC' ? this.data.webhook.destination : '',
    token: '',
    chatId: this.data.webhook?.chatId ?? '',
    events: [...(this.data.webhook?.events ?? [])],
    active: this.data.webhook?.active ?? true,
  });

  protected readonly complete = computed(() =>
    draftComplete(this.draft(), this.data.webhook?.format ?? null),
  );

  /** Whether a blank secret keeps the stored one: an edit that keeps its format. */
  protected readonly keepsSecret = computed(
    () => this.editing && this.data.webhook?.format === this.draft().format,
  );

  protected formatLabel(format: WebhookFormat): string {
    return formatLabel(format);
  }

  protected eventLabel(code: string): string {
    return eventLabel(code);
  }

  protected addressIsSecret(format: WebhookFormat): boolean {
    return addressIsSecret(format);
  }

  protected patch(champs: Partial<WebhookDraft>): void {
    this.draft.update((courant) => ({ ...courant, ...champs }));
  }

  protected toggleEvent(code: string, checked: boolean): void {
    this.draft.update((courant) => ({
      ...courant,
      events: checked
        ? [...courant.events.filter((each) => each !== code), code]
        : courant.events.filter((each) => each !== code),
    }));
  }

  protected submit(): void {
    if (this.complete()) {
      this.dialogRef.close(toRequest(this.draft()));
    }
  }
}
