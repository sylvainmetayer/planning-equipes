import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
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
import {
  FenetreHoraire,
  HoraireStand,
  IndisponibiliteStand,
  JourSemaine,
  NiveauEffort,
  OuvertureStand,
  Stand
} from '../../core/models';

interface StandDraft {
  id: string;
  nom: string;
  effectifMin: number;
  effectifMax: number;
  reserveMajeurs: boolean;
  premium: boolean;
  niveauEffort: NiveauEffort;
  typologiesProposees: string[];
  emplacementId: string | null;
  indisponibilites: IndisponibiliteStand[];
  ouvertures: OuvertureStand[];
  horaires: HoraireStand[];
}

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
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  protected readonly dialogRef = inject<MatDialogRef<StandFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<StandFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

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

  protected readonly effectifInvalid = computed(() => {
    const draft = this.draft();
    return Number(draft.effectifMax) < Number(draft.effectifMin);
  });
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    return id ? $localize`:@@stands.form.editTitle:Modifier le stand ${id}:id:` : $localize`:@@stands.form.newTitle:Nouveau stand`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId() ? $localize`:@@stands.submit.edit:Modifier le stand` : $localize`:@@stands.submit.create:Créer le stand`
  );

  /**
   * Any closure missing a date or a start time, or whose end time isn't strictly
   * after its start — the backend rejects these outright. An *empty* end time is
   * valid and means "until closing time".
   */
  protected readonly indisponibiliteInvalide = computed(() =>
    this.draft().indisponibilites.some(
      (indispo) =>
        !indispo.date || !indispo.heureDebut || (!!indispo.heureFin && indispo.heureFin <= indispo.heureDebut)
    )
  );

  /** Same rules as {@link indisponibiliteInvalide}, for the opening exceptions. */
  protected readonly ouvertureInvalide = computed(() =>
    this.draft().ouvertures.some(
      (ouverture) =>
        !ouverture.date ||
        !ouverture.heureDebut ||
        (!!ouverture.heureFin && ouverture.heureFin <= ouverture.heureDebut)
    )
  );

  /** A day can't carry both a closure and an opening — the backend rejects this outright. */
  protected readonly conflitOuvertureFermeture = computed(() => {
    const draft = this.draft();
    const joursFermeture = new Set(draft.indisponibilites.map((indispo) => indispo.date).filter(Boolean));
    return draft.ouvertures.some((ouverture) => ouverture.date && joursFermeture.has(ouverture.date));
  });

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

  /**
   * Days the preview covers: those of the active timeslot group, since that's
   * the one the solver builds from. Falls back to every group's days when none
   * is flagged active, so the preview is never empty for no visible reason.
   */
  protected readonly datesFestival = computed(() => {
    const creneaux = this.store.creneaux();
    const actifs = creneaux.filter((creneau) => creneau.groupe?.actif);
    const retenus = actifs.length > 0 ? actifs : creneaux;
    return [...new Set(retenus.map((creneau) => creneau.date))].sort();
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
    this.draft.update((draft) => ({
      ...draft,
      indisponibilites: [...draft.indisponibilites, { id: null, date: '', heureDebut: '', heureFin: null, motif: null }]
    }));
  }

  protected patchIndisponibilite(index: number, patch: Partial<IndisponibiliteStand>): void {
    this.draft.update((draft) => ({
      ...draft,
      indisponibilites: draft.indisponibilites.map((indispo, i) => (i === index ? { ...indispo, ...patch } : indispo))
    }));
  }

  protected retirerIndisponibilite(index: number): void {
    this.draft.update((draft) => ({
      ...draft,
      indisponibilites: draft.indisponibilites.filter((_, i) => i !== index)
    }));
  }

  protected ajouterOuverture(): void {
    this.draft.update((draft) => ({
      ...draft,
      ouvertures: [...draft.ouvertures, { id: null, date: '', heureDebut: '', heureFin: null, motif: null }]
    }));
  }

  protected patchOuverture(index: number, patch: Partial<OuvertureStand>): void {
    this.draft.update((draft) => ({
      ...draft,
      ouvertures: draft.ouvertures.map((ouverture, i) => (i === index ? { ...ouverture, ...patch } : ouverture))
    }));
  }

  protected retirerOuverture(index: number): void {
    this.draft.update((draft) => ({
      ...draft,
      ouvertures: draft.ouvertures.filter((_, i) => i !== index)
    }));
  }

  /* ------------------------- Recurring horaires ------------------------- */

  protected ajouterHoraire(): void {
    this.draft.update((draft) => ({ ...draft, horaires: [...draft.horaires, horaireVide()] }));
  }

  protected patchHoraire(index: number, patch: Partial<HoraireStand>): void {
    this.draft.update((draft) => ({
      ...draft,
      horaires: draft.horaires.map((horaire, i) => (i === index ? { ...horaire, ...patch } : horaire))
    }));
  }

  protected retirerHoraire(index: number): void {
    this.draft.update((draft) => ({ ...draft, horaires: draft.horaires.filter((_, i) => i !== index) }));
  }

  protected ajouterFenetre(indexHoraire: number): void {
    this.majFenetres(indexHoraire, (fenetres) => [...fenetres, { heureDebut: '', heureFin: null }]);
  }

  protected patchFenetre(indexHoraire: number, indexFenetre: number, patch: Partial<FenetreHoraire>): void {
    this.majFenetres(indexHoraire, (fenetres) =>
      fenetres.map((fenetre, i) => (i === indexFenetre ? { ...fenetre, ...patch } : fenetre))
    );
  }

  protected retirerFenetre(indexHoraire: number, indexFenetre: number): void {
    this.majFenetres(indexHoraire, (fenetres) => fenetres.filter((_, i) => i !== indexFenetre));
  }

  /**
   * Toggles one weekday of a `JOURS_SEMAINE` rule. Kept here rather than bound
   * to a multi-select so the seven days read as seven checkboxes — the shape the
   * question actually has ("which days does the weekend schedule cover?").
   */
  protected basculerJourSemaine(indexHoraire: number, jour: JourSemaine, coche: boolean): void {
    this.draft.update((draft) => ({
      ...draft,
      horaires: draft.horaires.map((horaire, i) => {
        if (i !== indexHoraire) {
          return horaire;
        }
        const joursSemaine = coche
          ? [...new Set([...horaire.joursSemaine, jour])]
          : horaire.joursSemaine.filter((autre) => autre !== jour);
        return { ...horaire, joursSemaine };
      })
    }));
  }

  /** Comma-separated ISO dates, for the `DATES` scope — a plain text field beats seven date pickers. */
  protected patchDates(indexHoraire: number, valeur: string): void {
    const dates = valeur
      .split(',')
      .map((date) => date.trim())
      .filter((date) => /^\d{4}-\d{2}-\d{2}$/.test(date));
    this.patchHoraire(indexHoraire, { dates });
  }

  private majFenetres(indexHoraire: number, transformer: (fenetres: FenetreHoraire[]) => FenetreHoraire[]): void {
    this.draft.update((draft) => ({
      ...draft,
      horaires: draft.horaires.map((horaire, i) =>
        i === indexHoraire ? { ...horaire, fenetres: transformer(horaire.fenetres) } : horaire
      )
    }));
  }

  protected readonly formulaireInvalide = computed(
    () =>
      this.effectifInvalid() ||
      this.indisponibiliteInvalide() ||
      this.ouvertureInvalide() ||
      this.conflitOuvertureFermeture() ||
      this.erreurHoraires() !== null
  );

  protected async save(): Promise<void> {
    if (this.formulaireInvalide()) {
      return;
    }
    const draft = this.draft();
    const stand: Stand = {
      id: draft.id.trim(),
      nom: draft.nom.trim(),
      typologiesProposees: draft.typologiesProposees,
      effectifMin: Number(draft.effectifMin) || 0,
      effectifMax: Number(draft.effectifMax) || 0,
      reserveMajeurs: draft.reserveMajeurs,
      premium: draft.premium,
      niveauEffort: draft.niveauEffort,
      emplacement: draft.emplacementId
        ? (this.store.emplacements().find((e) => e.id === draft.emplacementId) ?? null)
        : null,
      indisponibilites: draft.indisponibilites.map(normaliserPlage),
      ouvertures: draft.ouvertures.map(normaliserPlage),
      horaires: draft.horaires.map(normaliserHoraire)
    };
    if (await this.crud.save('stands', stand, this.editingId(), $localize`:@@stands.entityLabel:Stand`)) {
      this.dialogRef.close(true);
    }
  }
}

/**
 * An emptied `<input type="time">` gives back `''`, not `null` — and `''` would
 * reach the backend as a malformed time rather than as "until closing time".
 */
function normaliserPlage<T extends { heureFin: string | null }>(plage: T): T {
  return { ...plage, heureFin: plage.heureFin || null };
}

function normaliserHoraire(horaire: HoraireStand): HoraireStand {
  return {
    ...horaire,
    fenetres: horaire.fenetres.map((fenetre) => ({ ...fenetre, heureFin: fenetre.heureFin || null })),
    // Only the fields the chosen scope uses are sent, so a rule switched from
    // PLAGE to TOUS doesn't keep dragging its old bounds along.
    joursSemaine: horaire.jours === 'JOURS_SEMAINE' ? horaire.joursSemaine : [],
    dateDebut: horaire.jours === 'PLAGE' ? horaire.dateDebut : null,
    dateFin: horaire.jours === 'PLAGE' ? horaire.dateFin : null,
    dates: horaire.jours === 'DATES' ? horaire.dates : []
  };
}

function toDraft(stand: Stand | null): StandDraft {
  if (!stand) {
    return {
      id: '',
      nom: '',
      effectifMin: 1,
      effectifMax: 1,
      reserveMajeurs: false,
      premium: false,
      niveauEffort: 'NORMAL',
      typologiesProposees: [],
      emplacementId: null,
      indisponibilites: [],
      ouvertures: [],
      horaires: []
    };
  }
  return {
    id: stand.id,
    nom: stand.nom ?? '',
    effectifMin: stand.effectifMin,
    effectifMax: stand.effectifMax,
    reserveMajeurs: Boolean(stand.reserveMajeurs),
    premium: Boolean(stand.premium),
    niveauEffort: stand.niveauEffort ?? 'NORMAL',
    typologiesProposees: [...(stand.typologiesProposees ?? [])],
    emplacementId: stand.emplacement?.id ?? null,
    indisponibilites: (stand.indisponibilites ?? []).map((indispo) => ({ ...indispo })),
    ouvertures: (stand.ouvertures ?? []).map((ouverture) => ({ ...ouverture })),
    horaires: (stand.horaires ?? []).map((horaire) => ({
      ...horaire,
      joursSemaine: [...horaire.joursSemaine],
      dates: [...horaire.dates],
      fenetres: horaire.fenetres.map((fenetre) => ({ ...fenetre }))
    }))
  };
}
