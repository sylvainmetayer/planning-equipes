import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatStepperModule } from '@angular/material/stepper';
import { StandsApi } from '../../core/api/stands-api';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Stand } from '../../core/models';
import { StatusMessage } from '../../shared/status-message';
import { colonnes, saisie } from '../ouvertures/grille-horaires';
import {
  CreationWeekday,
  CreationWindow,
  creationBounds,
  creationCells,
  creationWeekdays,
  creationWindows,
  isDefaultOpening,
} from './stand-creation';
import { toDraft, versStand } from './stand-draft';
import { NEW_LOCATION, createLocation } from './new-location';

/**
 * « Ajouter un stand », in four steps: identity, game categories, location,
 * hours. The last one lists the windows of the edition's timeslots, each open
 * at a headcount — untick what the stand does not open, type how many people
 * it holds — and the weekdays of the event, to untick a day it is closed on.
 * No rule and no syntax: the choices are saved as the stand's row of
 * the Horaires des stands grid, that grid's own save, which compacts them into rules.
 *
 * Closes with the new stand's id, for the page to open its fiche — cancelled
 * too, once the stand is written: only its hours failed then, and its fiche is
 * where they are typed again.
 */
@Component({
  selector: 'app-stand-creation-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatChipsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatStepperModule,
    StatusMessage,
  ],
  templateUrl: './stand-creation-dialog.html',
  styleUrl: './stand-creation-dialog.css',
  // Global by design (AGENTS.md): loaded with the dialog, unscoped like a partial.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StandCreationDialog {
  protected readonly store = inject(ReferenceDataStore);
  private readonly standsApi = inject(StandsApi);
  private readonly crud = inject(ReferenceCrudService);
  private readonly notifications = inject(NotificationService);
  private readonly dialog = inject(MatDialog);
  private readonly dialogRef =
    inject<MatDialogRef<StandCreationDialog, string | null>>(MatDialogRef);
  private readonly gel = injectGelReferentiel();

  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly locationsFrozen = computed(() => this.gel.isFrozen('TYPOLOGIES_EMPLACEMENTS'));
  protected readonly newLocation = NEW_LOCATION;

  protected readonly nom = signal('');
  protected readonly code = signal('');
  protected readonly effectifMin = signal(1);
  protected readonly effectifMax = signal(2);
  protected readonly reserveMajeurs = signal(false);
  protected readonly typologies = signal<string[]>([]);
  protected readonly emplacementId = signal<string | null>(null);

  /** The edition's columns, the windows of the last step. */
  private readonly openings = resource({ loader: () => this.standsApi.openings() });
  private readonly colonnes = computed(() =>
    this.openings.hasValue() ? colonnes(this.openings.value()) : [],
  );
  protected readonly windows = signal<CreationWindow[]>([]);
  protected readonly weekdays = signal<CreationWeekday[]>([]);
  /** The last step's lines, laid once the columns are in, at the minimum typed in the first. */
  private windowsLaid = false;
  /** The headcount the windows were laid at: left as it is, it writes no grid. */
  private laidEffectif = 1;

  protected readonly saving = signal(false);
  /**
   * The stand, once written: its hours are a second call, and a retry after
   * that one failed must send the hours alone, never a second stand.
   */
  protected readonly createdId = signal<string | null>(null);
  /** The stand was written and its hours were not: said in the dialog, next to « Réessayer ». */
  protected readonly hoursError = signal('');

  protected readonly identityValid = computed(
    () =>
      this.nom().trim() !== '' &&
      this.effectifMin() >= 1 &&
      this.effectifMax() >= this.effectifMin(),
  );
  protected readonly typologiesValid = computed(() => this.typologies().length > 0);

  /** The hours step, the fourth, is laid when it is reached. */
  protected onStep(index: number): void {
    if (index === 3) {
      this.layWindows();
    }
  }

  /** Entering the hours step lays its lines, at the minimum typed in the first step. */
  private layWindows(): void {
    if (this.windowsLaid || !this.openings.hasValue()) {
      return;
    }
    this.windowsLaid = true;
    this.laidEffectif = this.effectifMin();
    this.windows.set(creationWindows(this.colonnes(), this.laidEffectif));
    this.weekdays.set(creationWeekdays(this.openings.value().jours));
  }

  protected toggleTypologie(id: string, selected: boolean): void {
    this.typologies.update((ids) =>
      selected ? [...new Set([...ids, id])] : ids.filter((each) => each !== id),
    );
  }

  protected setWindow(index: number, patch: Partial<CreationWindow>): void {
    this.windows.update((windows) =>
      windows.map((window, i) => (i === index ? { ...window, ...patch } : window)),
    );
  }

  /** A headcount typed: zero, or nothing, closes the window. */
  protected onEffectif(index: number, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    const effectif = Math.max(0, Math.trunc(Number(value) || 0));
    this.setWindow(index, { effectif, open: effectif > 0 });
  }

  protected toggleWeekday(day: number, open: boolean): void {
    this.weekdays.update((days) =>
      days.map((weekday) => (weekday.day === day ? { ...weekday, open } : weekday)),
    );
  }

  protected weekdayLabel(day: number): string {
    switch (day) {
      case 1:
        return $localize`:@@common.weekday.monday:Lundi`;
      case 2:
        return $localize`:@@common.weekday.tuesday:Mardi`;
      case 3:
        return $localize`:@@common.weekday.wednesday:Mercredi`;
      case 4:
        return $localize`:@@common.weekday.thursday:Jeudi`;
      case 5:
        return $localize`:@@common.weekday.friday:Vendredi`;
      case 6:
        return $localize`:@@common.weekday.saturday:Samedi`;
      default:
        return $localize`:@@common.weekday.sunday:Dimanche`;
    }
  }

  /** « Nouveau lieu… » : the location form over this one, what it created then chosen. */
  protected async chooseLocation(value: string | null): Promise<void> {
    if (value !== NEW_LOCATION) {
      this.emplacementId.set(value);
      return;
    }
    const created = await createLocation(this.dialog, this.store);
    this.emplacementId.set(created ?? this.emplacementId());
  }

  /**
   * Writes the stand, then its row of the grid — unless the last step was left
   * as laid, which a new stand already is. Two calls: when the second fails,
   * the stand is kept and a retry sends its hours alone.
   */
  protected async create(): Promise<void> {
    if (!this.identityValid() || !this.typologiesValid() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.hoursError.set('');
    let id = this.createdId();
    try {
      const stand = this.standToWrite();
      if (id === null) {
        const written = await this.store.save('stands', stand, null);
        if (written.id === null || written.id === undefined) {
          return;
        }
        id = String(written.id);
        this.createdId.set(id);
      }
      if (
        this.windows().length > 0 &&
        !isDefaultOpening(this.windows(), this.weekdays(), this.laidEffectif)
      ) {
        await this.saveHours(id);
      }
      const nom = stand.nom;
      const label = $localize`:@@stands.entityLabel:Stand`;
      this.notifications.notify({
        title: $localize`:@@crud.created:Création de ${label}:label: « ${nom}:nom: » effectuée.`,
        variant: 'success',
        timeout: 4000,
      });
      this.dialogRef.close(id);
    } catch (error) {
      if (id !== null) {
        this.hoursError.set(
          $localize`:@@stands.creation.horairesEchec:Le stand est créé, mais ses horaires n'ont pas été enregistrés. « Réessayer » les envoie seuls ; « Annuler » ouvre sa fiche, où les saisir.`,
        );
      }
      this.crud.reportError(error);
    } finally {
      this.saving.set(false);
    }
  }

  /** The stand of the first three steps, its bounds widened to what the windows ask. */
  private standToWrite(): Stand {
    const bounds = creationBounds(this.windows(), {
      min: this.effectifMin(),
      max: this.effectifMax(),
    });
    return versStand(
      {
        ...toDraft(null),
        nom: this.nom(),
        code: this.code(),
        effectifMin: Math.min(this.effectifMin(), bounds.min),
        effectifMax: Math.max(this.effectifMax(), bounds.max),
        reserveMajeurs: this.reserveMajeurs(),
        typologiesProposees: this.typologies(),
        emplacementId: this.emplacementId(),
      },
      this.store.emplacements(),
    );
  }

  /** The stand's row of the Horaires des stands grid, that grid's own save. */
  private async saveHours(id: string): Promise<void> {
    const cells = creationCells(id, this.colonnes(), this.windows(), this.weekdays());
    const stamp = this.store.stands().find((each) => each.id === id)?.modifieLe ?? null;
    await this.standsApi.saveOpeningsGrid(
      saisie(cells, [id], this.colonnes(), { modifieLeParStand: new Map([[id, stamp]]) }),
    );
    await this.store.reload(['stands']);
  }

  /** Once the stand is written, closing opens its fiche: it exists, whatever became of its hours. */
  protected cancel(): void {
    this.dialogRef.close(this.createdId());
  }
}
