import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ConsignesApi } from '../../core/api/consignes-api';
import { ApercuLeveeConsigne, VacationRef } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { bandeLabel, libelleDate } from './consignes';

export interface ConsigneLeveeData {
  /** The dates still to come that carry a consigne: the only ones that can be lifted. */
  datesLevables: string[];
  /** The dates ticked when the dialog opens — the row's own, or none. */
  datesInitiales: string[];
}

/**
 * « Lever » (issue #4): the consigne is withdrawn from the chosen dates, the
 * créneaux it added leave with their seats, and the day's reading is
 * withdrawn too. Previewed first, always — the figures are what somebody
 * confirms, not the button.
 */
@Component({
  selector: 'app-consigne-levee-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
  ],
  templateUrl: './consigne-levee-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConsigneLeveeDialog {
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly dialogRef = inject<MatDialogRef<ConsigneLeveeDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<ConsigneLeveeData>(MAT_DIALOG_DATA);
  private readonly api = inject(ConsignesApi);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly dates = signal<string[]>([...this.data.datesInitiales]);
  protected readonly apercu = signal<ApercuLeveeConsigne[] | null>(null);
  protected readonly chargement = signal(false);
  protected readonly levee = signal(false);

  protected readonly options = computed(() =>
    [...this.data.datesLevables].sort().map((date) => ({ date, libelle: libelleDate(date) })),
  );

  protected onDates(dates: string[]): void {
    this.dates.set([...dates].sort());
    this.apercu.set(null);
  }

  protected async previsualiser(): Promise<void> {
    if (this.dates().length === 0 || this.chargement()) {
      return;
    }
    this.chargement.set(true);
    try {
      this.apercu.set(await this.api.apercuLevee(this.dates()));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  protected async lever(): Promise<void> {
    if (this.dates().length === 0 || this.levee()) {
      return;
    }
    this.levee.set(true);
    try {
      await this.api.lever(this.dates());
      this.dialogRef.close(true);
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.levee.set(false);
    }
  }

  protected libelleDate(date: string): string {
    return libelleDate(date);
  }

  protected vacations(refs: VacationRef[]): string {
    return refs.map((ref) => bandeLabel(ref.heureDebut, ref.heureFin)).join(', ');
  }
}
