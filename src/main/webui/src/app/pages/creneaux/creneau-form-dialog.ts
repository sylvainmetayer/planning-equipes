import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { creneauName } from '../../core/reference-labels';
import { SolverJobService } from '../../core/solver-job.service';
import { Creneau } from '../../core/models';
import { JoursFeriesService } from '../../core/jours-feries.service';
import { PastilleFerie } from '../../shared/pastille-ferie';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { GelNotice } from '../../shared/gel-notice';

/** Sentinel `mat-select` value that reveals the "new group" name field. */

interface CreneauDraft {
  date: string;
  heureDebut: string;
  heureFin: string;
  /**
   * A meal-relay vacation: the stands are staffed at half their headcount so
   * the other half can eat. The server writes `couverture_pause` from whatever
   * the body says, so the payload always carries it — a body that omitted it
   * used to reset a generated relay to full headcount on the next solve.
   */
  couverturePause: boolean;
  /** The store's `modifieLe` at opening, sent back as the write's precondition (issue #362). */
  modifieLe: string | null;
}

export interface CreneauFormData {
  creneau: Creneau | null;
}

/** Add/edit dialog for a timeslot: event day, date, hours and planning group. Stand availability is edited from the stand itself (see stands page). */
@Component({
  selector: 'app-creneau-form-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatTooltipModule,
    PastilleFerie,
    GelNotice,
  ],
  templateUrl: './creneau-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreneauFormDialog {
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;
  private readonly gel = injectGelReferentiel();
  /** Every field of a timeslot is covered by a CRENEAUX freeze (ADR 0052): the form only reads. */
  protected readonly creneauxFrozen = computed(() => this.gel.isFrozen('CRENEAUX'));

  protected readonly dialogRef = inject<MatDialogRef<CreneauFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<CreneauFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = signal<number | null>(this.data.creneau?.id ?? null);
  protected readonly draft = signal<CreneauDraft>(toDraft(this.data.creneau));
  private readonly feries = inject(JoursFeriesService);
  /** The public holiday the typed date falls on, said under the field — never a refusal. */
  protected readonly ferie = computed(() => this.feries.label(this.draft().date));

  constructor() {
    effect(() => void this.feries.load([this.draft().date]));
  }
  protected readonly formTitle = computed(() => {
    const creneau = this.data.creneau;
    return creneau
      ? $localize`:@@creneaux.form.editTitle:Modifier le créneau du ${creneau.date}:date: ${creneau.heureDebut}:heure:`
      : $localize`:@@creneaux.form.newTitle:Nouveau créneau`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId()
      ? $localize`:@@creneaux.submit.edit:Modifier le créneau`
      : $localize`:@@creneaux.submit.create:Créer le créneau`,
  );

  /**
   * What the three `required` attributes were promising and not delivering:
   * `(ngSubmit)` fires whatever the form's validity, so a créneau with empty
   * fields used to leave for the server, whose `NOT NULL` columns rejected it —
   * « Saisie refusée » instead of an error against the field. The stand dialog
   * next door already gates its submit this way.
   *
   * <p>A missing field is the <b>only</b> thing refused here. In particular an
   * end at or before the start is not an error: it is how the domain writes a
   * slot running past midnight ({@code Creneau.getDureeMinutes} counts
   * 20:00→00:00 as 240 minutes, and a stand window dated J+1 is only read by
   * such a slot). Refusing it would make the night slot unwritable from the
   * screen that exists to write slots.
   */
  protected readonly formulaireInvalide = computed(() => {
    const { date, heureDebut, heureFin } = this.draft();
    return !date || !heureDebut || !heureFin;
  });

  /**
   * Says out loud what an end before the start means, so a typo is caught by
   * the person who made it rather than accepted in silence — without refusing
   * the slot, which is legitimate. Only worth saying once both hours are
   * filled: before that they are simply missing.
   */
  protected readonly franchitMinuit = computed(() => {
    const { heureDebut, heureFin } = this.draft();
    return Boolean(heureDebut) && Boolean(heureFin) && heureFin <= heureDebut;
  });

  protected patch(patch: Partial<CreneauDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected async save(): Promise<void> {
    // Guarded here and not only on the button: pressing Enter in a field
    // submits the form, and a component that trusts its own template to hold
    // the rule has no rule.
    if (this.formulaireInvalide()) {
      return;
    }
    const draft = this.draft();
    const editingId = this.editingId();
    const creneau: Partial<Creneau> = {
      date: draft.date,
      heureDebut: draft.heureDebut,
      heureFin: draft.heureFin,
      couverturePause: draft.couverturePause,
      modifieLe: draft.modifieLe,
    };
    if (editingId != null) {
      creneau.id = editingId;
    }
    if (
      await this.crud.save(
        'creneaux',
        creneau,
        editingId,
        $localize`:@@creneaux.entityLabel:Créneau`,
        { text: creneauName(draft) },
      )
    ) {
      this.dialogRef.close(true);
    }
  }
}

function toDraft(creneau: Creneau | null): CreneauDraft {
  if (!creneau) {
    return { date: '', heureDebut: '', heureFin: '', couverturePause: false, modifieLe: null };
  }
  return {
    date: creneau.date ?? '',
    heureDebut: creneau.heureDebut ?? '',
    heureFin: creneau.heureFin ?? '',
    couverturePause: creneau.couverturePause ?? false,
    modifieLe: creneau.modifieLe ?? null,
  };
}
