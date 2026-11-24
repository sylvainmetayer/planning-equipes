// The tab of a page whose tabs are other screens (Animateurs › Compétences,
// Calendrier › Horaires des stands…): read from `?onglet=` and followed, and
// changed by replacing the address with the tab alone — the keys of the tab
// left behind mean nothing to the next one, which then opens on its defaults
// (`?avance=1`, the page's own fold, excepted).

import { Location } from '@angular/common';
import { inject, Signal, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { currentViewParams } from './view-query-params';

export interface OngletHote<T extends string> {
  readonly onglet: Signal<T>;
  /** Opens `next`, once the tab on screen agreed to be left (unsaved entries ask first). */
  changer(next: T): Promise<void>;
}

/**
 * Call from an injection context. `read` maps the address to a tab (an unknown
 * value to the default), `defaut` is the tab the bare address opens, and
 * `canLeave` asks the tab on screen before it is replaced.
 *
 * The host never mirrors its tab through `keepViewInQueryParams`: a hosted
 * screen with tabs of its own writes `onglet` too, and two effects on one key
 * would race on arrival. The address is written once, when the reader moves.
 */
export function ongletHote<T extends string>(
  read: (value: string | null) => T,
  defaut: T,
  canLeave: () => Promise<boolean> = () => Promise.resolve(true),
): OngletHote<T> {
  const location = inject(Location);
  const onglet = signal<T>(read(currentViewParams().get('onglet')));
  // Followed: the palette's tab entries navigate to this very route with
  // another `onglet`, and the router reuses the component.
  inject(ActivatedRoute, { optional: true })
    ?.queryParamMap?.pipe(takeUntilDestroyed())
    .subscribe((params) => onglet.set(read(params.get('onglet'))));
  return {
    onglet: onglet.asReadonly(),
    async changer(next: T): Promise<void> {
      if (next === onglet() || !(await canLeave())) {
        return;
      }
      const [chemin, requete = ''] = location.path().split('?');
      // `?avance=1` belongs to the page, not to the tab: the fold stays open.
      const params = new URLSearchParams();
      if (next !== defaut) {
        params.set('onglet', next);
      }
      if (new URLSearchParams(requete).get('avance') === '1') {
        params.set('avance', '1');
      }
      const query = params.toString();
      location.replaceState(query ? `${chemin}?${query}` : chemin || '/');
      onglet.set(next);
    },
  };
}
