import { ChangeDetectionStrategy, Component, ElementRef, Injector, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { focusApresSuppression } from '../../core/focus-apres-suppression';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import {
  conflitDeMode,
  decrireFenetre,
  erreurHoraire,
  horaireVide,
  JourResolu,
  resoudreHoraires
} from '../../core/horaire-stand';
import { FenetreHoraire, HoraireStand, IndisponibiliteStand, JourSemaine, OuvertureStand, Stand } from '../../core/models';
import {
  StandDraft,
  ajouterA,
  basculerJour,
  brouillonInvalide,
  conflitOuvertureFermeture,
  datesDepuisTexte,
  effectifInvalide,
  fenetreVide,
  indisponibiliteInvalide,
  ouvertureInvalide,
  patchDansListe,
  plageVide,
  retirerDe,
  toDraft,
  versStand
} from './stand-draft';

export interface StandFormData {
  stand: Stand | null;
}

/**
 * Add/edit dialog for a stand: identity, staffing bounds, adults-only flag,
 * typologies, and its opening schedule.
 *
 * The schedule is edited on two levels, matching how the backend resolves it:
 * recurring **horaires** state the pattern ("open 10:00-12:00 then 14:00 to
 * closing, every day" — one rule instead of twenty-four dated windows), and
 * dated **exceptions** override them for the single day they name. The preview
 * strip below the editor resolves both against the active group's days, so the
 * effect of a rule is visible without saving and re-reading.
 */
@Component({
  selector: 'app-stand-form-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule
  ],
  templateUrl: './stand-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StandFormDialog {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly dialogRef = inject<MatDialogRef<StandFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<StandFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** Removing a row destroys the focused button: hand the focus to the section's "add" button. */
  private focusApres(selecteur: string): void {
    focusApresSuppression(this.hote.nativeElement, selecteur, this.injector);
  }

  protected readonly editingId = signal<string | null>(this.data.stand?.id ?? null);
  protected readonly draft = signal<StandDraft>(toDraft(this.data.stand));

  protected readonly joursSemaine: readonly JourSemaine[] = [
    'MONDAY',
    'TUESDAY',
    'WEDNESDAY',
    'THURSDAY',
    'FRIDAY',
    'SATURDAY',
    'SUNDAY'
  ];

  protected readonly effectifInvalid = computed(() => effectifInvalide(this.draft()));
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    return id ? $localize`:@@stands.form.editTitle:Modifier le stand ${id}:id:` : $localize`:@@stands.form.newTitle:Nouveau stand`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId() ? $localize`:@@stands.submit.edit:Modifier le stand` : $localize`:@@stands.submit.create:Créer le stand`
  );

  protected readonly indisponibiliteInvalide = computed(() => indisponibiliteInvalide(this.draft()));

  protected readonly ouvertureInvalide = computed(() => ouvertureInvalide(this.draft()));

  protected readonly conflitOuvertureFermeture = computed(() => conflitOuvertureFermeture(this.draft()));

  /** First problem among the recurring rules, or `null` — mirrors the backend's own check. */
  protected readonly erreurHoraires = computed(() => {
    for (const horaire of this.draft().horaires) {
      const erreur = erreurHoraire(horaire, {
        fenetreRequise: $localize`:@@stands.horaires.error.fenetreRequise:Chaque horaire doit porter au moins une fenêtre.`,
        heureDebutRequise: $localize`:@@stands.horaires.error.heureDebutRequise:Chaque fenêtre doit avoir une heure de début.`,
        fenetreInversee: $localize`:@@stands.horaires.error.fenetreInversee:L'heure de fin doit être après l'heure de début (laissez-la vide pour aller jusqu'à la fermeture).`,
        joursSemaineRequis: $localize`:@@stands.horaires.error.joursSemaineRequis:Choisissez au moins un jour de la semaine.`,
        plageRequise: $localize`:@@stands.horaires.error.plageRequise:Renseignez une date de début et une date de fin cohérentes.`,
        datesRequises: $localize`:@@stands.horaires.error.datesRequises:Choisissez au moins une date.`
      });
      if (erreur) {
        return erreur;
      }
    }
    return conflitDeMode(this.draft().horaires)
      ? $localize`:@@stands.horaires.error.conflitMode:Deux horaires de même portée portant sur les mêmes jours ne peuvent pas être l'un une ouverture et l'autre une fermeture. Utilisez une portée plus précise pour celui qui doit primer.`
      : null;
  });

  /** Days the preview covers: the edition's créneaux — what the solver builds from. */
  protected readonly datesFestival = computed(() => {
    return [...new Set(this.store.creneaux().map((creneau) => creneau.date))].sort();
  });

  /** The schedule as the solver will read it, day by day — the point of the whole editor. */
  protected readonly apercu = computed<JourResolu[]>(() => {
    const draft = this.draft();
    return resoudreHoraires(
      {
        indisponibilites: draft.indisponibilites,
        ouvertures: draft.ouvertures,
        horaires: draft.horaires
      } as Stand,
      this.datesFestival()
    );
  });

  protected decrireJour(jour: JourResolu): string {
    if (jour.mode === null) {
      return $localize`:@@stands.apercu.ouvertToutLeJour:Ouvert toute la journée`;
    }
    const fenetres = jour.fenetres
      .map((fenetre) => decrireFenetre(fenetre, $localize`:@@stands.apercu.fermeture:fermeture`))
      .join(', ');
    return jour.mode === 'OUVERTURE'
      ? $localize`:@@stands.apercu.ouvertSur:Ouvert ${fenetres}:fenetres:`
      : $localize`:@@stands.apercu.fermeSur:Fermé ${fenetres}:fenetres:`;
  }

  /** Day label of the preview strip: `08/07`, short enough for a dozen cells in a row. */
  protected libelleJour(date: string): string {
    const [, mois, jour] = date.split('-');
    return `${jour}/${mois}`;
  }

  /**
   * Weekday label of the `JOURS_SEMAINE` checkboxes. Written out rather than
   * derived from `Intl`, because the locale here is the app's own (translated at
   * runtime, see AGENTS.md) and not the browser's.
   */
  protected libelleJourSemaine(jour: JourSemaine): string {
    switch (jour) {
      case 'MONDAY':
        return $localize`:@@common.weekday.monday:Lundi`;
      case 'TUESDAY':
        return $localize`:@@common.weekday.tuesday:Mardi`;
      case 'WEDNESDAY':
        return $localize`:@@common.weekday.wednesday:Mercredi`;
      case 'THURSDAY':
        return $localize`:@@common.weekday.thursday:Jeudi`;
      case 'FRIDAY':
        return $localize`:@@common.weekday.friday:Vendredi`;
      case 'SATURDAY':
        return $localize`:@@common.weekday.saturday:Samedi`;
      case 'SUNDAY':
        return $localize`:@@common.weekday.sunday:Dimanche`;
    }
  }

  protected patch(patch: Partial<StandDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected ajouterIndisponibilite(): void {
    this.patch({ indisponibilites: ajouterA(this.draft().indisponibilites, plageVide()) });
  }

  protected patchIndisponibilite(index: number, patch: Partial<IndisponibiliteStand>): void {
    this.patch({ indisponibilites: patchDansListe(this.draft().indisponibilites, index, patch) });
  }

  protected retirerIndisponibilite(index: number): void {
    this.patch({ indisponibilites: retirerDe(this.draft().indisponibilites, index) });
    this.focusApres('[data-focus="ajouter-indisponibilite"]');
  }

  protected ajouterOuverture(): void {
    this.patch({ ouvertures: ajouterA(this.draft().ouvertures, plageVide()) });
  }

  protected patchOuverture(index: number, patch: Partial<OuvertureStand>): void {
    this.patch({ ouvertures: patchDansListe(this.draft().ouvertures, index, patch) });
  }

  protected retirerOuverture(index: number): void {
    this.patch({ ouvertures: retirerDe(this.draft().ouvertures, index) });
    this.focusApres('[data-focus="ajouter-ouverture"]');
  }

  /* ------------------------- Recurring horaires ------------------------- */

  protected ajouterHoraire(): void {
    this.patch({ horaires: ajouterA(this.draft().horaires, horaireVide()) });
  }

  protected patchHoraire(index: number, patch: Partial<HoraireStand>): void {
    this.patch({ horaires: patchDansListe(this.draft().horaires, index, patch) });
  }

  protected retirerHoraire(index: number): void {
    this.patch({ horaires: retirerDe(this.draft().horaires, index) });
    this.focusApres('[data-focus="ajouter-horaire"]');
  }

  protected ajouterFenetre(indexHoraire: number): void {
    this.majFenetres(indexHoraire, (fenetres) => ajouterA(fenetres, fenetreVide()));
  }

  protected patchFenetre(indexHoraire: number, indexFenetre: number, patch: Partial<FenetreHoraire>): void {
    this.majFenetres(indexHoraire, (fenetres) => patchDansListe(fenetres, indexFenetre, patch));
  }

  protected retirerFenetre(indexHoraire: number, indexFenetre: number): void {
    this.majFenetres(indexHoraire, (fenetres) => retirerDe(fenetres, indexFenetre));
    this.focusApres(`[data-focus="ajouter-fenetre-${indexHoraire}"]`);
  }

  /**
   * Toggles one weekday of a `JOURS_SEMAINE` rule. Kept here rather than bound
   * to a multi-select so the seven days read as seven checkboxes — the shape the
   * question actually has ("which days does the weekend schedule cover?").
   */
  protected basculerJourSemaine(indexHoraire: number, jour: JourSemaine, coche: boolean): void {
    const horaire = this.draft().horaires[indexHoraire];
    if (horaire) {
      this.patchHoraire(indexHoraire, { joursSemaine: basculerJour(horaire.joursSemaine, jour, coche) });
    }
  }

  /** Comma-separated ISO dates, for the `DATES` scope — a plain text field beats seven date pickers. */
  protected patchDates(indexHoraire: number, valeur: string): void {
    this.patchHoraire(indexHoraire, { dates: datesDepuisTexte(valeur) });
  }

  private majFenetres(indexHoraire: number, transformer: (fenetres: FenetreHoraire[]) => FenetreHoraire[]): void {
    const horaire = this.draft().horaires[indexHoraire];
    if (horaire) {
      this.patchHoraire(indexHoraire, { fenetres: transformer(horaire.fenetres) });
    }
  }

  protected readonly formulaireInvalide = computed(
    () => brouillonInvalide(this.draft()) || this.erreurHoraires() !== null
  );

  protected async save(): Promise<void> {
    if (this.formulaireInvalide()) {
      return;
    }
    const stand = versStand(this.draft(), this.store.emplacements());
    if (await this.crud.save('stands', stand, this.editingId(), $localize`:@@stands.entityLabel:Stand`)) {
      this.dialogRef.close(true);
    }
  }
}
