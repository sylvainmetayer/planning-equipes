// Solver settings, persisted server-side via /api/parametres-solveur (not in
// localStorage, so every browser reads and writes the same value): the
// termination duration, edited on the Solveur page and honored by every job
// whoever starts it, and whether a finished solve mails its outcome to the
// admin, edited on the Paramètres page.
//
// Both travel in one payload, so every write sends both: a PUT carrying only
// one of them would silently reset the other to its default.

import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { ParametresSolveur } from './models';

/** Mirrors the backend default (`planning.solver.seconds-limit` in application.properties). */
export const DEFAULT_SOLVER_SECONDS_LIMIT = 900;

@Injectable({ providedIn: 'root' })
export class SolverSettingsService {
  readonly secondsLimit = signal(DEFAULT_SOLVER_SECONDS_LIMIT);
  /** Off by default, and inert until an admin address is configured server-side. */
  readonly mailFinResolution = signal(false);

  private readonly api = inject(ApiService);

  constructor() {
    void this.refresh().catch(() => {
      // Server unreachable at startup: keep the in-memory default, so
      // solve requests still work. A caller awaiting refresh()
      // directly (the Débogage page) still sees the error and can report it.
    });
  }

  async refresh(): Promise<void> {
    this.apply(await this.api.get<ParametresSolveur>('/api/parametres-solveur'));
  }

  async setSecondsLimit(seconds: number): Promise<void> {
    const value = Number.isFinite(seconds) && seconds > 0 ? Math.round(seconds) : DEFAULT_SOLVER_SECONDS_LIMIT;
    await this.enregistrer({ dureeResolutionSecondes: value, mailFinResolution: this.mailFinResolution() });
  }

  async setMailFinResolution(actif: boolean): Promise<void> {
    await this.enregistrer({ dureeResolutionSecondes: this.secondsLimit(), mailFinResolution: actif });
  }

  private async enregistrer(parametres: ParametresSolveur): Promise<void> {
    this.apply(await this.api.put<ParametresSolveur>('/api/parametres-solveur', parametres));
  }

  private apply(parametres: ParametresSolveur): void {
    this.secondsLimit.set(parametres.dureeResolutionSecondes);
    this.mailFinResolution.set(parametres.mailFinResolution ?? false);
  }
}
