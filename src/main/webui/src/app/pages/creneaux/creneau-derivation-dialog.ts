import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { labelsOf, standNames } from '../../core/reference-labels';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { DerivationRequest, RapportDerivation } from '../../core/models';
import { dayMonth, holidayDays, summarizeVacationsByDay } from './jours-resume';
import { JoursFeriesService } from '../../core/jours-feries.service';
import { PastilleFerie } from '../../shared/pastille-ferie';
import { bilanGrille, grilleBloquee, gridAnomalyIcon, trierAnomalies } from './grille-creneaux';

export interface CreneauDerivationData {
  /** Where the grid's dates already run, to prefill the range; `null` when the grid is empty. */
  dateDebut: string | null;
  dateFin: string | null;
}

/** The dialog's own state: strings where the inputs are. */
export interface DerivationDraft {
  dateDebut: string;
  dateFin: string;
  heureFermeture: string;
  dureeMinimaleMinutes: number;
}

/**
 * « Dériver des horaires des stands » : the grid the stands' own hours
 * imply — a cut at every hour some stand opens or closes — previewed with the
 * verdict on the grid that would result, then added to the grid or put in its
 * place. Replacing erases the solved plan, so it asks first, like the
 * découpage.
 */
@Component({
  selector: 'app-creneau-derivation-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    PastilleFerie,
  ],
  templateUrl: './creneau-derivation-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CreneauDerivationDialog {
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly dialogRef =
    inject<MatDialogRef<CreneauDerivationDialog, RapportDerivation | null>>(MatDialogRef);
  private readonly data = inject<CreneauDerivationData>(MAT_DIALOG_DATA);
  private readonly creneauxApi = inject(CreneauxApi);
  /** The public holidays of the preview's dates, marked beside them. */
  private readonly feries = inject(JoursFeriesService);
  private readonly crud = inject(ReferenceCrudService);
  private readonly confirm = inject(ConfirmService);
  /** Stand id → name: the preview names the stands at a cut by id. */
  private readonly store = inject(ReferenceDataStore);
  private readonly nomsStands = computed(() => standNames(this.store.stands()));

  protected readonly draft = signal<DerivationDraft>({
    dateDebut: this.data.dateDebut ?? '',
    dateFin: this.data.dateFin ?? '',
    heureFermeture: '20:00',
    dureeMinimaleMinutes: 15,
  });
  protected readonly apercu = signal<RapportDerivation | null>(null);
  private readonly signatureApercu = signal<string | null>(null);
  protected readonly chargement = signal(false);
  protected readonly ecriture = signal(false);

  protected readonly erreur = computed(() => {
    const { dateDebut, dateFin, heureFermeture, dureeMinimaleMinutes } = this.draft();
    if (!dateDebut || !dateFin) {
      return $localize`:@@creneaux.derivation.error.plage:Indiquez la première et la dernière date à dériver.`;
    }
    if (dateFin < dateDebut) {
      return $localize`:@@creneaux.derivation.error.plageInversee:La dernière date est avant la première.`;
    }
    if (!heureFermeture) {
      return $localize`:@@creneaux.derivation.error.fermeture:Indiquez l'heure de fermeture : c'est la fin des fenêtres « jusqu'à la fermeture » (00:00 pour minuit).`;
    }
    if (!Number.isInteger(Number(dureeMinimaleMinutes)) || Number(dureeMinimaleMinutes) < 0) {
      return $localize`:@@creneaux.derivation.error.duree:La durée minimale est un nombre de minutes, zéro compris.`;
    }
    return null;
  });
  protected readonly apercuAJour = computed(
    () => this.apercu() !== null && this.signatureApercu() === JSON.stringify(this.draft()),
  );
  protected readonly resume = computed(() => {
    const apercu = this.apercu();
    return apercu ? summarizeVacationsByDay(apercu.creneaux, this.feries.byDate()) : [];
  });
  /** « 14/07, 15/08 »: the dates of the preview that fall on a public holiday. */
  protected readonly datesFeriees = computed(() =>
    holidayDays(this.resume()).map((jour) => dayMonth(jour.date)),
  );
  protected readonly bilan = computed(() => {
    const apercu = this.apercu();
    return apercu ? bilanGrille(apercu.controle) : null;
  });
  protected readonly anomalies = computed(() =>
    trierAnomalies(this.apercu()?.controle.anomalies ?? []),
  );
  protected readonly bloquee = computed(() => grilleBloquee(this.apercu()?.controle ?? null));
  /** Replacing the grid needs a fresh preview with something in it — a verdict on the existing grid does not stop it. */
  protected readonly peutRemplacer = computed(
    () =>
      this.apercuAJour() &&
      (this.apercu()?.nombreGeneres ?? 0) > 0 &&
      !this.ecriture() &&
      !this.editingLocked(),
  );
  /** Adding to the grid also needs the grid it would give to carry no error. */
  protected readonly canWrite = computed(() => this.peutRemplacer() && !this.bloquee());
  protected readonly gridAnomalyIcon = gridAnomalyIcon;

  /** The cuts of one day, `10:00 (Bourse, Quiz et 3 autres)`, for the preview. */
  protected coupuresOf(date: string): string[] {
    const noms = this.nomsStands();
    return (this.apercu()?.coupures ?? [])
      .filter((coupure) => coupure.date === date)
      .map((coupure) => {
        const reste = coupure.nombreStands - coupure.standIds.length;
        const nommes = labelsOf(noms, coupure.standIds).join(', ');
        const stands =
          reste > 0
            ? $localize`:@@creneaux.derivation.coupure.autres:${nommes}:stands: et ${reste}:reste: autre(s)`
            : nommes;
        return `${coupure.heure.slice(0, 5)} (${stands})`;
      });
  }

  protected patch(patch: Partial<DerivationDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  private request(remplacer: boolean): DerivationRequest {
    const draft = this.draft();
    return {
      dateDebut: draft.dateDebut,
      dateFin: draft.dateFin,
      heureFermeture: draft.heureFermeture,
      dureeMinimaleMinutes: Number(draft.dureeMinimaleMinutes),
      remplacer,
    };
  }

  protected async previsualiser(): Promise<void> {
    if (this.erreur() !== null || this.chargement()) {
      return;
    }
    this.chargement.set(true);
    try {
      const apercu = await this.creneauxApi.previewDerivation(this.request(false));
      this.apercu.set(apercu);
      void this.feries.load(apercu.creneaux.map((creneau) => creneau.date));
      this.signatureApercu.set(JSON.stringify(this.draft()));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  /**
   * Writes the derived timeslots: added to the grid, or — after the preview,
   * and on an explicit confirmation that says what goes with it — in place of
   * the grid. Replacing is a destructive gesture, never a box ticked before
   * anything was seen.
   */
  protected async write(remplacer = false): Promise<void> {
    if (!(remplacer ? this.peutRemplacer() : this.canWrite())) {
      return;
    }
    if (remplacer) {
      const confirme = await this.confirm.ask({
        title: $localize`:@@creneaux.derivation.remplacer.title:Remplacer la grille ?`,
        message: $localize`:@@creneaux.derivation.remplacer.confirm:Les créneaux actuels de l'édition seront remplacés par les créneaux dérivés, et le planning résolu sera effacé avec eux.`,
        confirmLabel: $localize`:@@creneaux.derivation.remplacer.label:Remplacer`,
        danger: true,
      });
      if (!confirme) {
        return;
      }
    }
    this.ecriture.set(true);
    try {
      const rapport = await this.creneauxApi.derive(this.request(remplacer));
      this.dialogRef.close(rapport);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.ecriture.set(false);
    }
  }
}
