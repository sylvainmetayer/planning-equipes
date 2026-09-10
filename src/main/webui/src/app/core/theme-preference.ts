// Which colour scheme the application wears, kept in localStorage so the choice
// looks the same on the next visit.
//
// Not in the URL, unlike the view state of `view-query-params.ts`: this is a
// chrome preference — how this person's eyes want this screen, on this machine
// — neither shareable nor worth a param, and a param would be gone on the next
// plain navigation. Same reasoning and same defensiveness as `nav-collapse` and
// `panel-collapse`, its two neighbours.
//
// Nothing here paints anything. Both palettes are already compiled into the
// bundle: `mat.theme()` defaults to its `color-scheme` theme type, so every
// `--mat-sys-*` colour is a `light-dark(light, dark)` pair and the CSS
// `color-scheme` property alone decides which half is used. All this module
// does is put the right `color-scheme` on the root element.

const STORAGE_KEY = 'planning-equipes.theme';

/**
 * What the user asked for. `system` is the default and is not a third palette:
 * it hands the decision back to `prefers-color-scheme`, which the browser then
 * follows live, with no listener and no reload.
 */
export type ThemePreference = 'system' | 'light' | 'dark';

/** The three values, in the order the toolbar button cycles through them. */
export const THEME_PREFERENCES: readonly ThemePreference[] = ['system', 'light', 'dark'];

/** The scheme actually painted: what `system` resolves to once the media query has answered. */
export type ResolvedScheme = 'light' | 'dark';

/**
 * Reads the stored preference. Anything unreadable — no storage, a value
 * written by an older version — reads as `system`: following the machine is
 * the harmless default, and it is what a first-time visitor gets.
 */
export function readThemePreference(
  storage: Pick<Storage, 'getItem' | 'setItem'> | null,
): ThemePreference {
  if (!storage) {
    return 'system';
  }
  try {
    const raw = storage.getItem(STORAGE_KEY);
    return raw === 'light' || raw === 'dark' ? raw : 'system';
  } catch {
    return 'system';
  }
}

/**
 * Writes it, ignoring a storage that refuses to be written to (private mode,
 * quota). `system` is written like the others rather than removed: it is a
 * deliberate answer — "follow my machine" — and losing it would be
 * indistinguishable from never having chosen.
 */
export function writeThemePreference(
  storage: Pick<Storage, 'getItem' | 'setItem'> | null,
  preference: ThemePreference,
): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(STORAGE_KEY, preference);
  } catch {
    // Nothing to do: the theme still switches, it just forgets on reload.
  }
}

/** The next value of the toolbar button: système → clair → sombre → système. */
export function nextThemePreference(preference: ThemePreference): ThemePreference {
  const index = THEME_PREFERENCES.indexOf(preference);
  return THEME_PREFERENCES[(index + 1) % THEME_PREFERENCES.length];
}

/**
 * The CSS `color-scheme` value a preference maps to. `light dark` is not
 * "both": it declares the two the document supports and lets
 * `prefers-color-scheme` pick, which is exactly what `system` means. An
 * explicit choice names one scheme and therefore outranks the machine's.
 */
export function colorSchemeFor(preference: ThemePreference): string {
  return preference === 'system' ? 'light dark' : preference;
}

/**
 * Puts the preference on the root element, where every `light-dark()` in the
 * document inherits it from.
 *
 * <p>Called once from `main.ts` before the application bootstraps — a theme
 * applied after the first frame is a flash of the wrong one — and again on
 * every switch.</p>
 */
export function applyThemePreference(preference: ThemePreference, root: HTMLElement): void {
  root.style.colorScheme = colorSchemeFor(preference);
}

/**
 * The media query telling whether the machine asks for a dark UI, or `null`
 * where there is none to ask (jsdom, a hardened browser). Callers treat the
 * absence as "no dark preference", which is the historical behaviour.
 */
export function darkSchemeQuery(): MediaQueryList | null {
  try {
    return typeof window === 'undefined' || typeof window.matchMedia !== 'function'
      ? null
      : window.matchMedia('(prefers-color-scheme: dark)');
  } catch {
    return null;
  }
}

/** What is actually painted, given the preference and what the machine asks for. */
export function resolveScheme(
  preference: ThemePreference,
  systemPrefersDark: boolean,
): ResolvedScheme {
  if (preference !== 'system') {
    return preference;
  }
  return systemPrefersDark ? 'dark' : 'light';
}
