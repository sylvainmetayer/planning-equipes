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
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { DerivationRequest, RapportDerivation } from '../../core/models';
import { summarizeVacationsByDay } from './jours-resume';
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
  remplacer: boolean;
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
  private readonly crud = inject(ReferenceCrudService);
  private readonly confirm = inject(ConfirmService);

  protected readonly draft = signal<DerivationDraft>({
    dateDebut: this.data.dateDebut ?? '',
    dateFin: this.data.dateFin ?? '',
    heureFermeture: '20:00',
    dureeMinimaleMinutes: 15,
    remplacer: false,
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
    return apercu ? summarizeVacationsByDay(apercu.creneaux) : [];
  });
  protected readonly bilan = computed(() => {
    const apercu = this.apercu();
    return apercu ? bilanGrille(apercu.controle) : null;
  });
  protected readonly anomalies = computed(() =>
    trierAnomalies(this.apercu()?.controle.anomalies ?? []),
  );
  protected readonly bloquee = computed(() => grilleBloquee(this.apercu()?.controle ?? null));
  protected readonly peutEcrire = computed(
    () =>
      this.apercuAJour() &&
      !this.bloquee() &&
      (this.apercu()?.nombreGeneres ?? 0) > 0 &&
      !this.ecriture() &&
      !this.editingLocked(),
  );
  protected readonly gridAnomalyIcon = gridAnomalyIcon;

  /** The cuts of one day, `10:00 (A, B et 3 autres)`, for the preview. */
  protected coupuresDe(date: string): string[] {
    return (this.apercu()?.coupures ?? [])
      .filter((coupure) => coupure.date === date)
      .map((coupure) => {
        const reste = coupure.nombreStands - coupure.standIds.length;
        const stands =
          reste > 0
            ? $localize`:@@creneaux.derivation.coupure.autres:${coupure.standIds.join(', ')}:stands: et ${reste}:reste: autre(s)`
            : coupure.standIds.join(', ');
        return `${coupure.heure.slice(0, 5)} (${stands})`;
      });
  }

  protected patch(patch: Partial<DerivationDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  private requete(): DerivationRequest {
    const draft = this.draft();
    return {
      dateDebut: draft.dateDebut,
      dateFin: draft.dateFin,
      heureFermeture: draft.heureFermeture,
      dureeMinimaleMinutes: Number(draft.dureeMinimaleMinutes),
      remplacer: draft.remplacer,
    };
  }

  protected async previsualiser(): Promise<void> {
    if (this.erreur() !== null || this.chargement()) {
      return;
    }
    this.chargement.set(true);
    try {
      this.apercu.set(await this.creneauxApi.previewDerivation(this.requete()));
      this.signatureApercu.set(JSON.stringify(this.draft()));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  protected async ecrire(): Promise<void> {
    if (!this.peutEcrire()) {
      return;
    }
    if (this.draft().remplacer) {
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
      const rapport = await this.creneauxApi.derive(this.requete());
      this.dialogRef.close(rapport);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.ecriture.set(false);
    }
  }
}
