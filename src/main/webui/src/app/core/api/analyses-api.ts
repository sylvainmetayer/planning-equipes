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
  ActionHistorique,
  EntreeHistorique,
  KpiHistoriqueEntry,
  ModeMarge,
  PlanFormation,
  GroupedArrivalReport,
  WalkSequenceReport,
  RapportFragilite,
  RapportMarge,
  RapportIntendance,
  RapportPauses,
  RapportTension,
  StaffingSummary,
  TypologieItem,
} from '../models';

@Injectable({ providedIn: 'root' })
export class AnalysesApi {
  private readonly api = inject(ApiService);

  breaks(): Promise<RapportPauses> {
    return this.api.get<RapportPauses>('/api/pauses');
  }

  /** The tight walks between two consecutive seats of the persisted plan. */
  walks(): Promise<WalkSequenceReport> {
    return this.api.get<WalkSequenceReport>('/api/planning/enchainements');
  }

  /** The grouped arrivals (covoiturages) of the persisted plan, day by day. */
  groupedArrivals(): Promise<GroupedArrivalReport> {
    return this.api.get<GroupedArrivalReport>('/api/planning/arrivees-groupees');
  }

  /**
   * The same meal breaks, counted rather than named: how many people are out,
   * per half-hour and per emplacement (issue #598).
   */
  intendance(): Promise<RapportIntendance> {
    return this.api.get<RapportIntendance>('/api/pauses/intendance');
  }

  /** The same list as a flat CSV, because it leaves the tool for the intendance. */
  exportIntendance(): Promise<string> {
    return this.api.downloadGet(
      '/api/pauses/intendance/export',
      'intendance-repas.csv',
      'text/csv',
    );
  }

  staffing(): Promise<StaffingSummary> {
    return this.api.get<StaffingSummary>('/api/staffing');
  }

  fragility(): Promise<RapportFragilite> {
    return this.api.get<RapportFragilite>('/api/fragilite');
  }

  /** Who to train, typologie by typologie — the « À former » tab. */
  trainingPlan(): Promise<PlanFormation> {
    return this.api.get<PlanFormation>('/api/formation');
  }

  /** The same tab as a CSV, names included, like the Équité export. */
  exportTrainingPlan(): Promise<string> {
    return this.api.downloadGet('/api/formation/export', 'plan-formation.csv', 'text/csv');
  }

  /**
   * Day × timeslot margin. `avant` reads the seats a solve would have to fill,
   * `apres` the plan already persisted.
   */
  margin(mode: ModeMarge): Promise<RapportMarge> {
    return this.api.get<RapportMarge>(`/api/marge?mode=${mode === 'APRES' ? 'apres' : 'avant'}`);
  }

  /** The « après » margin crossed with the fragility of the same plan, cell by cell. */
  tension(): Promise<RapportTension> {
    return this.api.get<RapportTension>('/api/marge/tension');
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

  /**
   * The edition's most recent actions. `exports` asks the server for the
   * files that left the application only — selected in the database, so over
   * the whole retention rather than over the last page of every kind.
   */
  actionHistory(nature: 'exports' | null = null): Promise<EntreeHistorique[]> {
    const query = nature ? `?nature=${nature}` : '';
    return this.api.get<EntreeHistorique[]>(`/api/historique${query}`);
  }

  /** The inventory of actions, with the server's classification of each. */
  actionInventory(): Promise<ActionHistorique[]> {
    return this.api.get<ActionHistorique[]>('/api/historique/actions');
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
