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
import { Animateur, NiveauCompetence } from '../../core/models';

const NIVEAUX: NiveauCompetence[] = ['DEBUTANT', 'AUTONOME', 'REFERENT'];

interface CompetenceRow {
  typologie: string;
  niveau: NiveauCompetence;
}

interface AnimateurDraft {
  id: string;
  prenom: string;
  nom: string;
  dateNaissance: string;
  manager: boolean;
  email: string;
  competences: CompetenceRow[];
  souhaits: string[];
  joursIndisponibles: string[];
}

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
    FormsModule,
    MatDialogModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule
  ],
  templateUrl: './animateur-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
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
  protected readonly draft = signal<AnimateurDraft>(toDraft(this.data.animateur));
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
      : $localize`:@@animateurs.submit.create:Créer l'animateur`
  );

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
      dateNaissance: draft.dateNaissance || null,
      manager: draft.manager,
      email: draft.email.trim() || null,
      competences,
      souhaits: draft.souhaits,
      joursIndisponibles: draft.joursIndisponibles
    };
    if (await this.crud.save('animateurs', animateur, this.editingId(), $localize`:@@animateurs.entityLabel:Animateur`)) {
      this.dialogRef.close(true);
    }
  }

  protected addCompetence(): void {
    const firstTypologie = this.store.typologies()[0]?.id ?? '';
    this.draft.update((draft) => ({
      ...draft,
      competences: [...draft.competences, { typologie: firstTypologie, niveau: 'AUTONOME' }]
    }));
  }

  protected removeCompetence(index: number): void {
    this.draft.update((draft) => ({
      ...draft,
      competences: draft.competences.filter((_, position) => position !== index)
    }));
  }

  protected updateCompetence(index: number, patch: Partial<CompetenceRow>): void {
    this.draft.update((draft) => ({
      ...draft,
      competences: draft.competences.map((row, position) => (position === index ? { ...row, ...patch } : row))
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
        : [...draft.joursIndisponibles, date]
    }));
    this.newJour.set('');
  }

  protected removeJour(date: string): void {
    this.draft.update((draft) => ({
      ...draft,
      joursIndisponibles: draft.joursIndisponibles.filter((day) => day !== date)
    }));
  }

  protected removeJourLabel(jour: string): string {
    return $localize`:@@animateurs.indispo.removeLabel:Retirer ${jour}:jour:`;
  }
}

function toDraft(animateur: Animateur | null): AnimateurDraft {
  if (!animateur) {
    return {
      id: '',
      prenom: '',
      nom: '',
      dateNaissance: '',
      manager: false,
      email: '',
      competences: [],
      souhaits: [],
      joursIndisponibles: []
    };
  }
  return {
    id: animateur.id,
    prenom: animateur.prenom ?? '',
    nom: animateur.nom ?? '',
    dateNaissance: animateur.dateNaissance ?? '',
    manager: animateur.manager ?? false,
    email: animateur.email ?? '',
    competences: Object.entries(animateur.competences ?? {}).map(([typologie, niveau]) => ({ typologie, niveau })),
    souhaits: [...(animateur.souhaits ?? [])],
    joursIndisponibles: [...(animateur.joursIndisponibles ?? [])]
  };
}
