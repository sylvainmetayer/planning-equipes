import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  ViewEncapsulation,
  computed,
  signal,
  inject,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AnalysesApi } from '../../core/api/analyses-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { KpiHistoriqueEntry } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StatusMessage } from '../../shared/status-message';
import { kpiSentence } from '../versions/versions';

/**
 * The indicators of every finished solve, every edition together, newest
 * first (`GET /api/mesures-edition/indicateurs`): the history one reads to put
 * two editions side by side. A row outlives its edition, and is deleted from
 * here whichever edition it came from.
 */
@Component({
  selector: 'app-indicateurs-view',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
    StatusMessage,
  ],
  templateUrl: './indicateurs-view.html',
  styleUrl: './indicateurs-view.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IndicateursView implements OnInit {
  protected readonly columns = ['quand', 'edition', 'resultat', 'actions'];

  private readonly analysesApi = inject(AnalysesApi);
  private readonly confirm = inject(ConfirmService);

  protected readonly entries = signal<KpiHistoriqueEntry[]>([]);
  protected readonly loading = signal(false);
  protected readonly pending = signal<number | null>(null);
  protected readonly error = signal('');
  protected readonly empty = computed(() => this.entries().length === 0 && !this.loading());

  ngOnInit(): void {
    void this.reload();
  }

  protected async reload(): Promise<void> {
    this.error.set('');
    this.loading.set(true);
    try {
      this.entries.set(await this.analysesApi.kpiHistory());
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected dateLabel(entry: KpiHistoriqueEntry): string {
    return entry.creeLe ? new Date(entry.creeLe).toLocaleString(intlLocale()) : '';
  }

  protected sentenceOf(entry: KpiHistoriqueEntry): string {
    return kpiSentence(entry.kpi);
  }

  protected async remove(entry: KpiHistoriqueEntry): Promise<void> {
    const date = this.dateLabel(entry);
    const confirme = await this.confirm.ask({
      title: $localize`:@@kpi.delete.title:Supprimer cette ligne d'historique ?`,
      message: $localize`:@@kpi.delete.message:La mesure du ${date}:date: sera définitivement perdue.`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    this.pending.set(entry.id);
    this.error.set('');
    try {
      await this.analysesApi.deleteKpiEntry(entry.id);
      this.entries.update((entries) => entries.filter((each) => each.id !== entry.id));
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.pending.set(null);
    }
  }
}
