import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ApiService } from '../../core/api.service';
import { ConstraintsView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
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
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatDividerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    OutputPanel
  ],
  templateUrl: './debug-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DebugPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly output = signal('');
  protected readonly appVersion = APP_VERSION;
  protected readonly repoUrl = REPO_URL;

  /** Minutes, derived from the seconds stored by {@link SolverSettingsService} — the unit solvers/backend use. */
  protected readonly solverDurationMinutes = computed(() => this.solverSettings.secondsLimit() / 60);

  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly solverSettings = inject(SolverSettingsService);

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

  protected onSolverDurationMinutesChange(minutes: number): void {
    this.solverSettings.setSecondsLimit(minutes * 60);
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
}
