import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Stand } from '../../core/models';
import { StandFormData, StandFormDialog } from './stand-form-dialog';

/** Stands CRUD: identity, staffing bounds, adults-only flag and typologies. */
@Component({
  selector: 'app-stands-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTableModule, MatTooltipModule],
  templateUrl: './stands-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StandsPage {
  protected readonly columns = ['id', 'nom', 'effectif', 'typologies', 'emplacement', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.crud.reload();
  }

  protected typologiesLabel(stand: Stand): string {
    return (stand.typologiesProposees ?? []).join(', ') || '—';
  }

  protected effectifSuffix(stand: Stand): string {
    const majeurs = stand.reserveMajeurs ? $localize`:@@stands.suffix.majeurs: · majeurs` : '';
    const premium = stand.premium ? $localize`:@@stands.suffix.premium: · premium` : '';
    return `${majeurs}${premium}`;
  }

  protected emplacementLabel(stand: Stand): string {
    return stand.emplacement?.nom || '—';
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(stand: Stand): void {
    this.openDialog(stand);
  }

  private openDialog(stand: Stand | null): void {
    this.dialog.open<StandFormDialog, StandFormData, boolean>(StandFormDialog, {
      data: { stand },
      width: '40rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  protected async remove(stand: Stand): Promise<void> {
    await this.crud.remove('stands', stand.id, $localize`:@@stands.entityLabel:Stand`);
  }
}
