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
import { formatSeconds } from '../solver/solver-duration';

/** A plateau as said on screen: « jamais » for 0. */
function plateauLabel(seconds: number): string {
  return seconds === 0 ? $localize`:@@solverBudget.never:jamais` : formatSeconds(seconds);
}

/**
 * « Règles du planning › Calcul »: what shapes a solve without being a rule —
 * whether its end is mailed, the hour the evening starts for the Équité
 * screen, and the polyvalent typologie. Rare settings, all on one form with one
 * « Enregistrer »: nothing here is written as it is typed.
 *
 * <p>The solve budget (the longest a solve may run, how long a feasible plan
 * may go without improving) is shown first and is not editable: it is the
 * instance's, set by the operator (ADR 0110), the same for every edition.</p>
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

  protected readonly bounds = this.solverSettings.bounds;

  /** The instance's budget, said as the operator would. */
  protected readonly budgetText = computed(() => {
    const bounds = this.bounds();
    return bounds
      ? $localize`:@@solverBudget.instanceValues:Durée maximale d'une résolution : ${formatSeconds(bounds.defaultSecondsLimit)}:duree:. Arrêt faute de progrès suffisant depuis : ${plateauLabel(bounds.defaultPlateauSeconds)}:plateau:.`
      : '';
  });

  /** What the solve under way on this edition was actually given. */
  protected readonly runningText = computed(() => {
    const job = this.jobs.activeJob();
    const edition = this.editions.courant()?.id ?? null;
    if (job?.secondsLimit == null || (edition !== null && job.editionId !== edition)) {
      return '';
    }
    const plateau =
      job.plateauSeconds == null ? '' : `, plateau ${plateauLabel(job.plateauSeconds)}`;
    return $localize`:@@solverBudget.running:Calcul en cours : ${formatSeconds(job.secondsLimit)}:duree:${plateau}:plateau:.`;
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

  /* ------------------------------ Polyvalent typologie ---------------------------- */

  /** Id of the typologie currently flagged polyvalent — at most one, `null` when none. */
  private readonly polyvalentStored = computed(
    () => this.store.typologies().find((typologie) => typologie.polyvalent)?.id ?? null,
  );
  /** `undefined` while untouched: `null` is a choice of its own, « Aucune ». */
  protected readonly polyvalentDraft = signal<string | null | undefined>(undefined);
  protected readonly typologiePolyvalentId = computed(() =>
    this.polyvalentDraft() === undefined
      ? this.polyvalentStored()
      : (this.polyvalentDraft() ?? null),
  );
  private readonly polyvalentDirty = computed(
    () =>
      this.polyvalentDraft() !== undefined && this.polyvalentDraft() !== this.polyvalentStored(),
  );
  /** The polyvalent typologie is one of the fields a TYPOLOGIES_EMPLACEMENTS freeze covers (ADR 0052). */
  protected readonly typologiesFrozen = computed(() =>
    this.gel.isFrozen('TYPOLOGIES_EMPLACEMENTS'),
  );

  /**
   * Without a polyvalent typologie nobody is polyvalent — nobody can be seated
   * outside their own competences, and the rule keeping one polyvalent free per
   * timeslot has nothing to protect: a silent degradation worth a sentence.
   */
  protected readonly alertePolyvalentManquant = computed(() => {
    if (this.store.typologies().length === 0 || this.typologiePolyvalentId() !== null) {
      return '';
    }
    return $localize`:@@typologies.polyvalentManquant:Aucune typologie « polyvalente » n'est désignée : aucun animateur n'est polyvalent, et la contrainte « préserver un polyvalent libre par créneau » ne protège plus rien.`;
  });

  /* --------------------------------- Form ----------------------------------- */

  /** Something typed on this tab that is not saved. */
  readonly dirty = computed(() => this.mailDirty() || this.soireeDirty() || this.polyvalentDirty());

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
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** Puts every field back to what is stored. */
  cancel(): void {
    this.mailDraft.set(null);
    this.soireeDraft.set(null);
    this.polyvalentDraft.set(undefined);
    this.error.set('');
  }

  /**
   * One « Enregistrer » for the tab, each record written only when something
   * of it changed: the mail is a setting of the edition, the evening is a
   * field of the legal parameters — re-read just before, so a save here never
   * resets what the « Légal » tab stored meanwhile —, the polyvalent is a flag of
   * one typologie.
   */
  protected async save(): Promise<void> {
    if (!this.dirty() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      if (this.mailDirty()) {
        await this.solverSettings.setMailFinResolution(this.mailFinResolution());
        this.mailDraft.set(null);
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
      if (this.polyvalentDirty()) {
        await this.savePolyvalent(this.typologiePolyvalentId());
        this.polyvalentDraft.set(undefined);
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
   * Promotes `id` as the single polyvalent typologie, or clears the flag when `id`
   * is `null`. Only the newly chosen typologie is sent: the server demotes the
   * previous holder in the same transaction.
   */
  private async savePolyvalent(id: string | null): Promise<void> {
    const label = $localize`:@@typologies.entityLabel:Typologie`;
    const courante = this.store.typologies().find((typologie) => typologie.polyvalent) ?? null;
    if (id === null) {
      if (courante) {
        await this.crud.save('typologies', { ...courante, polyvalent: false }, courante.id, label, {
          text: courante.label,
        });
      }
      return;
    }
    const target = this.store.typologies().find((typologie) => typologie.id === id);
    if (target) {
      await this.crud.save('typologies', { ...target, polyvalent: true }, target.id, label, {
        text: target.label,
      });
    }
  }
}
