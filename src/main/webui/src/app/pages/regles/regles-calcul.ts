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
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { EditionStore } from '../../core/edition.store';
import { errorPrefix } from '../../core/error-message';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { GelNotice } from '../../shared/gel-notice';
import { StatusMessage } from '../../shared/status-message';
import {
  SOLVER_DURATION_UNIT_STEP,
  SolverDurationUnit,
  bestUnitFor,
  budgetToSend,
  formatSeconds,
  secondsToValue,
  valueToSeconds,
} from '../solver/solver-duration';

/** A plateau as said on screen: « jamais » for 0. */
function plateauLabel(seconds: number): string {
  return seconds === 0 ? $localize`:@@solverBudget.never:jamais` : formatSeconds(seconds);
}

/**
 * « Règles du planning › Calcul »: what shapes a solve without being a rule —
 * its budget (the longest it may run, how long a feasible plan may go without
 * improving, whether its end is mailed), the hour the evening starts for the
 * Équité screen, and the ninja typologie. Rare settings, all on one form with
 * one « Enregistrer »: nothing here is written as it is typed.
 *
 * <p>The budget moved here from the Solveur page, which now only solves; its
 * arithmetic stays in `solver/solver-duration.ts`, unit tested there.</p>
 */
@Component({
  selector: 'app-regles-calcul',
  imports: [
    FormsModule,
    GelNotice,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './regles-calcul.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReglesCalcul implements OnInit {
  private readonly solverSettings = inject(SolverSettingsService);
  private readonly notifications = inject(NotificationService);
  private readonly jobs = inject(SolverJobService);
  private readonly editions = inject(EditionStore);
  private readonly adminApi = inject(AdminApi);
  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly crud = inject(ReferenceCrudService);
  protected readonly store = inject(ReferenceDataStore);
  private readonly gel = injectGelReferentiel();

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  /* ------------------------------ Budget ----------------------------------- */

  protected readonly unit = signal<SolverDurationUnit>('MINUTES');
  protected readonly valueDraft = signal(0);
  private readonly secondsSaved = signal(0);
  protected readonly secondsDraft = computed(() => valueToSeconds(this.valueDraft(), this.unit()));
  protected readonly step = computed(() => SOLVER_DURATION_UNIT_STEP[this.unit()]);

  protected readonly plateauUnit = signal<SolverDurationUnit>('MINUTES');
  protected readonly plateauDraft = signal(0);
  private readonly plateauSaved = signal(0);
  protected readonly plateauSecondsDraft = computed(() =>
    valueToSeconds(this.plateauDraft(), this.plateauUnit()),
  );
  protected readonly plateauStep = computed(() => SOLVER_DURATION_UNIT_STEP[this.plateauUnit()]);

  /**
   * « Revenir au défaut » is a draft like any other: it puts the instance's
   * values back in the fields and saves nothing — the edition follows the
   * instance again once « Enregistrer » sends it.
   */
  private readonly backToDefault = signal(false);

  protected readonly bounds = this.solverSettings.bounds;
  /** Whether the edition set anything of its own — what « Revenir au défaut » undoes. */
  protected readonly customised = computed(
    () =>
      !this.backToDefault() &&
      (this.solverSettings.dureeResolutionSecondes() !== null ||
        this.solverSettings.plateauSecondes() !== null),
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

  /** What « Enregistrer » sends for the duration: `null` while it follows the instance. */
  private readonly durationToSend = computed(() =>
    this.backToDefault()
      ? null
      : budgetToSend(
          this.solverSettings.dureeResolutionSecondes(),
          this.secondsDraft(),
          this.bounds()?.defaultSecondsLimit ?? null,
        ),
  );

  /** What « Enregistrer » sends for the plateau: `null` while it follows the instance. */
  private readonly plateauToSend = computed(() =>
    this.backToDefault()
      ? null
      : budgetToSend(
          this.solverSettings.plateauSecondes(),
          this.plateauSecondsDraft(),
          this.bounds()?.defaultPlateauSeconds ?? null,
        ),
  );

  private readonly budgetDirty = computed(
    () =>
      this.durationToSend() !== this.solverSettings.dureeResolutionSecondes() ||
      this.plateauToSend() !== this.solverSettings.plateauSecondes() ||
      (!this.backToDefault() &&
        (Math.round(this.secondsDraft()) !== this.secondsSaved() ||
          Math.round(this.plateauSecondsDraft()) !== this.plateauSaved())),
  );

  /**
   * Refused before the server does: the same rules, said where the value is
   * typed. As on the server, a ceiling binds only a half the write changes,
   * and the plateau is held against the duration only when the edition sets
   * one of its own.
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

  /* --------------------------- Mail at the end ------------------------------ */

  /** Admin address configured server-side, `null` when mail is disabled entirely. */
  protected readonly adminEmail = signal<string | null>(null);
  protected readonly mailDraft = signal<boolean | null>(null);
  protected readonly mailFinResolution = computed(
    () => this.mailDraft() ?? this.solverSettings.mailFinResolution(),
  );
  private readonly mailDirty = computed(
    () => this.mailDraft() !== null && this.mailDraft() !== this.solverSettings.mailFinResolution(),
  );

  /* -------------------------- Start of the evening -------------------------- */

  private readonly soireeStored = signal('');
  protected readonly soireeDraft = signal<string | null>(null);
  protected readonly heureDebutSoiree = computed(() => this.soireeDraft() ?? this.soireeStored());
  private readonly soireeDirty = computed(
    () => this.soireeDraft() !== null && this.soireeDraft() !== this.soireeStored(),
  );

  /* ------------------------------ Ninja typologie ---------------------------- */

  /** Id of the typologie currently flagged ninja — at most one, `null` when none. */
  private readonly ninjaStored = computed(
    () => this.store.typologies().find((typologie) => typologie.ninja)?.id ?? null,
  );
  /** `undefined` while untouched: `null` is a choice of its own, « Aucune ». */
  protected readonly ninjaDraft = signal<string | null | undefined>(undefined);
  protected readonly typologieNinjaId = computed(() =>
    this.ninjaDraft() === undefined ? this.ninjaStored() : (this.ninjaDraft() ?? null),
  );
  private readonly ninjaDirty = computed(
    () => this.ninjaDraft() !== undefined && this.ninjaDraft() !== this.ninjaStored(),
  );
  /** The ninja typologie is one of the fields a TYPOLOGIES_EMPLACEMENTS freeze covers (ADR 0052). */
  protected readonly typologiesFrozen = computed(() =>
    this.gel.isFrozen('TYPOLOGIES_EMPLACEMENTS'),
  );

  /**
   * Without a ninja typologie nobody is polyvalent — nobody can be seated
   * outside their own competences, and the rule keeping one polyvalent free per
   * timeslot has nothing to protect: a silent degradation worth a sentence.
   */
  protected readonly alerteNinjaManquant = computed(() => {
    if (this.store.typologies().length === 0 || this.typologieNinjaId() !== null) {
      return '';
    }
    return $localize`:@@typologies.ninjaManquant:Aucune typologie « ninja » n'est désignée : aucun animateur n'est polyvalent, et la contrainte « préserver un polyvalent libre par créneau » ne protège plus rien.`;
  });

  /* --------------------------------- Form ----------------------------------- */

  /** Something typed on this tab that is not saved. */
  readonly dirty = computed(
    () => this.budgetDirty() || this.mailDirty() || this.soireeDirty() || this.ninjaDirty(),
  );

  /** A solve holds the edition: the writes it would land over wait, like everywhere else. */
  protected readonly locked = computed(() => this.jobs.editingLocked());

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const [, legaux] = await Promise.all([
        this.solverSettings.refresh(),
        this.constraintsApi.legalParameters(),
        this.adminApi
          .mailConfig()
          .then((config) => this.adminEmail.set(config.adminEmail))
          .catch(() => this.adminEmail.set(null)),
      ]);
      this.soireeStored.set((legaux.heureDebutSoiree ?? '').slice(0, 5));
      this.applyStored();
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected onValueDraftChange(value: number): void {
    this.backToDefault.set(false);
    this.valueDraft.set(value);
  }

  protected onPlateauDraftChange(value: number): void {
    this.backToDefault.set(false);
    this.plateauDraft.set(value);
  }

  /** Switching unit re-expresses the current draft value, it never resets it (3 min → 180 s). */
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

  /** « Revenir au défaut »: the instance's values in the fields, sent on « Enregistrer ». */
  protected resetToDefault(): void {
    const bounds = this.bounds();
    if (!bounds) {
      return;
    }
    this.backToDefault.set(true);
    this.unit.set(bestUnitFor(bounds.defaultSecondsLimit));
    this.valueDraft.set(secondsToValue(bounds.defaultSecondsLimit, this.unit()));
    this.plateauUnit.set(bestUnitFor(bounds.defaultPlateauSeconds));
    this.plateauDraft.set(secondsToValue(bounds.defaultPlateauSeconds, this.plateauUnit()));
  }

  /** Puts every field back to what is stored. */
  cancel(): void {
    this.backToDefault.set(false);
    this.mailDraft.set(null);
    this.soireeDraft.set(null);
    this.ninjaDraft.set(undefined);
    this.applyStored();
    this.error.set('');
  }

  /**
   * One « Enregistrer » for the tab, each record written only when something
   * of it changed: the budget and the mail share a payload, the evening is a
   * field of the legal parameters — re-read just before, so a save here never
   * resets what the « Légal » tab stored meanwhile —, the ninja is a flag of
   * one typologie.
   */
  protected async save(): Promise<void> {
    if (!this.dirty() || this.draftError() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      if (this.budgetDirty() || this.mailDirty()) {
        await this.solverSettings.setSettings(
          this.durationToSend(),
          this.plateauToSend(),
          this.mailFinResolution(),
        );
        this.backToDefault.set(false);
        this.mailDraft.set(null);
        this.applyStored(this.unit(), this.plateauUnit());
      }
      if (this.soireeDirty()) {
        const legaux = await this.constraintsApi.legalParameters();
        const saved = await this.constraintsApi.saveLegalParameters({
          ...legaux,
          heureDebutSoiree: this.heureDebutSoiree(),
        });
        this.soireeStored.set((saved.heureDebutSoiree ?? '').slice(0, 5));
        this.soireeDraft.set(null);
      }
      if (this.ninjaDirty()) {
        await this.saveNinja(this.typologieNinjaId());
        this.ninjaDraft.set(undefined);
      }
      this.notifications.notify({
        title: $localize`:@@regles.calcul.saved:Réglages du calcul enregistrés`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.saving.set(false);
    }
  }

  /**
   * Promotes `id` as the single ninja typologie, or clears the flag when `id`
   * is `null`. Only the newly chosen typologie is sent: the server demotes the
   * previous holder in the same transaction.
   */
  private async saveNinja(id: string | null): Promise<void> {
    const label = $localize`:@@typologies.entityLabel:Typologie`;
    const courante = this.store.typologies().find((typologie) => typologie.ninja) ?? null;
    if (id === null) {
      if (courante) {
        await this.crud.save('typologies', { ...courante, ninja: false }, courante.id, label, {
          text: courante.label,
        });
      }
      return;
    }
    const target = this.store.typologies().find((typologie) => typologie.id === id);
    if (target) {
      await this.crud.save('typologies', { ...target, ninja: true }, target.id, label, {
        text: target.label,
      });
    }
  }

  /** Syncs the budget drafts from what the server holds, in the given units or the best ones. */
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
