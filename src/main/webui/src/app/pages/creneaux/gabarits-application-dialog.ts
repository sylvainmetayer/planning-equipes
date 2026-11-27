import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { GenerateurCreneauxApi } from '../../core/api/generateur-creneaux-api';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { RapportApplication } from '../../core/models';
import { bilanGrille, gridAnomalyIcon, trierAnomalies } from './grille-creneaux';
import { HhmmPipe } from '../../shared/hhmm-pipe';

export interface GabaritsApplicationData {
  /** The preview the card took before opening: what « Appliquer » will do. */
  apercu: RapportApplication;
}

/**
 * « Appliquer le calendrier » : the preview in words — kept, updated, created,
 * removed, and which removals take seats of the persisted plan with them —
 * then the write. The dialog never previews itself: the card did, and hands it
 * over, so what the user reads is exactly what was computed.
 */
@Component({
  selector: 'app-gabarits-application-dialog',
  imports: [HhmmPipe, MatDialogModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './gabarits-application-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GabaritsApplicationDialog {
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly dialogRef =
    inject<MatDialogRef<GabaritsApplicationDialog, RapportApplication | null>>(MatDialogRef);
  protected readonly data = inject<GabaritsApplicationData>(MAT_DIALOG_DATA);
  private readonly api = inject(GenerateurCreneauxApi);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly application = signal(false);
  protected readonly apercu = this.data.apercu;
  protected readonly bilan = computed(() => bilanGrille(this.apercu.controle));
  protected readonly anomalies = computed(() => trierAnomalies(this.apercu.controle.anomalies));
  protected readonly gridAnomalyIcon = gridAnomalyIcon;
  /** A write that removes seats is confirmed as destructive: the button says so. */
  protected readonly destructif = this.apercu.postesSupprimes > 0;

  protected async appliquer(): Promise<void> {
    if (this.application() || this.editingLocked() || this.apercu.aucunChangement) {
      return;
    }
    this.application.set(true);
    try {
      const generation = await this.api.apply({ source: 'GABARITS' });
      this.dialogRef.close(generation.application ?? null);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.application.set(false);
    }
  }
}
