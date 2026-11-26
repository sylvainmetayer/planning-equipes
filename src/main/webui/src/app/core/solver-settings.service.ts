// The solve budget of the instance (the longest a solve may run, how long a
// feasible planning may go without improving) and the edition's end-of-solve
// mail, both read from /api/parametres/calcul. The budget is the operator's,
// set on the server (SOLVER_SECONDS_LIMIT, SOLVER_UNIMPROVED_SECONDS_LIMIT)
// and the same for every edition: it is shown, never written from here. The
// mail switch is the one thing an edition decides, edited on « Règles du
// planning › Calcul ».

import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { ParametresSolveur, SolverBudgetBounds, SolverSettingsView } from './models';

/** Mirrors the backend default (`planning.solver.seconds-limit` in application.properties). */
export const DEFAULT_SOLVER_SECONDS_LIMIT = 900;

@Injectable({ providedIn: 'root' })
export class SolverSettingsService {
  /** The instance's budget and ceiling; `null` until the server has answered. */
  private readonly _bounds = signal<SolverBudgetBounds | null>(null);
  readonly bounds = this._bounds.asReadonly();
  /** Off by default, and inert until an admin address is configured server-side. */
  private readonly _mailFinResolution = signal(false);
  readonly mailFinResolution = this._mailFinResolution.asReadonly();

  /** The duration a job launched now would run: the instance's. */
  readonly secondsLimit = computed(
    () => this.bounds()?.defaultSecondsLimit ?? DEFAULT_SOLVER_SECONDS_LIMIT,
  );
  /** The plateau a job launched now would run under, `null` while the instance is unknown. */
  readonly effectivePlateauSeconds = computed(() => this.bounds()?.defaultPlateauSeconds ?? null);

  private readonly api = inject(ApiService);

  constructor() {
    void this.refresh().catch(() => {
      // Server unreachable at startup: keep the in-memory default. A caller
      // awaiting refresh() directly (the Débogage page) still sees the error
      // and can report it.
    });
  }

  async refresh(): Promise<void> {
    this.apply(await this.api.get<SolverSettingsView>('/api/parametres/calcul'));
  }

  /** Saves the edition's end-of-solve mail switch. */
  async setMailFinResolution(mailFinResolution: boolean): Promise<void> {
    const parametres: ParametresSolveur = { mailFinResolution };
    this.apply(await this.api.put<SolverSettingsView>('/api/parametres/calcul', parametres));
  }

  private apply(parametres: SolverSettingsView): void {
    this._mailFinResolution.set(parametres.mailFinResolution ?? false);
    if (parametres.instance) {
      this._bounds.set(parametres.instance);
    }
  }
}
