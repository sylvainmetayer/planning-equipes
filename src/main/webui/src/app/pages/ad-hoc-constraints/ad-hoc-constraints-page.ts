import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { consumeQueryParam } from '../../core/view-query-params';
import { ContrainteAdHoc, TypeContrainteAdHoc } from '../../core/models';
import {
  AdHocConstraintFormData,
  AdHocConstraintFormDialog,
} from './ad-hoc-constraint-form-dialog';

/** Called lazily (never at module scope, see `app.ts`'s `buildNavGroups`). */
function contrainteTypeLabel(value: TypeContrainteAdHoc): string {
  switch (value) {
    case 'INDISPONIBILITE_FORCEE':
      return $localize`:@@adHoc.type.indisponibiliteForcee:Indisponibilité forcée`;
    case 'INCOMPATIBILITE':
      return $localize`:@@adHoc.type.incompatibilite:Incompatibilité`;
    case 'AFFECTATION_FORCEE':
      return $localize`:@@adHoc.type.affectationForcee:Affectation forcée`;
    case 'AFFINITE':
      return $localize`:@@adHoc.type.affinite:Affinité (paire à privilégier)`;
  }
}

/**
 * CRUD of the manual adjustments — {@code ContrainteAdHoc} in the domain and on
 * the wire, « Ajustements manuels » on screen: what an organiser types here is
 * an exception to the plan, not one of the catalogue's rules, and the two used
 * to read as the same thing on the Contraintes screen next door.
 *
 * <p>The prescriptive ones are evaluated as hard constraints by the solver. The
 * backend only exposes POST (create or overwrite by id) and DELETE, so an edit
 * is always saved as a creation (see the form dialog).</p>
 */
@Component({
  selector: 'app-ad-hoc-constraints-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule,
    RouterLink,
  ],
  templateUrl: './ad-hoc-constraints-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdHocConstraintsPage {
  protected readonly columns = ['id', 'type', 'animateurs', 'portee', 'raison', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /**
   * Holds `causeParContrainteAdHocId`: exceptions that contradict each other
   * are refused at entry time, so what this badges is what was recorded before
   * that check existed, or imported in one go — nothing else would ever point
   * at them.
   */
  protected readonly problemes = inject(ProblemesStore);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    const chargement = this.crud.reload();
    void this.problemes.reloadFeasibility();
    // `?edit=<id>`: « Voir la fiche » on an adjustment saved with a warning
    // lands here with its form open, as it does on the other reference screens.
    consumeQueryParam('edit', async (edit) => {
      await chargement;
      const contrainte = this.store.contraintes().find((candidate) => candidate.id === edit);
      if (contrainte) {
        this.openDialog(contrainte);
      }
    });
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
      standId ? $localize`:@@adHoc.scope.stand:stand ${standId}:id:` : '',
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
    this.dialog.open<AdHocConstraintFormDialog, AdHocConstraintFormData, boolean>(
      AdHocConstraintFormDialog,
      {
        data: { contrainte },
        width: '40rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      },
    );
  }

  protected async remove(contrainte: ContrainteAdHoc): Promise<void> {
    await this.crud.remove(
      'contraintes-ad-hoc',
      contrainte.id,
      $localize`:@@adHoc.entityLabel:Ajustement`,
    );
  }
}
