import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ApiService } from '../../core/api.service';
import { ConstraintsView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { OutputPanel } from '../../shared/output-panel';
import { APP_VERSION, REPO_URL } from '../../version';

/**
 * Raw dump of the last solve/analyze diagnostic (`GET /api/constraints`):
 * global score, unfilled seats, feasibility and per-constraint score/match
 * count. Deliberately excludes animateurs/creneaux/postes — this is a debug
 * aid, kept separate from the "Constraints" page's business-friendly card
 * view. The backend keeps the last diagnostic in memory for the life of the
 * server process, so this survives a browser refresh (it is only lost if the
 * server itself restarts).
 */
@Component({
  selector: 'app-debug-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatProgressBarModule, OutputPanel],
  templateUrl: './debug-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DebugPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly output = signal('');
  protected readonly appVersion = APP_VERSION;
  protected readonly repoUrl = REPO_URL;

  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);

  constructor() {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const view = await this.api.get<ConstraintsView>('/api/constraints');
      this.output.set(JSON.stringify(view, null, 2));
    } catch (error) {
      this.output.set('');
      const message = error instanceof Error ? error.message : String(error);
      this.error.set($localize`:@@common.errorPrefix:Erreur : ${message}:message:`);
    } finally {
      this.loading.set(false);
    }
  }

  /** Exercises the info/warning/alert path end-to-end: snack bar and the persisted Notifications log. */
  protected sendTestNotification(severity: 'info' | 'warning' | 'alert'): void {
    const variant = severity === 'alert' ? 'error' : severity;
    this.notifications.notify({
      title: $localize`:@@debug.testNotification.title:Notification de test (${severity}:severity:)`,
      message: $localize`:@@debug.testNotification.message:Générée depuis la page Débogage.`,
      variant
    });
  }

  /**
   * Thrown straight from a template event handler, so it reaches Angular's
   * `ErrorHandler` the same way a real unhandled bug would — that's the
   * handler Sentry's `createErrorHandler()` replaces (see observability.ts),
   * so this exercises the exact same path a genuine frontend crash takes.
   */
  protected triggerFrontException(): void {
    throw new Error('Test exception (bouton Débogage / Exception front)');
  }

  /**
   * The endpoint always throws: `GlobalExceptionMapper` reports it to
   * Sentry/Bugsink server-side before answering 500, which is the point of
   * this button. The resulting rejection is expected and not worth surfacing.
   */
  protected async triggerBackException(): Promise<void> {
    try {
      await this.api.post('/api/debug/test-exception', {});
    } catch {
      // Expected: see the docstring above.
    }
  }
}
