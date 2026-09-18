import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ConsignesApi } from '../../core/api/consignes-api';
import { normaliseHour } from '../../core/horaire-stand';
import { PrereglageConsigne } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConsigneRepasFields } from './consigne-repas-fields';
import {
  erreursRepas,
  fenetresDemandees,
  fenetresSaisies,
  formatFenetresSaisie,
  heureSaisie,
  parseFenetresSaisie,
  repasDemande,
  repasSaisie,
} from './consignes';

export interface PrereglageDialogData {
  prereglage: PrereglageConsigne | null;
}

/**
 * One preset — « Plan canicule » — its band, its motif, its default windows
 * on one line, the way the series dialog reads a day, and the meal windows a
 * consigne made from it restates. Nothing is laid on a date here: a preset
 * changes nothing until a consigne is made from it.
 */
@Component({
  selector: 'app-prereglage-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    ConsigneRepasFields,
  ],
  templateUrl: './prereglage-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrereglageDialog {
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly dialogRef =
    inject<MatDialogRef<PrereglageDialog, PrereglageConsigne | null>>(MatDialogRef);
  private readonly data = inject<PrereglageDialogData>(MAT_DIALOG_DATA);
  private readonly api = inject(ConsignesApi);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly editingId = this.data.prereglage?.id ?? null;
  protected readonly nom = signal(this.data.prereglage?.nom ?? '');
  protected readonly fermetureDebut = signal(
    heureSaisie(this.data.prereglage?.fermetureDebut ?? '12:00'),
  );
  protected readonly fermetureFin = signal(
    this.data.prereglage ? heureSaisie(this.data.prereglage.fermetureFin) : '18:00',
  );
  protected readonly motif = signal(this.data.prereglage?.motif ?? '');
  protected readonly ligne = signal(
    this.data.prereglage
      ? formatFenetresSaisie(fenetresSaisies(this.data.prereglage.fenetres))
      : '',
  );
  protected readonly repas = signal(repasSaisie(this.data.prereglage?.repas ?? null));
  protected readonly enregistrement = signal(false);

  protected readonly formTitle = this.editingId
    ? $localize`:@@consignes.prereglage.form.editTitle:Modifier le préréglage`
    : $localize`:@@consignes.prereglage.form.newTitle:Nouveau préréglage`;

  /** The windows line read back, `null` while it cannot be read. */
  protected readonly fenetres = computed(() =>
    this.ligne().trim() === '' ? [] : parseFenetresSaisie(this.ligne()),
  );
  protected readonly invalide = computed(
    () =>
      this.nom().trim() === '' ||
      this.motif().trim() === '' ||
      normaliseHour(this.fermetureDebut()) === null ||
      (this.fermetureFin() !== '' && normaliseHour(this.fermetureFin()) === null) ||
      this.fenetres() === null ||
      erreursRepas(this.repas()).length > 0,
  );

  protected async save(): Promise<void> {
    const fenetres = this.fenetres();
    if (this.invalide() || fenetres === null || this.enregistrement()) {
      return;
    }
    const prereglage = {
      nom: this.nom().trim(),
      fermetureDebut: this.fermetureDebut(),
      fermetureFin: this.fermetureFin() === '' ? null : this.fermetureFin(),
      motif: this.motif().trim(),
      fenetres: fenetresDemandees(fenetres),
      repas: repasDemande(this.repas()),
    };
    this.enregistrement.set(true);
    try {
      const written =
        this.editingId !== null
          ? await this.api.updatePrereglage(this.editingId, prereglage)
          : await this.api.createPrereglage(prereglage);
      this.dialogRef.close(written);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.enregistrement.set(false);
    }
  }
}
