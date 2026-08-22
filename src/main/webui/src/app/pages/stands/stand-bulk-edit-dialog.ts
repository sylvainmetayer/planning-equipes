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
import { ModeBooleen, ModeListe } from '../../core/bulk-edit';
import { labelStandsPluriel } from '../../core/entity-labels';
import { horaireVide } from '../../core/horaire-stand';
import { effectifDepuisSaisie, libelleJourSemaine, premiereErreurHoraire } from './stand-horaires';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { FenetreHoraire, HoraireStand, JourSemaine, NiveauEffort, Stand } from '../../core/models';
import {
  ModeEmplacement,
  ModeHoraires,
  StandBulkPatch,
  appliquerPatchStand,
  patchStandEstVide,
  patchStandVide,
  standsAvecEffectifInvalide
} from './stand-bulk-edit';

export interface StandBulkEditData {
  stands: Stand[];
}

/**
 * Bulk edit of the selected stands: emplacement (the GPS-located place),
 * typologies proposées, staffing bounds and the premium/majeurs/effort flags.
 * Every field defaults to "ne pas modifier", so only what the user explicitly
 * changes is written.
 *
 * Recurring horaires are in scope — they are exactly the kind of thing a whole
 * set of stands shares ("open from 14:00 to closing, every day" covers thirty of
 * them on the reference festival). Dated exceptions stay out: those are
 * per-stand, per-day data by nature.
 */
@Component({
  selector: 'app-stand-bulk-edit-dialog',
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
  templateUrl: './stand-bulk-edit-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StandBulkEditDialog {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly dialogRef = inject<MatDialogRef<StandBulkEditDialog, boolean>>(MatDialogRef);
  private readonly data = inject<StandBulkEditData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly patch = signal<StandBulkPatch>(patchStandVide());
  protected readonly rienAModifier = computed(() => patchStandEstVide(this.patch()));
  protected readonly enCours = signal(false);

  /** Stands the patch would leave with `effectifMax < effectifMin`: the batch is blocked as a whole. */
  protected readonly standsInvalides = computed(() =>
    standsAvecEffectifInvalide(this.data.stands, this.patch(), this.store.emplacements())
  );
  protected readonly effectifInvalideMessage = computed(() => {
    const noms = this.standsInvalides()
      .map((stand) => stand.nom || stand.id)
      .join(', ');
    return $localize`:@@stands.bulk.effectifInvalide:Effectif maximum inférieur au minimum pour : ${noms}:stands:`;
  });

  protected readonly modesBooleen: { value: ModeBooleen; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'OUI', label: $localize`:@@common.oui:Oui` },
    { value: 'NON', label: $localize`:@@common.non:Non` }
  ];
  protected readonly modesListe: { value: ModeListe; label: string }[] = [
    { value: 'AUCUN', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'AJOUTER', label: $localize`:@@bulk.mode.ajouter:Ajouter` },
    { value: 'RETIRER', label: $localize`:@@bulk.mode.retirer:Retirer` },
    { value: 'REMPLACER', label: $localize`:@@bulk.mode.remplacer:Remplacer` }
  ];
  protected readonly modesEmplacement: { value: ModeEmplacement; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'DEFINIR', label: $localize`:@@bulk.mode.definir:Définir` },
    { value: 'EFFACER', label: $localize`:@@bulk.mode.effacer:Effacer` }
  ];
  protected readonly niveauxEffort: { value: 'INCHANGE' | NiveauEffort; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'NORMAL', label: 'NORMAL' },
    { value: 'EPUISANT', label: 'EPUISANT' }
  ];
  protected readonly modesHoraires: { value: ModeHoraires; label: string }[] = [
    { value: 'INCHANGE', label: $localize`:@@bulk.mode.inchange:Ne pas modifier` },
    { value: 'AJOUTER', label: $localize`:@@bulk.mode.ajouter:Ajouter` },
    { value: 'REMPLACER', label: $localize`:@@bulk.mode.remplacer:Remplacer` },
    { value: 'EFFACER', label: $localize`:@@bulk.mode.effacer:Effacer` }
  ];
  protected readonly joursSemaine: readonly JourSemaine[] = [
    'MONDAY',
    'TUESDAY',
    'WEDNESDAY',
    'THURSDAY',
    'FRIDAY',
    'SATURDAY',
    'SUNDAY'
  ];

  /** First problem among the rules being applied, or `null` — same check as the single-stand form. */
  protected readonly erreurHoraires = computed(() => {
    const patch = this.patch().horaires;
    if (patch.mode === 'INCHANGE' || patch.mode === 'EFFACER') {
      return null;
    }
    return premiereErreurHoraire(patch.horaires);
  });

  protected updateModeHoraires(mode: ModeHoraires): void {
    this.update({ horaires: { ...this.patch().horaires, mode } });
  }

  protected ajouterHoraire(): void {
    const horaires = this.patch().horaires;
    this.update({ horaires: { ...horaires, horaires: [...horaires.horaires, horaireVide()] } });
  }

  protected patchHoraire(index: number, patch: Partial<HoraireStand>): void {
    this.majHoraires((horaires) =>
      horaires.map((horaire, i) => (i === index ? { ...horaire, ...patch } : horaire))
    );
  }

  protected retirerHoraire(index: number): void {
    this.majHoraires((horaires) => horaires.filter((_, i) => i !== index));
  }

  protected ajouterFenetre(index: number): void {
    this.majFenetres(index, (fenetres) => [...fenetres, { heureDebut: '', heureFin: null }]);
  }

  protected patchFenetre(indexHoraire: number, indexFenetre: number, patch: Partial<FenetreHoraire>): void {
    this.majFenetres(indexHoraire, (fenetres) =>
      fenetres.map((fenetre, i) => (i === indexFenetre ? { ...fenetre, ...patch } : fenetre))
    );
  }

  protected retirerFenetre(indexHoraire: number, indexFenetre: number): void {
    this.majFenetres(indexHoraire, (fenetres) => fenetres.filter((_, i) => i !== indexFenetre));
  }

  protected basculerJourSemaine(index: number, jour: JourSemaine, coche: boolean): void {
    this.majHoraires((horaires) =>
      horaires.map((horaire, i) =>
        i === index
          ? {
              ...horaire,
              joursSemaine: coche
                ? [...new Set([...horaire.joursSemaine, jour])]
                : horaire.joursSemaine.filter((autre) => autre !== jour)
            }
          : horaire
      )
    );
  }

  protected patchDates(index: number, valeur: string): void {
    const dates = valeur
      .split(',')
      .map((date) => date.trim())
      .filter((date) => /^\d{4}-\d{2}-\d{2}$/.test(date));
    this.patchHoraire(index, { dates });
  }

  protected libelleJourSemaine(jour: JourSemaine): string {
    return libelleJourSemaine(jour);
  }

  private majHoraires(transformer: (horaires: HoraireStand[]) => HoraireStand[]): void {
    const horaires = this.patch().horaires;
    this.update({ horaires: { ...horaires, horaires: transformer(horaires.horaires) } });
  }

  private majFenetres(index: number, transformer: (fenetres: FenetreHoraire[]) => FenetreHoraire[]): void {
    this.majHoraires((horaires) =>
      horaires.map((horaire, i) =>
        i === index ? { ...horaire, fenetres: transformer(horaire.fenetres) } : horaire
      )
    );
  }

  protected readonly formTitle = $localize`:@@stands.bulk.title:Modifier ${this.data.stands.length}:count: stands`;

  protected update(patch: Partial<StandBulkPatch>): void {
    this.patch.update((courant) => ({ ...courant, ...patch }));
  }

  /** An emptied number field means "ne pas modifier", not zero. */
  protected updateEffectif(champ: 'effectifMin' | 'effectifMax', valeur: unknown): void {
    this.update({ [champ]: effectifDepuisSaisie(valeur) } as Partial<StandBulkPatch>);
  }

  protected async save(): Promise<void> {
    if (this.rienAModifier() || this.standsInvalides().length > 0 || this.enCours() || this.erreurHoraires()) {
      return;
    }
    const patch = this.patch();
    const emplacements = this.store.emplacements();
    const payloads = this.data.stands.map((stand) => appliquerPatchStand(stand, patch, emplacements));
    this.enCours.set(true);
    try {
      if ((await this.crud.saveMany('stands', payloads, labelStandsPluriel())) > 0) {
        this.dialogRef.close(true);
      }
    } finally {
      this.enCours.set(false);
    }
  }
}
