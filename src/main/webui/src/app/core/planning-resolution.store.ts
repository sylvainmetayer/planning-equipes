// Tracks when the persisted plan was last solved, and whether reference data
// was edited since — so the screens showing that plan can hint it may be
// stale. Self-contained on purpose: it must work on screens (e.g. the
// calendars) that never load `ReferenceDataStore`.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { PlanningResolution } from './models';

@Injectable({ providedIn: 'root' })
export class PlanningResolutionStore {
  readonly resolution = signal<PlanningResolution | null>(null);

  /**
   * True once a solve has run and reference data was edited afterwards: the
   * persisted planning may no longer reflect it. Deliberately soft: an edit
   * from an earlier server run before this one started is not known, so it
   * never flags anything until an edit actually happens during this run.
   */
  readonly dataStale = computed(() => {
    const resolution = this.resolution();
    if (!resolution?.solved || !resolution.derniereModificationDonnees) {
      return false;
    }
    return new Date(resolution.derniereModificationDonnees) > new Date(resolution.resoluLe ?? 0);
  });

  private readonly api = inject(ApiService);

  async reload(): Promise<void> {
    this.resolution.set(await this.api.get<PlanningResolution>('/api/planning/persisted/resolution'));
  }
}
