import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { ConstraintsView } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { OutputPanel } from '../../shared/output-panel';
import { APP_VERSION, REPO_URL } from '../../version';

/** Unit the Débogage tab edits the solver duration in — always converted to/from seconds for the API. */
export type SolverDurationUnit = 'SECONDES' | 'MINUTES' | 'HEURES';

const SOLVER_DURATION_UNIT_FACTORS: Record<SolverDurationUnit, number> = {
  SECONDES: 1,
  MINUTES: 60,
  HEURES: 3600
};

/** Per-unit `<input type="number">` granularity: whole seconds, half-minutes, quarter-hours. */
const SOLVER_DURATION_UNIT_STEP: Record<SolverDurationUnit, number> = {
  SECONDES: 1,
  MINUTES: 0.5,
  HEURES: 0.25
};

function secondsToValue(seconds: number, unit: SolverDurationUnit): number {
  return seconds / SOLVER_DURATION_UNIT_FACTORS[unit];
}

function valueToSeconds(value: number, unit: SolverDurationUnit): number {
  return value * SOLVER_DURATION_UNIT_FACTORS[unit];
}

/** Picks the largest unit that represents `seconds` as a whole number, so e.g. 180s shows as "3 min", not "0.05 h". */
function bestUnitFor(seconds: number): SolverDurationUnit {
  if (seconds !== 0 && seconds % SOLVER_DURATION_UNIT_FACTORS.HEURES === 0) {
    return 'HEURES';
  }
  if (seconds % SOLVER_DURATION_UNIT_FACTORS.MINUTES === 0) {
    return 'MINUTES';
  }
  return 'SECONDES';
}

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
    MatSelectModule,
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

  protected readonly solverDurationLoading = signal(false);
  protected readonly solverDurationSaving = signal(false);
  protected readonly solverDurationError = signal('');

  /**
   * Editable value + unit for the duration persisted server-side as seconds
   * (`/api/parametres-solveur`), only sent back when the user clicks
   * "Enregistrer" — an unsaved value never silently applies. Tracked against
   * {@link solverDurationSecondsSaved} rather than {@link SolverSettingsService}
   * directly, since that signal is fetched asynchronously and may not be
   * loaded yet when this field initializes.
   */
  protected readonly solverDurationUnit = signal<SolverDurationUnit>('MINUTES');
  protected readonly solverDurationValueDraft = signal(0);
  protected readonly solverDurationSecondsSaved = signal(0);
  protected readonly solverDurationSecondsDraft = computed(() =>
    valueToSeconds(this.solverDurationValueDraft(), this.solverDurationUnit())
  );
  protected readonly solverDurationDirty = computed(
    () => Math.round(this.solverDurationSecondsDraft()) !== this.solverDurationSecondsSaved()
  );
  protected readonly solverDurationStep = computed(() => SOLVER_DURATION_UNIT_STEP[this.solverDurationUnit()]);

  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly solverSettings = inject(SolverSettingsService);

  constructor() {
    void this.refresh();
    void this.loadSolverDuration();
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

  private async loadSolverDuration(): Promise<void> {
    this.solverDurationLoading.set(true);
    this.solverDurationError.set('');
    try {
      await this.solverSettings.refresh();
      const seconds = this.solverSettings.secondsLimit();
      this.applySolverDurationSeconds(seconds, bestUnitFor(seconds));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.solverDurationError.set($localize`:@@common.errorPrefix:Erreur : ${message}:message:`);
    } finally {
      this.solverDurationLoading.set(false);
    }
  }

  protected onSolverDurationValueDraftChange(value: number): void {
    this.solverDurationValueDraft.set(value);
  }

  /** Switching unit re-expresses the current draft value, it never resets it (e.g. 3 min → 180 s, not back to 0). */
  protected onSolverDurationUnitChange(unit: SolverDurationUnit): void {
    const seconds = this.solverDurationSecondsDraft();
    this.solverDurationUnit.set(unit);
    this.solverDurationValueDraft.set(secondsToValue(seconds, unit));
  }

  protected async saveSolverDuration(): Promise<void> {
    this.solverDurationSaving.set(true);
    this.solverDurationError.set('');
    try {
      await this.solverSettings.setSecondsLimit(this.solverDurationSecondsDraft());
      this.applySolverDurationSeconds(this.solverSettings.secondsLimit(), this.solverDurationUnit());
      this.notifications.notify({
        title: $localize`:@@debug.solverDuration.saved:Durée de résolution enregistrée`,
        message: $localize`:@@debug.solverDuration.savedHint:Appliquée à tous les navigateurs.`,
        variant: 'success'
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.solverDurationError.set($localize`:@@common.errorPrefix:Erreur : ${message}:message:`);
    } finally {
      this.solverDurationSaving.set(false);
    }
  }

  /** Syncs draft + saved state from a seconds value freshly read from (or written to) the server, in the given unit. */
  private applySolverDurationSeconds(seconds: number, unit: SolverDurationUnit): void {
    this.solverDurationUnit.set(unit);
    this.solverDurationValueDraft.set(secondsToValue(seconds, unit));
    this.solverDurationSecondsSaved.set(Math.round(seconds));
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
