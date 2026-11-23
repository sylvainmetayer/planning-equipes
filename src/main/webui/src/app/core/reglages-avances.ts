// « Réglages avancés »: what a screen offers beyond its everyday use — an
// expert's threshold, a technical choice — folded by default and opened by
// `?avance=1`, so a link can still name it and a reload keeps it open.

import { Signal, signal } from '@angular/core';
import { currentViewParams, keepViewInQueryParams } from './view-query-params';

export interface ReglagesAvances {
  readonly ouvert: Signal<boolean>;
  basculer(): void;
}

/** Call from an injection context: one per screen, its state in `?avance=`. */
export function reglagesAvances(): ReglagesAvances {
  const ouvert = signal(currentViewParams().get('avance') === '1');
  keepViewInQueryParams(() => ({ avance: ouvert() ? '1' : null }));
  return {
    ouvert: ouvert.asReadonly(),
    basculer: () => ouvert.update((value) => !value),
  };
}
