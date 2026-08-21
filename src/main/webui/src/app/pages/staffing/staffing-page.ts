import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { JourStaffing, StaffingSummary } from '../../core/models';
import { errorPrefix } from '../../core/error-message';

/**
 * Staffing-need calculator: how many animateurs the stands and créneaux
 * currently configured require at a minimum, before any animateur is entered.
 *
 * Everything is computed server-side (`GET /api/staffing`) on the very seats a
 * solve would have to fill. This page used to compute it in the browser from
 * `effectifMin` × open stands × créneaux, which ignored recurring horaires
 * (every stand counted open around the clock) and counted overlapping relay
 * vacations several times over — on edition-1708 it announced more than 1500
 * animateurs for a festival staffed by 153. See `StaffingAnalyzer` on the
 * backend for the methodology and its limits.
 */
@Component({
  selector: 'app-staffing-page',
  imports: [MatCardModule, MatIconModule, MatProgressBarModule, MatTableModule, MatTooltipModule, DecimalPipe],
  templateUrl: './staffing-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StaffingPage {
  protected readonly columns = ['jour', 'standsOuverts', 'sieges', 'heures', 'picSimultane', 'picAvecPause'];
  protected readonly summary = signal<StaffingSummary | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal('');

  protected readonly heuresParSemaine = computed(() => {
    const summary = this.summary();
    return summary && summary.nombreSemaines > 0 ? summary.capaciteHeuresParAnimateur / summary.nombreSemaines : 0;
  });

  private readonly api = inject(ApiService);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      this.summary.set(await this.api.get<StaffingSummary>('/api/staffing'));
      this.error.set('');
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected jourCritiqueLabel(jour: JourStaffing): string {
    const date = jour.date;
    const numero = jour.jour;
    const standsOuverts = jour.standsOuverts;
    return $localize`:@@staffing.busiestDay:Journée la plus chargée : J${numero}:jour: · ${date}:date: (${standsOuverts}:count: stands ouverts)`;
  }

  /** True for the bound that set the retained minimum, highlighted in the list. */
  protected estBorneRetenue(borne: StaffingSummary['borneRetenue']): boolean {
    return this.summary()?.borneRetenue === borne;
  }

  protected pauseMinutes(): number {
    return this.summary()?.pauseMinimaleMinutes ?? 0;
  }
}
