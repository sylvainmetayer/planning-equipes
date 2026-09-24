import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { urlLegifrance } from '../../core/legifrance';
import { Animateur, NiveauCompetence, TypologieItem } from '../../core/models';
import { DraftBanner, FormDraft } from '../../shared/brouillon-dialog';
import { StatusMessage } from '../../shared/status-message';
import { NewWindowLink } from '../../shared/new-window-link';
import {
  AnimateurDraft,
  CompetenceRow,
  isAnimateurModified,
  readAnimateurDraft,
  toDraft,
} from './animateur-brouillon';

const NIVEAUX: NiveauCompetence[] = ['DEBUTANT', 'AUTONOME', 'REFERENT'];

export interface AnimateurFormData {
  animateur: Animateur | null;
}

/**
 * Add/edit dialog for an animateur. Minor/adult status is never stored: it is
 * derived from the birth date at the date of each timeslot, so only the birth
 * date is edited. Availability is opt-out: an animateur works unless a day is
 * listed here.
 */
@Component({
  selector: 'app-animateur-form-dialog',
  imports: [
    DraftBanner,
    StatusMessage,
    NewWindowLink,
    FormsModule,
    MatDialogModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
  ],
  templateUrl: './animateur-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnimateurFormDialog {
  protected readonly niveaux = NIVEAUX;

  // The two obligations the under-16 warning says stay outside the application:
  // whoever has to satisfy them should be able to read them.
  protected readonly articleAutorisationInspection = urlLegifrance('L4153-3');
  protected readonly articleReposVacancesScolaires = urlLegifrance('D4153-2');
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly dialogRef = inject<MatDialogRef<AnimateurFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<AnimateurFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = signal<string | null>(this.data.animateur?.id ?? null);
  private readonly initial = toDraft(this.data.animateur);
  protected readonly draft = signal<AnimateurDraft>(this.initial);

  /**
   * The interrupted entry, kept in sessionStorage and nowhere else: a fiche
   * carries an identity, a birth date and an e-mail, which must not outlive
   * the tab on a shared computer (docs/rgpd.md §7).
   */
  protected readonly formDraft = new FormDraft<AnimateurDraft>({
    type: 'animateur',
    recordId: this.data.animateur?.id ?? null,
    modifieLe: this.initial.modifieLe,
    state: () => this.draft(),
    modified: () => isAnimateurModified(this.initial, this.draft()),
    read: (raw) => readAnimateurDraft(raw, this.data.animateur?.id ?? null),
    precondition: (draft) => draft.modifieLe,
    apply: (draft) => this.draft.set(draft),
    dialogRef: this.dialogRef,
    cancelResult: false,
  });
  protected readonly newJour = signal('');
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    return id
      ? $localize`:@@animateurs.form.editTitle:Modifier l'animateur ${id}:id:`
      : $localize`:@@animateurs.form.newTitle:Nouvel animateur`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId()
      ? $localize`:@@animateurs.submit.edit:Modifier l'animateur`
      : $localize`:@@animateurs.submit.create:Créer l'animateur`,
  );

  /**
   * The three fields the server refuses a fiche without (issue #432): a blank
   * name is as missing as no name, and the birth date is what the whole
   * minor/adult regime is derived from. The submit button waits for them, so
   * the refusal is read here rather than in a snack bar — the server still
   * checks, and its message is shown as is when it does refuse.
   */
  protected readonly identityComplete = computed(() => {
    const draft = this.draft();
    return (
      draft.prenom.trim().length > 0 && draft.nom.trim().length > 0 && draft.dateNaissance !== ''
    );
  });

  /**
   * True when the birth date puts the animateur under 16 <i>today</i>. Only a
   * data-entry hint: the rules themselves re-derive the age bracket at each
   * créneau's date (see `Animateur.estMoinsDe16AnsLe`), and nothing about the
   * bracket is ever stored.
   */
  protected readonly moinsDe16Ans = computed(() => {
    const naissance = this.draft().dateNaissance;
    if (!naissance) {
      return false;
    }
    const date = new Date(naissance);
    if (Number.isNaN(date.getTime())) {
      return false;
    }
    const seizeAns = new Date(date.getFullYear() + 16, date.getMonth(), date.getDate());
    return seizeAns > new Date();
  });

  protected patch(patch: Partial<AnimateurDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected async save(): Promise<void> {
    if (!this.identityComplete()) {
      return;
    }
    const draft = this.draft();
    const competences: Record<string, NiveauCompetence> = {};
    draft.competences.forEach((row) => {
      if (row.typologie) {
        competences[row.typologie] = row.niveau;
      }
    });
    const animateur: Animateur = {
      id: draft.id.trim(),
      prenom: draft.prenom.trim(),
      nom: draft.nom.trim(),
      dateNaissance: draft.dateNaissance,
      manager: draft.manager,
      email: draft.email.trim() || null,
      competences,
      souhaits: draft.souhaits,
      joursIndisponibles: draft.joursIndisponibles,
      modifieLe: draft.modifieLe,
    };
    if (
      await this.crud.save(
        'animateurs',
        animateur,
        this.editingId(),
        $localize`:@@animateurs.entityLabel:Animateur`,
      )
    ) {
      this.formDraft.complete();
      this.dialogRef.close(true);
    }
  }

  /**
   * The typologies a given row may still take: all of them, minus the ones the
   * other rows already hold. An animateur carries ONE appreciation per
   * typologie — the model is a map — so two rows on the same typologie collapse
   * into one on save, the last silently winning over the level the user had
   * entered. Making the duplicate unselectable is the only fix that also covers
   * the user picking it by hand.
   */
  protected typologiesDisponibles(index: number): TypologieItem[] {
    const prises = new Set(
      this.draft()
        .competences.filter((_, position) => position !== index)
        .map((row) => row.typologie),
    );
    return this.store.typologies().filter((typologie) => !prises.has(typologie.id));
  }

  /** No typologie left to appreciate: the row would only be a duplicate. */
  protected readonly toutesTypologiesPrises = computed(() => {
    const prises = new Set(this.draft().competences.map((row) => row.typologie));
    return this.store.typologies().every((typologie) => prises.has(typologie.id));
  });

  protected addCompetence(): void {
    const libre = this.typologiesDisponibles(-1)[0];
    if (!libre) {
      return;
    }
    this.draft.update((draft) => ({
      ...draft,
      competences: [...draft.competences, { typologie: libre.id, niveau: 'AUTONOME' }],
    }));
  }

  protected removeCompetence(index: number): void {
    this.draft.update((draft) => ({
      ...draft,
      competences: draft.competences.filter((_, position) => position !== index),
    }));
  }

  protected updateCompetence(index: number, patch: Partial<CompetenceRow>): void {
    this.draft.update((draft) => ({
      ...draft,
      competences: draft.competences.map((row, position) =>
        position === index ? { ...row, ...patch } : row,
      ),
    }));
  }

  protected addJour(): void {
    const date = this.newJour();
    if (!date) {
      return;
    }
    this.draft.update((draft) => ({
      ...draft,
      joursIndisponibles: draft.joursIndisponibles.includes(date)
        ? draft.joursIndisponibles
        : [...draft.joursIndisponibles, date],
    }));
    this.newJour.set('');
  }

  protected removeJour(date: string): void {
    this.draft.update((draft) => ({
      ...draft,
      joursIndisponibles: draft.joursIndisponibles.filter((day) => day !== date),
    }));
  }

  protected removeJourLabel(jour: string): string {
    return $localize`:@@animateurs.indispo.removeLabel:Retirer ${jour}:jour:`;
  }
}
