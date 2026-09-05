import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import {
  JourSemaine,
  ModeGrilleCreneaux,
  RapportGrille,
  RapportRecurrence,
  RegleRecurrence
} from '../../core/models';
import { libelleJourSemaine } from '../stands/stand-horaires';
import { summarizeVacationsByDay } from './decoupage';
import {
  ErreurSerie,
  SerieDraft,
  bilanGrille,
  erreursIntroduites,
  grilleBloquee,
  iconeAnomalieGrille,
  regleDepuis,
  serieVide,
  signatureSerie,
  trierAnomalies
} from './grille-creneaux';

export interface CreneauSerieData {
  /** The mode the edition declares: what the preview's verdict is read in. */
  mode: ModeGrilleCreneaux;
  /** The grid's current verdict, so only the errors the rule introduces block it. */
  controleActuel: RapportGrille | null;
}

/**
 * « Créer une série » : one rule — the day's windows on a line, a day
 * selector — that adds dozens of créneaux at once, the way the MCP tools
 * already did. Always previewed first: the server says what the rule would
 * add and what the grid would then look like, and a rule that would repeat
 * an existing créneau (an error in the verdict) cannot be written. Nothing is
 * written until « Créer ».
 */
@Component({
  selector: 'app-creneau-serie-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './creneau-serie-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CreneauSerieDialog {
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly dialogRef = inject<MatDialogRef<CreneauSerieDialog, RapportRecurrence | null>>(MatDialogRef);
  private readonly data = inject<CreneauSerieData>(MAT_DIALOG_DATA);
  private readonly api = inject(ApiService);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly joursSemaine: readonly JourSemaine[] = [
    'MONDAY',
    'TUESDAY',
    'WEDNESDAY',
    'THURSDAY',
    'FRIDAY',
    'SATURDAY',
    'SUNDAY'
  ];

  protected readonly draft = signal<SerieDraft>(serieVide());
  protected readonly apercu = signal<RapportRecurrence | null>(null);
  /** The rule the preview was taken on: an edit since sends the user back to « Prévisualiser ». */
  private readonly signatureApercu = signal<string | null>(null);
  protected readonly chargement = signal(false);
  protected readonly creation = signal(false);

  protected readonly regle = computed(() => regleDepuis(this.draft()));
  protected readonly erreur = computed(() => {
    const resultat = this.regle();
    return resultat.erreur === null ? null : this.message(resultat.erreur, resultat.morceau);
  });
  protected readonly apercuAJour = computed(
    () => this.apercu() !== null && this.signatureApercu() === signatureSerie(this.draft())
  );
  protected readonly bilan = computed(() => {
    const apercu = this.apercu();
    return apercu ? bilanGrille(apercu.controle) : null;
  });
  protected readonly resume = computed(() => {
    const apercu = this.apercu();
    return apercu ? summarizeVacationsByDay(apercu.creneaux) : [];
  });
  protected readonly anomalies = computed(() => {
    const apercu = this.apercu();
    return apercu ? trierAnomalies(apercu.controle.anomalies) : [];
  });
  protected readonly bloquee = computed(() =>
    grilleBloquee(this.apercu()?.controle ?? null, this.data.controleActuel)
  );
  /** The errors this rule would add — the ones the message is about. */
  protected readonly erreursIntroduites = computed(() => {
    const apercu = this.apercu();
    return apercu ? erreursIntroduites(apercu.controle, this.data.controleActuel) : [];
  });
  protected readonly peutCreer = computed(
    () => this.apercuAJour() && !this.bloquee() && !this.creation() && !this.editingLocked()
  );

  protected readonly iconeAnomalieGrille = iconeAnomalieGrille;

  protected libelleJourSemaine(jour: JourSemaine): string {
    return libelleJourSemaine(jour);
  }

  protected patch(patch: Partial<SerieDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected basculerJour(jour: JourSemaine, coche: boolean): void {
    const actuels = this.draft().joursSemaine;
    this.patch({ joursSemaine: coche ? [...new Set([...actuels, jour])] : actuels.filter((autre) => autre !== jour) });
  }

  protected async previsualiser(): Promise<void> {
    const regle = this.regle().regle;
    if (!regle || this.chargement()) {
      return;
    }
    this.chargement.set(true);
    try {
      const apercu = await this.api.post<RapportRecurrence>(
        `/api/creneaux/recurrence/apercu?mode=${this.data.mode}`,
        regle satisfies RegleRecurrence
      );
      this.apercu.set(apercu);
      this.signatureApercu.set(signatureSerie(this.draft()));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  protected async creer(): Promise<void> {
    const regle = this.regle().regle;
    if (!regle || !this.peutCreer()) {
      return;
    }
    this.creation.set(true);
    try {
      const rapport = await this.api.post<RapportRecurrence>(`/api/creneaux/recurrence?mode=${this.data.mode}`, regle);
      this.dialogRef.close(rapport);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.creation.set(false);
    }
  }

  private message(erreur: ErreurSerie, morceau: string | null): string {
    switch (erreur) {
      case 'FENETRES_VIDES':
        return $localize`:@@creneaux.serie.error.fenetresVides:Indiquez les fenêtres d'une journée, par exemple « 09:00-12:00, 14:00-18:00 ».`;
      case 'FENETRE_ILLISIBLE':
        return $localize`:@@creneaux.serie.error.fenetreIllisible:« ${morceau ?? ''}:morceau: » n'est pas une fenêtre : écrivez « début-fin », les heures comme 09:00 ou 9h30.`;
      case 'FIN_REQUISE':
        return $localize`:@@creneaux.serie.error.finRequise:La fenêtre qui commence à ${morceau ?? ''}:debut: n'a pas de fin : un créneau est l'amplitude du jour elle-même, il n'a pas de fermeture dont hériter.`;
      case 'EFFECTIF_REFUSE':
        return $localize`:@@creneaux.serie.error.effectifRefuse:« ${morceau ?? ''}:morceau: » : un créneau ne porte pas d'effectif, c'est chaque stand qui dit le sien.`;
      case 'PLAGE_REQUISE':
        return $localize`:@@creneaux.serie.error.plageRequise:Indiquez la première et la dernière date de la série.`;
      case 'PLAGE_INVERSEE':
        return $localize`:@@creneaux.serie.error.plageInversee:La dernière date est avant la première.`;
      case 'JOURS_SEMAINE_REQUIS':
        return $localize`:@@creneaux.serie.error.joursSemaineRequis:Cochez au moins un jour de la semaine.`;
      case 'DATES_REQUISES':
        return $localize`:@@creneaux.serie.error.datesRequises:Indiquez au moins une date (AAAA-MM-JJ).`;
      case 'DATES_ILLISIBLES':
        return $localize`:@@creneaux.serie.error.datesIllisibles:« ${morceau ?? ''}:morceau: » n'est pas une date : écrivez-la AAAA-MM-JJ, sinon elle serait ignorée sans un mot.`;
    }
  }
}
