// Solver settings (server-persisted via /api/parametres-solveur), currently
// just the termination duration exposed on the Débogage page. Read by the
// pages that submit a solve or analyze job so every job — not only ones
// started from Débogage — honors the configured duration. Persisted
// server-side (not localStorage) so the same value is seen from any browser.

import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { ParametresSolveur } from './models';

/** Mirrors the backend default (`planning.solver.seconds-limit` in application.properties). */
export const DEFAULT_SOLVER_SECONDS_LIMIT = 180;

@Injectable({ providedIn: 'root' })
export class SolverSettingsService {
  readonly secondsLimit = signal(DEFAULT_SOLVER_SECONDS_LIMIT);

  private readonly api = inject(ApiService);

  constructor() {
    void this.refresh().catch(() => {
      // Server unreachable at startup: keep the in-memory default, so
      // solve/analyze requests still work. A caller awaiting refresh()
      // directly (the Débogage page) still sees the error and can report it.
    });
  }

  async refresh(): Promise<void> {
    const parametres = await this.api.get<ParametresSolveur>('/api/parametres-solveur');
    this.secondsLimit.set(parametres.dureeResolutionSecondes);
  }

  async setSecondsLimit(seconds: number): Promise<void> {
    const value = Number.isFinite(seconds) && seconds > 0 ? Math.round(seconds) : DEFAULT_SOLVER_SECONDS_LIMIT;
    const saved = await this.api.put<ParametresSolveur>('/api/parametres-solveur', {
      dureeResolutionSecondes: value
    });
    this.secondsLimit.set(saved.dureeResolutionSecondes);
  }
}
