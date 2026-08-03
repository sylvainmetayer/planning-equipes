import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { ParametresLegaux } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { computeStaffingSummary, CreneauStaffing } from './staffing';

/**
 * Staffing-need calculator: from the configured stands and créneaux alone
 * (effectifMin, reserveMajeurs, standsOuvertsIds) plus the legal weekly-hour
 * cap, computes two lower bounds on the number of animateurs to recruit —
 * peak concurrent seats, and total workload divided by the legal cap — and
 * keeps the larger one. See `computeStaffingSummary` for the methodology and
 * its measured limits.
 */
@Component({
  selector: 'app-staffing-page',
  imports: [MatCardModule, MatIconModule, MatTableModule, MatTooltipModule, DecimalPipe],
  templateUrl: './staffing-page.html'
})
export class StaffingPage {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly columns = ['creneau', 'standsOuverts', 'total', 'majeurs', 'mineurs'];
  protected readonly dureeHebdomadaireMaxMinutes = signal<number | null>(null);
  /** Only the active groupe de créneaux' slots count — the others are an alternate planning, not the one being staffed. */
  private readonly activeGroupeId = computed(() => this.store.groupesCreneaux().find((groupe) => groupe.actif)?.id ?? null);
  private readonly creneauxGroupeActif = computed(() =>
    this.store.creneaux().filter((creneau) => creneau.groupe?.id === this.activeGroupeId())
  );
  protected readonly summary = computed(() =>
    computeStaffingSummary(this.store.stands(), this.creneauxGroupeActif(), this.dureeHebdomadaireMaxMinutes() ?? undefined)
  );

  private readonly crud = inject(ReferenceCrudService);
  private readonly api = inject(ApiService);

  constructor() {
    void this.crud.reload();
    void this.loadParametresLegaux();
  }

  private async loadParametresLegaux(): Promise<void> {
    try {
      const parametres = await this.api.get<ParametresLegaux>('/api/parametres-legaux');
      this.dureeHebdomadaireMaxMinutes.set(parametres.dureeHebdomadaireMaxMinutes);
    } catch {
      // Falls back to the computeStaffingSummary default (48h) if unreachable.
    }
  }

  protected busiestCreneauLabel(critique: CreneauStaffing): string {
    const jour = critique.jour;
    const date = critique.date;
    const heureDebut = critique.heureDebut;
    const heureFin = critique.heureFin;
    const standsOuverts = critique.standsOuverts;
    return $localize`:@@staffing.busiestCreneau:Créneau le plus chargé : J${jour}:jour: · ${date}:date: ${heureDebut}:heureDebut:–${heureFin}:heureFin: (${standsOuverts}:count: stands ouverts)`;
  }
}
