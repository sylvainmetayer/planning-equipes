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
import { EditionStore } from '../../core/edition.store';
import { errorPrefix } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import {
  SOLVER_DURATION_UNIT_STEP,
  SolverDurationUnit,
  bestUnitFor,
  budgetToSend,
  formatSeconds,
  secondsToValue,
  valueToSeconds,
} from './solver-duration';
import { StatusMessage } from '../../shared/status-message';

/**
 * The edition's solve budget — the longest a solve may run, and how long a
 * feasible planning may go without improving before it stops — edited in the
 * unit the operator thinks in and stored server-side in seconds
 * (`SolverSettingsService`), under the ceiling the operator of the instance
 * set. It reads the setting when it appears and writes it on « Enregistrer »
 * only — an unsaved value never silently applies. The arithmetic lives in
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
  private readonly jobs = inject(SolverJobService);
  private readonly editions = inject(EditionStore);

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly unit = signal<SolverDurationUnit>('MINUTES');
  protected readonly valueDraft = signal(0);
  protected readonly secondsSaved = signal(0);
  protected readonly secondsDraft = computed(() => valueToSeconds(this.valueDraft(), this.unit()));
  protected readonly step = computed(() => SOLVER_DURATION_UNIT_STEP[this.unit()]);

  protected readonly plateauUnit = signal<SolverDurationUnit>('MINUTES');
  protected readonly plateauDraft = signal(0);
  protected readonly plateauSaved = signal(0);
  protected readonly plateauSecondsDraft = computed(() =>
    valueToSeconds(this.plateauDraft(), this.plateauUnit()),
  );
  protected readonly plateauStep = computed(() => SOLVER_DURATION_UNIT_STEP[this.plateauUnit()]);

  protected readonly dirty = computed(
    () =>
      Math.round(this.secondsDraft()) !== this.secondsSaved() ||
      Math.round(this.plateauSecondsDraft()) !== this.plateauSaved(),
  );

  protected readonly bounds = this.solverSettings.bounds;
  /** Whether the edition set anything of its own — what « Revenir au défaut » undoes. */
  protected readonly customised = computed(
    () =>
      this.solverSettings.dureeResolutionSecondes() !== null ||
      this.solverSettings.plateauSecondes() !== null,
  );

  protected readonly ceilingText = computed(() => {
    const bounds = this.bounds();
    return bounds
      ? $localize`:@@solverBudget.ceiling:Au plus ${formatSeconds(bounds.maxSecondsLimit)}:max: par résolution sur cette instance, fixé par l'exploitant.`
      : '';
  });

  protected readonly defaultsText = computed(() => {
    const bounds = this.bounds();
    if (!bounds) {
      return '';
    }
    const defaults = $localize`:@@solverBudget.defaults:Défaut de l'instance : ${formatSeconds(bounds.defaultSecondsLimit)}:duree:, plateau ${plateauLabel(bounds.defaultPlateauSeconds)}:plateau:.`;
    return this.customised()
      ? defaults
      : `${defaults} ${$localize`:@@solverBudget.followsDefault:Cette édition le suit.`}`;
  });

  /** What the solve under way on this edition was actually given, and why it differs, if it does. */
  protected readonly runningText = computed(() => {
    const job = this.jobs.activeJob();
    const edition = this.editions.courant()?.id ?? null;
    if (!job || job.secondsLimit == null || (edition !== null && job.editionId !== edition)) {
      return '';
    }
    const plateau =
      job.plateauSeconds == null ? '' : `, plateau ${plateauLabel(job.plateauSeconds)}`;
    const lines = [
      $localize`:@@solverBudget.running:Calcul en cours : ${formatSeconds(job.secondsLimit)}:duree:${plateau}:plateau:.`,
    ];
    if (job.cappedFromSecondsLimit != null) {
      lines.push(
        $localize`:@@solverBudget.durationCapped:La durée enregistrée (${formatSeconds(job.cappedFromSecondsLimit)}:stored:) dépasse le plafond de l'instance : ce calcul tourne au plafond, ${formatSeconds(job.secondsLimit)}:max:.`,
      );
    }
    if (job.cappedFromPlateauSeconds != null && job.plateauSeconds != null) {
      lines.push(
        $localize`:@@solverBudget.plateauCapped:L'arrêt sur plateau enregistré (${formatSeconds(job.cappedFromPlateauSeconds)}:stored:) dépasse le plafond de l'instance : ce calcul s'arrête au plafond, ${formatSeconds(job.plateauSeconds)}:max:.`,
      );
    }
    return lines.join(' ');
  });

  /** What « Enregistrer » sends for the duration: `null` while it still follows the instance. */
  private readonly durationToSend = computed(() =>
    budgetToSend(
      this.solverSettings.dureeResolutionSecondes(),
      this.secondsDraft(),
      this.bounds()?.defaultSecondsLimit ?? null,
    ),
  );

  /** What « Enregistrer » sends for the plateau: `null` while it still follows the instance. */
  private readonly plateauToSend = computed(() =>
    budgetToSend(
      this.solverSettings.plateauSecondes(),
      this.plateauSecondsDraft(),
      this.bounds()?.defaultPlateauSeconds ?? null,
    ),
  );

  /**
   * Refused before the server does: the same rules, said where the value is
   * typed. As on the server, a ceiling binds only a half the write changes — a
   * value stored before the ceiling was lowered runs capped, and must not
   * block saving the other half — and the plateau is held against the
   * duration only when the edition sets one of its own.
   */
  protected readonly draftError = computed(() => {
    const bounds = this.bounds();
    const seconds = Math.round(this.secondsDraft());
    const plateau = Math.round(this.plateauSecondsDraft());
    const durationChanged = this.durationToSend() !== this.solverSettings.dureeResolutionSecondes();
    const plateauChanged = this.plateauToSend() !== this.solverSettings.plateauSecondes();
    if (seconds <= 0) {
      return $localize`:@@solverBudget.durationPositive:La durée doit être strictement positive.`;
    }
    if (plateau < 0) {
      return $localize`:@@solverBudget.plateauNegative:Le plateau ne peut pas être négatif ; 0 signifie jamais.`;
    }
    if (bounds && durationChanged && seconds > bounds.maxSecondsLimit) {
      return $localize`:@@solverBudget.aboveCeiling:Au plus ${formatSeconds(bounds.maxSecondsLimit)}:max: sur cette instance.`;
    }
    if (bounds && plateauChanged && plateau > bounds.maxPlateauSeconds) {
      return $localize`:@@solverBudget.plateauAboveCeiling:Plateau : au plus ${formatSeconds(bounds.maxPlateauSeconds)}:max: sur cette instance.`;
    }
    if (this.plateauToSend() !== null && plateau > seconds) {
      return $localize`:@@solverBudget.plateauAboveDuration:Le plateau ne peut pas dépasser la durée.`;
    }
    return '';
  });

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      await this.solverSettings.refresh();
      this.applyStored();
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected onValueDraftChange(value: number): void {
    this.valueDraft.set(value);
  }

  protected onPlateauDraftChange(value: number): void {
    this.plateauDraft.set(value);
  }

  /** Switching unit re-expresses the current draft value, it never resets it (e.g. 3 min → 180 s, not back to 0). */
  protected onUnitChange(unit: SolverDurationUnit): void {
    const seconds = this.secondsDraft();
    this.unit.set(unit);
    this.valueDraft.set(secondsToValue(seconds, unit));
  }

  protected onPlateauUnitChange(unit: SolverDurationUnit): void {
    const seconds = this.plateauSecondsDraft();
    this.plateauUnit.set(unit);
    this.plateauDraft.set(secondsToValue(seconds, unit));
  }

  protected async save(): Promise<void> {
    await this.write(
      this.durationToSend(),
      this.plateauToSend(),
      $localize`:@@solverBudget.saved:Budget de calcul enregistré`,
    );
  }

  /** « Revenir au défaut »: both halves follow the instance again. */
  protected async resetToDefault(): Promise<void> {
    await this.write(
      null,
      null,
      $localize`:@@solverBudget.resetDone:Budget de calcul revenu au défaut de l'instance`,
    );
  }

  private async write(duree: number | null, plateau: number | null, title: string): Promise<void> {
    this.saving.set(true);
    this.error.set('');
    try {
      await this.solverSettings.setBudget(duree, plateau);
      this.applyStored(this.unit(), this.plateauUnit());
      this.notifications.notify({
        title,
        message: $localize`:@@dataSetup.solverDuration.savedHint:Appliquée à tous les navigateurs.`,
        variant: 'success',
      });
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.saving.set(false);
    }
  }

  /** Syncs drafts and saved state from what the server holds, in the given units or the best ones. */
  private applyStored(unit?: SolverDurationUnit, plateauUnit?: SolverDurationUnit): void {
    const seconds = this.solverSettings.secondsLimit();
    const plateau = this.solverSettings.effectivePlateauSeconds() ?? 0;
    const durationUnit = unit ?? bestUnitFor(seconds);
    this.unit.set(durationUnit);
    this.valueDraft.set(secondsToValue(seconds, durationUnit));
    this.secondsSaved.set(Math.round(seconds));
    const unitForPlateau = plateauUnit ?? bestUnitFor(plateau);
    this.plateauUnit.set(unitForPlateau);
    this.plateauDraft.set(secondsToValue(plateau, unitForPlateau));
    this.plateauSaved.set(Math.round(plateau));
  }
}

/** A plateau as said on screen: « jamais » for 0. */
function plateauLabel(seconds: number): string {
  return seconds === 0 ? $localize`:@@solverBudget.never:jamais` : formatSeconds(seconds);
}
