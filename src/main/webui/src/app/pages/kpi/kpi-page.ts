import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AnalysesApi } from '../../core/api/analyses-api';
import { intlLocale } from '../../core/locale';
import { KpiHistoriqueEntry } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StatusMessage } from '../../shared/status-message';
import { errorMessage } from '../../core/error-message';
import { errorText, retainedValue } from '../../core/resource-state';

/**
 * KPI history (issue #89): one row per completed solve, kept across editions
 * so a year can be compared to the previous one. Rows survive the deletion of
 * their edition (labels are denormalised server-side), and hold nothing
 * nominative — fairness is a dispersion of hours, never a ranking of people.
 */
@Component({
  selector: 'app-kpi-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
    StatusMessage,
  ],
  templateUrl: './kpi-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KpiPage {
  protected readonly columns = [
    'creeLe',
    'edition',
    'score',
    'couverture',
    'fairness',
    'modifications',
    'duree',
    'actions',
  ];

  private readonly analysesApi = inject(AnalysesApi);
  private readonly confirm = inject(ConfirmService);

  private readonly history = resource({ loader: () => this.analysesApi.kpiHistory() });
  // The list is kept across a failed refresh, unlike the report of /heures
  // which is dropped: this is history the server already holds, and losing
  // the screen to a network blip helps nobody. The error sits next to it, and
  // the operator just pressed « Actualiser », so nothing here passes for fresh.
  private readonly historyKept = retainedValue(this.history);
  protected readonly entries = computed(() => this.historyKept() ?? []);
  protected readonly chargement = this.history.isLoading;
  /** A deletion refused server-side; the next refresh clears it. */
  private readonly actionError = signal('');
  private readonly loadError = errorText(this.history, errorMessage);
  protected readonly error = computed(() => this.actionError() || this.loadError());

  protected recharger(): void {
    this.actionError.set('');
    this.history.reload();
  }

  protected async supprimer(entry: KpiHistoriqueEntry): Promise<void> {
    const confirme = await this.confirm.ask({
      title: $localize`:@@kpi.delete.title:Supprimer cette ligne d'historique ?`,
      message: $localize`:@@kpi.delete.message:La mesure du ${this.dateLabel(entry)}:date: sera définitivement perdue.`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    try {
      await this.analysesApi.deleteKpiEntry(entry.id);
      this.recharger();
    } catch (error) {
      this.actionError.set(errorMessage(error));
    }
  }

  protected dateLabel(entry: KpiHistoriqueEntry): string {
    return entry.creeLe ? new Date(entry.creeLe).toLocaleString(intlLocale()) : '';
  }

  protected editionLabel(entry: KpiHistoriqueEntry): string {
    return entry.editionNom ?? entry.editionId;
  }

  protected scoreLabel(entry: KpiHistoriqueEntry): string {
    return entry.kpi.score ?? '—';
  }

  protected couvertureLabel(entry: KpiHistoriqueEntry): string {
    if (entry.kpi.postesTotal === 0) {
      return '—';
    }
    const pourcent = ((entry.kpi.postesPourvus / entry.kpi.postesTotal) * 100).toFixed(1);
    return `${entry.kpi.postesPourvus} / ${entry.kpi.postesTotal} (${pourcent} %)`;
  }

  protected fairnessLabel(entry: KpiHistoriqueEntry): string {
    return entry.kpi.heuresEcartType === null ? '—' : `σ ${entry.kpi.heuresEcartType.toFixed(1)} h`;
  }

  protected modificationsLabel(entry: KpiHistoriqueEntry): string {
    if (entry.kpi.modificationsManuelles === null) {
      return '—';
    }
    const taux =
      entry.kpi.tauxModificationsManuelles === null
        ? ''
        : ` (${(entry.kpi.tauxModificationsManuelles * 100).toFixed(1)} %)`;
    return `${entry.kpi.modificationsManuelles}${taux}`;
  }

  protected dureeLabel(entry: KpiHistoriqueEntry): string {
    return entry.kpi.dureeSolveSecondes === null ? '—' : `${entry.kpi.dureeSolveSecondes} s`;
  }
}
