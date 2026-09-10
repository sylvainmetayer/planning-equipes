// Day-by-day navigation of a dated view: the rail, the map and the breaks
// each showed one day of the event and each carried the same forty lines —
// a selected key, the day it resolves to, previous/next, first/last, and
// what the `jour` query param should carry (issue #392, B9). Once, here.

import { Signal, WritableSignal, computed, signal } from '@angular/core';

export interface DayNavigation<J, K extends string | number> {
  /** The key the user asked for; null until they did, or when the URL named none. */
  readonly selected: WritableSignal<K | null>;
  /**
   * The day actually displayed. Resolved rather than corrected by an effect:
   * a `jour` from the URL naming a day the plan no longer holds falls back to
   * the first one instead of leaving the page blank.
   */
  readonly current: Signal<J | null>;
  readonly isFirst: Signal<boolean>;
  readonly isLast: Signal<boolean>;
  /** What the `jour` query param carries: nothing on the first day, which is the default. */
  readonly queryParam: Signal<string | null>;
  select(key: K): void;
  /** Steps to the previous/next day; a no-op at either end. */
  step(delta: number): void;
}

export function dayNavigation<J, K extends string | number>(
  days: Signal<readonly J[]>,
  keyOf: (day: J) => K,
  options: { initial?: K | null; onSelect?: (key: K) => void } = {},
): DayNavigation<J, K> {
  const selected = signal<K | null>(options.initial ?? null);
  const current = computed<J | null>(() => {
    const list = days();
    const wanted = selected();
    return list.find((day) => keyOf(day) === wanted) ?? list[0] ?? null;
  });
  const keyAt = (day: J | undefined): K | null => (day === undefined ? null : keyOf(day));
  const currentKey = computed(() => keyAt(current() ?? undefined));
  const isFirst = computed(() => keyAt(days()[0]) === currentKey());
  const isLast = computed(() => keyAt(days()[days().length - 1]) === currentKey());
  const queryParam = computed(() => {
    const key = currentKey();
    const first = keyAt(days()[0]);
    return key !== null && first !== null && key !== first ? String(key) : null;
  });
  const select = (key: K): void => {
    selected.set(key);
    options.onSelect?.(key);
  };
  const step = (delta: number): void => {
    const list = days();
    const index = list.findIndex((day) => keyOf(day) === currentKey());
    const target = list[index + delta];
    if (target !== undefined) {
      select(keyOf(target));
    }
  };
  return { selected, current, isFirst, isLast, queryParam, select, step };
}

/** A `jour` query param naming a day by its number; anything else is no request at all. */
export function dayNumberParam(value: string | null): number | null {
  const jour = Number(value);
  return Number.isFinite(jour) && jour > 0 ? jour : null;
}
