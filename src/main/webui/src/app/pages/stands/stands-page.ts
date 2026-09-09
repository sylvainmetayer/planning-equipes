import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { labelStandsPluriel } from '../../core/entity-labels';
import { resumerHoraires } from '../../core/horaire-stand';
import { NotificationService } from '../../core/notification.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ReferenceTablePage } from '../../core/reference-table-page';
import { RapportCompactage, Stand } from '../../core/models';
import { BulkActionsBar } from '../../shared/bulk-actions-bar';
import { TableFilter } from '../../shared/table-filter';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StandBulkEditData, StandBulkEditDialog } from './stand-bulk-edit-dialog';
import { buildStandDetail } from './stand-detail';
import { StandFormData, StandFormDialog } from './stand-form-dialog';

/** Relay families of the edition's grid: below two, the family says nothing (issue #390). */
function nombreFamilles(store: ReferenceDataStore): number {
  return Math.max(0, ...store.creneaux().map((creneau) => creneau.famille ?? 0)) + 1;
}

/**
 * Stands CRUD: identity, staffing bounds, adults-only flag and typologies.
 *
 * Rows named by a feasibility cause carry an alert icon whose tooltip is the
 * cause's own message, so a stand nobody can staff is visible where it is
 * edited, not only on the Problèmes page.
 *
 * Rows are multi-selectable, for a bulk delete or a bulk edit of the fields
 * stands share (emplacement, typologies, effectif, indicateurs).
 */
@Component({
  selector: 'app-stands-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule,
    RouterLink,
    BulkActionsBar,
    TableFilter
  ],
  templateUrl: './stands-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StandsPage extends ReferenceTablePage<Stand> {
  protected readonly columns = ['select', 'id', 'nom', 'effectif', 'typologies', 'emplacement', 'horaires', 'actions'];

  /** The template names the rows after the entity, as the other pages do. */
  protected readonly standsFiltres = this.lignesFiltrees;

  /** True while the compaction round-trip is in flight, to keep it from being fired twice. */
  protected readonly compactageEnCours = signal(false);

  /** Holds `causeParStandId`: a memoised map, so each row only does a lookup. */
  protected readonly problemes = inject(ProblemesStore);

  private readonly api = inject(ApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  constructor() {
    super({
      rows: (store) => store.stands(),
      id: (stand) => stand.id,
      champsFiltre: (stand) => [
        stand.id,
        stand.nom,
        ...(stand.typologiesProposees ?? []),
        stand.emplacement?.nom,
        stand.emplacement?.id
      ],
      detail: (stand, store) => ({
        title: stand.nom || stand.id,
        subtitle: stand.id,
        sections: buildStandDetail(stand, store.typologies(), nombreFamilles(store))
      }),
      formulaire: (stand, dialog: MatDialog) => {
        dialog.open<StandFormDialog, StandFormData, boolean>(StandFormDialog, {
          data: { stand },
          width: '40rem',
          maxWidth: '95vw',
          autoFocus: 'first-tabbable'
        });
      },
      ressource: 'stands',
      libelle: () => $localize`:@@stands.entityLabel:Stand`,
      libellePluriel: labelStandsPluriel
    });
    void this.problemes.reloadFeasibility();
  }

  protected typologiesLabel(stand: Stand): string {
    return (stand.typologiesProposees ?? []).join(', ') || '—';
  }

  protected effectifSuffix(stand: Stand): string {
    const majeurs = stand.reserveMajeurs ? $localize`:@@stands.suffix.majeurs: · majeurs` : '';
    const premium = stand.premium ? $localize`:@@stands.suffix.premium: · premium` : '';
    const epuisant = stand.niveauEffort === 'EPUISANT' ? $localize`:@@stands.suffix.epuisant: · épuisant` : '';
    return `${majeurs}${premium}${epuisant}`;
  }

  protected emplacementLabel(stand: Stand): string {
    return stand.emplacement?.nom || '—';
  }

  /**
   * "2 règles · 1 exception" instead of the raw window count. The old column
   * showed `24` for a stand simply open 10:00-12:00 then 14:00-20:00 every day,
   * which said nothing about its schedule.
   */
  protected horairesLabel(stand: Stand): string {
    return resumerHoraires(stand, {
      aucun: '—',
      regles: (n) => $localize`:@@stands.horaires.summary.regles:${n}:count: règle(s)`,
      exceptions: (n) => $localize`:@@stands.horaires.summary.exceptions:${n}:count: exception(s)`
    });
  }

  /**
   * Rewrites hand-entered dated windows as the recurring rules they repeat.
   * Always a dry run first: the report it returns is what the confirmation
   * dialog shows, so nothing is written before the user has seen the trade.
   */
  protected async compacterHoraires(): Promise<void> {
    this.compactageEnCours.set(true);
    try {
      await this.lancerCompactage();
    } catch (error) {
      // Same channel as every other write of this page: a snack bar, not an
      // unhandled rejection swallowed by the click handler.
      this.crud.reportError(error);
    } finally {
      this.compactageEnCours.set(false);
    }
  }

  private async lancerCompactage(): Promise<void> {
    const apercu = await this.api.post<RapportCompactage>('/api/stands/compactage-horaires?appliquer=false', {});
    if (apercu.standsCompactes === 0) {
      this.notifications.notify({
        title: $localize`:@@stands.compactage.rienATitle:Aucun horaire à compacter`,
        message: $localize`:@@stands.compactage.rienAMessage:Aucun stand ne répète un motif qui pourrait devenir une règle.`,
        variant: 'info'
      });
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@stands.compactage.confirmTitle:Compacter les horaires ?`,
      message: $localize`:@@stands.compactage.confirmMessage:${apercu.standsCompactes}:stands: stand(s) verront leurs ${apercu.fenetresAvant}:avant: plages datées remplacées par ${apercu.fenetresApres}:apres: règles et exceptions. Les stands dont les règles ne reproduiraient pas exactement les mêmes ouvertures sont laissés inchangés.`,
      confirmLabel: $localize`:@@stands.compactage.confirmLabel:Compacter`
    });
    if (!confirme) {
      return;
    }
    const rapport = await this.api.post<RapportCompactage>('/api/stands/compactage-horaires?appliquer=true', {});
    await this.crud.reload();
    this.notifications.notify({
      title: $localize`:@@stands.compactage.doneTitle:Horaires compactés`,
      message: $localize`:@@stands.compactage.doneMessage:${rapport.standsCompactes}:stands: stand(s) compacté(s), ${rapport.fenetresAvant}:avant: plages ramenées à ${rapport.fenetresApres}:apres: entrées.`,
      variant: 'success'
    });
  }

  protected editSelection(): void {
    const selectionnes = new Set(this.selection.selectedIds());
    this.dialog.open<StandBulkEditDialog, StandBulkEditData, boolean>(StandBulkEditDialog, {
      data: { stands: this.store.stands().filter((stand) => selectionnes.has(stand.id)) },
      width: '48rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable'
    });
  }
}
