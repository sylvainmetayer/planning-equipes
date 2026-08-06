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
import { ContrainteAdHoc, TypeContrainteAdHoc } from '../../core/models';
import { AdHocConstraintFormData, AdHocConstraintFormDialog } from './ad-hoc-constraint-form-dialog';

/** Called lazily (never at module scope, see `app.ts`'s `buildNavGroups`). */
function contrainteTypeLabel(value: TypeContrainteAdHoc): string {
  switch (value) {
    case 'INDISPONIBILITE_FORCEE':
      return $localize`:@@adHoc.type.indisponibiliteForcee:Indisponibilité forcée`;
    case 'INCOMPATIBILITE':
      return $localize`:@@adHoc.type.incompatibilite:Incompatibilité`;
    case 'AFFECTATION_FORCEE':
      return $localize`:@@adHoc.type.affectationForcee:Affectation forcée`;
  }
}

/**
 * Ad hoc constraints CRUD. They are evaluated as hard constraints by the
 * solver. The backend only exposes POST (create or overwrite by id) and
 * DELETE, so an edit is always saved as a creation (see the form dialog).
 */
@Component({
  selector: 'app-ad-hoc-constraints-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTableModule, MatTooltipModule],
  templateUrl: './ad-hoc-constraints-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdHocConstraintsPage {
  protected readonly columns = ['id', 'type', 'animateurs', 'portee', 'raison', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.crud.reload();
  }

  protected typeLabel(contrainte: ContrainteAdHoc): string {
    return contrainteTypeLabel(contrainte.type);
  }

  protected animateursLabel(contrainte: ContrainteAdHoc): string {
    const ids = (contrainte.animateursConcernes ?? []).map((animateur) => animateur.id);
    return ids.length ? ids.join(', ') : '—';
  }

  protected porteeLabel(contrainte: ContrainteAdHoc): string {
    const standId = contrainte.stand?.id;
    const scope = [
      this.creneauScopeLabel(contrainte.creneau),
      standId ? $localize`:@@adHoc.scope.stand:stand ${standId}:id:` : ''
    ]
      .filter(Boolean)
      .join(' · ');
    return scope || '—';
  }

  /** The backend only sends the créneau id (see `ContrainteAdHoc`): resolve the human-readable slot from the reference store rather than showing that internal id. */
  private creneauScopeLabel(creneauRef: { id: number } | null): string {
    if (!creneauRef) {
      return '';
    }
    const creneau = this.store.creneaux().find((c) => c.id === creneauRef.id);
    if (!creneau) {
      return $localize`:@@adHoc.scope.creneauSupprime:créneau supprimé`;
    }
    return $localize`:@@adHoc.scope.creneau:créneau J${creneau.jour}:jour: · ${creneau.date}:date: ${creneau.heureDebut}:heureDebut:–${creneau.heureFin}:heureFin:`;
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(contrainte: ContrainteAdHoc): void {
    this.openDialog(contrainte);
  }

  private openDialog(contrainte: ContrainteAdHoc | null): void {
    this.dialog.open<AdHocConstraintFormDialog, AdHocConstraintFormData, boolean>(AdHocConstraintFormDialog, {
      data: { contrainte },
      width: '40rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  protected async remove(contrainte: ContrainteAdHoc): Promise<void> {
    await this.crud.remove('contraintes-ad-hoc', contrainte.id, $localize`:@@adHoc.entityLabel:Contrainte`);
  }
}
