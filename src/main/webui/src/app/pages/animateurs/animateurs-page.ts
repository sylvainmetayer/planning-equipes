import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Animateur } from '../../core/models';
import { AnimateurFormData, AnimateurFormDialog } from './animateur-form-dialog';

/**
 * Animateurs CRUD. Minor/adult status is never stored: it is derived from the
 * birth date at the date of each timeslot, so only the birth date is edited.
 * Availability is opt-out: an animator works unless a day is listed here.
 *
 * An animateur declared unavailable on a day that carries a CRITIQUE
 * feasibility cause is flagged: that single unavailability is one of the
 * reasons the day cannot be staffed at all.
 */
@Component({
  selector: 'app-animateurs-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTableModule, MatTooltipModule],
  templateUrl: './animateurs-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AnimateursPage {
  protected readonly columns = ['id', 'nom', 'manager', 'competences', 'indisponibilites', 'actions'];

  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = computed(() => this.jobs.solverBusy());

  private readonly problemes = inject(ProblemesStore);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);

  /**
   * Animateur id → the tooltip explaining that one of their unavailability days
   * is a day with a critical shortfall. Memoised as a map so each row is a
   * lookup rather than a scan of every cause.
   */
  protected readonly alerteParAnimateurId = computed<Map<string, string>>(() => {
    const causesParDate = this.problemes.causeCritiqueParDate();
    const alertes = new Map<string, string>();
    if (causesParDate.size === 0) {
      return alertes;
    }
    for (const animateur of this.store.animateurs()) {
      const jour = (animateur.joursIndisponibles ?? []).find((date) => causesParDate.has(date));
      if (jour) {
        alertes.set(animateur.id, this.indisponibiliteCritiqueMessage(jour, causesParDate.get(jour)!.message));
      }
    }
    return alertes;
  });

  constructor() {
    void this.crud.reload();
    void this.problemes.reloadFeasibility();
  }

  private indisponibiliteCritiqueMessage(jour: string, cause: string): string {
    return $localize`:@@animateurs.alerte.indisponibiliteCritique:Indisponible le ${jour}:date:, un jour où l'effectif est structurellement insuffisant : ${cause}:cause:`;
  }

  protected competencesLabel(animateur: Animateur): string {
    const entries = Object.entries(animateur.competences ?? {});
    return entries.length === 0 ? '—' : entries.map(([typo, niveau]) => `${typo}: ${niveau}`).join(', ');
  }

  protected ouiNon(value: boolean): string {
    return value ? $localize`:@@common.oui:Oui` : $localize`:@@common.non:Non`;
  }

  protected openCreate(): void {
    this.openDialog(null);
  }

  protected edit(animateur: Animateur): void {
    this.openDialog(animateur);
  }

  private openDialog(animateur: Animateur | null): void {
    this.dialog.open<AnimateurFormDialog, AnimateurFormData, boolean>(AnimateurFormDialog, {
      data: { animateur },
      width: '44rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }

  protected async remove(animateur: Animateur): Promise<void> {
    await this.crud.remove('animateurs', animateur.id, $localize`:@@animateurs.entityLabel:Animateur`);
  }
}
