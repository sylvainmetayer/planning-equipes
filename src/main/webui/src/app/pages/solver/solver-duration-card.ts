import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { errorPrefix } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import {
  SOLVER_DURATION_UNIT_STEP,
  SolverDurationUnit,
  bestUnitFor,
  secondsToValue,
  valueToSeconds,
} from './solver-duration';
import { StatusMessage } from '../../shared/status-message';

/**
 * The solve budget, edited in the unit the operator thinks in and stored
 * server-side in seconds (`SolverSettingsService`). Self-contained: it reads
 * the setting when it appears and writes it on « Enregistrer » only — an
 * unsaved value never silently applies. The arithmetic lives in
 * `solver-duration.ts`, unit tested without rendering.
 */
@Component({
  selector: 'app-solver-duration-card',
  imports: [
    StatusMessage,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './solver-duration-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SolverDurationCard implements OnInit {
  private readonly solverSettings = inject(SolverSettingsService);
  private readonly notifications = inject(NotificationService);

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly unit = signal<SolverDurationUnit>('MINUTES');
  protected readonly valueDraft = signal(0);
  protected readonly secondsSaved = signal(0);
  protected readonly secondsDraft = computed(() => valueToSeconds(this.valueDraft(), this.unit()));
  protected readonly dirty = computed(
    () => Math.round(this.secondsDraft()) !== this.secondsSaved(),
  );
  protected readonly step = computed(() => SOLVER_DURATION_UNIT_STEP[this.unit()]);

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      await this.solverSettings.refresh();
      const seconds = this.solverSettings.secondsLimit();
      this.applySeconds(seconds, bestUnitFor(seconds));
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected onValueDraftChange(value: number): void {
    this.valueDraft.set(value);
  }

  /** Switching unit re-expresses the current draft value, it never resets it (e.g. 3 min → 180 s, not back to 0). */
  protected onUnitChange(unit: SolverDurationUnit): void {
    const seconds = this.secondsDraft();
    this.unit.set(unit);
    this.valueDraft.set(secondsToValue(seconds, unit));
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.error.set('');
    try {
      await this.solverSettings.setSecondsLimit(this.secondsDraft());
      this.applySeconds(this.solverSettings.secondsLimit(), this.unit());
      this.notifications.notify({
        title: $localize`:@@dataSetup.solverDuration.saved:Durée de résolution enregistrée`,
        message: $localize`:@@dataSetup.solverDuration.savedHint:Appliquée à tous les navigateurs.`,
        variant: 'success',
      });
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.saving.set(false);
    }
  }

  /** Syncs draft + saved state from a seconds value freshly read from (or written to) the server, in the given unit. */
  private applySeconds(seconds: number, unit: SolverDurationUnit): void {
    this.unit.set(unit);
    this.valueDraft.set(secondsToValue(seconds, unit));
    this.secondsSaved.set(Math.round(seconds));
  }
}
