// The edition's solve budget, persisted server-side via /api/parametres-solveur
// (not in localStorage, so every browser reads and writes the same value): the
// longest a solve may run and how long a feasible planning may go without
// improving, applied by the server to every job launched on the edition,
// whoever starts it; and whether a finished solve mails its outcome to the
// admin. All three are edited on « Règles du planning › Calcul ».
//
// Everything travels in one payload, so every write sends everything: a PUT
// carrying only one field would silently reset the others to their default.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { ParametresSolveur, SolverBudgetBounds, SolverSettingsView } from './models';

/** Mirrors the backend default (`planning.solver.seconds-limit` in application.properties). */
export const DEFAULT_SOLVER_SECONDS_LIMIT = 900;

@Injectable({ providedIn: 'root' })
export class SolverSettingsService {
  /** The edition's own duration, `null` when it follows the instance. */
  private readonly _dureeResolutionSecondes = signal<number | null>(null);
  readonly dureeResolutionSecondes = this._dureeResolutionSecondes.asReadonly();
  /** The edition's own plateau (0 = never), `null` when it follows the instance. */
  private readonly _plateauSecondes = signal<number | null>(null);
  readonly plateauSecondes = this._plateauSecondes.asReadonly();
  /** The instance's defaults and ceilings; `null` until the server has answered. */
  private readonly _bounds = signal<SolverBudgetBounds | null>(null);
  readonly bounds = this._bounds.asReadonly();
  /** Off by default, and inert until an admin address is configured server-side. */
  private readonly _mailFinResolution = signal(false);
  readonly mailFinResolution = this._mailFinResolution.asReadonly();

  /** The duration a job launched now would run: the edition's, else the instance's. */
  readonly secondsLimit = computed(
    () =>
      this.dureeResolutionSecondes() ??
      this.bounds()?.defaultSecondsLimit ??
      DEFAULT_SOLVER_SECONDS_LIMIT,
  );
  /** The plateau a job launched now would run under, `null` while the instance is unknown. */
  readonly effectivePlateauSeconds = computed(
    () => this.plateauSecondes() ?? this.bounds()?.defaultPlateauSeconds ?? null,
  );

  private readonly api = inject(ApiService);

  constructor() {
    void this.refresh().catch(() => {
      // Server unreachable at startup: keep the in-memory default. A caller
      // awaiting refresh() directly (the Débogage page) still sees the error
      // and can report it.
    });
  }

  async refresh(): Promise<void> {
    this.apply(await this.api.get<SolverSettingsView>('/api/parametres-solveur'));
  }

  /**
   * Saves the edition's budget and the end-of-solve mail in one write;
   * `null` for either half of the budget follows the instance. The server
   * refuses a value above the ceiling, or a plateau longer than the duration,
   * and the caller shows why.
   */
  async setSettings(
    dureeResolutionSecondes: number | null,
    plateauSecondes: number | null,
    mailFinResolution: boolean,
  ): Promise<void> {
    await this.enregistrer({
      dureeResolutionSecondes: roundedOrNull(dureeResolutionSecondes),
      plateauSecondes: roundedOrNull(plateauSecondes),
      mailFinResolution,
    });
  }

  private async enregistrer(parametres: ParametresSolveur): Promise<void> {
    this.apply(await this.api.put<SolverSettingsView>('/api/parametres-solveur', parametres));
  }

  private apply(parametres: SolverSettingsView): void {
    this._dureeResolutionSecondes.set(parametres.dureeResolutionSecondes ?? null);
    this._plateauSecondes.set(parametres.plateauSecondes ?? null);
    this._mailFinResolution.set(parametres.mailFinResolution ?? false);
    if (parametres.instance) {
      this._bounds.set(parametres.instance);
    }
  }
}

function roundedOrNull(seconds: number | null): number | null {
  return seconds == null || !Number.isFinite(seconds) ? null : Math.round(seconds);
}
