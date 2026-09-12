// The read-only analyses the screens draw: breaks, staffing, fragility, the
// bench, the KPI history, the action history, the alerts — and the two
// referentials read for their labels only (the CRUD is the store's).

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  AlerteView,
  BancDeTouche,
  ChangementsDonnees,
  Emplacement,
  EntreeHistorique,
  KpiHistoriqueEntry,
  RapportFragilite,
  RapportPauses,
  StaffingSummary,
  TypologieItem,
} from '../models';

@Injectable({ providedIn: 'root' })
export class AnalysesApi {
  private readonly api = inject(ApiService);

  breaks(): Promise<RapportPauses> {
    return this.api.get<RapportPauses>('/api/pauses');
  }

  staffing(): Promise<StaffingSummary> {
    return this.api.get<StaffingSummary>('/api/staffing');
  }

  fragility(): Promise<RapportFragilite> {
    return this.api.get<RapportFragilite>('/api/fragilite');
  }

  /**
   * Who could hold a seat: on one créneau, or across the day when none is
   * named; narrowed to a stand when one is.
   */
  bench(creneauId: number | string | null, standId: string | null): Promise<BancDeTouche> {
    const chemin = creneauId === null ? '/api/banc-de-touche' : `/api/banc-de-touche/${creneauId}`;
    const query = standId ? `?standId=${encodeURIComponent(standId)}` : '';
    return this.api.get<BancDeTouche>(`${chemin}${query}`);
  }

  kpiHistory(): Promise<KpiHistoriqueEntry[]> {
    return this.api.get<KpiHistoriqueEntry[]>('/api/kpi/historique');
  }

  deleteKpiEntry(entryId: number | string): Promise<void> {
    return this.api.delete(`/api/kpi/historique/${entryId}`);
  }

  actionHistory(): Promise<EntreeHistorique[]> {
    return this.api.get<EntreeHistorique[]>('/api/historique');
  }

  /**
   * What changed in the problem since `depuis` — how many, of what kind, and
   * the most recent lines. Read by the solver screen to say what moved under
   * its « données modifiées depuis cette résolution » hint.
   */
  changesSince(depuis: string): Promise<ChangementsDonnees> {
    return this.api.get<ChangementsDonnees>(
      `/api/historique/changements?depuis=${encodeURIComponent(depuis)}`,
    );
  }

  alerts(): Promise<AlerteView[]> {
    return this.api.get<AlerteView[]>('/api/alertes');
  }

  /** Labels only: a screen that cannot read them degrades its chips to raw ids. */
  typologies(): Promise<TypologieItem[]> {
    return this.api.get<TypologieItem[]>('/api/typologies');
  }

  emplacements(): Promise<Emplacement[]> {
    return this.api.get<Emplacement[]>('/api/emplacements');
  }
}
