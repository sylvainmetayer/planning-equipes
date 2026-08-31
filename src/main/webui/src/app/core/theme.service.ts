// The colour scheme the admin chrome wears (issue #317), as signals.
//
// The persistence and the DOM write live in `theme-preference.ts`, which
// `main.ts` also calls before bootstrap; this service is what the toolbar
// binds to.

import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';

import { defaultNavStorage } from './nav-collapse';
import {
  ResolvedScheme,
  ThemePreference,
  applyThemePreference,
  darkSchemeQuery,
  nextThemePreference,
  readThemePreference,
  resolveScheme,
  writeThemePreference
} from './theme-preference';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly storage = defaultNavStorage();
  private readonly query = darkSchemeQuery();

  private readonly _preference = signal<ThemePreference>(readThemePreference(this.storage));
  private readonly systemPrefersDark = signal(this.query?.matches ?? false);

  /** What the user asked for: `system`, `light` or `dark`. */
  readonly preference = this._preference.asReadonly();

  /**
   * What is painted right now. Only the icon of the toolbar button reads
   * it: under `system` the browser switches the palette by itself, because
   * `color-scheme: light dark` is native. The listener below exists so that the
   * *button* stops lying when the machine flips to dark at sunset — not to
   * repaint anything.
   */
  readonly scheme = computed<ResolvedScheme>(() => resolveScheme(this._preference(), this.systemPrefersDark()));

  constructor() {
    const query = this.query;
    if (query) {
      const onChange = (event: MediaQueryListEvent): void => this.systemPrefersDark.set(event.matches);
      query.addEventListener('change', onChange);
      inject(DestroyRef).onDestroy(() => query.removeEventListener('change', onChange));
    }
  }

  /** Applies and remembers an explicit choice. */
  set(preference: ThemePreference): void {
    this._preference.set(preference);
    writeThemePreference(this.storage, preference);
    applyThemePreference(preference, document.documentElement);
  }

  /** One control for the three states: système → clair → sombre → système. */
  cycle(): ThemePreference {
    const next = nextThemePreference(this._preference());
    this.set(next);
    return next;
  }
}
