import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
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
  competences: CompetenceRow[];
  joursIndisponibles: string[];
}

/**
 * Animateurs CRUD. Minor/adult status is never stored: it is derived from the
 * birth date at the date of each timeslot, so only the birth date is edited.
 * Availability is opt-out: an animator works unless a day is listed here.
 */
@Component({
  selector: 'app-animateurs-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    MatExpansionModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule
  ],
  templateUrl: './animateurs-page.html'
})
export class AnimateursPage {
  protected readonly niveaux = NIVEAUX;
  protected readonly columns = ['id', 'nom', 'manager', 'competences', 'indisponibilites', 'actions'];

  protected readonly store = inject(ReferenceDataStore);
  protected readonly draft = signal<AnimateurDraft>(emptyDraft());
  protected readonly editingId = signal<string | null>(null);
  protected readonly newJour = signal('');
  protected readonly formTitle = computed(() =>
    this.editingId() ? `Modifier l'animateur ${this.editingId()}` : 'Nouvel animateur'
  );
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.crud.reload();
  }

  protected patch(patch: Partial<AnimateurDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected competencesLabel(animateur: Animateur): string {
    const entries = Object.entries(animateur.competences ?? {});
    return entries.length === 0 ? '—' : entries.map(([typo, niveau]) => `${typo}: ${niveau}`).join(', ');
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
      competences,
      joursIndisponibles: draft.joursIndisponibles
    };
    if (await this.crud.save('animateurs', animateur, this.editingId(), 'Animateur')) {
      this.cancel();
    }
  }

  protected edit(animateur: Animateur): void {
    this.draft.set({
      id: animateur.id,
      prenom: animateur.prenom ?? '',
      nom: animateur.nom ?? '',
      dateNaissance: animateur.dateNaissance ?? '',
      manager: animateur.manager ?? false,
      competences: Object.entries(animateur.competences ?? {}).map(([typologie, niveau]) => ({ typologie, niveau })),
      joursIndisponibles: [...(animateur.joursIndisponibles ?? [])]
    });
    this.editingId.set(animateur.id);
  }

  protected cancel(): void {
    this.draft.set(emptyDraft());
    this.newJour.set('');
    this.editingId.set(null);
  }

  protected async remove(animateur: Animateur): Promise<void> {
    if (await this.crud.remove('animateurs', animateur.id, 'Animateur') && this.editingId() === animateur.id) {
      this.cancel();
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
}

function emptyDraft(): AnimateurDraft {
  return {
    id: '',
    prenom: '',
    nom: '',
    dateNaissance: '',
    manager: false,
    competences: [],
    joursIndisponibles: []
  };
}
