// Client-side solver settings (localStorage-persisted), currently just the
// termination duration exposed on the Débogage page. Read by the pages that
// submit a solve or analyze job so every job — not only ones started from
// Débogage — honors the configured duration.

import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'planning-equipes.solver-settings.seconds-limit';
/** Mirrors the backend default (`planning.solver.seconds-limit` in application.properties). */
export const DEFAULT_SOLVER_SECONDS_LIMIT = 180;

function loadSecondsLimit(): number {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? Number(raw) : NaN;
    return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_SOLVER_SECONDS_LIMIT;
  } catch {
    return DEFAULT_SOLVER_SECONDS_LIMIT; // corrupted or unavailable storage: fall back to the default
  }
}

@Injectable({ providedIn: 'root' })
export class SolverSettingsService {
  readonly secondsLimit = signal(loadSecondsLimit());

  setSecondsLimit(seconds: number): void {
    const value = Number.isFinite(seconds) && seconds > 0 ? Math.round(seconds) : DEFAULT_SOLVER_SECONDS_LIMIT;
    this.secondsLimit.set(value);
    try {
      localStorage.setItem(STORAGE_KEY, String(value));
    } catch {
      /* storage full or unavailable: the in-memory value still works for this session */
    }
  }
}
