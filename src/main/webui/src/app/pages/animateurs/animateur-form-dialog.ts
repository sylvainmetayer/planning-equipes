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
import { animateurName } from '../../core/reference-labels';
import { SolverJobService } from '../../core/solver-job.service';
import { urlLegifrance } from '../../core/legifrance';
import { intlLocale } from '../../core/locale';
import { Animateur, NiveauCompetence, TypologieItem } from '../../core/models';
import { COMPETENCE_LEVELS, libelleNiveau } from '../../core/niveau-competence';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { typologieLabel, typologieLabels } from '../../core/typologie-colors';
import { Router } from '@angular/router';
import { addRange, editionDays, toggleDayOff } from './jours-indisponibles';
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

export interface AnimateurFormData {
  animateur: Animateur | null;
  /**
   * « Dupliquer »: a new person prefilled from this fiche — appreciations,
   * wishes, days off, manager — the identity left blank: two people never
   * share one.
   */
  modele?: Animateur | null;
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
  private readonly router = inject(Router);
  private readonly gel = injectGelReferentiel();
  /**
   * The appreciations are typed in the grid of `/competences`, and the form
   * only reads them — except on a creation under a COMPETENCES freeze (ADR
   * 0052): the freeze covers the fiches already in the roster and locks that
   * grid, while a new animateur still arrives with theirs, so the form takes
   * them itself.
   */
  protected readonly competencesEditable = computed(
    () => this.editingId() === null && this.gel.isFrozen('COMPETENCES'),
  );
  protected readonly levels = COMPETENCE_LEVELS;
  protected readonly levelLabel = libelleNiveau;
  private readonly initial: AnimateurDraft =
    this.data.animateur || !this.data.modele
      ? toDraft(this.data.animateur)
      : {
          ...toDraft(this.data.modele),
          id: '',
          prenom: '',
          nom: '',
          dateNaissance: '',
          email: '',
          telephone: '',
          modifieLe: null,
        };
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
  /** « Absent du … au … »: the range the user is typing. */
  protected readonly rangeStart = signal('');
  protected readonly rangeEnd = signal('');
  /** Said under the range once applied: how many edition days it marked. */
  protected readonly rangeResult = signal<string | null>(null);

  /** The edition's days, from its timeslots: the frieze a day off is ticked on. */
  protected readonly editionDays = computed(() => editionDays(this.store.creneaux()));

  /** The frieze as the template binds it: one button per day of the edition. */
  protected readonly frise = computed(() => {
    const away = new Set(this.draft().joursIndisponibles);
    const format = new Intl.DateTimeFormat(intlLocale(), {
      weekday: 'short',
      day: 'numeric',
      month: 'numeric',
      timeZone: 'UTC',
    });
    return this.editionDays().map((date) => {
      const libelle = format.format(new Date(`${date}T00:00:00Z`));
      return { date, libelle, absent: away.has(date) };
    });
  });

  /** Days off outside the edition's days — typed before the timeslots moved: listed apart, removable. */
  protected readonly daysOutsideEdition = computed(() => {
    const edition = new Set(this.editionDays());
    return this.draft().joursIndisponibles.filter((date) => !edition.has(date));
  });

  /** The appreciations, read-only: they are typed in the grid, not here. */
  protected readonly appreciations = computed(() => {
    const typologies = typologieLabels(this.store.typologies());
    return this.draft().competences.map((row) => ({
      label: typologieLabel(typologies, row.typologie),
      niveau: libelleNiveau(row.niveau as NiveauCompetence),
    }));
  });
  /**
   * Names the animateur as the fiche was loaded. A dialog title is never
   * logged, so it may carry the identity the notifications keep out of their
   * log (docs/rgpd.md §7).
   */
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    const animateur = this.data.animateur;
    const name = (animateur && animateurName(animateur)) || id;
    return id
      ? $localize`:@@animateurs.form.editTitle:Modifier l'animateur ${name}:nom:`
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
      id: draft.id,
      prenom: draft.prenom.trim(),
      nom: draft.nom.trim(),
      dateNaissance: draft.dateNaissance,
      manager: draft.manager,
      email: draft.email.trim() || null,
      telephone: draft.telephone.trim() || null,
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
        // Shown in the snack bar, replaced by the id in the notifications log.
        { text: animateurName(animateur), personal: true },
      )
    ) {
      this.formDraft.complete();
      this.dialogRef.close(true);
    }
  }

  /** A day of the frieze, clicked: away, or back. */
  protected toggleDay(date: string): void {
    this.draft.update((draft) => ({
      ...draft,
      joursIndisponibles: toggleDayOff(draft.joursIndisponibles, date),
    }));
  }

  /** « Absent du 8 au 12 » in one gesture: the edition's days of the range, marked away. */
  protected addRange(): void {
    const from = this.rangeStart();
    const to = this.rangeEnd() || from;
    if (!from) {
      return;
    }
    const result = addRange(this.draft().joursIndisponibles, from, to, this.editionDays());
    this.draft.update((draft) => ({ ...draft, joursIndisponibles: result.days }));
    this.rangeResult.set(
      $localize`:@@animateurs.indispo.plageResultat:${result.added}:count: jour(s) de l'édition marqué(s) absent(s).`,
    );
    this.rangeStart.set('');
    this.rangeEnd.set('');
  }

  protected jourLabel(jour: { libelle: string; absent: boolean }): string {
    return jour.absent
      ? $localize`:@@animateurs.indispo.jourAbsent:${jour.libelle}:jour: : absent`
      : $localize`:@@animateurs.indispo.jourPresent:${jour.libelle}:jour: : disponible`;
  }

  protected removeJour(date: string): void {
    this.draft.update((draft) => ({
      ...draft,
      joursIndisponibles: draft.joursIndisponibles.filter((day) => day !== date),
    }));
  }

  /**
   * « Saisir dans la grille »: the competences grid narrowed to this person.
   * Leaving goes through the draft's own close, which asks before throwing a
   * modified form away; the grid opens only once the form is gone.
   */
  protected async openGrid(id: string): Promise<void> {
    if (await this.formDraft.close()) {
      void this.router.navigate(['/competences'], { queryParams: { animateur: id } });
    }
  }

  /**
   * The game categories one row may still take: all of them, minus the ones
   * the other rows hold — an animateur carries one appreciation per
   * category, and a duplicate row would collapse into one on save.
   */
  protected availableTypologies(index: number): TypologieItem[] {
    const taken = new Set(
      this.draft()
        .competences.filter((_, position) => position !== index)
        .map((row) => row.typologie),
    );
    return this.store.typologies().filter((typologie) => !taken.has(typologie.id));
  }

  /** Every game category already appreciated: another row could only be a duplicate. */
  protected readonly allTypologiesTaken = computed(() => {
    const taken = new Set(this.draft().competences.map((row) => row.typologie));
    return this.store.typologies().every((typologie) => taken.has(typologie.id));
  });

  protected addCompetence(): void {
    const free = this.availableTypologies(-1)[0];
    if (!free) {
      return;
    }
    this.draft.update((draft) => ({
      ...draft,
      competences: [...draft.competences, { typologie: free.id, niveau: 'AUTONOME' }],
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

  protected removeJourLabel(jour: string): string {
    return $localize`:@@animateurs.indispo.removeLabel:Retirer ${jour}:jour:`;
  }
}
