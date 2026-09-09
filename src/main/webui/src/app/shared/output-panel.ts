import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { NotificationService } from '../core/notification.service';

/**
 * Monospaced result panel shared by the action pages (Données, Débogage,
 * Validateur YAML).
 *
 * It is a live region: these panels are where "import terminé", "3 erreurs de
 * validation" or a stack trace land, and they were previously written into
 * silence — a screen-reader user pressed a button and never learned what it
 * did. `role="log"` (rather than `status`) matches what it is: successive
 * lines appended over time, of which the newest matters.
 *
 * The copy button exists because these outputs get pasted into a message: a
 * validation report is meant to be sent to whoever wrote the file.
 */
@Component({
  selector: 'app-output-panel',
  imports: [MatButtonModule, MatCardModule, MatIconModule],
  template: `
    @if (text()) {
      <mat-card appearance="outlined" class="output-panel">
        <mat-card-content>
          <div class="output-panel-actions">
            <button matButton type="button" (click)="copy()">
              <mat-icon>content_copy</mat-icon>
              @if (copie()) {
                <span i18n="@@output.copied">Copié</span>
              } @else {
                <span i18n="@@output.copy">Copier</span>
              }
            </button>
          </div>
          <pre role="log" aria-live="polite">{{ text() }}</pre>
        </mat-card-content>
      </mat-card>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class OutputPanel {
  readonly text = input('');

  /** Switches the button's label for a moment, so the click has a visible effect. */
  protected readonly copie = signal(false);

  private readonly notifications = inject(NotificationService);

  protected async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.text());
      this.copie.set(true);
      setTimeout(() => this.copie.set(false), 2000);
    } catch {
      // Clipboard access can be refused (permissions, insecure context); saying
      // so beats a button that silently does nothing.
      this.notifications.notify({
        title: $localize`:@@output.copyFailed:Copie impossible`,
        message: $localize`:@@output.copyFailedMessage:Le navigateur a refusé l'accès au presse-papiers. Sélectionnez le texte et copiez-le à la main.`,
        variant: 'error'
      });
    }
  }
}
